package com.morsedecoder.core

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.fixtures.NonMorseAudio
import com.morsedecoder.core.live.LiveDecoderEngine
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P-4, from the other side: what the decoder does with audio that is **not** Morse.
 *
 * Almost everything a phone microphone hears is not Morse, so declining is most of the
 * job. The acceptance criteria measure accuracy on signals that *are* Morse and say
 * nothing about this; the failure users actually report is invented text, because garbage
 * output is worse than no output.
 *
 * Fixtures are in [NonMorseAudio] and are adversarial by construction rather than random.
 * Every bar here is a **measured** number, not a guess.
 */
class NonMorseTest {

    private fun liveChars(audio: FloatArray, chunk: Int = 4096): Int {
        val engine = LiveDecoderEngine()
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            engine.feed(audio.copyOfRange(i, i + n), n)
            i += n
        }
        engine.finish()
        return engine.transcript.size
    }

    private fun fileChars(audio: FloatArray): Int =
        MorseDecoder().decodeFile(FloatArrayPcmSource(audio, NonMorseAudio.SAMPLE_RATE)).chars.size

    /** Two characters over twenty seconds is silence for practical purposes. */
    private val quiet = 2

    // ---- what the decoder gets right, locked in ------------------------------------

    /**
     * Audio with no keying structure: nothing to decode, so nothing must be emitted.
     * These are the cases the low-signal guard (FR-3.7) and the no-keying gate (D-13)
     * exist to catch, and they hold on both paths.
     */
    @Test
    fun `structureless and steady audio decodes to nothing`() {
        val fixtures = listOf(
            "silence" to NonMorseAudio.silence(),
            "white-noise" to NonMorseAudio.whiteNoise(),
            "pink-noise" to NonMorseAudio.pinkNoise(),
            "steady-carrier" to NonMorseAudio.steadyCarrier(),
            "mains-hum" to NonMorseAudio.mainsHum(),
            "music" to NonMorseAudio.music(),
        )
        for ((name, audio) in fixtures) {
            assertTrue(liveChars(audio) <= quiet) { "live decoded ${liveChars(audio)} chars from $name" }
            assertTrue(fileChars(audio) <= quiet) { "file decoded ${fileChars(audio)} chars from $name" }
        }
    }

    /** Guard against fixtures that are accidentally silent and so prove nothing. */
    @Test
    fun `non-Morse fixtures actually carry signal`() {
        for ((name, audio) in NonMorseAudio.corpus()) {
            if (name == "silence") continue
            assertTrue(NonMorseAudio.rms(audio) > 0.01) { "$name is effectively silent, rms ${NonMorseAudio.rms(audio)}" }
        }
    }

    /** Impulsive room noise must not decode on the live path. */
    @Test
    fun `knocking and handling noise decodes to nothing on the live path`() {
        assertTrue(liveChars(NonMorseAudio.knocking()) <= quiet)
    }

    // ---- what the decoder gets wrong, pinned so it cannot get worse ----------------

    // ---- what the decoder should do, and does not yet ------------------------------
    //
    // These assert the behaviour we want. They fail today, are counted as known failures,
    // and do not redden the build. Current measured output is quoted so the distance to
    // correct is visible without running anything.

    /**
     * Speech has syllabic rhythm but no dot/dash structure. Nothing should be emitted.
     * Measured today: 26 characters live, 31 from a file.
     */
    @Test
    @KnownFailure("D-8", "speech decodes as Morse")
    fun `speech decodes to nothing`() {
        val audio = NonMorseAudio.speech()
        assertTrue(liveChars(audio) <= quiet) { "live emitted ${liveChars(audio)} characters of Morse from speech" }
        assertTrue(fileChars(audio) <= quiet) { "file emitted ${fileChars(audio)} characters of Morse from speech" }
    }

    /**
     * The hardest negative case: narrowband and genuinely on/off keyed, so every
     * "is this being keyed?" test says yes. Runs of *k* identical bits give marks of
     * *k* × 22 ms, which a two-cluster fit reads as a clean 3:1 dot/dash split.
     * Measured today: 70 characters live, 124 from a file.
     */
    @Test
    @KnownFailure("D-8", "RTTY decodes as Morse")
    fun `RTTY decodes to nothing`() {
        val audio = NonMorseAudio.rtty()
        assertTrue(liveChars(audio) <= quiet) { "live emitted ${liveChars(audio)} characters of Morse from RTTY" }
        assertTrue(fileChars(audio) <= quiet) { "file emitted ${fileChars(audio)} characters of Morse from RTTY" }
    }

    /** An overloaded input stage. Measured today: 10 live, 19 file. */
    @Test
    @KnownFailure("D-8", "clipped input decodes as Morse")
    fun `a clipping input stage decodes to nothing`() {
        val audio = NonMorseAudio.clipping()
        assertTrue(liveChars(audio) <= quiet) { "live emitted ${liveChars(audio)} characters from clipped audio" }
        assertTrue(fileChars(audio) <= quiet) { "file emitted ${fileChars(audio)} characters from clipped audio" }
    }

    /** Room sound over a hum, and music under noise. Measured today: 22 and 50 live. */
    @Test
    @KnownFailure("D-8", "mixed room audio decodes as Morse")
    fun `mixed everyday room audio decodes to nothing`() {
        val corpus = NonMorseAudio.corpus().toMap()
        for (name in listOf("speech+hum", "music+noise")) {
            val n = liveChars(corpus.getValue(name))
            assertTrue(n <= quiet) { "$name: live emitted $n characters" }
        }
    }

    /** Impulsive handling noise. Live is already correct; the file path is not (30 chars). */
    @Test
    @KnownFailure("D-8", "knocking decodes on the file path")
    fun `knocking decodes to nothing on the file path`() {
        val n = fileChars(NonMorseAudio.knocking())
        assertTrue(n <= quiet) { "file emitted $n characters from knocking" }
    }

    /**
     * The property that would have to hold for the output gate to be tunable at all: a
     * clear margin between what non-Morse scores and what real Morse scores.
     *
     * Measured today there is none — RTTY scores 0.70–0.76 and legitimate hand-sent Morse
     * scores 0.64–0.88, so RTTY's distribution sits entirely inside the real one. This
     * test failing is the evidence that D-8 cannot be closed by choosing a better
     * threshold; it needs a different measurement.
     */
    @Test
    @KnownFailure("D-8", "fitQuality cannot separate RTTY from Morse")
    fun `fitQuality separates RTTY from hand-sent Morse`() {
        val rttyFits = fitsFor(NonMorseAudio.rtty())
        val morse = com.morsedecoder.core.synth.SyntheticMorseGenerator.generate(
            com.morsedecoder.core.synth.SyntheticMorseGenerator.Config(
                text = AcceptanceTest.LONG_TEXT, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
                dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L,
            )
        )
        val morseFits = fitsFor(morse.samples)
        assertTrue(rttyFits.isNotEmpty() && morseFits.isNotEmpty()) { "no fit samples to compare" }

        val rttyHigh = rttyFits.sorted()[(rttyFits.size * 9) / 10]
        val morseLow = morseFits.sorted()[morseFits.size / 10]
        assertTrue(rttyHigh < morseLow) {
            "no separation: RTTY p90 = ${"%.2f".format(rttyHigh)} but hand-sent Morse " +
                "p10 = ${"%.2f".format(morseLow)} — the distributions overlap"
        }
    }

    private fun fitsFor(audio: FloatArray): DoubleArray {
        val engine = LiveDecoderEngine()
        var i = 0
        while (i < audio.size) {
            val n = minOf(4096, audio.size - i)
            engine.feed(audio.copyOfRange(i, i + n), n)
            i += n
        }
        engine.finish()
        return engine.recentFits()
    }
}

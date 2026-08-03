package com.morsedecoder.core

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P-4 as testable propositions: when the decoder cannot do the job, it must say so rather
 * than produce confident output.
 *
 * Every test here currently fails, and each one is a distinct open defect. They are
 * gathered in one file because they share a single root cause — **nothing in the system
 * measures whether the output is right** — and reading them together makes that obvious in
 * a way the scattered entries in known-issues.md do not.
 *
 * The decoder's only self-assessment, `modelFitQuality`, asks "does my two-cluster model
 * fit my own measurements?". It answers that faithfully even when the measurements are
 * referred to a unit four times too short, which is why a 250 % error rate can coexist
 * with a perfect score.
 */
class HonestFailureTest {

    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    private fun feed(engine: LiveDecoderEngine, audio: FloatArray) {
        var i = 0
        while (i < audio.size) {
            val n = minOf(4096, audio.size - i)
            engine.feed(audio.copyOfRange(i, i + n), n)
            i += n
        }
        engine.finish()
    }

    private fun decoded(engine: LiveDecoderEngine) = buildString {
        for ((k, c) in engine.transcript.withIndex()) {
            if (k > 0 && c.isWordStart) append(' ')
            append(c.token)
        }
    }

    /** 25 % jitter, heavy per-character bias, +5 dB: a beginner on a straight key. */
    private fun roughFist() = SyntheticMorseGenerator.generate(
        Config(
            text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
            dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L,
        )
    )

    /**
     * A rough fist is squarely inside the intended use — a beginner on a straight key in a
     * noisy room is most of the target audience. Measured today: the live path emits 145
     * characters against a 62-character reference at a reported 53 WPM when the truth is
     * 13, and the file path emits nothing at all.
     */
    @Test
    @KnownFailure("D-6", "a rough 13 WPM fist decodes at 250 % CER")
    fun `a rough hand-sent fist at 13 WPM decodes usefully`() {
        val gen = roughFist()
        val engine = LiveDecoderEngine()
        feed(engine, gen.samples)
        val cer = TextMetrics.cer(gen.normalizedText, decoded(engine))
        assertTrue(cer < 0.50) { "live CER $cer; decoded '${decoded(engine).take(120)}'" }

        val file = MorseDecoder().decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
        assertTrue(file.chars.isNotEmpty()) { "file path declined the whole transmission" }
    }

    /**
     * The speed estimate is the decoder's own claim about what it is hearing. When it is
     * out by a factor of four, every character derived from it is wrong, and something has
     * to notice. Measured today: 53 WPM reported for a 13 WPM signal, `outputSuppressed`
     * false throughout.
     */
    @Test
    @KnownFailure("D-1", "a 4x unit-scale error is not detected")
    fun `a wildly wrong speed estimate suppresses output`() {
        val gen = roughFist()
        val engine = LiveDecoderEngine()
        feed(engine, gen.samples)

        val reported = engine.wpm
        val plausible = reported != null && reported > 6.0 && reported < 26.0
        assertTrue(plausible || engine.outputSuppressed) {
            "reported ${reported}. WPM for a 13 WPM signal and kept emitting " +
                "${engine.transcript.size} characters with fit ${"%.2f".format(engine.fitQuality)}"
        }
    }

    /**
     * Below the calibrated SNR floor, accuracy targets are explicitly void — being *wrong*
     * is allowed. Being confidently verbose is not: P-4 asks for a decoder that declines
     * rather than one that produces plausible-looking output from unusable input.
     *
     * Closed by D-16: characters computed from a speed estimate pinned at the physical
     * clamp are no longer emitted. At −5 dB that took output from 167 characters against
     * 99 sent (CER 108 %) down to 80 (CER 38 %), with no change at all at 0 dB or above.
     */
    @Test
    fun `below the SNR floor the decoder declines rather than inventing text`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = -5.0, seed = 14L)
        )
        val engine = LiveDecoderEngine()
        feed(engine, gen.samples)
        // Emitting *fewer* characters than were sent is the signature of declining;
        // emitting more is the signature of inventing.
        val sent = gen.normalizedText.count { it != ' ' }
        assertTrue(engine.transcript.size <= sent) {
            "emitted ${engine.transcript.size} characters for $sent sent, at -5 dB"
        }
    }

    /**
     * FR-5.7's out-of-range flag must describe the speed the user is shown. It used to be
     * derived from `u0`, the pass-1 bootstrap, while the UI displays the pass-2 Kalman
     * speed — which is how the field recording came to show 58.9 WPM with no
     * "outside 5–40" warning beside it.
     */
    @Test
    fun `the out-of-range flag agrees with the reported speed`() {
        // A genuine contest-speed sender: above the 5-40 calibrated band, below the 60 WPM
        // physical clamp. Exactly the case FR-5.7's flag exists for — decoded best-effort
        // and labelled, not suppressed (D-16).
        val gen = SyntheticMorseGenerator.generate(
            Config(text = text, wpm = 45.0, toneHz = 612.0, snrDb = 10.0, seed = 42L)
        )
        val result = MorseDecoder().decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
        assertTrue(result.wpm > 40.0) { "fixture decoded at ${result.wpm} WPM, inside the band" }
        assertTrue(result.chars.isNotEmpty()) { "a 45 WPM sender must still be decoded (FR-5.7)" }
        assertTrue(result.outOfCalibratedRange) {
            "reported ${"%.1f".format(result.wpm)} WPM without flagging it out of range"
        }
    }

    /** The other direction: a signal inside the band must not be flagged. */
    @Test
    fun `a speed inside the calibrated band is not flagged`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L)
        )
        val result = MorseDecoder().decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
        assertTrue(result.wpm in 15.0..25.0) { "fixture decoded at ${result.wpm} WPM" }
        assertTrue(!result.outOfCalibratedRange) {
            "flagged ${"%.1f".format(result.wpm)} WPM as outside 5-40"
        }
    }

    /** And at the edges, where the ~6 % margin exists so noise alone cannot trip the flag. */
    @Test
    fun `signals sent at the band edges are not flagged`() {
        for (wpm in listOf(5.0, 40.0)) {
            val gen = SyntheticMorseGenerator.generate(
                Config(text = text, wpm = wpm, toneHz = 612.0, snrDb = 12.0, seed = 71L)
            )
            val result = MorseDecoder().decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
            assertTrue(!result.outOfCalibratedRange) {
                "$wpm WPM decoded as ${"%.1f".format(result.wpm)} and was flagged out of range"
            }
        }
    }

    /**
     * "No data" and "perfect" must not be the same value. `fitQuality` used to return a
     * neutral 1.0 until twelve marks had been observed, which the UI rendered as
     * `fit 1.00` — indistinguishable from a flawless decode, so a lock producing nothing
     * displayed maximum confidence.
     */
    @Test
    fun `fit quality is unknown, not perfect, before any marks are observed`() {
        val engine = LiveDecoderEngine()
        feed(engine, FloatArray(3 * 16_000))    // silence: nothing to fit
        assertTrue(engine.recentFits().isEmpty()) { "fixture unexpectedly produced fit samples" }
        assertNull(engine.fitQuality) {
            "fitQuality reads ${engine.fitQuality} with no observations behind it"
        }
    }

    /** The counterpart: once marks have been seen, a real score must be reported. */
    @Test
    fun `fit quality is reported once the observation window fills`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L)
        )
        val engine = LiveDecoderEngine()
        feed(engine, gen.samples)
        val fit = engine.fitQuality
        assertTrue(fit != null && fit > 0.5) { "clean 20 WPM reported fit $fit" }
    }
}

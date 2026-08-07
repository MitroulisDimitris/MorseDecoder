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
     * noisy room is most of the target audience.
     *
     * Was 145 characters against a 62-character reference at a reported 53 WPM against a
     * true 13. D-17's seed guard took the live path to 65.9 % CER at 21.5 WPM, emitting 99
     * characters against 99 sent — the character *count* is now right and the timebase is
     * within 1.65x rather than 4x. Still far short of the 50 % this asserts, and the file
     * path still declines outright, so the defect stays open.
     */
    @Test
    @KnownFailure("D-6", "a rough 13 WPM fist decodes at 66 % CER")
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
     * to notice. This read 53 WPM for a 13 WPM signal with `outputSuppressed` false
     * throughout, and was a `@KnownFailure("D-1")` on that basis.
     *
     * It passes since D-17, but **it did not start passing because anything noticed** — it
     * passes because the 4x error no longer occurs on this fixture. The reported speed is
     * now 21.5 WPM, inside the plausible band the assertion checks. Kept as a normal
     * regression test of D-17's seed guard, which is what it now measures.
     *
     * D-1 itself is not fixed and is not closed. Nothing in the system can still see a
     * scale error; what changed is that the synthetic matrix no longer produces one to
     * see. See `known-issues.md` D-1 for the evidence that remains.
     */
    @Test
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
     * D-1's reproducer, restored (step 4a). A clean 20 WPM signal decoded against a unit
     * pinned at 40 WPM — the octave-type scale error the defect is about.
     *
     * The pin (FR-9.5) is the *vehicle*, not the defect. It is the only supported way to
     * hand the decoder a unit known to be wrong, now that D-17 has stopped the bootstrap
     * producing one on any synthetic fixture; the real recording still bootstraps its own,
     * reading 30.6 WPM against a true 17.7, and is uncommitted (G-1). What the pin buys is
     * a scale error that can be *constructed on demand* rather than waited for.
     *
     * This test guards the reproducer itself. The previous one evaporated silently — D-17
     * removed the input that triggered it and the test went green without anything having
     * been fixed — so the premise is asserted separately from the defect. It holds under
     * any legitimate fix: a decoder that suppressed this output would score 100 % here, a
     * decoder that decoded it correctly would score near zero and this would fail loudly.
     */
    @Test
    fun `a unit pinned an octave out does not reproduce the text`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L)
        )
        val engine = LiveDecoderEngine()
        engine.pinWpm(40.0)          // true speed is 20
        feed(engine, gen.samples)
        val cer = TextMetrics.cer(gen.normalizedText, decoded(engine))
        assertTrue(cer > 0.5) {
            "a 2x unit error decoded at CER $cer — the scale-error reproducer has gone, " +
                "and D-1 has nothing holding it again"
        }
    }

    /**
     * The defect itself: **nothing in the system can see a scale error.**
     *
     * Against the fixture above the decoder emits 202 characters for 62 sent at 297 % CER,
     * 98.5 % of them `E`/`T`/`I`/`M`, and reports `fit 1.00` over a full observation window
     * with `outputSuppressed` false and the lock state LOCKED. The UI shows maximum
     * confidence beside `TA C Q TEE E EEE EEET ETTTT ET TEEE TETE TET T EEEE`.
     *
     * The cause is structural rather than a bad threshold, which is why no tuning closes
     * it. `ObservationFit` scores 2-means clusters over *normalised* durations
     * `ln(duration / unit)`, and `fitQuality` is separation x ratio x tightness over those
     * clusters — every term is invariant to the unit. Halving the unit shifts every z by
     * `ln 2` and leaves all three untouched. The model fits its own measurements perfectly;
     * the measurements are referred to the wrong unit.
     *
     * The bar is the gate's own suppression threshold: either the gate fires, or the score
     * is at least low enough that it would. Today neither is remotely true.
     *
     * Note the pin also disables D-21's periodic re-fit (FR-9.5 owns the unit), so the one
     * signal that *could* have caught this — a streaming-vs-batch disagreement — is not even
     * computed. That is a genuine gap, not an artefact of the reproducer.
     */
    @Test
    @KnownFailure("D-1", "a 2x unit error scores fit 1.00 with nothing suppressed")
    fun `a decode built on a wrong unit does not report perfect confidence`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L)
        )
        val engine = LiveDecoderEngine()
        engine.pinWpm(40.0)
        feed(engine, gen.samples)

        val out = decoded(engine)
        val letters = out.filter { it != ' ' }
        val singles = letters.count { it in "ETIM" }.toDouble() / maxOf(1, letters.length)
        val noticed = engine.outputSuppressed ||
            (engine.fitQuality ?: 1.0) <= engine.gateSuppressBelow
        assertTrue(noticed) {
            "emitted ${engine.transcript.size} characters for " +
                "${gen.normalizedText.count { it != ' ' }} sent at CER " +
                "${"%.3f".format(TextMetrics.cer(gen.normalizedText, out))}, " +
                "${"%.0f".format(100 * singles)} % of them E/T/I/M, and reported fit " +
                "${engine.fitQuality?.let { "%.2f".format(it) }} with outputSuppressed " +
                "${engine.outputSuppressed} in state ${engine.lockState}"
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
     * The file path's honest-failure gate, from both sides at once.
     *
     * It used to decline anything scoring under `fitQuality` 0.12, which sounds
     * conservative and measured as the opposite: a rough but genuine 13 WPM fist scores
     * 0.0987 and was thrown away whole, while speech (0.4741), knocking (0.4033) and a
     * clipping input stage (0.7386) all scored higher and were decoded. There is no cut
     * point that separates those populations — see `known-issues.md` D-8 — so the gate now
     * claims only what it can prove: a fit that is *structurally* impossible.
     *
     * Both halves are asserted together deliberately. Relaxing the gate is only defensible
     * if it does not also open the door to the fixtures it was correctly rejecting, and
     * those score a hard zero rather than a small number, which is why the door stays shut.
     */
    @Test
    fun `the file gate declines impossible fits and not merely poor ones`() {
        val gen = roughFist()
        val rough = MorseDecoder().decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
        assertTrue(rough.chars.isNotEmpty()) {
            "declined a rough fist that fits at ${"%.1f".format(rough.unitMs)} ms " +
                "(fit ${"%.4f".format(rough.modelFitQuality)})"
        }

        // The rejections the gate exists for, all of them hard zeros.
        val corpus = com.morsedecoder.core.fixtures.NonMorseAudio.corpus().toMap()
        for (name in listOf("music", "speech+hum", "music+noise")) {
            val r = MorseDecoder().decodeFile(
                FloatArrayPcmSource(corpus.getValue(name), com.morsedecoder.core.fixtures.NonMorseAudio.SAMPLE_RATE)
            )
            assertTrue(r.chars.isEmpty()) { "$name decoded ${r.chars.size} characters" }
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

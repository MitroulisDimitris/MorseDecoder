package com.morsedecoder.core.bench

import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.AcousticChannel
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Does an acoustic capture path reproduce **"correct tone, plausible speed, unusable text"**?
 *
 *   DIAG=1 gradlew :core:test --tests "*AcousticDiagnostic*" -i
 *
 * ### The question
 *
 * A phone pointed at a Morse video was reported decoding to gibberish while the tone lock
 * and the WPM readout both looked right. Every bench fixture delivers its signal by wire
 * and scores that class of signal at 2.5 % CER, so the bench is silent about it.
 * [AcousticChannel] was built to close that gap, and at first-guess parameters it **did
 * not**: RT60 0.25–0.35 s with 30–35 % wet, and a capture AGC, all decode at 2.5 % —
 * indistinguishable from clean. Only an extreme setting (0.6 s, 55 % wet) broke anything,
 * and it broke the *wrong* thing: the live path never locked at all.
 *
 * Guessing again would be the same mistake. This sweeps the channel's parameter space and
 * asks, for every point, which of four states the decoder is in:
 *
 * | state | meaning |
 * |---|---|
 * | `ok` | decodes, CER below 10 % |
 * | **`GIBBERISH`** | **locked, tone within 25 Hz, WPM within 25 %, and CER above 40 %** |
 * | `nolock` | never locked, or emitted nothing |
 * | `degraded` | wrong somewhere, but not the reported signature |
 *
 * `GIBBERISH` is the reported symptom stated as a predicate. If no point in the sweep
 * reaches it, **reverberation and capture AGC are not the mechanism** and this diagnostic
 * has done its job by saying so — that is a result worth having before any threshold is
 * tuned or any fix is designed.
 *
 * ### Why the columns are what they are
 *
 * The hypothesis under test is specific: a reverberant tail outlives key-up, so marks are
 * heard long and gaps short. The unit is estimated from **marks only** (`decisions.md`
 * D-2), so it inherits the inflation, and gaps are then measured against a unit that is
 * already too big — 3-unit character gaps read as 1-unit element gaps and characters merge.
 *
 * That predicts a signature, so the sweep measures it rather than inferring it from CER:
 * `markInfl` and `gapDefl` are the measured mark and gap durations against the generator's
 * own ground-truth timeline, and `mk/ch` is marks per committed character. Merging predicts
 * `markInfl > 1`, `gapDefl < 1`, and `mk/ch` **above** healthy — which is the direction
 * `OutputPlausibility` documents itself as blind to. If the numbers come out the other way
 * the mechanism is something else.
 *
 * ### This is calibrated against nothing
 *
 * The RT60 and AGC values here are plausible, not measured. `progress.md` lesson 5 is
 * precisely about what synthetic-only parameters are worth: the D-8 gate fitted to
 * synthetics rejects genuine Morse and accepts RTTY. **Nothing in this file should set a
 * threshold or justify a fix until it has been calibrated against a real phone recording**
 * (G-1, plan step 7). Its only current job is to locate the failure's neighbourhood, or to
 * rule the mechanism out.
 */
class AcousticDiagnostic {

    private val on get() = System.getenv("DIAG") != null

    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    private val trueWpm = 20.0
    private val trueTone = 612.0

    private data class Outcome(
        val cer: Double,
        val chars: Int,
        val wpm: Double?,
        val toneErr: Double,
        val lock: String,
        val marksPerChar: Double?,
        val fit: Double?,
        val implausible: Boolean,
    )

    /**
     * The reported symptom as a predicate: every readout a user would check looks right,
     * and the text is wrong anyway.
     */
    private fun state(o: Outcome): String = when {
        o.chars < 10 || o.lock.startsWith("SEAR") -> "nolock"
        o.cer < 0.10 -> "ok"
        o.toneErr < 25.0 && o.wpm != null &&
            abs(o.wpm - trueWpm) / trueWpm < 0.25 && o.cer > 0.40 -> "GIBBERISH"
        else -> "degraded"
    }

    @Test
    fun sweep() {
        if (!on) return

        println("=".repeat(104))
        println("ACOUSTIC DIAGNOSTIC — does a room + capture chain produce 'good lock, bad text'?")
        println("true tone ${trueTone.toInt()} Hz, true speed ${trueWpm.toInt()} WPM, +12 dB SNR")
        println("=".repeat(104))

        // --- 1. reverb alone, swept ---------------------------------------------------
        println()
        println("REVERB SWEEP (no AGC, no band limits)")
        header()
        for (rt60 in listOf(0.15, 0.25, 0.35, 0.50, 0.70, 1.00)) {
            for (mix in listOf(0.20, 0.35, 0.50, 0.65, 0.80)) {
                val cfg = AcousticChannel.Config(rt60S = rt60, reverbMix = mix)
                row("rt60=%.2f mix=%.2f".format(rt60, mix), cfg)
            }
        }

        // --- 2. AGC alone, swept on the parameter that fills gaps ---------------------
        println()
        println("AGC SWEEP (no reverb) — release is the gap-filling parameter")
        header()
        for (rel in listOf(0.05, 0.20, 0.40, 0.80, 1.50)) {
            for (maxGain in listOf(4.0, 12.0, 40.0)) {
                val cfg = AcousticChannel.Config(
                    agc = AcousticChannel.Agc(releaseS = rel, maxGain = maxGain),
                )
                row("agc rel=%.2f max=%.0f".format(rel, maxGain), cfg)
            }
        }

        // --- 3. the combination, at the edge reverb alone starts to bite --------------
        println()
        println("COMBINED — moderate reverb plus AGC plus speaker/mic band limits")
        header()
        for (rt60 in listOf(0.25, 0.35, 0.50)) {
            for (mix in listOf(0.35, 0.50, 0.65)) {
                val cfg = AcousticChannel.Config(
                    rt60S = rt60, reverbMix = mix,
                    highPassHz = 250.0, lowPassHz = 6000.0,
                    agc = AcousticChannel.Agc(),
                )
                row("rt60=%.2f mix=%.2f +agc+bl".format(rt60, mix), cfg)
            }
        }

        println()
        println("-".repeat(104))
        println("Read the 'state' column. GIBBERISH = the reported symptom reproduced:")
        println("  locked, tone < 25 Hz off, WPM within 25 %, and CER > 40 %.")
        println("If no row reaches it, reverb and AGC are NOT the mechanism behind the report.")
        println("markInfl/gapDefl test the merging story: it predicts markInfl > 1, gapDefl < 1,")
        println("and mk/ch ABOVE the healthy 3.4 — the side OutputPlausibility cannot see.")
        println("Every number here is uncalibrated. See the class docs before trusting one.")
    }

    private fun header() {
        println(
            "%-28s %7s %6s %7s %8s %6s %8s %8s %6s  %s".format(
                "channel", "CER%", "chars", "wpm", "toneErr", "mk/ch", "markInfl", "gapDefl", "fit", "state",
            ),
        )
        println("-".repeat(104))
    }

    private fun row(label: String, ch: AcousticChannel.Config) {
        val live = runLive(ch)
        val (markInfl, gapDefl) = channelTimingShift(ch)
        println(
            "%-28s %7.1f %6d %7s %8.1f %6s %8.3f %8.3f %6s  %s".format(
                label,
                live.cer * 100,
                live.chars,
                live.wpm?.let { "%.1f".format(it) } ?: "—",
                live.toneErr,
                live.marksPerChar?.let { "%.2f".format(it) } ?: "—",
                markInfl,
                gapDefl,
                live.fit?.let { "%.2f".format(it) } ?: "—",
                state(live) + if (live.implausible) " !impl" else "",
            ),
        )
    }

    private fun generate(ch: AcousticChannel.Config?) = SyntheticMorseGenerator.generate(
        Config(
            text = text, wpm = trueWpm, toneHz = trueTone, snrDb = 12.0, seed = 31L,
            acoustic = ch,
        ),
    )

    private fun runLive(ch: AcousticChannel.Config): Outcome {
        val sig = generate(ch)
        val engine = LiveDecoderEngine()
        var i = 0
        while (i < sig.samples.size) {
            val n = minOf(1280, sig.samples.size - i)   // 80 ms, what MicCapture feeds
            engine.feed(sig.samples.copyOfRange(i, i + n), n)
            i += n
        }
        engine.finish()
        val out = buildString {
            for ((k, c) in engine.transcript.withIndex()) {
                if (k > 0 && c.isWordStart) append(' ')
                append(c.token)
            }
        }
        return Outcome(
            cer = TextMetrics.cer(sig.normalizedText, out),
            chars = engine.transcript.size,
            wpm = engine.wpm,
            toneErr = abs(engine.toneHz - trueTone),
            lock = engine.lockState.name,
            marksPerChar = engine.marksPerChar,
            fit = engine.fitQuality,
            implausible = engine.outputImplausible,
        )
    }

    /**
     * How the channel moves total key-down and key-up time, independent of the decoder.
     *
     * Returns (mark inflation, gap retention): measured key-down time over true mark time,
     * and measured key-up time over true gap time. A transparent channel gives 1.0 and 1.0.
     * The merging hypothesis predicts **markInfl > 1 and gapRet < 1** — the reverb tail
     * spending gap time as mark time.
     *
     * ### The threshold has to be relative, and the first version was not
     *
     * This originally thresholded at half the peak of the *dry* signal. That is invalid
     * against a channel which changes the level: capture AGC normalises loudness, so a
     * perfectly good decode (3.8 % CER at `rel=0.80`) scored 0.004 on the mark statistic —
     * the whole signal had simply moved below a threshold computed from a different signal.
     * The column was measuring gain staging, not timing.
     *
     * The threshold is now half of the wet signal's own p95 envelope, so it asks the only
     * question that survives a level change: relative to how loud this signal actually is,
     * when is the key down?
     */
    private fun channelTimingShift(ch: AcousticChannel.Config): Pair<Double, Double> {
        val wet = generate(ch)
        val env = envelope(wet.samples, wet.sampleRate)
        val sorted = env.clone().also { it.sort() }
        val thr = 0.5 * sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]

        val msPerSample = 1000.0 / wet.sampleRate
        var trueMarkMs = 0.0
        var trueGapMs = 0.0
        for (e in wet.elements) if (e.isMark) trueMarkMs += e.durationMs else trueGapMs += e.durationMs

        // Only count inside the transmission; the lead-in and tail are silence by design.
        val firstMs = wet.elements.firstOrNull()?.startMs ?: 0.0
        val lastMs = wet.elements.lastOrNull()?.endMs ?: 0.0
        val from = (firstMs / msPerSample).toInt().coerceIn(0, env.size - 1)
        val to = (lastMs / msPerSample).toInt().coerceIn(0, env.size)

        var keyedSamples = 0
        for (k in from until to) if (env[k] > thr) keyedSamples++
        val keyedMs = keyedSamples * msPerSample
        val unkeyedMs = (to - from) * msPerSample - keyedMs

        val markInfl = if (trueMarkMs > 0) keyedMs / trueMarkMs else Double.NaN
        val gapRet = if (trueGapMs > 0) unkeyedMs / trueGapMs else Double.NaN
        return markInfl to gapRet
    }

    /** Crude one-pole magnitude envelope, ~5 ms — enough to time key edges. */
    private fun envelope(x: FloatArray, sampleRate: Int): DoubleArray {
        val a = kotlin.math.exp(-1.0 / (0.005 * sampleRate))
        val out = DoubleArray(x.size)
        var y = 0.0
        for (n in x.indices) {
            val m = abs(x[n]).toDouble()
            y = m + (y - m) * a
            out[n] = y
        }
        return out
    }
}

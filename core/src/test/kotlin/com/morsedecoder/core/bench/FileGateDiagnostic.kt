package com.morsedecoder.core.bench

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.fixtures.NonMorseAudio
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.model.KalmanUnit
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * File-path gate diagnostic — the file-mode counterpart to [BootstrapDiagnostic].
 *
 *   DIAG=1 gradlew :core:test --tests "*FileGateDiagnostic*" -i
 *
 * Asserts nothing and is skipped unless `DIAG` is set. It is an instrument, not a guard.
 *
 * ### What it answers
 *
 * `decodeFile` can decline a transmission at four different points, and when it returns
 * nothing the caller cannot tell which one fired or what the decode would have been. This
 * replicates both passes, names the deciding guard per fixture, and reports the transcript
 * that each guard is suppressing — so the *cost* of a veto is visible next to its benefit.
 *
 * Four sections, each written to answer one question:
 *
 * 1. **The main table** — every guard's verdict over the bench matrix and the whole
 *    non-Morse corpus, with an ungated decode beside it.
 * 2. **Length sensitivity** — the same fist at three transmission lengths. This is what
 *    retired the 0.12 fit threshold: `u0` holds at 86.8-89.2 ms across all three while
 *    `fitQuality` swings 0.0987-0.3312, so the veto was sampling noise, not signal.
 * 3. **Step 1b** — D-17's `bootstrapMaxRatio` ported to the file seed. Kept because a
 *    measured no-op is a result: it is the evidence that the file path's failure was never
 *    the unguarded 2-means, and it stops the port being re-proposed.
 *
 * Pass 1 is replicated rather than instrumented because `decodeFile` returns `empty()` on
 * every decline, discarding the statistics that explain it. The replication is checked by
 * the `chars` column, which comes from the real `decodeFile` and must agree with the
 * verdict this computes.
 */
class FileGateDiagnostic {

    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    private fun morseCases() = listOf(
        "hand-13-rough" to Config(text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
            dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L),
        "qsb-impulse" to Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 8.0,
            fadingDepth = 0.6, fadingRateHz = 0.15, impulsesPerSecond = 2.0, seed = 23L),
        "hand-18" to Config(text = text, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
            dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L),
        "clean-20" to Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 20.0, seed = 11L),
        "slow-7" to Config(text = text, wpm = 7.0, toneHz = 612.0, snrDb = 10.0, seed = 17L),
        "fast-35" to Config(text = text, wpm = 35.0, toneHz = 612.0, snrDb = 10.0, seed = 18L),
    )

    /** Verbatim from [MorseDecoder.twoMeansSeed]. */
    private fun twoMeansSeed(values: DoubleArray): DoubleArray {
        var c1 = values.min()
        var c2 = values.max()
        repeat(20) {
            var s1 = 0.0; var n1 = 0
            var s2 = 0.0; var n2 = 0
            for (v in values) {
                if (v - c1 <= c2 - v) { s1 += v; n1++ } else { s2 += v; n2++ }
            }
            if (n1 > 0) c1 = s1 / n1
            if (n2 > 0) c2 = s2 / n2
        }
        return if (c2 - c1 < ln(1.5)) doubleArrayOf(c1, c1 + ln(3.0)) else doubleArrayOf(c1, c2)
    }

    private data class Report(
        val marks: Int, val seedRatio: Double, val u0: Double,
        val fitQuality: Double, val sep: Double, val sigmaSum: Double,
        val ceiling: Boolean, val verdict: String,
        val ungatedChars: Int, val ungatedText: String,
    ) {
        /** Whether the retired 0.12 fit threshold would have declined this. */
        val declinedByOldGate: Boolean get() = fitQuality < 0.12
    }

    /**
     * Replicates decodeFile's pass 1 + pass 2 and names the first guard that fires.
     *
     * [maxSeedRatio] applies step 1b as specified — D-17's `bootstrapMaxRatio` ported to
     * the file path. There is no "keep listening" here, so a rejected seed can only fall
     * back to the weak 3:1 spread the low-end branch already uses.
     */
    private fun dissect(samples: FloatArray, rate: Int, maxSeedRatio: Double = Double.MAX_VALUE): Report? {
        val fe = FrontEnd().process(FloatArrayPcmSource(samples, rate), null)
        val marks = fe.runs.filter { it.isMark }.map { it.durationMs }
        if (marks.size < 4) return null
        val z = DoubleArray(marks.size) { ln(marks[it] / fe.bootstrapUnitMs) }
        val rawSeed = twoMeansSeed(z)
        val rawRatio = exp(rawSeed[1] - rawSeed[0])
        val seed = if (rawRatio > maxSeedRatio) doubleArrayOf(rawSeed[0], rawSeed[0] + ln(3.0)) else rawSeed
        val mix = GaussianMixture.fit(z, seed, initSigma = 0.3)
        val shift = mix.means[0]
        val u0 = fe.bootstrapUnitMs * exp(shift)
        mix.means[0] = 0.0
        mix.means[1] -= shift
        val fq = RobustTimingDecoder.fitQuality(mix)
        val sep = (mix.means[1] - mix.means[0]) /
            kotlin.math.sqrt(0.5 * (mix.sigmas[0] * mix.sigmas[0] + mix.sigmas[1] * mix.sigmas[1]))
        val sigmaSum = mix.sigmas[0] + mix.sigmas[1]

        // pass 2 with every gate lifted, so the cost of each gate is visible
        var ceiling = false
        var ungatedChars = 0
        var ungatedText = ""
        if (u0 in 15.0..500.0) {
            val zGaps = fe.runs.asSequence().filter { !it.isMark }
                .map { ln(it.durationMs / u0) }.filter { it <= ln(10.0) }.toList().toDoubleArray()
            val gapMix = GaussianMixture.fit(zGaps, doubleArrayOf(0.0, ln(3.0), ln(7.0)), initSigma = 0.3)
            if (gapMix.means[2] - gapMix.means[1] < 0.5) {
                val w1 = gapMix.weights[1]; val w2 = gapMix.weights[2]
                val merged = (w1 * gapMix.means[1] + w2 * gapMix.means[2]) / (w1 + w2)
                gapMix.means[1] = merged
                gapMix.sigmas[1] = maxOf(gapMix.sigmas[1], gapMix.sigmas[2])
                gapMix.weights[1] = w1 + w2
                gapMix.weights[2] = 0.0
                gapMix.means[2] = merged + 0.85
            }
            val k = KalmanUnit(u0, (0.25 * u0) * (0.25 * u0))
            val dec = RobustTimingDecoder(k, mix, gapMix)
            for (r in fe.runs) dec.feed(r)
            dec.flush()
            ceiling = k.atSpeedCeiling
            ungatedChars = dec.output.size
            ungatedText = buildString {
                for ((i, c) in dec.output.withIndex()) {
                    if (i > 0 && c.isWordStart) append(' ')
                    append(c.token)
                }
            }
        }

        val verdict = when {
            u0 < 15.0 || u0 > 500.0 -> "u0 out of range"
            fq <= 0.0 -> "impossible fit"
            ceiling -> "atSpeedCeiling"
            else -> "decodes"
        }
        return Report(marks.size, rawRatio, u0, fq, sep, sigmaSum, ceiling,
            verdict, ungatedChars, ungatedText)
    }

    private fun row(name: String, r: Report?, chars: Int, reference: String?) {
        if (r == null) { println("  %-16s %s".format(name, "too few marks")); return }
        val ungatedCer = reference?.let { "%.1f %%".format(TextMetrics.cer(it, r.ungatedText) * 100) } ?: "-"
        println("  %-16s %5d %8.2f %9.1f %9.4f %7.2f %7.2f %6d %6d %-9s %-6s %s".format(
            name, r.marks, r.seedRatio, r.u0, r.fitQuality, r.sep, r.sigmaSum,
            chars, r.ungatedChars, ungatedCer,
            if (r.declinedByOldGate) "yes" else "-", r.verdict))
    }

    @Test
    fun probe() {
        if (System.getenv("DIAG") == null) return
        println()
        println("FILE PATH — pass-1 statistics and the guard that decides the outcome")
        println("   'chars' is what ships today; 'ung*' is the same decode with every gate lifted;")
        println("   'old' marks what the retired fitQuality < 0.12 threshold would have declined.")
        println("  %-16s %5s %8s %9s %9s %7s %7s %6s %6s %-9s %-6s %s".format(
            "fixture", "marks", "seedR", "u0 ms", "fitQual", "sep", "sig0+1",
            "chars", "ungC", "ungCER", "old", "verdict"))
        println("  " + "-".repeat(118))

        println("  -- Morse " + "-".repeat(100))
        for ((name, cfg) in morseCases()) {
            val gen = SyntheticMorseGenerator.generate(cfg)
            val res = MorseDecoder().decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
            row(name, dissect(gen.samples, gen.sampleRate), res.chars.size, gen.normalizedText)
        }

        println("  -- not Morse " + "-".repeat(96))
        for ((name, audio) in NonMorseAudio.corpus()) {
            val res = MorseDecoder().decodeFile(FloatArrayPcmSource(audio, NonMorseAudio.SAMPLE_RATE))
            row(name, dissect(audio, NonMorseAudio.SAMPLE_RATE), res.chars.size, null)
        }

        // Same fist, same impairments, same seed — only the length of the transmission
        // differs. If the gate's verdict moves, it is partly measuring recording length.
        println()
        println("LENGTH SENSITIVITY — hand-13-rough at three transmission lengths")
        println("  %-8s %6s %9s %9s %6s %s".format("chars", "marks", "u0 ms", "fitQual", "ungC", "verdict"))
        println("  " + "-".repeat(70))
        val long = "CQ CQ CQ DE SV1ABC SV1ABC K THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG " +
            "0123456789 UR RST 599 QTH ATHENS TNX FER CALL 73 ES GL"
        for (t in listOf(text.take(40), text, long)) {
            val cfg = Config(text = t, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
                dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L)
            val gen = SyntheticMorseGenerator.generate(cfg)
            val g = dissect(gen.samples, gen.sampleRate) ?: continue
            println("  %-8d %6d %9.1f %9.4f %6d %s".format(
                t.length, g.marks, g.u0, g.fitQuality, g.ungatedChars, g.verdict))
        }

        // Step 1b as specified: does D-17's 4.5:1 seed guard change anything here?
        println()
        println("STEP 1b — the same table with bootstrapMaxRatio = 4.5 applied to the file seed")
        println("  %-16s %8s %9s %9s %6s %-9s %s".format(
            "fixture", "seedR", "u0 ms", "fitQual", "ungC", "ungCER", "verdict"))
        println("  " + "-".repeat(90))
        for ((name, cfg) in morseCases()) {
            val gen = SyntheticMorseGenerator.generate(cfg)
            val g = dissect(gen.samples, gen.sampleRate, maxSeedRatio = 4.5) ?: continue
            println("  %-16s %8.2f %9.1f %9.4f %6d %-9s %s".format(
                name, g.seedRatio, g.u0, g.fitQuality, g.ungatedChars,
                "%.1f %%".format(TextMetrics.cer(gen.normalizedText, g.ungatedText) * 100), g.verdict))
        }
        for ((name, audio) in NonMorseAudio.corpus()) {
            val g = dissect(audio, NonMorseAudio.SAMPLE_RATE, maxSeedRatio = 4.5) ?: continue
            println("  %-16s %8.2f %9.1f %9.4f %6d %-9s %s".format(
                name, g.seedRatio, g.u0, g.fitQuality, g.ungatedChars, "-", g.verdict))
        }
        println()
    }
}

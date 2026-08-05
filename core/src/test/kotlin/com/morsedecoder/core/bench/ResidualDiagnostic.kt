package com.morsedecoder.core.bench

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.model.KalmanUnit
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.ElementKind
import com.morsedecoder.core.synth.SyntheticMorseGenerator.GeneratedSignal
import com.morsedecoder.core.synth.SyntheticMorseGenerator.TimedElement
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Residual diagnostic — plan step 6a. Where does the error on a rough fist actually come
 * from, now that the timebase is right?
 *
 *   DIAG=1 gradlew :core:test --tests "*ResidualDiagnostic*" -i
 *
 * Asserts nothing and is skipped unless `DIAG` is set. It is an instrument, not a guard.
 *
 * ### Why this exists
 *
 * D-17 removed the bootstrap's 4x unit error and D-18 removed the file path's veto, and
 * `hand-13-rough` still decodes at ~65 % on both paths. The file path is the honest place
 * to ask why: it is two-pass, has no warmup, no chunk dependency (N-2), and fits `u0` to
 * within 1.04x of truth — every excuse the live path has is already gone.
 *
 * ### The ladder
 *
 * Each row hands one more stage the generator's ground truth, so the CER *drop* between
 * two rows is the error that stage was contributing. `prior-art.md` idea 1 (the
 * soft-decision front end) targets segmentation; if row D is no better than row A, that
 * idea cannot be what stands between us and a good decode.
 *
 *   A  measured runs, fitted models, tracking Kalman   the shipped file path
 *   B  measured runs, fitted models, frozen Kalman     isolates tracking
 *   C  measured runs, truth models,  tracking Kalman   isolates the fitted model
 *   D  TRUE runs,     fitted models, tracking Kalman   isolates segmentation
 *   E  TRUE runs,     truth models,  frozen Kalman     everything but the lattice
 *
 * Row E is the one that matters. If it is near zero the error is distributed across the
 * stages above it and the ladder says which. If it is still high, the lattice itself is
 * losing the transmission and no front end will save it.
 */
class ResidualDiagnostic {

    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    private data class Case(val name: String, val cfg: Config)

    private fun cases() = listOf(
        Case("hand-13-rough", Config(text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
            dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L)),
        Case("hand-18", Config(text = text, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
            dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L)),
        Case("clean-20", Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 20.0, seed = 11L)),
        // The two scenarios that exist to exercise tracking. If row B (frozen Kalman) is a
        // free win on a rough fist it must be a loss here, or the tracker is doing nothing
        // at all and should be deleted rather than tuned.
        Case("drift-ramp", Config(text = text, wpm = 15.0, toneHz = 612.0, snrDb = 10.0,
            drift = SyntheticMorseGenerator.SpeedDrift.LinearRamp(25.0, 60.0), seed = 20L)),
        Case("wobble", Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
            drift = SyntheticMorseGenerator.SpeedDrift.SineWobble(0.15, 0.05), seed = 21L)),
    )

    // ---- ground truth ----------------------------------------------------------------

    /** The generator's own timeline as runs — what a perfect front end would have produced. */
    private fun trueRuns(gen: GeneratedSignal): List<FrontEnd.Run> =
        gen.elements.map { FrontEnd.Run(it.isMark, it.startMs, it.durationMs) }

    // ---- the decode, with each stage swappable --------------------------------------

    private fun decode(
        runs: List<FrontEnd.Run>,
        unitMs: Double,
        markMix: GaussianMixture,
        gapMix: GaussianMixture,
        freezeKalman: Boolean,
    ): String {
        val k = KalmanUnit(unitMs, (0.25 * unitMs) * (0.25 * unitMs))
        k.frozen = freezeKalman
        val dec = RobustTimingDecoder(k, markMix, gapMix)
        for (r in runs) dec.feed(r)
        dec.flush()
        return buildString {
            for ((i, c) in dec.output.withIndex()) {
                if (i > 0 && c.isWordStart) append(' ')
                append(c.token)
            }
        }
    }

    /** Pass 1 exactly as `MorseDecoder.decodeFile` does it, over whatever runs it is given. */
    private data class Fit(val unitMs: Double, val markMix: GaussianMixture, val gapMix: GaussianMixture)

    private fun fitFrom(runs: List<FrontEnd.Run>, bootstrapUnitMs: Double): Fit {
        val marks = runs.filter { it.isMark }.map { it.durationMs }
        val z = DoubleArray(marks.size) { ln(marks[it] / bootstrapUnitMs) }
        val markMix = GaussianMixture.fit(z, twoMeansSeed(z), initSigma = 0.3)
        val shift = markMix.means[0]
        val u0 = bootstrapUnitMs * exp(shift)
        markMix.means[0] = 0.0
        markMix.means[1] -= shift
        val zGaps = runs.asSequence().filter { !it.isMark }
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
        return Fit(u0, markMix, gapMix)
    }

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

    /** Models built from what the generator was told to send, not from measurements. */
    private fun truthModels(cfg: Config): Pair<GaussianMixture, GaussianMixture> =
        GaussianMixture.of(doubleArrayOf(0.0, ln(cfg.dashDotRatio)), 0.25) to
            GaussianMixture.of(doubleArrayOf(0.0, ln(3.0), ln(7.0)), 0.25)

    // ---- segmentation attribution ----------------------------------------------------

    private data class SegStats(
        val trueMarks: Int, val measuredMarks: Int,
        val clean: Int, val fragmented: Int, val merged: Int, val spurious: Int, val dropped: Int,
        val medianEdgeErrMs: Double, val medianDurErrPct: Double,
    )

    private fun attribute(runs: List<FrontEnd.Run>, elements: List<TimedElement>): SegStats {
        val trueMarks = elements.filter { it.isMark }
        val measured = runs.filter { it.isMark }
        var clean = 0; var frag = 0; var merged = 0; var spurious = 0
        val hitTrue = HashSet<Int>()
        val edgeErrs = ArrayList<Double>()
        val durErrs = ArrayList<Double>()
        for (r in measured) {
            val end = r.startMs + r.durationMs
            val hits = trueMarks.withIndex().filter { (_, t) -> t.startMs < end && t.endMs > r.startMs }
            if (hits.isEmpty()) { spurious++; continue }
            hits.forEach { hitTrue.add(it.index) }
            if (hits.size > 1) { merged++; continue }
            val t = hits[0].value
            val overlap = minOf(end, t.endMs) - maxOf(r.startMs, t.startMs)
            if (overlap / t.durationMs < 0.6) frag++ else clean++
            edgeErrs.add(abs(r.startMs - t.startMs))
            durErrs.add(100.0 * abs(r.durationMs - t.durationMs) / t.durationMs)
        }
        return SegStats(
            trueMarks.size, measured.size, clean, frag, merged, spurious,
            trueMarks.size - hitTrue.size, median(edgeErrs), median(durErrs),
        )
    }

    private fun median(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sorted()[v.size / 2]

    // ---- run --------------------------------------------------------------------------

    @Test
    fun diagnose() {
        if (System.getenv("DIAG") == null) return

        for (case in cases()) {
            val gen = SyntheticMorseGenerator.generate(case.cfg)
            val ref = gen.normalizedText
            val trueUnit = 1200.0 / case.cfg.wpm
            val fe = FrontEnd().process(FloatArrayPcmSource(gen.samples, gen.sampleRate), null)

            println()
            println("=".repeat(96))
            println("${case.name}  —  ${case.cfg.wpm} WPM, true unit ${"%.1f".format(trueUnit)} ms, " +
                "ratio ${case.cfg.dashDotRatio}:1, jitter ${(case.cfg.jitterFraction * 100).toInt()} %")
            println("=".repeat(96))

            // -- segmentation, over the whole recording rather than just the seed --
            val s = attribute(fe.runs, gen.elements)
            println("segmentation   %d measured marks against %d true (%.2fx)".format(
                s.measuredMarks, s.trueMarks, s.measuredMarks.toDouble() / s.trueMarks))
            println("               clean %d   fragmented %d   merged %d   spurious %d   dropped %d".format(
                s.clean, s.fragmented, s.merged, s.spurious, s.dropped))
            println(("               median leading-edge error %.1f ms (%.0f %% of a dot),  " +
                "median duration error %.1f %%").format(
                s.medianEdgeErrMs, 100.0 * s.medianEdgeErrMs / trueUnit, s.medianDurErrPct))

            // -- the ladder --
            val measuredFit = fitFrom(fe.runs, fe.bootstrapUnitMs)
            val tRuns = trueRuns(gen)
            val trueFit = fitFrom(tRuns, fe.bootstrapUnitMs)
            // Fresh model objects per row: GaussianMixture carries mutable online state, so
            // reusing one instance would let row C's updates leak into row E.
            val (tMark, tGap) = truthModels(case.cfg)
            val (tMark2, tGap2) = truthModels(case.cfg)

            val rows = listOf(
                Triple("A  shipped file path", decode(fe.runs, measuredFit.unitMs, measuredFit.markMix, measuredFit.gapMix, false), "baseline"),
                Triple("B  + frozen Kalman", decode(fe.runs, measuredFit.unitMs, measuredFit.markMix, measuredFit.gapMix, true), "isolates tracking"),
                Triple("C  + truth models", decode(fe.runs, trueUnit, tMark, tGap, false), "isolates the fitted model"),
                Triple("D  + TRUE runs", decode(tRuns, trueFit.unitMs, trueFit.markMix, trueFit.gapMix, false), "isolates segmentation"),
                Triple("E  + TRUE runs, truth models, frozen", decode(tRuns, trueUnit, tMark2, tGap2, true), "everything but the lattice"),
            )
            println()
            println("  %-38s %8s %7s  %s".format("counterfactual", "CER", "chars", "isolates"))
            println("  " + "-".repeat(90))
            val sent = ref.count { it != ' ' }
            for ((label, decoded, note) in rows) {
                println("  %-38s %7.1f %% %7d  %s".format(
                    label, TextMetrics.cer(ref, decoded) * 100, decoded.count { it != ' ' }, note))
            }
            println("  (%d characters sent)".format(sent))
            println()
            println("  ref: ${ref.take(70)}")
            println("  A:   ${rows[0].second.take(70)}")
            println("  E:   ${rows[4].second.take(70)}")
        }
        println()
    }
}

package com.morsedecoder.core.bench

import com.morsedecoder.core.AcceptanceTest
import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.audio.WavIo
import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.fixtures.NonMorseAudio
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Hesitation
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Structure bench — can the *shape* of the timing distribution tell Morse from not-Morse?
 *
 * This is the measurement behind known-issues D-8. `modelFitQuality` provably cannot make
 * that distinction (its distributions for RTTY and hand-sent Morse overlap completely), so
 * the question is whether some other cheap statistic can, without reaching for a neural
 * model. Not a test: it asserts nothing and is skipped unless `BENCH` is set.
 *
 *   BENCH=1 gradlew :core:test --tests "*StructureBench*" -i
 *   BENCH=1 REAL_WAV=path/to/real.wav gradlew :core:test --tests "*StructureBench*" -i
 *
 * `REAL_WAV` matters more than everything else here. Tuned on synthetic fixtures alone,
 * the most promising statistic below lands on a threshold that **rejects genuine
 * hand-sent Morse and accepts RTTY** — the exact inversion of what is wanted. Only a real
 * recording reveals that, which is why G-1 is filed as a risk rather than a nicety.
 *
 * ### The statistics
 *
 * - **gapErr** — the one worth knowing about. Fit the dot unit from *marks only* (D-2),
 *   then measure how far the *gaps* land from the 1/3/7-unit lattice they should occupy.
 *   Marks and gaps sharing one unit is a property of Morse that nothing else has reason
 *   to satisfy, and no other statistic here uses the two together.
 * - **spread** — p95/p5 of mark durations. Morse has two lengths, so ~3. A ladder of
 *   lengths, as FSK produces, runs higher.
 * - **r2** — RMS residual of a two-cluster fit in log space. High when two clusters are
 *   the wrong description.
 * - **off** — fraction of marks far from both fitted centres. Included because it was the
 *   original hypothesis, and it is the one that failed: see the note at the bottom.
 */
class StructureBench {

    private data class Stats(
        val marks: Int,
        val unitMs: Double,
        val dashRatio: Double,
        val spread: Double,
        val r2: Double,
        val off: Double,
        val gapErr: Double,
    )

    private fun statsFor(audio: FloatArray): Stats? {
        val out = FrontEnd().process(FloatArrayPcmSource(audio, 16_000))
        val m = out.runs.filter { it.isMark }.map { it.durationMs }.toDoubleArray()
        if (m.size < 12) return null
        val g = out.runs.filter { !it.isMark }.map { it.durationMs }.toDoubleArray()

        val logs = DoubleArray(m.size) { ln(m[it]) }
        val (lo, hi) = twoMeans(logs)
        val unit = exp(lo)
        val sorted = m.sortedArray()
        fun pct(q: Double) = sorted[(q * (sorted.size - 1)).toInt()]

        val tol = ln(1.30)
        val off = m.count { minOf(abs(ln(it) - lo), abs(ln(it) - hi)) > tol }.toDouble() / m.size

        return Stats(
            marks = m.size,
            unitMs = unit,
            dashRatio = exp(hi - lo),
            spread = pct(0.95) / maxOf(pct(0.05), 1e-9),
            r2 = kMeansResidual(logs, 2),
            off = off,
            gapErr = gapLatticeError(g, unit),
        )
    }

    /** Morse gaps sit at 1, 3 and 7 units of the unit the *marks* defined. */
    private fun gapLatticeError(gapsMs: DoubleArray, unitMs: Double): Double {
        if (gapsMs.isEmpty()) return Double.NaN
        val allowed = doubleArrayOf(ln(1.0), ln(3.0), ln(7.0))
        var acc = 0.0
        for (gap in gapsMs) {
            val z = ln(gap / unitMs)
            var best = Double.MAX_VALUE
            for (a in allowed) best = minOf(best, abs(z - a))
            acc += best * best
        }
        return sqrt(acc / gapsMs.size)
    }

    private fun twoMeans(logs: DoubleArray): Pair<Double, Double> {
        var c1 = logs.min()
        var c2 = logs.max()
        repeat(30) {
            var s1 = 0.0; var n1 = 0; var s2 = 0.0; var n2 = 0
            for (v in logs) if (v - c1 <= c2 - v) { s1 += v; n1++ } else { s2 += v; n2++ }
            if (n1 > 0) c1 = s1 / n1
            if (n2 > 0) c2 = s2 / n2
        }
        return c1 to c2
    }

    private fun kMeansResidual(logs: DoubleArray, k: Int): Double {
        if (logs.size < k) return Double.NaN
        val sorted = logs.sorted()
        val c = DoubleArray(k) { sorted[(it * (sorted.size - 1)) / maxOf(1, k - 1)] }
        repeat(40) {
            val sum = DoubleArray(k); val cnt = IntArray(k)
            for (v in logs) {
                var best = 0
                for (j in 1 until k) if (abs(v - c[j]) < abs(v - c[best])) best = j
                sum[best] += v; cnt[best]++
            }
            for (j in 0 until k) if (cnt[j] > 0) c[j] = sum[j] / cnt[j]
        }
        var ss = 0.0
        for (v in logs) {
            var best = 0
            for (j in 1 until k) if (abs(v - c[j]) < abs(v - c[best])) best = j
            ss += (v - c[best]) * (v - c[best])
        }
        return sqrt(ss / logs.size)
    }

    private fun row(label: String, kind: String, audio: FloatArray) {
        val s = statsFor(audio)
        if (s == null) {
            println("STRUCT %-9s %-18s  (fewer than 12 marks — already declined upstream)".format(kind, label))
            return
        }
        println(
            "STRUCT %-9s %-18s marks=%3d unit=%6.1f ratio=%5.2f spread=%5.2f r2=%.3f off=%.2f gapErr=%.3f"
                .format(kind, label, s.marks, s.unitMs, s.dashRatio, s.spread, s.r2, s.off, s.gapErr)
        )
    }

    @Test
    fun structure() {
        if (System.getenv("BENCH") == null) return
        val text = AcceptanceTest.LONG_TEXT

        println()
        println("=".repeat(100))
        println("STRUCTURE BENCH — can timing shape separate Morse from not-Morse? (known-issues D-8)")
        println("=".repeat(100))

        for ((n, c) in listOf(
            "clean-20" to Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 20.0, seed = 11L),
            "20@10" to Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L),
            "20@0" to Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 0.0, seed = 13L),
            "hand-18" to Config(text = text, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
                dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L),
            "bug-2to1" to Config(text = text, wpm = 22.0, toneHz = 612.0, snrDb = 10.0,
                dashDotRatio = 2.0, jitterFraction = 0.15, seed = 31L),
            "slow-7" to Config(text = text, wpm = 7.0, toneHz = 612.0, snrDb = 10.0, seed = 17L),
            "fast-35" to Config(text = text, wpm = 35.0, toneHz = 612.0, snrDb = 10.0, seed = 18L),
            "farnsworth" to Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
                farnsworthSpacingWpm = 8.0, seed = 19L),
            "hesitation" to Config(text = text, wpm = 16.0, toneHz = 612.0, snrDb = 10.0,
                hesitation = Hesitation(), seed = 22L),
            "qsb-impulse" to Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 8.0,
                fadingDepth = 0.6, fadingRateHz = 0.15, impulsesPerSecond = 2.0, seed = 23L),
        )) row(n, "MORSE", SyntheticMorseGenerator.generate(c).samples)

        // A rough fist across seeds: the hardest legitimate case, so its spread across
        // seeds is what any threshold has to clear.
        for (seed in longArrayOf(16L, 101L, 102L, 103L, 104L, 105L)) {
            row("hand-13-rough/$seed", "MORSE", SyntheticMorseGenerator.generate(
                Config(text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
                    dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = seed)
            ).samples)
        }

        for ((n, a) in NonMorseAudio.corpus(30.0)) row(n, "NOT-MORSE", a)
        for (seed in longArrayOf(201L, 202L, 203L)) {
            row("rtty/$seed", "NOT-MORSE", NonMorseAudio.rtty(seconds = 30.0, seed = seed))
            row("speech/$seed", "NOT-MORSE", NonMorseAudio.speech(30.0, seed = seed))
        }

        val realPath = System.getenv("REAL_WAV")
        if (realPath == null) {
            println()
            println("No REAL_WAV set. Read the conclusion in known-issues.md before trusting")
            println("any threshold derived from the synthetic rows alone — it inverts on real audio.")
        } else {
            val wav = WavIo.read(File(realPath))
            row("real (whole)", "REAL", wav.samples)
            val from = minOf(16 * 16_000, wav.samples.size)
            val to = minOf(66 * 16_000, wav.samples.size)
            if (to > from) row("real (keyed only)", "REAL", wav.samples.copyOfRange(from, to))
        }
        println("=".repeat(100))
        println()
    }
}

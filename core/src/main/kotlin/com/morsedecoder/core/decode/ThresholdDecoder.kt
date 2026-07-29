package com.morsedecoder.core.decode

import com.morsedecoder.core.audio.PcmSource
import com.morsedecoder.core.audio.readAllSamples
import com.morsedecoder.core.dsp.Goertzel
import com.morsedecoder.core.dsp.Spectrum
import com.morsedecoder.core.morse.MorseAlphabet
import kotlin.math.sqrt

/**
 * Milestone-1 baseline: a simple threshold decoder for machine-sent code, 10–25 WPM.
 *
 * This is deliberately the dumb version — fixed percentile floor, Schmitt trigger,
 * 2-means dot/dash split, hard gap thresholds. It exists to make the measurement
 * harness real (§7.1); the robust mixture/lattice decoder of milestone 2 replaces
 * its classification stages while keeping the same front end.
 *
 * File-mode shape per FM-1: two-pass — sweep for the tone first, then decode with the
 * frequency already locked.
 */
class ThresholdDecoder(
    private val windowMs: Double = 10.0,
    private val hopMs: Double = 2.5,
) {

    data class Result(
        val text: String,
        val toneHz: Double,
        val unitMs: Double,
        /** (isMark, durationMs) runs after glitch pruning — kept for diagnostics. */
        val runs: List<Pair<Boolean, Double>>,
    )

    companion object {
        /** Rendered for patterns with no alphabet entry (FR-6.5: nothing dropped silently). */
        const val UNDECODABLE = "<?>"
    }

    fun decode(source: PcmSource, toneHzOverride: Double? = null): Result {
        val samples = source.readAllSamples()
        val sr = source.sampleRate

        // Pass 1 — tone sweep (FR-2.1).
        val toneHz = toneHzOverride
            ?: Spectrum.estimateToneHz(samples, sr)
            ?: return Result("", 0.0, 0.0, emptyList())

        // Envelope at frame rate: Goertzel power per hop (FR-2.2).
        val win = (windowMs * sr / 1000.0).toInt()
        val hop = (hopMs * sr / 1000.0).toInt()
        if (samples.size < win) return Result("", toneHz, 0.0, emptyList())
        val goertzel = Goertzel(sr, toneHz, win)
        val frames = (samples.size - win) / hop + 1
        val env = DoubleArray(frames) { goertzel.power(samples, it * hop) }
        medianFilter3(env)

        // Noise floor as a low percentile, never a mean (FR-3.1).
        val sorted = env.sorted()
        val floor = sorted[(0.20 * (sorted.size - 1)).toInt()]
        val peak = sorted[(0.95 * (sorted.size - 1)).toInt()]
        // < ~6 dB floor-to-peak spread: nothing keying.
        if (peak < 1e-12 || peak < floor * 4.0) return Result("", toneHz, 0.0, emptyList())

        // Schmitt trigger (FR-3.2), thresholds in LINEAR power. Placing "on" at the
        // midpoint of the floor->peak range makes the crossing land at ~half-window
        // overlap with a mark edge, so measured durations carry no systematic bias.
        // (In dB the threshold sits orders of magnitude below peak and every mark
        // gets widened by nearly a full analysis window.)
        val onThr = floor + 0.50 * (peak - floor)
        val offThr = floor + 0.25 * (peak - floor)
        val bits = BooleanArray(frames)
        var state = false
        for (i in 0 until frames) {
            state = if (state) env[i] > offThr else env[i] > onThr
            bits[i] = state
        }

        // Run-length encode to (isMark, durationMs).
        var runs = rle(bits, hopMs)
        runs = trimSilence(runs)
        if (runs.none { it.first }) return Result("", toneHz, 0.0, emptyList())

        // Unit estimate from marks only (D-2), then prune sub-element glitches (FR-3.4)
        // and re-estimate.
        var unit = estimateUnit(runs)
        runs = pruneRuns(runs, 0.3 * unit)
        runs = trimSilence(runs)
        if (runs.none { it.first }) return Result("", toneHz, unit, emptyList())
        unit = estimateUnit(runs)

        // Calibrated band guard (D-1): 40 WPM is a 30 ms unit. A "unit" far below that
        // is threshold chatter on noise, not keying — emit nothing rather than garbage
        // (P-4). The full low-signal state (FR-3.7) arrives with the live path.
        if (unit < 20.0) return Result("", toneHz, unit, runs)

        // Classify and assemble characters.
        val marks = runs.filter { it.first }.map { it.second }
        val (dotMs, dashMs) = twoMeans(marks)
        val markSplit = sqrt(dotMs * dashMs) // geometric midpoint

        // Threshold crossings measure every mark short/long by the same constant
        // (keying ramps, window smear). Dot and dash centroids carry that identical
        // offset, so their half-difference is a bias-free unit estimate.
        // BASELINE ONLY: this leans on the machine 3:1 ratio. The M2 mixture model
        // must not assume it (D-3) — it gets bias-robustness from fitting instead.
        val unitOut = if (dotMs > 0 && dashMs / dotMs >= 1.7) (dashMs - dotMs) / 2.0 else unit
        val sb = StringBuilder()
        val pattern = StringBuilder()

        fun flush() {
            if (pattern.isEmpty()) return
            sb.append(MorseAlphabet.tokenFor(pattern.toString()) ?: UNDECODABLE)
            pattern.setLength(0)
        }

        for ((isMark, dur) in runs) {
            if (isMark) {
                pattern.append(if (dur < markSplit) '.' else '-')
            } else {
                when {
                    dur < 2.0 * unitOut -> Unit           // intra-character gap
                    dur < 5.0 * unitOut -> flush()        // inter-character gap
                    else -> { flush(); sb.append(' ') }   // word gap
                }
            }
        }
        flush()

        return Result(sb.toString().trim(), toneHz, unitOut, runs)
    }

    // ---- helpers ----

    private fun rle(bits: BooleanArray, hopMs: Double): List<Pair<Boolean, Double>> {
        val runs = ArrayList<Pair<Boolean, Double>>()
        var cur = bits[0]
        var count = 0
        for (b in bits) {
            if (b == cur) count++ else {
                runs.add(Pair(cur, count * hopMs))
                cur = b
                count = 1
            }
        }
        runs.add(Pair(cur, count * hopMs))
        return runs
    }

    private fun trimSilence(runs: List<Pair<Boolean, Double>>): List<Pair<Boolean, Double>> {
        val first = runs.indexOfFirst { it.first }
        val last = runs.indexOfLast { it.first }
        return if (first < 0) emptyList() else runs.subList(first, last + 1)
    }

    /** Dot duration from mark durations only: 2-means, smaller centroid wins. */
    private fun estimateUnit(runs: List<Pair<Boolean, Double>>): Double {
        val marks = runs.filter { it.first }.map { it.second }
        val (dot, dash) = twoMeans(marks)
        // Degenerate fit (all dots or all dashes): fall back to the smaller centroid
        // read as a dot — good enough for the baseline.
        return if (dash / dot < 1.7) marks.min() else dot
    }

    /** 1-D 2-means. Returns (smallMean, largeMean); collapses gracefully on tiny input. */
    private fun twoMeans(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return Pair(0.0, 0.0)
        if (values.size == 1) return Pair(values[0], values[0])
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
        return Pair(c1, c2)
    }

    /**
     * Merge runs shorter than [minMs] into their neighbours (FR-3.4/FR-3.5 in baseline
     * form): a too-short run flips to its surroundings, joining the runs either side.
     */
    private fun pruneRuns(runs: List<Pair<Boolean, Double>>, minMs: Double): List<Pair<Boolean, Double>> {
        val work = runs.toMutableList()
        while (true) {
            var shortest = -1
            for (i in work.indices) {
                if (work[i].second < minMs && (shortest < 0 || work[i].second < work[shortest].second)) {
                    shortest = i
                }
            }
            if (shortest < 0) break
            when {
                work.size == 1 -> break
                shortest == 0 -> {
                    val merged = Pair(work[1].first, work[0].second + work[1].second)
                    work.removeAt(0); work[0] = merged
                }
                shortest == work.size - 1 -> {
                    val merged = Pair(work[shortest - 1].first, work[shortest - 1].second + work[shortest].second)
                    work.removeAt(shortest); work[shortest - 1] = merged
                }
                else -> {
                    val merged = Pair(
                        work[shortest - 1].first,
                        work[shortest - 1].second + work[shortest].second + work[shortest + 1].second
                    )
                    work[shortest - 1] = merged
                    work.removeAt(shortest + 1)
                    work.removeAt(shortest)
                }
            }
        }
        return work
    }

    private fun medianFilter3(x: DoubleArray) {
        if (x.size < 3) return
        val copy = x.copyOf()
        for (i in 1 until x.size - 1) {
            val a = copy[i - 1]; val b = copy[i]; val c = copy[i + 1]
            x[i] = maxOf(minOf(a, b), minOf(maxOf(a, b), c))
        }
    }
}

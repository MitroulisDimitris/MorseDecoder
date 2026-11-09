package com.morsedecoder.core.decode

import kotlin.math.sqrt

/**
 * Keying weight: a constant offset the receive chain adds to every mark and takes from
 * every gap. **Off by default** (`decisions.md` D-25).
 *
 * A Schmitt threshold on an envelope that does not switch cleanly moves every boundary by
 * the same time, not the same fraction, and the unit is estimated from marks: short marks
 * read as fast sending, the gaps then look long against that unit, and characters split into
 * `E`/`T` fragments. Built for the phone captures of `known-issues.md` D-14, where every mark
 * came out ~20 ms short in whole-file medians. That symptom turned out not to be a constant
 * offset at all — the phone's UNPROCESSED input swallowed each mark's onset by an amount
 * that grew with the silence before it — and was fixed at the source by switching to
 * VOICE_RECOGNITION. This stays for a chain that does add a constant offset; enabled by
 * default it fitted a "weight" to noise and broke AC-8 and the non-Morse tests.
 *
 * ### The estimate, and why it respects D-2
 *
 * The weight shortens a dot and lengthens the gap after it by the same amount, so their
 * **difference** measures it: `w = (intra-character gap − dot) / 2`. Only the shortest gap
 * cluster is used — the gap between elements of one character — because the reasons D-2
 * gives for distrusting gaps do not reach it: hesitation only lengthens gaps (and a
 * lengthened one leaves the cluster), and Farnsworth stretches only the gaps between
 * characters and words. The unit itself is still estimated from marks only; they are just
 * corrected marks.
 *
 * ### Guards
 *
 * - Nothing until [minMarks] marks and [minGaps] intra gaps are in the window.
 * - The dot cluster needs marks that actually separate into two lengths (p90/p10 ≥
 *   [minSpread]); a run of only `T`s or only `E`s says nothing about which is a dot, so the
 *   previous estimate is held.
 * - |w| is capped at [maxUnitFraction] of the implied unit (`(dot + gap) / 2`).
 * - Estimates move by a fraction [smoothing] per update, so one odd character cannot
 *   swing every mark after it.
 */
class KeyingWeight(
    private val window: Int = 48,
    private val minMarks: Int = 8,
    private val minGaps: Int = 5,
    private val minSpread: Double = 1.8,
    private val maxUnitFraction: Double = 0.45,
    private val smoothing: Double = 0.25,
) {
    private val marks = DoubleArray(window)
    private val gaps = DoubleArray(window)
    private var markCount = 0
    private var gapCount = 0
    private var markIdx = 0
    private var gapIdx = 0
    private var estimated = false

    /** Milliseconds to add to each mark and subtract from each gap. 0 until estimable. */
    var weightMs: Double = 0.0
        private set

    /** Whether there has been enough evidence for an estimate yet. */
    val ready: Boolean get() = estimated

    fun observe(run: FrontEnd.Run) {
        if (run.durationMs <= 0.0) return
        if (run.isMark) {
            marks[markIdx] = run.durationMs
            markIdx = (markIdx + 1) % window
            if (markCount < window) markCount++
        } else {
            gaps[gapIdx] = run.durationMs
            gapIdx = (gapIdx + 1) % window
            if (gapCount < window) gapCount++
        }
        update()
    }

    /** The run as it would have measured with the offset removed; never below 1 ms. */
    fun correct(run: FrontEnd.Run): FrontEnd.Run {
        if (weightMs == 0.0) return run
        val d = if (run.isMark) run.durationMs + weightMs else run.durationMs - weightMs
        return run.copy(durationMs = maxOf(1.0, d))
    }

    fun reset() {
        markCount = 0; gapCount = 0; markIdx = 0; gapIdx = 0
        weightMs = 0.0; estimated = false
    }

    private fun update() {
        if (markCount < minMarks || gapCount < minGaps) return
        val m = marks.copyOf(markCount).also { it.sort() }
        val p10 = m[(0.10 * (markCount - 1)).toInt()]
        val p90 = m[(0.90 * (markCount - 1)).toInt()]
        if (p10 <= 0.0 || p90 / p10 < minSpread) return
        val split = sqrt(p10 * p90)
        val dots = m.filter { it < split }
        if (dots.size < 3) return
        val dot = median(dots)

        // The intra-character gap is the most common gap and the shortest cluster: a low
        // quantile lands inside it, and the cluster is what lies within 1.8x of that. The
        // next cluster (between characters) sits ~2-3x higher however large the weight is.
        val g = gaps.copyOf(gapCount).also { it.sort() }
        val anchor = g[(0.25 * (gapCount - 1)).toInt()]
        val intra = g.filter { it < 1.8 * anchor }
        if (intra.size < minGaps) return
        val gap = median(intra)

        val unit = (dot + gap) / 2.0
        val raw = ((gap - dot) / 2.0).coerceIn(-maxUnitFraction * unit, maxUnitFraction * unit)
        weightMs = if (!estimated) raw else weightMs + smoothing * (raw - weightMs)
        estimated = true
    }

    private fun median(v: List<Double>): Double {
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else 0.5 * (v[n / 2 - 1] + v[n / 2])
    }

    override fun toString() = "KeyingWeight(w=%.1f ms, ready=%b)".format(weightMs, estimated)
}

package com.morsedecoder.core.dsp

import kotlin.math.max

/**
 * How strongly a given frequency is being keyed on and off (FR-2.1 peak selection).
 *
 * The loudest thing in the band is not necessarily Morse. Mains hum, a data carrier, a
 * fan, and a phone speaker's own resonance are all steady; CW is not — that is the only
 * property that distinguishes a CW carrier from every other narrowband signal, so it is
 * what peak selection is scored on. Picking by power alone is how a decoder ends up
 * locked confidently onto something that will never produce a character.
 *
 * The measure is the same p95/p20 envelope ratio the low-signal guard uses (FR-3.7), so
 * the two are directly comparable. Calibrated against a real 17 WPM recording: the keyed
 * carrier scores 465 at its 10th percentile over a 0.6 s probe, while the loudest
 * unkeyed content in the same recording never exceeds 33. [LOCK_MIN_SPREAD] sits between
 * them with an order of magnitude of headroom on the signal side.
 */
object KeyingSpread {

    /**
     * Minimum spread for a peak to be treated as a keyed carrier. Above the noise the
     * real world produces, an order of magnitude below what genuine keying sustains.
     */
    const val LOCK_MIN_SPREAD = 40.0

    /** Shortest probe worth judging: below this a single element can span the window. */
    const val MIN_PROBE_MS = 300.0

    /**
     * On/off edges a probe must contain. Amplitude spread alone cannot tell keying from a
     * single noise burst against a quiet background — both put a wide gap between the
     * 20th and 95th percentiles. Keying is *repeated*, so counting edges is what separates
     * them. Four edges is two on-periods, which a 600 ms probe sees down to about 8 WPM.
     */
    const val MIN_TRANSITIONS = 4

    /**
     * p20 is floored at this fraction of p95, capping the ratio at its reciprocal.
     *
     * Without it the measure is worthless on real files: recordings contain stretches of
     * true digital silence, p20 lands on exactly zero, and the ratio explodes to 1e17 —
     * so silence scored as the most strongly keyed thing in the band. A ratio against zero
     * is evidence of nothing, not evidence of keying.
     */
    private const val FLOOR_FRACTION = 1e-4

    /**
     * How strongly [freqHz] is keyed: the p95/p20 envelope ratio, but only once the
     * envelope has crossed back and forth at least [MIN_TRANSITIONS] times. Returns
     * 0.0 — "not keyed" — when there is too little audio to judge or too few edges to
     * call it keying, so a caller gating on this errs toward not locking rather than
     * toward locking on no evidence.
     *
     * The 5 ms hop is deliberately coarser than the decode path's 2.5 ms: this only has
     * to see that the envelope moves, not measure an element boundary.
     */
    fun of(
        samples: FloatArray,
        sampleRate: Int,
        freqHz: Double,
        windowMs: Double = 10.0,
        hopMs: Double = 5.0,
    ): Double {
        val win = (windowMs * sampleRate / 1000.0).toInt()
        val hop = (hopMs * sampleRate / 1000.0).toInt()
        if (win < 4 || hop < 1) return 0.0
        if (samples.size < (MIN_PROBE_MS * sampleRate / 1000.0).toInt()) return 0.0
        val frames = (samples.size - win) / hop + 1
        if (frames < 16) return 0.0

        val goertzel = Goertzel(sampleRate, freqHz, win)
        val env = DoubleArray(frames) { goertzel.power(samples, it * hop) }

        val ranked = env.copyOf()   // percentiles need order; edges need the timeline
        ranked.sort()
        val p20 = ranked[(0.20 * (frames - 1)).toInt()]
        val p95 = ranked[(0.95 * (frames - 1)).toInt()]
        if (p95 <= 0.0) return 0.0  // nothing here at all

        // Same Schmitt shape as the decode path, so "crossed" means the same thing.
        val onThr = p20 + 0.50 * (p95 - p20)
        val offThr = p20 + 0.25 * (p95 - p20)
        var state = false
        var transitions = 0
        for (v in env) {
            val next = if (state) v > offThr else v > onThr
            if (next != state) {
                transitions++
                state = next
            }
        }
        if (transitions < MIN_TRANSITIONS) return 0.0

        return p95 / max(p20, p95 * FLOOR_FRACTION)
    }
}

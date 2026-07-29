package com.morsedecoder.core.model

import com.morsedecoder.core.decode.RobustTimingDecoder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * modelFitQuality over the *recent observation window*, independent of decoder
 * commits. The re-lock guard (FR-2.10.1) needs the fit to reflect what the last
 * ~30 elements looked like, not what the slowly-updated committed model remembers —
 * a wrong lock produces junk elements immediately, long before enough junk commits
 * to drag the mixture down.
 *
 * Input is normalised mark durations z = ln(duration / globalUnit).
 */
class ObservationFit(private val capacity: Int = 30) {
    private val ring = DoubleArray(capacity)
    private var count = 0
    private var writeIdx = 0

    val ready: Boolean get() = count >= 12

    fun add(z: Double) {
        ring[writeIdx] = z
        writeIdx = (writeIdx + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        count = 0
        writeIdx = 0
    }

    /** Two-cluster fit score over the window, same scale as FR-5.8. Neutral (1.0) until ready. */
    fun value(): Double {
        if (!ready) return 1.0
        val values = ring.copyOf(count)
        // 1-D 2-means
        var c1 = values.min()
        var c2 = values.max()
        repeat(15) {
            var s1 = 0.0; var n1 = 0
            var s2 = 0.0; var n2 = 0
            for (v in values) {
                if (v - c1 <= c2 - v) { s1 += v; n1++ } else { s2 += v; n2++ }
            }
            if (n1 > 0) c1 = s1 / n1
            if (n2 > 0) c2 = s2 / n2
        }
        var v1 = 0.0; var n1 = 0
        var v2 = 0.0; var n2 = 0
        for (v in values) {
            if (v - c1 <= c2 - v) { v1 += (v - c1) * (v - c1); n1++ }
            else { v2 += (v - c2) * (v - c2); n2++ }
        }
        val s0 = max(0.06, if (n1 > 1) sqrt(v1 / n1) else 0.06)
        val s1b = max(0.06, if (n2 > 1) sqrt(v2 / n2) else 0.06)
        return RobustTimingDecoder.fitQuality(c1, c2, s0, s1b)
    }
}

package com.morsedecoder.core.dsp

/**
 * Rolling low/high percentiles over a bounded window (FR-3.1): the noise floor is a
 * low percentile, never a mean, and the window is long relative to the slowest
 * expected element so a 5 WPM dash cannot be adapted away.
 *
 * Recomputes lazily every [recomputeEvery] samples — at frame rate that is a ~4000
 * element sort every ~60 ms, well inside the DSP budget (NFR-4).
 */
class RollingPercentiles(
    private val capacity: Int,
    private val recomputeEvery: Int = 25,
) {
    private val ring = DoubleArray(capacity)
    private var count = 0
    private var writeIdx = 0
    private var sinceRecompute = 0

    var p20 = 0.0; private set
    var p95 = 0.0; private set

    val ready: Boolean get() = count >= 240 // ~0.6 s at 2.5 ms hop

    fun add(value: Double) {
        ring[writeIdx] = value
        writeIdx = (writeIdx + 1) % capacity
        if (count < capacity) count++
        if (++sinceRecompute >= recomputeEvery) {
            sinceRecompute = 0
            recompute()
        }
    }

    fun clear() {
        count = 0
        writeIdx = 0
        sinceRecompute = 0
        p20 = 0.0
        p95 = 0.0
    }

    private fun recompute() {
        if (count == 0) return
        val sorted = ring.copyOf(count)
        sorted.sort()
        p20 = sorted[(0.20 * (count - 1)).toInt()]
        p95 = sorted[(0.95 * (count - 1)).toInt()]
    }
}

package com.morsedecoder.core.model

/**
 * Scalar Kalman tracker for the global dot unit (FR-5.5). Random-walk process model:
 * the sender's speed drifts slowly, so predict inflates variance a little per
 * character and each committed character's local unit is a measurement.
 *
 * The posterior variance is exported — it is the lock-quality signal the spec asks
 * for, and later drives the re-lock guard telemetry.
 */
class KalmanUnit(u0: Double, p0: Double) {
    var unitMs: Double = u0
        private set
    var variance: Double = p0
        private set

    /** FR-9.5 pin-WPM: a frozen tracker ignores all evidence until unpinned. */
    var frozen: Boolean = false

    /** Per-character process noise: allows ~2 % unit drift per character. */
    fun predict() {
        if (frozen) return
        val q = 0.02 * unitMs
        variance += q * q
    }

    fun update(measurementMs: Double, measurementVar: Double) {
        if (frozen) return
        val k = variance / (variance + measurementVar)
        unitMs += k * (measurementMs - unitMs)
        variance *= (1.0 - k)
    }

    /** Manual override (FR-9.5): set the unit directly with high confidence. */
    fun overrideTo(newUnitMs: Double) {
        unitMs = newUnitMs
        variance = (0.05 * newUnitMs) * (0.05 * newUnitMs)
    }

    /**
     * Re-lock support (FR-2.12): the unit estimate is retained as a prior — the
     * sender's speed did not change — but its certainty must not survive a lock
     * change intact.
     */
    fun inflateVariance(factor: Double) {
        variance *= factor
    }

    val wpm: Double get() = 1200.0 / unitMs
}

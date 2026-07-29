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
    companion object {
        /**
         * Hard sanity ceiling on sending speed. Nothing above this is Morse — the
         * calibrated band tops out at 40 WPM (FR-5.7) and even machine-sent CW does
         * not reach 60. Without a bound the tracker runs away on incoherent input:
         * a short unit makes the glitch gate (0.3 units) short, which admits more
         * noise blips as marks, which shortens the unit further. That feedback loop
         * is what put 100+ WPM on the readout while listening to an empty room.
         */
        const val MAX_WPM = 60.0
        const val MIN_UNIT_MS = 1200.0 / MAX_WPM   // 20 ms

        /** Lower speed bound; 2 WPM is far below anything sendable by hand. */
        const val MIN_WPM = 2.0
        const val MAX_UNIT_MS = 1200.0 / MIN_WPM   // 600 ms
    }

    var unitMs: Double = u0.coerceIn(MIN_UNIT_MS, MAX_UNIT_MS)
        private set
    var variance: Double = p0
        private set

    /**
     * True when the estimate is being held off the speed ceiling rather than measured.
     * A clamped unit is not an observation, so callers report it as "no estimate"
     * rather than as 60 WPM.
     */
    var atSpeedCeiling: Boolean = false
        private set

    init {
        atSpeedCeiling = u0 < MIN_UNIT_MS
    }

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
        clampUnit(unitMs + k * (measurementMs - unitMs))
        variance *= (1.0 - k)
    }

    /** Manual override (FR-9.5): set the unit directly with high confidence. */
    fun overrideTo(newUnitMs: Double) {
        clampUnit(newUnitMs)
        variance = (0.05 * unitMs) * (0.05 * unitMs)
    }

    private fun clampUnit(candidateMs: Double) {
        atSpeedCeiling = candidateMs < MIN_UNIT_MS
        unitMs = candidateMs.coerceIn(MIN_UNIT_MS, MAX_UNIT_MS)
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

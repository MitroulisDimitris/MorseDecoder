package com.morsedecoder.app

import android.content.Context
import com.morsedecoder.app.service.DecoderService
import com.morsedecoder.core.dsp.Spectrum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User settings, persisted across sessions.
 *
 * Only values that genuinely depend on the user's own equipment belong here — the
 * decoder's own thresholds are calibrated against measurements and are not a matter of
 * preference. The search-band ceiling qualifies: what tone a signal arrives on is a
 * property of the operator's sidetone and the recording chain, and no default can be
 * right for everyone (D-13).
 */
object Settings {

    private const val PREFS = "morse_decoder_settings"
    private const val KEY_BAND_MAX = "band_max_hz"

    /** The band floor is not adjustable: below 300 Hz is mains and handling noise. */
    const val BAND_MIN_HZ = Spectrum.DEFAULT_BAND_MIN_HZ

    const val BAND_MAX_DEFAULT = Spectrum.DEFAULT_BAND_MAX_HZ
    const val BAND_MAX_LOWEST = 1000.0
    const val BAND_MAX_HIGHEST = 4000.0
    const val BAND_MAX_STEP = 100.0

    private val _bandMaxHz = MutableStateFlow(BAND_MAX_DEFAULT)
    val bandMaxHz: StateFlow<Double> get() = _bandMaxHz

    /** Call before first read; safe to call repeatedly. */
    fun load(context: Context) {
        _bandMaxHz.value = prefs(context)
            .getFloat(KEY_BAND_MAX, BAND_MAX_DEFAULT.toFloat())
            .toDouble()
            .coerceIn(BAND_MAX_LOWEST, BAND_MAX_HIGHEST)
    }

    /**
     * Applies immediately to a running session — the engine re-reads the band on its next
     * sweep, so no lock or transcript is lost. A lock left above the new ceiling returns
     * to searching rather than sitting on a frequency the sweep no longer covers.
     */
    fun setBandMaxHz(context: Context, hz: Double) {
        val value = hz.coerceIn(BAND_MAX_LOWEST, BAND_MAX_HIGHEST)
        _bandMaxHz.value = value
        prefs(context).edit().putFloat(KEY_BAND_MAX, value.toFloat()).apply()
        DecoderService.setBandMaxHz(value)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

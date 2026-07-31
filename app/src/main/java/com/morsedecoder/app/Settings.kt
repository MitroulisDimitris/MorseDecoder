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
 * preference. The tone graph's bounds qualify: what frequency a signal arrives on is a
 * property of the operator's sidetone and the recording chain (D-13).
 *
 * These bounds are the **outer** limits — the extent of the spectrum strip's axis and the
 * widest the decoder will ever search. Narrowing the search *within* them is a live,
 * per-session action taken on the strip itself, and is deliberately not persisted: a range
 * narrowed to chase one stubborn signal should not silently still be narrow tomorrow.
 */
object Settings {

    private const val PREFS = "morse_decoder_settings"
    private const val KEY_GRAPH_MIN = "graph_min_hz"
    private const val KEY_GRAPH_MAX = "graph_max_hz"

    const val GRAPH_MIN_DEFAULT = Spectrum.DEFAULT_BAND_MIN_HZ
    const val GRAPH_MAX_DEFAULT = Spectrum.DEFAULT_BAND_MAX_HZ

    const val GRAPH_LOWEST = 100.0
    const val GRAPH_HIGHEST = 4000.0
    const val GRAPH_STEP = 100.0

    /** The graph must stay wide enough to be worth drawing and to hold a search range. */
    const val GRAPH_MIN_SPAN = 400.0

    private val _graphMinHz = MutableStateFlow(GRAPH_MIN_DEFAULT)
    private val _graphMaxHz = MutableStateFlow(GRAPH_MAX_DEFAULT)
    val graphMinHz: StateFlow<Double> get() = _graphMinHz
    val graphMaxHz: StateFlow<Double> get() = _graphMaxHz

    /** Call before first read; safe to call repeatedly. */
    fun load(context: Context) {
        val p = prefs(context)
        val lo = p.getFloat(KEY_GRAPH_MIN, GRAPH_MIN_DEFAULT.toFloat()).toDouble()
        val hi = p.getFloat(KEY_GRAPH_MAX, GRAPH_MAX_DEFAULT.toFloat()).toDouble()
        _graphMinHz.value = lo.coerceIn(GRAPH_LOWEST, GRAPH_HIGHEST - GRAPH_MIN_SPAN)
        _graphMaxHz.value = hi.coerceIn(_graphMinHz.value + GRAPH_MIN_SPAN, GRAPH_HIGHEST)
    }

    fun setGraphMinHz(context: Context, hz: Double) =
        apply(context, hz.coerceIn(GRAPH_LOWEST, _graphMaxHz.value - GRAPH_MIN_SPAN), _graphMaxHz.value)

    fun setGraphMaxHz(context: Context, hz: Double) =
        apply(context, _graphMinHz.value, hz.coerceIn(_graphMinHz.value + GRAPH_MIN_SPAN, GRAPH_HIGHEST))

    /**
     * Widening or moving the graph resets any live narrowing to the full new extent.
     * Keeping a stale narrowing inside freshly-moved bounds would leave the decoder
     * searching a range the user can no longer see the reason for.
     */
    private fun apply(context: Context, lo: Double, hi: Double) {
        _graphMinHz.value = lo
        _graphMaxHz.value = hi
        prefs(context).edit()
            .putFloat(KEY_GRAPH_MIN, lo.toFloat())
            .putFloat(KEY_GRAPH_MAX, hi.toFloat())
            .apply()
        DecoderService.setSearchRange(lo, hi)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

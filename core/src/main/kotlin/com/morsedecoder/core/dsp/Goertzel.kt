package com.morsedecoder.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Single-frequency energy extraction (FR-2.2): one windowed DFT bin per analysis
 * window at the tracked frequency — the same role and cost as a classic Goertzel
 * recursion, but with a Hann window. The window matters: rectangular sidelobes decay
 * so slowly that a strong signal 200 Hz away still keys the envelope (−15 dB); Hann
 * puts adjacent signals below −40 dB, which is what keeps QRM from bleeding into the
 * lock (FR-2.4).
 */
class Goertzel(sampleRate: Int, freqHz: Double, windowSize: Int) {
    private val wCos = DoubleArray(windowSize)
    private val wSin = DoubleArray(windowSize)

    init {
        val omega = 2.0 * PI * freqHz / sampleRate
        for (n in 0 until windowSize) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * n / (windowSize - 1)))
            wCos[n] = w * cos(omega * n)
            wSin[n] = w * sin(omega * n)
        }
    }

    private val norm = 1.0 / (windowSize.toDouble() * windowSize)

    /**
     * Signal power at the target frequency over one window starting at [start].
     *
     * Normalised by window length, so the value is mean power rather than total energy.
     * That matters because FR-2.7 changes the window length mid-stream: without this the
     * scale would jump by (N₂/N₁)² at the moment of the change, invalidating the rolling
     * percentile floor the keying threshold rides on. Everything downstream works in
     * ratios, so only the absolute "is this silence" guards see the difference, and they
     * sit far below any real signal either way.
     */
    fun power(samples: FloatArray, start: Int): Double {
        var re = 0.0
        var im = 0.0
        for (n in wCos.indices) {
            val s = samples[start + n].toDouble()
            re += s * wCos[n]
            im += s * wSin[n]
        }
        return (re * re + im * im) * norm
    }
}

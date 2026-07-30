package com.morsedecoder.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * FFT-based tone location (FR-2.1): averaged periodogram over the band of interest,
 * peak pick with parabolic interpolation. Used by the file-mode sweep and, later, the
 * live rolling search.
 */
object Spectrum {

    /**
     * The search band (FR-2.1). One definition, shared by the file sweep and the live
     * rolling search so the two can never disagree about where a tone may be found.
     *
     * The ceiling is not the 1200 Hz of the original spec. Sidetone is a matter of
     * operator taste and recording chain, not physics: a phone recording of a small
     * speaker measured 1882 Hz here, which the old ceiling put permanently out of reach.
     * Users can raise it further in settings; see decisions.md D-13.
     */
    const val DEFAULT_BAND_MIN_HZ = 300.0
    const val DEFAULT_BAND_MAX_HZ = 2000.0

    /** In-place iterative radix-2 complex FFT. Sizes must be powers of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && Integer.bitCount(n) == 1) { "FFT size must be a power of two" }
        // bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Estimate the strongest tone in [minHz, maxHz] by averaging Hann-windowed
     * periodograms across the whole signal, then refining the peak bin parabolically.
     * Returns null if the signal is too short.
     */
    fun estimateToneHz(
        samples: FloatArray,
        sampleRate: Int,
        minHz: Double = DEFAULT_BAND_MIN_HZ,
        maxHz: Double = DEFAULT_BAND_MAX_HZ,
        fftSize: Int = 4096,
    ): Double? {
        val avg = averagePeriodogram(samples, fftSize) ?: return null
        val binHz = sampleRate.toDouble() / fftSize
        val lo = (minHz / binHz).toInt().coerceAtLeast(1)
        val hi = (maxHz / binHz).toInt().coerceAtMost(avg.size - 2)
        if (lo >= hi) return null
        var peak = lo
        for (b in lo..hi) if (avg[b] > avg[peak]) peak = b
        return (peak + parabolicDelta(avg, peak)) * binHz
    }

    /**
     * Every plausible tone in the band, strongest first — the input to keyed-carrier
     * selection (see [com.morsedecoder.core.dsp.KeyingSpread]). Local spectral maxima
     * that clear the band median, parabolically refined, deduplicated within [mergeHz]
     * so one broad peak does not occupy every slot.
     *
     * Separate from [estimateToneHz] because the strongest peak and the right peak are
     * not the same question, and the caller needs the shortlist to tell them apart.
     */
    fun candidateTones(
        samples: FloatArray,
        sampleRate: Int,
        minHz: Double = DEFAULT_BAND_MIN_HZ,
        maxHz: Double = DEFAULT_BAND_MAX_HZ,
        fftSize: Int = 4096,
        maxCount: Int = 6,
        mergeHz: Double = 25.0,
    ): DoubleArray {
        val avg = averagePeriodogram(samples, fftSize) ?: return DoubleArray(0)
        val binHz = sampleRate.toDouble() / fftSize
        val lo = (minHz / binHz).toInt().coerceAtLeast(1)
        val hi = (maxHz / binHz).toInt().coerceAtMost(avg.size - 2)
        if (lo >= hi) return DoubleArray(0)

        val band = avg.copyOfRange(lo, hi + 1)
        val sorted = band.copyOf()
        sorted.sort()
        val floor = max(sorted[sorted.size / 2], 1e-18)

        val peaks = ArrayList<Pair<Double, Double>>() // freq to power
        for (b in lo + 1 until hi) {
            if (avg[b] > floor * 3.0 && avg[b] >= avg[b - 1] && avg[b] >= avg[b + 1]) {
                peaks.add(((b + parabolicDelta(avg, b)) * binHz) to avg[b])
            }
        }
        peaks.sortByDescending { it.second }

        val out = ArrayList<Double>(maxCount)
        for ((f, _) in peaks) {
            if (out.size >= maxCount) break
            if (out.none { kotlin.math.abs(it - f) < mergeHz }) out.add(f)
        }
        return out.toDoubleArray()
    }

    /** Hann-windowed periodogram averaged across the whole signal. */
    private fun averagePeriodogram(samples: FloatArray, fftSize: Int): DoubleArray? {
        if (samples.size < fftSize / 2) return null
        val hop = fftSize / 2
        val window = DoubleArray(fftSize) { 0.5 * (1.0 - cos(2.0 * PI * it / (fftSize - 1))) }
        val avg = DoubleArray(fftSize / 2)
        var frames = 0
        var start = 0
        while (start + fftSize <= samples.size || frames == 0) {
            val re = DoubleArray(fftSize)
            val im = DoubleArray(fftSize)
            val avail = minOf(fftSize, samples.size - start)
            for (i in 0 until avail) re[i] = samples[start + i] * window[i]
            fft(re, im)
            for (b in avg.indices) avg[b] += re[b] * re[b] + im[b] * im[b]
            frames++
            start += hop
            if (start + fftSize > samples.size && frames > 0) break
        }
        return avg
    }

    /** Sub-bin peak refinement on the log-power spectrum. */
    private fun parabolicDelta(avg: DoubleArray, peak: Int): Double {
        if (peak <= 0 || peak >= avg.size - 1) return 0.0
        val a = ln(avg[peak - 1])
        val b = ln(avg[peak])
        val c = ln(avg[peak + 1])
        val denom = a - 2.0 * b + c
        return if (denom == 0.0) 0.0 else (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
    }

    private fun ln(x: Double): Double = kotlin.math.ln(x.coerceAtLeast(1e-30))
}

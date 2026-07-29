package com.morsedecoder.core.dsp

/**
 * Coarse unit estimate from envelope autocorrelation (FR-5.1).
 *
 * This exists to break the circularity that the duration gates are themselves
 * expressed in units: autocorrelation finds the dominant repeating timescale
 * without any classification. It only needs to be right within a factor of ~2 —
 * it sets prune gates and window lengths, and the mixture fit takes over from there.
 */
object Bootstrap {

    /** Estimate the dot unit in ms from a linear-power envelope at [hopMs] frame spacing. */
    fun estimateUnitMs(envelope: DoubleArray, hopMs: Double, fallbackMs: Double = 60.0): Double {
        val n = envelope.size
        val minLag = (40.0 / hopMs).toInt().coerceAtLeast(1)      // 40 ms
        val maxLag = (1000.0 / hopMs).toInt().coerceAtMost(n / 2) // 1 s
        if (maxLag <= minLag + 2) return fallbackMs

        val mean = envelope.average()
        val x = DoubleArray(n) { envelope[it] - mean }
        var r0 = 0.0
        for (v in x) r0 += v * v
        if (r0 <= 0.0) return fallbackMs

        val r = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var acc = 0.0
            for (i in 0 until n - lag) acc += x[i] * x[i + lag]
            r[lag] = acc / r0
        }

        // Local maxima above a modest prominence.
        val peaks = ArrayList<Int>()
        for (lag in minLag + 1 until maxLag) {
            if (r[lag] > 0.10 && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) peaks.add(lag)
        }
        if (peaks.isEmpty()) return fallbackMs
        val topPeaks = peaks.sortedByDescending { r[it] }.take(5)

        // The strongest peak may sit at 1×, 2×, or 4× the unit depending on text mix.
        // Disambiguate with a comb score: a correct unit lines up with the autocorr
        // at several of its multiples.
        var bestUnit = fallbackMs
        var bestScore = Double.NEGATIVE_INFINITY
        for (peak in topPeaks) {
            for (k in 1..4) {
                val unitMs = peak * hopMs / k
                if (unitMs < 25.0 || unitMs > 300.0) continue
                var score = 0.0
                for (m in 1..4) {
                    val lag = (m * unitMs / hopMs).toInt()
                    if (lag in minLag..maxLag) score += r[lag]
                }
                if (score > bestScore) {
                    bestScore = score
                    bestUnit = unitMs
                }
            }
        }
        return bestUnit.coerceIn(25.0, 300.0)
    }
}

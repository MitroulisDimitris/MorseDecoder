package com.morsedecoder.core.model

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Gaussian mixture over log-durations (FR-5.2). Used twice, always separately:
 * a 2-component mixture for marks (dot, dash) and a 3-component mixture for gaps
 * (intra, inter, word). Components are fitted independently — the 3:1 dash/dot
 * ratio is never imposed (FR-5.3 / D-3); degenerate-fit disambiguation lives in
 * the decoder, not here.
 *
 * Observations are normalised: z = ln(duration / globalUnit). That makes the model
 * drift-invariant — as the Kalman unit tracks the sender, the clusters stay put.
 *
 * Supports batch EM (file-mode warmup, FM-1) and online exponentially-forgotten
 * updates (live adaptation).
 */
class GaussianMixture private constructor(
    val weights: DoubleArray,
    val means: DoubleArray,
    val sigmas: DoubleArray,
    private val sigmaFloor: Double,
    private val forgetting: Double,
) {
    private val counts = DoubleArray(weights.size) { 8.0 * weights[it] + 1.0 }

    val componentCount: Int get() = means.size

    fun logPdf(z: Double, k: Int): Double {
        val d = (z - means[k]) / sigmas[k]
        return -0.5 * d * d - ln(sigmas[k]) - 0.9189385332046727
    }

    /** Posterior component responsibilities for one observation. */
    fun posterior(z: Double): DoubleArray {
        val logs = DoubleArray(componentCount) { ln(weights[it].coerceAtLeast(1e-9)) + logPdf(z, it) }
        val m = logs.max()
        val exps = DoubleArray(componentCount) { exp(logs[it] - m) }
        val sum = exps.sum()
        return DoubleArray(componentCount) { exps[it] / sum }
    }

    /** Online EM step with exponential forgetting — one observation. */
    fun update(z: Double) {
        val post = posterior(z)
        var totalCount = 0.0
        for (k in 0 until componentCount) {
            counts[k] = forgetting * counts[k] + post[k]
            totalCount += counts[k]
            val alpha = post[k] / counts[k]
            val dPre = z - means[k]
            means[k] += alpha * dPre
            val dPost = z - means[k]
            val v = (1.0 - alpha) * sigmas[k] * sigmas[k] + alpha * dPre * dPost
            sigmas[k] = max(sigmaFloor, sqrt(max(v, 0.0)))
        }
        for (k in 0 until componentCount) weights[k] = counts[k] / totalCount
    }

    companion object {
        fun of(
            initMeans: DoubleArray,
            initSigma: Double,
            sigmaFloor: Double = 0.06,
            forgetting: Double = 0.98,
        ): GaussianMixture = GaussianMixture(
            DoubleArray(initMeans.size) { 1.0 / initMeans.size },
            initMeans.copyOf(),
            DoubleArray(initMeans.size) { initSigma },
            sigmaFloor,
            forgetting,
        )

        /**
         * Batch EM fit. [initMeans] seeds the components (and their order); means are
         * re-sorted ascending afterwards so callers can rely on component identity
         * (dot < dash; intra < inter < word).
         */
        fun fit(
            values: DoubleArray,
            initMeans: DoubleArray,
            initSigma: Double = 0.35,
            iters: Int = 40,
            sigmaFloor: Double = 0.06,
            forgetting: Double = 0.98,
        ): GaussianMixture {
            val k = initMeans.size
            val mix = of(initMeans, initSigma, sigmaFloor, forgetting)
            if (values.size < k + 1) return mix // too little evidence; keep the prior shape

            repeat(iters) {
                val respSum = DoubleArray(k)
                val zSum = DoubleArray(k)
                val z2Sum = DoubleArray(k)
                for (z in values) {
                    val post = mix.posterior(z)
                    for (c in 0 until k) {
                        respSum[c] += post[c]
                        zSum[c] += post[c] * z
                        z2Sum[c] += post[c] * z * z
                    }
                }
                for (c in 0 until k) {
                    if (respSum[c] < 1e-6) continue // starved component keeps its prior position
                    val mu = zSum[c] / respSum[c]
                    val varC = z2Sum[c] / respSum[c] - mu * mu
                    mix.means[c] = mu
                    mix.sigmas[c] = max(sigmaFloor, sqrt(max(varC, 0.0)))
                    mix.weights[c] = respSum[c] / values.size
                }
                val wSum = mix.weights.sum()
                for (c in 0 until k) mix.weights[c] /= wSum
            }

            // restore ascending component identity
            val order = (0 until k).sortedBy { mix.means[it] }
            val m = DoubleArray(k) { mix.means[order[it]] }
            val s = DoubleArray(k) { mix.sigmas[order[it]] }
            val w = DoubleArray(k) { mix.weights[order[it]] }
            m.copyInto(mix.means); s.copyInto(mix.sigmas); w.copyInto(mix.weights)
            for (c in 0 until k) mix.counts[c] = 8.0 * mix.weights[c] + 1.0
            return mix
        }
    }
}

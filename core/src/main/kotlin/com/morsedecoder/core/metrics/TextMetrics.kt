package com.morsedecoder.core.metrics

/**
 * Character error rate — the unit every acceptance criterion in §6 is stated in.
 */
object TextMetrics {

    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** CER = edit distance / reference length. Reference must be non-empty. */
    fun cer(reference: String, hypothesis: String): Double {
        require(reference.isNotEmpty()) { "reference must be non-empty" }
        return levenshtein(reference, hypothesis).toDouble() / reference.length
    }
}

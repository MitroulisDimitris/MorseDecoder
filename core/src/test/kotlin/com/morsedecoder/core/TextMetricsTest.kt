package com.morsedecoder.core

import com.morsedecoder.core.metrics.TextMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextMetricsTest {

    @Test
    fun `identical strings have zero distance`() {
        assertEquals(0, TextMetrics.levenshtein("CQ CQ DE", "CQ CQ DE"))
        assertEquals(0.0, TextMetrics.cer("PARIS", "PARIS"))
    }

    @Test
    fun `single substitution insertion deletion`() {
        assertEquals(1, TextMetrics.levenshtein("PARIS", "PARIT"))
        assertEquals(1, TextMetrics.levenshtein("PARIS", "PARIS5"))
        assertEquals(1, TextMetrics.levenshtein("PARIS", "PAIS"))
    }

    @Test
    fun `cer is normalised by reference length`() {
        assertEquals(0.2, TextMetrics.cer("PARIS", "PARIT"), 1e-9)
    }
}

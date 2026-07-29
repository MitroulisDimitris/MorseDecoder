package com.morsedecoder.core.decode

import com.morsedecoder.core.morse.MorseAlphabet

/**
 * Lattice alphabet indexed by element count. Prosigns are single entries exactly like
 * letters (FR-6.4) — a segment of n marks is matched against every entry of length n.
 */
object MorseTrie {

    data class Entry(val pattern: String, val token: String)

    val byLength: Map<Int, List<Entry>> =
        MorseAlphabet.patternToToken.entries
            .map { Entry(it.key, it.value) }
            .groupBy { it.pattern.length }

    val maxLength: Int = byLength.keys.max()
}

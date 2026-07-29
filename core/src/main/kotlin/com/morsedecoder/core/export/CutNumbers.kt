package com.morsedecoder.core.export

/**
 * Cut-number expansion (FR-7.3): `T`->0, `N`->9, `A`->1, so `5NN` reads `599`.
 *
 * Applied as a view/export transform over the raw decode, never a mutation — the
 * raw transcript stays inspectable and the assist is individually toggleable (P-3).
 *
 * Context rule: only words that contain at least one real digit and consist solely
 * of digits and cut-mappable letters are expanded. `TNX` stays `TNX`; `5NN`
 * becomes `599`.
 */
object CutNumbers {

    private val map = mapOf('T' to '0', 'N' to '9', 'A' to '1')

    /**
     * Expand one word given as per-character tokens. Returns the expanded token list,
     * or null when the word is left untouched.
     */
    fun expandWord(tokens: List<String>): List<String>? {
        var hasDigit = false
        var hasMappable = false
        for (t in tokens) {
            val c = t.singleOrNull() ?: return null // prosigns / <?> exclude the word
            when {
                c.isDigit() -> hasDigit = true
                map.containsKey(c) -> hasMappable = true
                else -> return null
            }
        }
        if (!hasDigit || !hasMappable) return null
        return tokens.map { t ->
            val c = t.single()
            map[c]?.toString() ?: t
        }
    }
}

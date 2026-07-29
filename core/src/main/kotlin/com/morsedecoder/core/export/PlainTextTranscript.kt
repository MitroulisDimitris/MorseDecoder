package com.morsedecoder.core.export

/**
 * Plain-text rendering of the transcript annotation vocabulary (FR-9.10). Used by
 * tap-to-copy in M4 and the `.txt` exporter in M5 — one vocabulary, one renderer.
 *
 * - undecodable stays `<?>`
 * - the error prosign renders `[HH]`
 * - a re-lock renders `|`
 * - other prosigns render bare (`<SK>` -> `SK`), matching the sample in
 *   docs/export-formats.md
 * - low confidence is deliberately OMITTED — it has no readable text form (FR-10.5)
 */
object PlainTextTranscript {

    sealed interface Item {
        data class Char(val token: String, val isWordStart: Boolean) : Item
        data object Relock : Item
    }

    fun renderToken(token: String): String = when {
        token == "<?>" -> "<?>"
        token == "<HH>" -> "[HH]"
        token.length > 2 && token.startsWith("<") && token.endsWith(">") ->
            token.substring(1, token.length - 1)
        else -> token
    }

    fun format(items: List<Item>): String = buildString {
        var pendingSpace = false
        for (item in items) {
            when (item) {
                is Item.Char -> {
                    val rendered = renderToken(item.token)
                    val standalone = rendered == "[HH]" // reads as its own word
                    if (isNotEmpty() && (item.isWordStart || pendingSpace || standalone)) append(' ')
                    append(rendered)
                    pendingSpace = standalone
                }
                Item.Relock -> {
                    if (isNotEmpty()) append(' ')
                    append('|')
                    pendingSpace = true
                }
            }
        }
    }
}

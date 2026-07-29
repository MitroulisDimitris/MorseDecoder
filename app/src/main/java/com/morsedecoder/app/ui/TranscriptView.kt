package com.morsedecoder.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.morsedecoder.app.service.TxItem
import com.morsedecoder.core.export.PlainTextTranscript

/**
 * The transcript (FR-9.1/FR-9.10): monospace, append-only, four annotation types.
 *
 * - low confidence: amber underline (the mockup's dotted underline has no direct
 *   Compose text decoration; solid amber reads the same at 13 sp)
 * - undecodable: `<?>` chip on warning background
 * - error prosign: `HH` chip on danger background — nothing before it is altered (P-2)
 * - re-lock: an accent `|` bar; detail (old → new frequency, timestamp) on tap
 *   rather than inline (FR-9.11)
 */
@Composable
fun TranscriptView(
    items: List<TxItem>,
    p: Palette,
    onBarTap: (TxItem.Bar) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = remember(items, p) {
        buildAnnotatedString {
            for (item in items) {
                when (item) {
                    is TxItem.Ch -> {
                        when (item.token) {
                            "<?>" -> {
                                if (length > 0) append(' ')
                                withStyle(SpanStyle(background = p.bgWarning, color = p.warning)) {
                                    append("<?>")
                                }
                                append(' ')
                            }
                            "<HH>" -> {
                                if (length > 0) append(' ')
                                withStyle(
                                    SpanStyle(
                                        background = p.bgDanger,
                                        color = p.danger,
                                        fontSize = 11.sp,
                                    )
                                ) { append(" HH ") }
                                append(' ')
                            }
                            else -> {
                                if (item.isWordStart && length > 0) append(' ')
                                val rendered = PlainTextTranscript.renderToken(item.token)
                                when {
                                    // P-3: assist-changed characters carry a visible mark
                                    item.assisted -> withStyle(
                                        SpanStyle(
                                            color = p.accent,
                                            background = p.accent.copy(alpha = 0.16f),
                                        )
                                    ) { append(rendered) }
                                    item.lowConfidence -> withStyle(
                                        SpanStyle(
                                            color = p.warning,
                                            textDecoration = TextDecoration.Underline,
                                        )
                                    ) { append(rendered) }
                                    else -> append(rendered)
                                }
                            }
                        }
                    }
                    is TxItem.Bar -> {
                        if (length > 0) append(' ')
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "relock",
                                styles = TextLinkStyles(
                                    SpanStyle(color = p.accent, fontWeight = FontWeight.Medium)
                                ),
                            ) { onBarTap(item) }
                        ) { append("|") }
                        append(' ')
                    }
                }
            }
        }
    }
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 24.sp,
        color = p.textPrimary,
        modifier = modifier,
    )
}

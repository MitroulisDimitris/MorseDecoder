package com.morsedecoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings (FR-9.14). Deliberately short: the decoder's thresholds are calibrated against
 * measurements, not preferences, so they do not belong here. What does belong is anything
 * that depends on the user's own equipment rather than on the signal.
 *
 * The graph bounds set here are the **outer** limits — the extent of the spectrum strip's
 * axis, and the widest the decoder will ever search. Narrowing the search inside them is
 * done on the strip itself, live, and is not persisted. This screen is for *exact*; the
 * strip is for *fast*. It is also the accessible path to the same values, since dragging
 * an edge is not usable with a screen reader.
 */
@Composable
fun SettingsScreen(
    graphMinHz: Double,
    graphMaxHz: Double,
    lowest: Double,
    highest: Double,
    step: Double,
    minSpan: Double,
    onGraphMinHz: (Double) -> Unit,
    onGraphMaxHz: (Double) -> Unit,
    onBack: () -> Unit,
    p: Palette,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("←  Back", fontSize = 13.sp, color = p.accent)
            }
            Spacer(Modifier.weight(1f))
            Text("settings", color = p.textMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(p.surface1, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("tone graph", color = p.textMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)

            Text(
                "%.0f – %.0f Hz".format(graphMinHz, graphMaxHz),
                fontFamily = FontFamily.Monospace, fontSize = 19.sp, color = p.textPrimary,
            )

            BoundStepper(
                label = "low",
                value = graphMinHz,
                canDecrease = graphMinHz > lowest + 0.5,
                canIncrease = graphMinHz < graphMaxHz - minSpan - 0.5,
                onChange = onGraphMinHz,
                step = step,
                p = p,
            )
            BoundStepper(
                label = "high",
                value = graphMaxHz,
                canDecrease = graphMaxHz > graphMinHz + minSpan + 0.5,
                canIncrease = graphMaxHz < highest - 0.5,
                onChange = onGraphMaxHz,
                step = step,
                p = p,
            )

            Text(
                "The span the spectrum covers, and the widest the decoder will search. " +
                    "Widen it if a signal you can clearly hear never locks — sidetone pitch " +
                    "depends on the operator's radio and on how the audio was recorded.",
                fontSize = 12.sp, color = p.textSecondary, lineHeight = 17.sp,
            )
            Text(
                "To exclude part of the band instead, drag the dotted edges on the spectrum. " +
                    "That narrowing lasts for the session; these bounds persist.",
                fontSize = 12.sp, color = p.textMuted, lineHeight = 17.sp,
            )
            Text(
                "Changing either bound resets any narrowing back to the full span.",
                fontSize = 12.sp, color = p.textMuted, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun BoundStepper(
    label: String,
    value: Double,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onChange: (Double) -> Unit,
    step: Double,
    p: Palette,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = p.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            "%.0f Hz".format(value),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = p.textPrimary,
            modifier = Modifier.padding(end = 8.dp),
        )
        OutlinedButton(
            onClick = { onChange(value - step) },
            enabled = canDecrease,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp, vertical = 4.dp
            ),
        ) { Text("−", fontSize = 16.sp) }
        Spacer(Modifier.padding(horizontal = 3.dp))
        OutlinedButton(
            onClick = { onChange(value + step) },
            enabled = canIncrease,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp, vertical = 4.dp
            ),
        ) { Text("+", fontSize = 16.sp) }
    }
}

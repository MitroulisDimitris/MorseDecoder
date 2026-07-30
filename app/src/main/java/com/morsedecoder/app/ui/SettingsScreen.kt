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
 */
@Composable
fun SettingsScreen(
    bandMinHz: Double,
    bandMaxHz: Double,
    bandMaxLowest: Double,
    bandMaxHighest: Double,
    bandMaxStep: Double,
    onBandMaxHz: (Double) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("tone search band", color = p.textMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%.0f – %.0f Hz".format(bandMinHz, bandMaxHz),
                    fontFamily = FontFamily.Monospace, fontSize = 19.sp, color = p.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                val atMin = bandMaxHz <= bandMaxLowest + 0.5
                val atMax = bandMaxHz >= bandMaxHighest - 0.5
                OutlinedButton(
                    onClick = { onBandMaxHz(bandMaxHz - bandMaxStep) },
                    enabled = !atMin,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 4.dp
                    ),
                ) { Text("−", fontSize = 16.sp) }
                Spacer(Modifier.padding(horizontal = 3.dp))
                OutlinedButton(
                    onClick = { onBandMaxHz(bandMaxHz + bandMaxStep) },
                    enabled = !atMax,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 4.dp
                    ),
                ) { Text("+", fontSize = 16.sp) }
            }

            Text(
                "The range searched for a CW tone. Raise the ceiling if a signal you can " +
                    "clearly hear never locks — sidetone pitch depends on the operator's " +
                    "radio and on how the audio was recorded, and a high note falls outside " +
                    "a narrow band entirely.",
                fontSize = 12.sp, color = p.textSecondary, lineHeight = 17.sp,
            )
            Text(
                "A wider band means more noise peaks to reject, so keep it only as wide as " +
                    "you need. Takes effect immediately; the transcript is kept.",
                fontSize = 12.sp, color = p.textMuted, lineHeight = 17.sp,
            )
            Text(
                "The %.0f Hz floor is fixed — below it is mains hum and handling noise."
                    .format(bandMinHz),
                fontSize = 12.sp, color = p.textMuted, lineHeight = 17.sp,
            )
        }
    }
}

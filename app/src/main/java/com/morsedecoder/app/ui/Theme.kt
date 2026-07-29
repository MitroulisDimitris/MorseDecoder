package com.morsedecoder.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Instrument-panel palette, lifted verbatim from design/main-screen.html.
 * Simple but technical: hairline borders, monospace numerals, no decoration.
 */
data class Palette(
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val bgSuccess: Color,
    val bgWarning: Color,
    val bgDanger: Color,
)

val LightPalette = Palette(
    surface0 = Color(0xFFF5F4EF),
    surface1 = Color(0xFFECEAE3),
    surface2 = Color(0xFFFFFFFF),
    border = Color(0x1F000000),
    borderStrong = Color(0x38000000),
    textPrimary = Color(0xFF26261F),
    textSecondary = Color(0xFF5F5E5A),
    textMuted = Color(0xFF8A8985),
    accent = Color(0xFF185FA5),
    success = Color(0xFF3B6D11),
    warning = Color(0xFF854F0B),
    danger = Color(0xFFA32D2D),
    bgSuccess = Color(0xFFEAF3DE),
    bgWarning = Color(0xFFFAEEDA),
    bgDanger = Color(0xFFFCEBEB),
)

val DarkPalette = Palette(
    surface0 = Color(0xFF16160F),
    surface1 = Color(0xFF22221C),
    surface2 = Color(0xFF2C2C26),
    border = Color(0x24FFFFFF),
    borderStrong = Color(0x42FFFFFF),
    textPrimary = Color(0xFFE6E4DA),
    textSecondary = Color(0xFFB4B2A9),
    textMuted = Color(0xFF888780),
    accent = Color(0xFF85B7EB),
    success = Color(0xFF97C459),
    warning = Color(0xFFEF9F27),
    danger = Color(0xFFF09595),
    bgSuccess = Color(0xFF27500A),
    bgWarning = Color(0xFF633806),
    bgDanger = Color(0xFF791F1F),
)

@Composable
fun decoderPalette(): Palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette

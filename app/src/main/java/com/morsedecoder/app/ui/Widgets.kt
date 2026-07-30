package com.morsedecoder.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morsedecoder.core.live.LiveDecoderEngine.LockState

/**
 * Status row (FR-9.13): listening dot + lock pill with elapsed lock time, and the way in
 * to settings. Settings lives here rather than in the control rows because it must be
 * reachable while idle as well as while listening.
 */
@Composable
fun StatusRow(
    listening: Boolean,
    lockState: LockState,
    elapsedMs: Double,
    p: Palette,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (listening) p.success else p.textMuted, CircleShape)
            )
            Text(
                if (listening) "  Listening" else "  Idle",
                color = p.textSecondary, fontSize = 13.sp,
            )
        }
        val (label, bg, fg) = when (lockState) {
            LockState.LOCKED -> Triple("LOCKED ${mmss(elapsedMs)}", p.bgSuccess, p.success)
            LockState.DEGRADED -> Triple("DEGRADED ${mmss(elapsedMs)}", p.bgWarning, p.warning)
            LockState.ACQUIRING -> Triple("ACQUIRING", p.bgWarning, p.warning)
            LockState.SEARCHING -> Triple("SEARCHING", p.surface1, p.textMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = fg,
                modifier = Modifier
                    .background(bg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Text(
                "⚙",
                fontSize = 15.sp, color = p.textMuted,
                modifier = Modifier
                    .clickable(onClick = onSettings)
                    .padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
            )
        }
    }
}

fun mmss(ms: Double): String {
    val s = (ms / 1000).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** One cell of the readout grid (FR-9.2). */
@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String?,
    p: Palette,
    valueColor: Color = p.textPrimary,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .background(p.surface1, RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(label, color = p.textMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 21.sp, color = valueColor)
            if (unit != null) {
                Text(" $unit", fontSize = 12.sp, color = p.textMuted)
            }
        }
        extra?.invoke()
    }
}

/** WPM live trace (FR-9.3): drift is expected, and visible drift is diagnostic. */
@Composable
fun Sparkline(samples: List<Float>, p: Palette) {
    if (samples.size < 2) return
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .padding(top = 3.dp)
    ) {
        val min = samples.min()
        val max = samples.max()
        val span = (max - min).coerceAtLeast(0.5f)
        val path = Path()
        for ((i, v) in samples.withIndex()) {
            val x = i * size.width / (samples.size - 1)
            val y = size.height - (v - min) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, p.accent, style = Stroke(width = 2f))
    }
}

/**
 * Spectrum strip (FR-9.4): an aiming aid. The locked peak renders in accent with a
 * marker, qualified candidates in warning color.
 *
 * Two horizontal rules overlay the bars (FR-9.5): the keying threshold a tone must
 * exceed to register as a mark, and — dashed — where that threshold sits when the `thr`
 * control is turned all the way down. The gap between them is the sensitivity the user
 * still has in hand; when they meet, the control is spent and a signal below the line
 * is not going to be heard by turning `thr` down further. Both are plotted in the bars'
 * own units (dB above the noise floor), so reading a line against a bar is meaningful.
 */
@Composable
fun SpectrumStrip(
    spectrum: FloatArray,
    lockHz: Double,
    locked: Boolean,
    candidates: List<Double>,
    p: Palette,
    thresholdNorm: Float = 0f,
    thresholdMinNorm: Float = 0f,
    bandMinHz: Double = 300.0,
    bandMaxHz: Double = 2000.0,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, p.border, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            val n = spectrum.size
            if (n == 0) {
                drawLine(p.borderStrong, Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
                return@Canvas
            }
            // Log-frequency axis over whatever band is in force: the geometric mean lands
            // mid-strip (600 Hz for the original 300-1200, matching the mockup), and
            // low-frequency detail — where most CW sits — gets more width.
            val lnLo = kotlin.math.ln(bandMinHz)
            val lnSpan = kotlin.math.ln(bandMaxHz) - lnLo
            val bandSpan = bandMaxHz - bandMinHz
            fun xOf(hz: Double) =
                ((kotlin.math.ln(hz.coerceIn(bandMinHz, bandMaxHz)) - lnLo) / lnSpan * size.width).toFloat()
            for (b in 0 until n) {
                val f0 = bandMinHz + b * bandSpan / n
                val f1 = bandMinHz + (b + 1) * bandSpan / n
                val x0 = xOf(f0)
                val x1 = xOf(f1)
                val h = (spectrum[b] * (size.height - 8f)).coerceAtLeast(2f)
                val isLock = locked && lockHz >= f0 && lockHz < f1
                val color = when {
                    isLock -> p.accent
                    candidates.any { it >= f0 && it < f1 } -> p.warning
                    else -> p.textMuted.copy(alpha = 0.55f)
                }
                drawRect(
                    color,
                    topLeft = Offset(x0 + 0.5f, size.height - 2f - h),
                    size = Size((x1 - x0 - 1f).coerceAtLeast(1f), h),
                )
            }
            // Threshold rules, drawn over the bars on the bars' own vertical scale. The
            // minimum goes down first so the live threshold stays legible where they meet.
            fun yOf(norm: Float) = size.height - 2f - norm.coerceIn(0f, 1f) * (size.height - 8f)
            if (thresholdMinNorm > 0f) {
                drawLine(
                    p.textMuted,
                    Offset(0f, yOf(thresholdMinNorm)), Offset(size.width, yOf(thresholdMinNorm)),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f)),
                )
            }
            if (thresholdNorm > 0f) {
                drawLine(
                    p.warning,
                    Offset(0f, yOf(thresholdNorm)), Offset(size.width, yOf(thresholdNorm)),
                    strokeWidth = 1.5f,
                )
            }
            if (locked && lockHz > 0) {
                val cx = xOf(lockHz)
                val tri = Path().apply {
                    moveTo(cx - 4f, 0f); lineTo(cx + 4f, 0f); lineTo(cx, 6f); close()
                }
                drawPath(tri, p.accent)
            }
            drawLine(p.borderStrong, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), strokeWidth = 1f)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mid label is the geometric mean so it sits where the log axis puts it.
            val mid = kotlin.math.sqrt(bandMinHz * bandMaxHz)
            for (f in listOf(bandMinHz, mid, bandMaxHz)) {
                Text(
                    "%.0f".format(f),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = p.textMuted,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("── thr", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = p.warning)
            Text("╌╌ thr min", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = p.textMuted)
        }
    }
}

/** Warning banner (FR-9.7 / P-4): replaces output rather than decorating it. */
@Composable
fun Banner(text: String, bg: Color, fg: Color) {
    Text(
        text,
        color = fg, fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/**
 * Assist switches (FR-9.6/P-3): state lives in the label (`lexicon · off`), not in a
 * toggle position that must be interpreted. Each assist is independent — see D-12 for
 * why the FR-7.4 master switch is not here.
 */
@Composable
fun AssistRow(
    lexicon: Boolean,
    cutNumbers: Boolean,
    onLexicon: (Boolean) -> Unit,
    onCutNumbers: (Boolean) -> Unit,
    p: Palette,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip("lexicon", lexicon, p, Modifier.weight(1f)) { onLexicon(!lexicon) }
        AssistChip("cut nums", cutNumbers, p, Modifier.weight(1f)) { onCutNumbers(!cutNumbers) }
    }
}

@Composable
private fun AssistChip(
    name: String,
    on: Boolean,
    p: Palette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Text(
        "$name · ${if (on) "on" else "off"}",
        color = if (on) p.accent else p.textMuted,
        fontSize = 11.sp,
        modifier = modifier
            .border(0.5.dp, if (on) p.accent else p.border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

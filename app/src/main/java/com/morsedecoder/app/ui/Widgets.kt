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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
 * Spectrum strip (FR-9.4): an aiming aid, and the tone search-range control (FR-2.1c).
 *
 * Two frequency spans are in play, and the distinction is the whole design:
 *
 * - **the graph** ([graphMinHz]..[graphMaxHz]) is the axis extent — a persisted setting
 *   (FR-9.14) that never moves while the user is watching a signal.
 * - **the search range** ([searchLoHz]..[searchHiHz]) is what the decoder actually
 *   sweeps, narrowed live by dragging the dotted edges. Session-only by design.
 *
 * Excluded spectrum is **dimmed, not hidden**. The user narrows the range precisely
 * because something out there was winning the lock, and they need to keep seeing it to
 * understand why it stopped. A strip that hid it would be lying about what is in the
 * room, and the narrowing would look like it had done nothing.
 *
 * One horizontal rule overlays the bars (FR-9.5): the keying threshold a tone must exceed
 * to register as a mark, plotted in the bars' own units (dB above the noise floor) so
 * reading it against a bar is meaningful.
 *
 * Exactly one rule, deliberately. An earlier version also drew the threshold's lower
 * travel limit as a dashed second line, which answered "will turning `thr` down help?"
 * at the cost of implying the decoder has two thresholds. It does not. A limit is a
 * property of the control, so it is stated on the control — the steppers disable and the
 * readout says `min` / `max` — and the strip shows only the live value.
 */
@Composable
fun SpectrumStrip(
    spectrum: FloatArray,
    lockHz: Double,
    locked: Boolean,
    candidates: List<Double>,
    p: Palette,
    thresholdNorm: Float = 0f,
    graphMinHz: Double = 300.0,
    graphMaxHz: Double = 2000.0,
    searchLoHz: Double = graphMinHz,
    searchHiHz: Double = graphMaxHz,
    onRangeChange: (Double, Double) -> Unit = { _, _ -> },
    onResetRange: () -> Unit = {},
) {
    // 44 dp touch target: ±22 dp either side of an edge grabs it. The 56 dp strip is too
    // short to grab reliably otherwise.
    val grabPx = with(LocalDensity.current) { 22.dp.toPx() }
    var widthPx by remember { mutableStateOf(1f) }

    // Everything the gesture handlers read must come through rememberUpdatedState, and
    // both pointerInput blocks must be keyed on Unit.
    //
    // Keying a pointerInput on a value the gesture itself changes cancels the gesture:
    // Compose tears down and relaunches the coroutine when the key changes, so the drag
    // tracked the finger until its first update landed and then died. The tap block had
    // the same fault against `spectrum`, which is replaced ~10x a second — long-press
    // needs half a second of uninterrupted detection and so could essentially never fire.
    val curLo by rememberUpdatedState(searchLoHz)
    val curHi by rememberUpdatedState(searchHiHz)
    val curGraphMin by rememberUpdatedState(graphMinHz)
    val curGraphMax by rememberUpdatedState(graphMaxHz)
    val curSpectrum by rememberUpdatedState(spectrum)
    val rangeChanged by rememberUpdatedState(onRangeChange)
    val rangeReset by rememberUpdatedState(onResetRange)

    // While a drag is in flight the strip renders its own local value rather than the
    // engine's. The engine is authoritative, but it is two thread hops away — decode
    // thread, then StateFlow, then recomposition — and a line that chases the finger
    // across that round trip reads as lag. On release the local value is dropped and the
    // engine's (clamped) value takes over.
    var dragEdge by remember { mutableStateOf<String?>(null) }
    var dragLo by remember { mutableStateOf(searchLoHz) }
    var dragHi by remember { mutableStateOf(searchHiHz) }
    val shownLo = if (dragEdge != null) dragLo else searchLoHz
    val shownHi = if (dragEdge != null) dragHi else searchHiHz

    // Log-frequency axis over the *graph*, so bars never move when the range changes —
    // only the shading does. An axis shifting under a dragging finger would make the
    // control feel like it was fighting back.
    fun xFrac(hz: Double) = fractionOfHz(hz, graphMinHz, graphMaxHz)

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
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                // Drag lives in its own pointerInput from the taps below, so movement past
                // touch slop wins the gesture while a stationary press falls through.
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val loX = fractionOfHz(curLo, curGraphMin, curGraphMax) * widthPx
                            val hiX = fractionOfHz(curHi, curGraphMin, curGraphMax) * widthPx
                            val dLo = kotlin.math.abs(offset.x - loX)
                            val dHi = kotlin.math.abs(offset.x - hiX)
                            dragLo = curLo
                            dragHi = curHi
                            dragEdge = when {
                                minOf(dLo, dHi) > grabPx -> null
                                dLo <= dHi -> "lo"
                                else -> "hi"
                            }
                        },
                        onDragEnd = { dragEdge = null },
                        onDragCancel = { dragEdge = null },
                    ) { change, _ ->
                        val edge = dragEdge ?: return@detectDragGestures
                        change.consume()
                        val hz = snapHz(hzAtFraction(change.position.x / widthPx, curGraphMin, curGraphMax))
                        // Clamped here with the engine's own minimum span, so the line the
                        // finger is holding never shows a position the engine will refuse.
                        if (edge == "lo") {
                            dragLo = hz.coerceIn(curGraphMin, dragHi - MIN_SPAN_HZ)
                        } else {
                            dragHi = hz.coerceIn(dragLo + MIN_SPAN_HZ, curGraphMax)
                        }
                        rangeChanged(dragLo, dragHi)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Long-press isolates the peak under the finger — the one-gesture
                        // form of "just decode this one".
                        onLongPress = { offset ->
                            val hz = peakNear(
                                curSpectrum, curGraphMin, curGraphMax,
                                hzAtFraction(offset.x / widthPx, curGraphMin, curGraphMax),
                            )
                            rangeChanged(
                                snapHz(hz - ISOLATE_HALF_WIDTH_HZ),
                                snapHz(hz + ISOLATE_HALF_WIDTH_HZ),
                            )
                        },
                        // Double-tap clears isolation and any manual narrowing at once.
                        onDoubleTap = { rangeReset() },
                    )
                }
        ) {
            val n = spectrum.size
            if (n == 0) {
                drawLine(p.borderStrong, Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
                return@Canvas
            }
            val graphSpan = graphMaxHz - graphMinHz
            fun xOf(hz: Double) = xFrac(hz) * size.width

            for (b in 0 until n) {
                val f0 = graphMinHz + b * graphSpan / n
                val f1 = graphMinHz + (b + 1) * graphSpan / n
                val x0 = xOf(f0)
                val x1 = xOf(f1)
                val h = (spectrum[b] * (size.height - 8f)).coerceAtLeast(2f)
                val inRange = f0 >= shownLo && f1 <= shownHi
                val isLock = locked && lockHz >= f0 && lockHz < f1
                val color = when {
                    !inRange -> p.textMuted.copy(alpha = 0.18f)
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

            val xLo = xOf(shownLo)
            val xHi = xOf(shownHi)
            // Wash over the excluded regions, so the boundary reads at a glance rather
            // than having to be inferred from two thin rules against the axis labels.
            val wash = p.textMuted.copy(alpha = 0.10f)
            if (xLo > 0f) drawRect(wash, Offset(0f, 0f), Size(xLo, size.height - 1f))
            if (xHi < size.width) drawRect(wash, Offset(xHi, 0f), Size(size.width - xHi, size.height - 1f))

            // Threshold rule spans only the searched part: it describes what the decoder
            // is doing, and outside the range it is not doing anything.
            fun yOf(norm: Float) = size.height - 2f - norm.coerceIn(0f, 1f) * (size.height - 8f)
            if (thresholdNorm > 0f) {
                drawLine(
                    p.warning,
                    Offset(xLo, yOf(thresholdNorm)), Offset(xHi, yOf(thresholdNorm)),
                    strokeWidth = 1.5f,
                )
            }

            // Range edges: dotted rules doubling as grab handles. The wedge at the base
            // points into the searched side, so which way to drag needs no legend.
            val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
            for ((x, dir) in listOf(xLo to 1f, xHi to -1f)) {
                drawLine(
                    p.accent, Offset(x, 0f), Offset(x, size.height - 1f),
                    strokeWidth = 1.5f, pathEffect = dash,
                )
                val baseY = size.height - 1f
                drawPath(
                    Path().apply {
                        moveTo(x, baseY); lineTo(x + dir * 7f, baseY); lineTo(x, baseY - 7f); close()
                    },
                    p.accent,
                )
            }

            if (locked && lockHz > 0 && lockHz >= shownLo && lockHz <= shownHi) {
                val cx = xOf(lockHz)
                drawPath(
                    Path().apply { moveTo(cx - 4f, 0f); lineTo(cx + 4f, 0f); lineTo(cx, 6f); close() },
                    p.accent,
                )
            }
            drawLine(p.borderStrong, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), strokeWidth = 1f)
        }

        // Axis labels mark the graph, and stay put while the range moves inside it.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mid = kotlin.math.sqrt(graphMinHz * graphMaxHz)
            for (f in listOf(graphMinHz, mid, graphMaxHz)) {
                Text(
                    "%.0f".format(f),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = p.textMuted,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("── thr", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = p.warning)
            Spacer(Modifier.weight(1f))
            val narrowed = shownLo > graphMinHz + 1 || shownHi < graphMaxHz - 1
            Text(
                "range %.0f – %.0f Hz".format(shownLo, shownHi),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                color = if (narrowed) p.accent else p.textMuted,
            )
            // Visible only while narrowed: the gesture to undo it (double-tap) is
            // invisible, so the state must never be.
            if (narrowed) {
                Text(
                    "⟲",
                    fontSize = 12.sp, color = p.accent,
                    modifier = Modifier
                        .clickable(onClick = onResetRange)
                        .padding(start = 8.dp, end = 2.dp),
                )
            }
        }
    }
}

/** Isolation half-width: room for the tracker to breathe, tight enough to exclude. */
private const val ISOLATE_HALF_WIDTH_HZ = 60.0

/**
 * Mirrors `LiveDecoderEngine`'s own minimum span. Applied while dragging so the line under
 * the finger never shows a position the engine would refuse — the alternative is a rubber
 * band that snaps back on release.
 */
private const val MIN_SPAN_HZ = 100.0

/**
 * Position of a frequency on the strip's log axis, as a 0..1 fraction.
 *
 * Top-level rather than a local function so the gesture handlers can call it with values
 * read fresh from [androidx.compose.runtime.rememberUpdatedState]. A local closure would
 * capture the graph bounds from the composition that created the pointer-input coroutine,
 * which — because that coroutine deliberately outlives recomposition now — is exactly the
 * stale-capture bug this arrangement exists to avoid.
 */
private fun fractionOfHz(hz: Double, graphMinHz: Double, graphMaxHz: Double): Float {
    val lnLo = kotlin.math.ln(graphMinHz)
    val lnSpan = kotlin.math.ln(graphMaxHz) - lnLo
    if (lnSpan <= 0.0) return 0f
    return ((kotlin.math.ln(hz.coerceIn(graphMinHz, graphMaxHz)) - lnLo) / lnSpan).toFloat()
}

/** Inverse of [fractionOfHz]. */
private fun hzAtFraction(frac: Float, graphMinHz: Double, graphMaxHz: Double): Double {
    val lnLo = kotlin.math.ln(graphMinHz)
    val lnSpan = kotlin.math.ln(graphMaxHz) - lnLo
    return kotlin.math.exp(lnLo + frac.coerceIn(0f, 1f) * lnSpan)
}

/** Round to a readable step so the readout never reads 1837 Hz. */
private fun snapHz(hz: Double): Double = kotlin.math.round(hz / 25.0) * 25.0

/**
 * Frequency of the tallest bar near [aroundHz]: long-press isolates the peak the user
 * aimed at, not the exact pixel they happened to hit.
 */
private fun peakNear(
    spectrum: FloatArray,
    graphMinHz: Double,
    graphMaxHz: Double,
    aroundHz: Double,
    windowHz: Double = 80.0,
): Double {
    if (spectrum.isEmpty()) return aroundHz
    val span = graphMaxHz - graphMinHz
    var bestHz = aroundHz
    var best = -1f
    for (b in spectrum.indices) {
        val f = graphMinHz + (b + 0.5) * span / spectrum.size
        if (kotlin.math.abs(f - aroundHz) > windowHz) continue
        if (spectrum[b] > best) {
            best = spectrum[b]
            bestHz = f
        }
    }
    return bestHz
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

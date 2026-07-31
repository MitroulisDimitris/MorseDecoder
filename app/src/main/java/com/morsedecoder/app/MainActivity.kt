package com.morsedecoder.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.morsedecoder.app.audio.AudioFileImporter
import com.morsedecoder.app.service.DecoderService
import com.morsedecoder.app.service.TxItem
import com.morsedecoder.app.ui.AssistRow
import com.morsedecoder.app.ui.Banner
import com.morsedecoder.app.ui.MetricCard
import com.morsedecoder.app.ui.Palette
import com.morsedecoder.app.ui.Sparkline
import com.morsedecoder.app.ui.SettingsScreen
import com.morsedecoder.app.ui.SpectrumStrip
import com.morsedecoder.app.ui.StatusRow
import com.morsedecoder.app.ui.TranscriptView
import com.morsedecoder.app.ui.decoderPalette
import com.morsedecoder.app.ui.mmss
import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.export.PlainTextTranscript
import com.morsedecoder.core.live.LiveDecoderEngine.LockState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main screen (docs/ui-spec.md): status row, readout grid, spectrum strip, lock
 * controls, transcript, assist switches. Decode state lives in the foreground
 * service, so this activity is stateless across rotation (FR-9.8).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.load(this)
        setContent {
            val p = decoderPalette()
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (dark) {
                androidx.compose.material3.darkColorScheme(
                    primary = p.accent, onPrimary = p.surface0,
                    surface = p.surface2, onSurface = p.textPrimary,
                    background = p.surface0, onBackground = p.textPrimary,
                    outline = p.borderStrong, error = p.danger,
                )
            } else {
                androidx.compose.material3.lightColorScheme(
                    primary = p.accent, onPrimary = p.surface2,
                    surface = p.surface2, onSurface = p.textPrimary,
                    background = p.surface0, onBackground = p.textPrimary,
                    outline = p.borderStrong, error = p.danger,
                )
            }
            androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(p.surface0)
                        .safeDrawingPadding() // Android 15+ edge-to-edge
                ) {
                    MainScreen(p)
                }
            }
        }
    }
}

/**
 * Process-scoped so the launch auto-start fires once per app start. Activity recreation
 * (rotation, theme change) must not restart a session the user stopped, so this cannot
 * live in `remember` or in saved instance state.
 */
private var autoStartAttempted = false

@Composable
private fun MainScreen(p: Palette) {
    val state by DecoderService.stateFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var fileResultText by remember { mutableStateOf<String?>(null) }
    var fileSession by remember { mutableStateOf<com.morsedecoder.core.export.SessionRecorder?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var barDetail by remember { mutableStateOf<TxItem.Bar?>(null) }
    var wpmPinDialog by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val graphMinHz by Settings.graphMinHz.collectAsState()
    val graphMaxHz by Settings.graphMaxHz.collectAsState()

    val saveTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val content = pendingSave
        if (uri != null && content != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
        pendingSave = null
    }
    val saveJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingSave
        if (uri != null && content != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
        pendingSave = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) DecoderService.start(context)
        else showRationale = true
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            fileResultText = "Decoding file…"
            fileResultText = withContext(Dispatchers.Default) {
                try {
                    val source = AudioFileImporter.load(context, uri)
                    // File decode searches the full graph, not a live narrowing: the
                    // narrowing was aimed at what the microphone was hearing, and has no
                    // bearing on a file the user has just chosen. Read fresh, so a change
                    // in settings applies to the next import.
                    val result = MorseDecoder(
                        FrontEnd(
                            bandMinHz = Settings.graphMinHz.value,
                            bandMaxHz = Settings.graphMaxHz.value,
                        )
                    ).decodeFile(source)
                    buildString {
                        append(
                            "tone %.0f Hz · %.1f wpm · fit %.2f".format(
                                result.toneHz, result.wpm, result.modelFitQuality
                            )
                        )
                        if (result.outOfCalibratedRange) append(" · OUTSIDE 5–40 WPM")
                        append('\n')
                        append(result.text.ifEmpty { "(nothing decodable)" })
                    }
                } catch (e: Exception) {
                    "Import failed: ${e.message}"
                }
            }
        }
    }

    // Listening is the default mode: the app opens straight into it rather than making
    // the user tap through an idle screen to reach the thing it exists to do. Stop drops
    // back to the idle screen, which is where a file gets imported instead.
    //
    // The guard is process-scoped, not remembered: a rotation must not re-start a session
    // the user deliberately stopped, and neither must returning to a stopped screen.
    val startListening = {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) permissionLauncher.launch(needed.toTypedArray())
        else DecoderService.start(context)
    }
    LaunchedEffect(Unit) {
        if (!autoStartAttempted) {
            autoStartAttempted = true
            if (!state.listening) startListening()
        }
    }

    val scroll = rememberScrollState()
    LaunchedEffect(state.transcript.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // 1. status row
        StatusRow(
            state.listening && !state.paused && !state.userPaused,
            state.lockState, state.elapsedLockMs, p,
            onSettings = { showSettings = true },
        )

        if (showSettings) {
            SettingsScreen(
                graphMinHz = graphMinHz,
                graphMaxHz = graphMaxHz,
                lowest = Settings.GRAPH_LOWEST,
                highest = Settings.GRAPH_HIGHEST,
                step = Settings.GRAPH_STEP,
                minSpan = Settings.GRAPH_MIN_SPAN,
                onGraphMinHz = { Settings.setGraphMinHz(context, it) },
                onGraphMaxHz = { Settings.setGraphMaxHz(context, it) },
                onBack = { showSettings = false },
                p = p,
            )
            return@Column
        }

        if (!state.listening) {
            IdleControls(p, showRationale,
                onListen = startListening,
                onImport = { importLauncher.launch(arrayOf("audio/*")) })
            fileResultText?.let {
                Text("file decode", color = p.textMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = p.textPrimary)
            }
            return@Column
        }

        // top controls: pause/resume and stop the session (fixed position)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { DecoderService.setUserPaused(!state.userPaused) },
                modifier = Modifier.weight(1f),
            ) { Text(if (state.userPaused) "▶  Resume" else "❙❙  Pause", fontSize = 13.sp, maxLines = 1) }
            OutlinedButton(
                onClick = { DecoderService.stop(context) },
                modifier = Modifier.weight(1f),
            ) { Text("■  Stop", fontSize = 13.sp, maxLines = 1) }
        }

        // 2. readout grid (FR-9.2: fit is a primary readout, not a settings detail)
        val outOfRange = state.wpm?.let { it < 5.0 || it > 40.0 } == true
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                "wpm", state.wpm?.let { "%.1f".format(it) } ?: "—",
                if (state.pinnedWpm != null) "pin" else null,
                p, modifier = Modifier.weight(1f),
                extra = { Sparkline(state.wpmTrace, p) },
            )
            MetricCard(
                "tone", if (state.toneHz > 0) "%.0f".format(state.toneHz) else "—", "Hz",
                p, modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                "snr", "%.0f".format(state.snrDb), "dB",
                p, modifier = Modifier.weight(1f),
            )
            MetricCard(
                "fit", "%.2f".format(state.fitQuality), null, p,
                valueColor = when {
                    state.lockState == LockState.DEGRADED -> p.danger
                    state.fitQuality >= 0.8 -> p.success
                    else -> p.warning
                },
                modifier = Modifier.weight(1f),
            )
        }

        // debug: fitQuality distribution over recent marks, for calibrating the output
        // gate thresholds against real audio (compare p10/p50/p90 to the gate band).
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.surface1, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("fit", fontSize = 10.sp, color = p.textMuted, fontFamily = FontFamily.Monospace)
            Text(
                "p10 %.2f  p50 %.2f  p90 %.2f  n%d".format(
                    state.fitP10, state.fitP50, state.fitP90, state.fitSamples
                ),
                fontSize = 10.sp, color = p.textSecondary, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "gate %.2f–%.2f".format(state.gateSuppressBelow, state.gateResumeAbove),
                fontSize = 10.sp, color = p.textMuted, fontFamily = FontFamily.Monospace,
            )
            Text(
                if (state.poorFit) "paused" else "emit",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = if (state.poorFit) p.warning else p.success,
            )
        }

        // 3. spectrum strip (FR-9.4) with the keying threshold and its lower travel
        //    limit overlaid (FR-9.5) — see SpectrumStrip for why they share an axis
        SpectrumStrip(
            state.spectrum, state.toneHz,
            state.lockState == LockState.LOCKED || state.lockState == LockState.DEGRADED,
            state.candidates, p,
            thresholdNorm = state.thresholdNorm,
            graphMinHz = state.graphMinHz,
            graphMaxHz = state.graphMaxHz,
            searchLoHz = state.bandMinHz,
            searchHiHz = state.bandMaxHz,
            onRangeChange = { lo, hi -> DecoderService.setSearchRange(lo, hi) },
            onResetRange = { DecoderService.setSearchRange(state.graphMinHz, state.graphMaxHz) },
        )

        // 4. lock controls — Re-lock and Pin are separate on purpose (FR-9.9) and hold a
        //    fixed position: candidate chips live in their own reserved slot below so that
        //    a candidate appearing or dropping can never reflow these two buttons.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { DecoderService.manualRelock() },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) { Text("Re-lock", fontSize = 13.sp, maxLines = 1) }
            OutlinedButton(
                onClick = { DecoderService.setPinned(!state.pinned) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                colors = if (state.pinned)
                    ButtonDefaults.outlinedButtonColors(containerColor = p.surface1)
                else ButtonDefaults.outlinedButtonColors(),
            ) { Text(if (state.pinned) "Pinned" else "Pin", fontSize = 13.sp, maxLines = 1) }
        }

        // candidate chips (FR-9.12): labelled, tappable actions in a reserved fixed-height
        // slot. The slot stays present but empty when nothing qualifies — its appearance is
        // itself the signal — so the controls above never shift.
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            for (c in state.candidates) {
                OutlinedButton(
                    onClick = { DecoderService.selectCandidate(c) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("⇄ %.0f Hz".format(c), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = p.accent)
                }
            }
        }

        // manual overrides (FR-9.5)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (state.pinnedWpm != null) {
                TextButton(onClick = { DecoderService.pinWpm(null) }) {
                    Text("wpm %.0f ✕".format(state.pinnedWpm), fontSize = 11.sp, color = p.accent)
                }
            } else {
                TextButton(onClick = { wpmPinDialog = true }) {
                    Text("pin wpm", fontSize = 11.sp, color = p.textMuted)
                }
            }
            Spacer(Modifier.weight(1f))
            // The control's travel is finite — past either end the internal clamp
            // swallows the change — so the steppers grey out and the readout says which
            // end it is against. This is where the limit is stated; the spectrum strip
            // shows only the live threshold, never its bounds.
            val atMin = state.thresholdDb <= state.thresholdDbMin + 0.5
            val atMax = state.thresholdDb >= state.thresholdDbMax - 0.5
            Text("thr", fontSize = 11.sp, color = p.warning)
            TextButton(
                onClick = { DecoderService.adjustThreshold(state.thresholdDb - 1.0) },
                enabled = !atMin,
            ) { Text("−", fontSize = 13.sp, color = if (atMin) p.textMuted else p.textSecondary) }
            Text(
                "%+.0f dB".format(state.thresholdDb) +
                    if (atMin) " min" else if (atMax) " max" else "",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = if (atMin || atMax) p.textMuted else p.textSecondary,
            )
            TextButton(
                onClick = { DecoderService.adjustThreshold(state.thresholdDb + 1.0) },
                enabled = !atMax,
            ) { Text("+", fontSize = 13.sp, color = if (atMax) p.textMuted else p.textSecondary) }
        }

        // warnings (FR-9.7) — these replace output rather than decorating it (P-4)
        if (state.userPaused) Banner("❙❙ Paused — tap Resume to continue listening", p.bgWarning, p.warning)
        if (state.paused) Banner("⏸ Paused — another app claimed the microphone", p.bgWarning, p.warning)
        if (state.lowSignal) Banner(
            "Low signal — decoding stopped (weak signal or competing audio)", p.bgDanger, p.danger
        )
        if (state.poorFit && !state.lowSignal) Banner(
            "Signal doesn't match a Morse timing pattern — decoding paused (ambient noise?)",
            p.bgWarning, p.warning,
        )
        if (state.bluetoothMic) Banner("Bluetooth microphone — decode quality degraded", p.bgWarning, p.warning)
        if (outOfRange) Banner("Outside calibrated range (5–40 WPM) — accuracy targets void", p.bgWarning, p.warning)
        if (state.speedImplausible) Banner(
            "Signal > 60 WPM. Decoding Stopped ",
            p.bgWarning, p.warning,
        )
        state.error?.let { Banner(it, p.bgDanger, p.danger) }

        // 5. transcript (FR-9.1: autoscroll, tap-to-copy)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("transcript", color = p.textMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { DecoderService.clearTranscript() }) {
                    Text("clear", fontSize = 11.sp, color = p.textMuted)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(plainText(state.transcript)))
                }) { Text("copy", fontSize = 11.sp, color = p.textMuted) }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(0.5.dp, p.border, RoundedCornerShape(8.dp))
                .padding(10.dp)
                .verticalScroll(scroll)
                .heightIn(min = 80.dp)
        ) {
            TranscriptView(state.transcript, p, onBarTap = { barDetail = it })
            if (state.transcript.isEmpty()) {
                Text("…", fontFamily = FontFamily.Monospace, color = p.textMuted)
            }
        }

        // 6. assist switches (FR-9.6) — visibly off by default (P-3)
        AssistRow(
            lexicon = state.lexiconOn,
            cutNumbers = state.cutNumbersOn,
            onLexicon = { DecoderService.setLexicon(it) },
            onCutNumbers = { DecoderService.setCutNumbers(it) },
            p = p,
        )
    }

    // re-lock bar detail (FR-9.11): old frequency, new frequency, timestamp — on tap
    barDetail?.let { bar ->
        AlertDialog(
            onDismissRequest = { barDetail = null },
            confirmButton = { TextButton(onClick = { barDetail = null }) { Text("OK") } },
            title = { Text("Re-lock") },
            text = {
                Text(
                    "%.0f Hz → %.0f Hz at %s".format(bar.fromHz, bar.toHz, mmss(bar.atMs)),
                    fontFamily = FontFamily.Monospace,
                )
            },
        )
    }

    if (wpmPinDialog) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { wpmPinDialog = false },
            title = { Text("Pin WPM") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("5–40") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    value.toDoubleOrNull()?.let { if (it in 5.0..40.0) DecoderService.pinWpm(it) }
                    wpmPinDialog = false
                }) { Text("Pin") }
            },
            dismissButton = { TextButton(onClick = { wpmPinDialog = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The idle screen — where Stop lands. Listening is the launch default, so the reason to
 * be here is almost always to decode a file instead; Import leads, Listen restarts.
 */
@Composable
private fun IdleControls(p: Palette, showRationale: Boolean, onListen: () -> Unit, onImport: () -> Unit) {
    if (showRationale) {
        Banner(
            "Microphone access is required to hear the Morse tone. Audio is processed " +
                "on-device only; the app has no network permission.",
            p.bgWarning, p.warning,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onImport) { Text("Import file") }
        OutlinedButton(onClick = onListen) { Text("Listen") }
    }
}

/** Clipboard form: FR-9.10 vocabulary via the shared renderer, no header (FR-10.3). */
private fun plainText(items: List<TxItem>): String =
    PlainTextTranscript.format(items.map {
        when (it) {
            is TxItem.Ch -> PlainTextTranscript.Item.Char(it.token, it.isWordStart)
            is TxItem.Bar -> PlainTextTranscript.Item.Relock
        }
    })

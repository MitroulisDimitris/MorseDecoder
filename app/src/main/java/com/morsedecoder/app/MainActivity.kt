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
import com.morsedecoder.app.ui.SpectrumStrip
import com.morsedecoder.app.ui.StatusRow
import com.morsedecoder.app.ui.TranscriptView
import com.morsedecoder.app.ui.decoderPalette
import com.morsedecoder.app.ui.mmss
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
                    val result = MorseDecoder().decodeFile(source)
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

    val scroll = rememberScrollState()
    LaunchedEffect(state.transcript.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // 1. status row
        StatusRow(state.listening && !state.paused && !state.userPaused, state.lockState, state.elapsedLockMs, p)

        if (!state.listening) {
            IdleControls(p, showRationale,
                onListen = {
                    val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
                    val missing = needed.any {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing) permissionLauncher.launch(needed.toTypedArray())
                    else DecoderService.start(context)
                },
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

        // 3. spectrum strip (FR-9.4)
        SpectrumStrip(
            state.spectrum, state.toneHz,
            state.lockState == LockState.LOCKED || state.lockState == LockState.DEGRADED,
            state.candidates, p,
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
            Text("thr", fontSize = 11.sp, color = p.textMuted)
            TextButton(onClick = { DecoderService.adjustThreshold(state.thresholdDb - 1.0) }) {
                Text("−", fontSize = 13.sp, color = p.textSecondary)
            }
            Text(
                "%+.0f dB".format(state.thresholdDb),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = p.textSecondary,
            )
            TextButton(onClick = { DecoderService.adjustThreshold(state.thresholdDb + 1.0) }) {
                Text("+", fontSize = 13.sp, color = p.textSecondary)
            }
        }

        // warnings (FR-9.7) — these replace output rather than decorating it (P-4)
        if (state.userPaused) Banner("❙❙ Paused — tap Resume to continue listening", p.bgWarning, p.warning)
        if (state.paused) Banner("⏸ Paused — another app claimed the microphone", p.bgWarning, p.warning)
        if (state.lowSignal) Banner(
            "Low signal — decoding stopped (weak signal or competing audio)", p.bgDanger, p.danger
        )
        if (state.bluetoothMic) Banner("Bluetooth microphone — decode quality degraded", p.bgWarning, p.warning)
        if (outOfRange) Banner("Outside calibrated range (5–40 WPM) — accuracy targets void", p.bgWarning, p.warning)
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
            master = state.assistsMaster,
            lexicon = state.lexiconOn,
            cutNumbers = state.cutNumbersOn,
            onMaster = { DecoderService.setAssistsMaster(it) },
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
        Button(onClick = onListen) { Text("Listen") }
        OutlinedButton(onClick = onImport) { Text("Import file") }
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

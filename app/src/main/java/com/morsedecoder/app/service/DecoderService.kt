package com.morsedecoder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import com.morsedecoder.app.MainActivity
import com.morsedecoder.app.audio.MicCapture
import com.morsedecoder.core.export.CutNumbers
import com.morsedecoder.core.export.SessionRecorder
import com.morsedecoder.core.live.LiveDecoderEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Transcript items as the UI renders them: characters with their annotation state,
 * and re-lock bars positioned in the stream (FR-9.10). Append-only by construction —
 * the service only ever adds to this list (P-2).
 */
sealed interface TxItem {
    data class Ch(
        val token: String,
        val lowConfidence: Boolean,
        val isWordStart: Boolean,
        /** An assist changed this character; rendered distinctly (P-3). */
        val assisted: Boolean = false,
    ) : TxItem

    data class Bar(val fromHz: Double, val toHz: Double, val atMs: Double) : TxItem
}

/**
 * Foreground service owning the live engine and the microphone (NFR-6): decoding
 * continues with the screen off, held by a partial wakelock.
 *
 * All engine access happens on a single decode thread; the UI observes immutable
 * [UiState] snapshots. Decode state living here — not in the activity — is what
 * makes rotation and configuration changes free (FR-9.8).
 */
class DecoderService : Service() {

    data class UiState(
        val listening: Boolean = false,
        val paused: Boolean = false,        // FR-1.6: mic lost to another app / focus
        val userPaused: Boolean = false,    // user tapped Pause; distinct from focus loss
        val lockState: LiveDecoderEngine.LockState = LiveDecoderEngine.LockState.SEARCHING,
        val toneHz: Double = 0.0,
        val wpm: Double? = null,
        val snrDb: Double = 0.0,
        /** null until enough marks are observed to have an opinion (D-5). */
        val fitQuality: Double? = null,
        val lowSignal: Boolean = false,
        val poorFit: Boolean = false,       // FR-5.8/P-4: fit gate is holding output back
        /** P-4: input implies a speed above the 60 WPM ceiling, so no speed is shown. */
        val speedImplausible: Boolean = false,
        /**
         * P-4 / `known-issues` D-1: the decoded characters cannot be text — the signature
         * of a unit-scale error, which `fitQuality` cannot see because Morse timing is
         * scale-free. Warns; nothing is suppressed on it (`decisions.md` D-22).
         */
        val outputImplausible: Boolean = false,
        /** Mean marks per committed character behind the flag; real text sits near 3.4. */
        val marksPerChar: Double? = null,
        // Debug: fit-quality distribution over recent marks, for on-device gate tuning.
        val fitP10: Double = 1.0,
        val fitP50: Double = 1.0,
        val fitP90: Double = 1.0,
        val fitSamples: Int = 0,
        val gateSuppressBelow: Double = 0.35,
        val gateResumeAbove: Double = 0.55,
        val pinned: Boolean = false,
        val pinnedWpm: Double? = null,      // FR-9.5
        val thresholdDb: Double = 0.0,      // FR-9.5
        // Travel limits of the thr control; placeholders until the engine publishes.
        val thresholdDbMin: Double = -10.0,
        val thresholdDbMax: Double = 2.79,
        // Keying threshold drawn on the spectrum strip (FR-9.4/9.5), in the same
        // dB-above-noise-floor units as the bars.
        val thresholdNorm: Float = 0f,
        val envelopeNorm: Float = 0f,
        val elapsedLockMs: Double = 0.0,
        val candidates: List<Double> = emptyList(),
        val bluetoothMic: Boolean = false,  // FR-1.5
        // The spectrum strip's axis extent — persisted user setting (FR-9.14).
        val graphMinHz: Double = 300.0,
        val graphMaxHz: Double = 2000.0,
        // The search range actually in force (FR-2.1), narrowed live within the graph.
        val bandMinHz: Double = 300.0,
        val bandMaxHz: Double = 2000.0,
        val spectrum: FloatArray = FloatArray(0),   // FR-9.4
        val wpmTrace: List<Float> = emptyList(),    // FR-9.3
        val transcript: List<TxItem> = emptyList(),
        // FR-7: assist switches, independent, all off by default (P-3)
        val lexiconOn: Boolean = false,
        val cutNumbersOn: Boolean = false,
        val error: String? = null,
    )

    companion object {
        private const val CHANNEL_ID = "decoder"
        private const val NOTIFICATION_ID = 1

        /** Stop from the notification: the only control reachable once the UI is gone. */
        private const val ACTION_STOP = "com.morsedecoder.app.STOP"

        val state = MutableStateFlow(UiState())
        val stateFlow: StateFlow<UiState> get() = state

        @Volatile private var instance: DecoderService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DecoderService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DecoderService::class.java))
        }

        fun manualRelock() = instance?.onDecodeThread { it.manualRelock() }
        fun setUserPaused(paused: Boolean) = instance?.setUserPaused(paused)
        fun clearTranscript() = instance?.clearTranscript()
        fun setPinned(value: Boolean) = instance?.onDecodeThread { it.pinned = value }
        fun selectCandidate(freqHz: Double) = instance?.onDecodeThread { it.selectCandidate(freqHz) }
        fun pinWpm(wpm: Double?) = instance?.onDecodeThread { it.pinWpm(wpm) }
        fun adjustThreshold(db: Double) = instance?.onDecodeThread { it.thresholdAdjustDb = db }

        /**
         * FR-2.1 search range, live-adjustable; no-op when not running. Order matters —
         * each setter clamps against the other, so widening has to move the far edge
         * outward first or the clamp swallows half the change.
         */
        fun setSearchRange(loHz: Double, hiHz: Double) = instance?.onDecodeThread {
            if (hiHz > it.bandMaxHz) { it.bandMaxHz = hiHz; it.bandMinHz = loHz }
            else { it.bandMinHz = loHz; it.bandMaxHz = hiHz }
        }

        // ---- assists (FR-7): independent switches, both off by default (P-3) ----
        fun setLexicon(on: Boolean) = instance?.setAssists(lexicon = on)
        fun setCutNumbers(on: Boolean) = instance?.setAssists(cutNumbers = on)

        /** The live session for export (FR-10); null when the service is not running. */
        fun currentSession(): SessionRecorder? = instance?.recorder

        /** Low-confidence annotation threshold (dotted underline in the UI). */
        const val LOW_CONFIDENCE = 0.6
    }

    private val decodeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "decode") }
    private var engine = LiveDecoderEngine()
    private var mic: MicCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    // Folded engine events -> UI transcript. Append-only (P-2); bounded (FR-10.6).
    private val txItems = ArrayList<TxItem>()
    private val wpmTrace = ArrayList<Float>()
    private var eventsConsumed = 0
    private val maxTranscriptItems = 5_000
    private val maxTraceSamples = 160

    // Session recording for export (FR-10.1/10.4).
    var recorder: SessionRecorder? = null
        private set

    // Axis extent from settings; the engine holds the (possibly narrower) search range.
    private var graphMinHz = 300.0
    private var graphMaxHz = 2000.0

    // Assist state (FR-7), both off by default (P-3).
    private var lexiconOn = false
    private var cutNumbersOn = false

    fun setAssists(
        lexicon: Boolean = lexiconOn,
        cutNumbers: Boolean = cutNumbersOn,
    ) {
        lexiconOn = lexicon
        cutNumbersOn = cutNumbers
        recorder?.assistLexicon = lexiconOn
        recorder?.assistCutNumbers = cutNumbersOn
        onDecodeThread { it.setLexiconEnabled(lexiconOn) }
    }

    fun onDecodeThread(block: (LiveDecoderEngine) -> Unit) {
        decodeExecutor.execute {
            block(engine)
            publish()
        }
    }

    /** Debug: nearest-rank percentile of the recent fit window; 1.0 (neutral) when empty. */
    private fun percentile(xs: DoubleArray, p: Double): Double {
        if (xs.isEmpty()) return 1.0
        val sorted = xs.sortedArray()
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** User-initiated pause of the live mic (distinct from FR-1.6 focus loss). */
    fun setUserPaused(paused: Boolean) {
        mic?.setPaused(paused)
        state.value = state.value.copy(userPaused = paused)
    }

    /**
     * Clear the visible transcript. This is a deliberate user reset that starts a fresh
     * display segment — not a decoder retraction. P-2 (append-only) governs the decoder
     * never rewriting what it emitted; it does not bind the user's own screen. The engine
     * and its models are untouched, so decoding continues seamlessly.
     */
    fun clearTranscript() = decodeExecutor.execute {
        txItems.clear()
        wpmTrace.clear()
        publish()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        // A second start while a session is running must not build a second capture on
        // top of the first: the old AudioRecord would be dropped unreleased and would go
        // on holding the microphone for the life of the process. Nothing to do here — the
        // session already running is the session that was asked for.
        if (mic != null) return START_NOT_STICKY

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morsedecoder:decode").apply {
            setReferenceCounted(false)
            acquire()
        }

        requestAudioFocus()

        engine = LiveDecoderEngine()
        // Graph bounds are a persisted user setting, so each session loads them rather
        // than taking the defaults. A live narrowing is deliberately *not* restored —
        // every session starts searching the full graph, so yesterday's narrowing cannot
        // silently hide today's signal.
        com.morsedecoder.app.Settings.load(this)
        graphMinHz = com.morsedecoder.app.Settings.graphMinHz.value
        graphMaxHz = com.morsedecoder.app.Settings.graphMaxHz.value
        engine.bandMaxHz = graphMaxHz
        engine.bandMinHz = graphMinHz
        txItems.clear()
        wpmTrace.clear()
        eventsConsumed = 0
        recorder = SessionRecorder(
            startedAtEpochMs = System.currentTimeMillis(),
            zoneId = java.time.ZoneId.systemDefault().id,
            sourceType = "microphone",
            sourceName = null,
        ).also {
            it.assistLexicon = lexiconOn
            it.assistCutNumbers = cutNumbersOn
        }
        debugTap = DebugTap.create(this)
        val tap = debugTap
        val capture = MicCapture(
            onAudio = { samples, count ->
                val copy = samples.copyOf(count)
                decodeExecutor.execute {
                    tap?.audio(copy, count)
                    engine.feed(copy, count)
                    publish()
                }
            },
            onError = { message ->
                state.value = state.value.copy(error = message, paused = true)
            },
            sourceOverride = tap?.audioSourceOverride,
        )
        // Held only once the capture is actually running: this field is what the guard
        // above reads to decide a session exists, so a failed start must not set it.
        if (!capture.start()) {
            stopSelf()
            return START_NOT_STICKY
        }
        mic = capture
        state.value = UiState(listening = true, bluetoothMic = capture.isBluetoothScoRoute())
        // Deliberately not sticky. A microphone service the system killed must not come
        // back listening on its own with no one there to have asked for it — and on
        // targetSdk 34+ a system-restarted `microphone` service cannot legally call
        // startForeground from the background anyway, so the restart would only crash.
        return START_NOT_STICKY
    }

    /** Device-test instrumentation; null on release builds. See [DebugTap]. */
    @Volatile private var debugTap: DebugTap? = null

    private fun publish() {
        // Fold new engine events into the transcript stream. Characters and re-lock
        // bars arrive in stream order, so the bar lands where it happened (FR-2.13).
        while (eventsConsumed < engine.events.size) {
            when (val ev = engine.events[eventsConsumed++]) {
                is LiveDecoderEngine.Event.CharDecoded -> {
                    txItems.add(
                        TxItem.Ch(
                            token = ev.char.token,
                            lowConfidence = ev.char.confidence < LOW_CONFIDENCE,
                            isWordStart = ev.char.isWordStart,
                            assisted = ev.char.assisted,
                        )
                    )
                    wpmTrace.add((1200.0 / ev.char.unitMsGlobal).toFloat())
                    if (wpmTrace.size > maxTraceSamples) wpmTrace.removeAt(0)
                    recorder?.addChar(ev.char, engine.toneHz, engine.snrDb)
                    debugTap?.char(ev.char, engine.nowMs())
                }
                is LiveDecoderEngine.Event.Relock -> {
                    debugTap?.relock(ev)
                    txItems.add(TxItem.Bar(ev.fromHz, ev.toHz, ev.atMs))
                    recorder?.addRelock(
                        ev.atMs, ev.fromHz, ev.toHz,
                        when (ev.trigger) {
                            LiveDecoderEngine.RelockTrigger.MANUAL -> "manual"
                            LiveDecoderEngine.RelockTrigger.CANDIDATE -> "candidate"
                            LiveDecoderEngine.RelockTrigger.AUTOMATIC -> "automatic"
                        },
                    )
                }
                is LiveDecoderEngine.Event.LowSignal -> Unit // surfaced via state.lowSignal
            }
        }
        debugTap?.status(engine)
        while (txItems.size > maxTranscriptItems) txItems.removeAt(0)

        val fits = engine.recentFits()
        val prev = state.value
        state.value = prev.copy(
            listening = true,
            lockState = engine.lockState,
            toneHz = engine.toneHz,
            wpm = engine.wpm,
            snrDb = engine.snrDb,
            fitQuality = engine.fitQuality,
            lowSignal = engine.lowSignal,
            poorFit = engine.outputSuppressed,
            speedImplausible = engine.speedImplausible,
            outputImplausible = engine.outputImplausible,
            marksPerChar = engine.marksPerChar,
            fitP10 = percentile(fits, 0.10),
            fitP50 = percentile(fits, 0.50),
            fitP90 = percentile(fits, 0.90),
            fitSamples = fits.size,
            gateSuppressBelow = engine.gateSuppressBelow,
            gateResumeAbove = engine.gateResumeAbove,
            pinned = engine.pinned,
            pinnedWpm = engine.pinnedWpm,
            thresholdDb = engine.thresholdAdjustDb,
            thresholdDbMin = engine.thresholdDbMin,
            thresholdDbMax = engine.thresholdDbMax,
            thresholdNorm = engine.thresholdNorm,
            envelopeNorm = engine.envelopeNorm,
            elapsedLockMs = engine.elapsedLockMs,
            candidates = engine.candidates,
            graphMinHz = graphMinHz,
            graphMaxHz = graphMaxHz,
            bandMinHz = engine.bandMinHz,
            bandMaxHz = engine.bandMaxHz,
            bluetoothMic = mic?.isBluetoothScoRoute() ?: false,
            spectrum = engine.spectrum,
            wpmTrace = wpmTrace.toList(),
            transcript = displayItems(),
            lexiconOn = lexiconOn,
            cutNumbersOn = cutNumbersOn,
        )
    }

    /**
     * Display view of the transcript: cut-number expansion (FR-7.3) applied per word
     * when enabled, affected characters flagged. A pure transform — the raw stream is
     * untouched, so switching the assist off shows the raw decode again (P-1/P-3).
     */
    private fun displayItems(): List<TxItem> {
        if (!cutNumbersOn) return txItems.toList()
        val out = ArrayList<TxItem>(txItems.size)
        val word = ArrayList<TxItem.Ch>()
        fun flushWord() {
            if (word.isEmpty()) return
            val expanded = CutNumbers.expandWord(word.map { it.token })
            if (expanded == null) {
                out.addAll(word)
            } else {
                for ((i, ch) in word.withIndex()) {
                    out.add(ch.copy(token = expanded[i], assisted = ch.assisted || expanded[i] != ch.token))
                }
            }
            word.clear()
        }
        for (item in txItems) {
            when (item) {
                is TxItem.Ch -> {
                    if (item.isWordStart) flushWord()
                    word.add(item)
                }
                is TxItem.Bar -> {
                    flushWord()
                    out.add(item)
                }
            }
        }
        flushWord()
        return out
    }

    /** FR-1.6: pause cleanly when another app claims audio, resume on focus return. */
    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        mic?.setPaused(true)
                        state.value = state.value.copy(paused = true)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mic?.setPaused(false)
                        state.value = state.value.copy(paused = false, error = null)
                    }
                }
            }
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    /**
     * The ongoing notification is not dismissable and outlives the UI, so it carries both
     * ways back: tapping it reopens the screen, and Stop ends the session. Without them a
     * backgrounded session holds the microphone with no control anywhere that reaches it.
     */
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Decoder", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DecoderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Morse decoder is listening")
            .setContentText("Using the microphone")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stop,
                ).build()
            )
            .build()
    }

    /**
     * The user swiped the app out of recents. NFR-6 keeps a session alive across the
     * screen going off and the UI going to the background — that is what the service is
     * for — but closing the app is not backgrounding it, and the microphone has to come
     * back at that point rather than stay held by a task that no longer exists.
     *
     * The release runs here rather than being left to [onDestroy] alone: task removal is
     * one of the moments the process may be killed outright, and the microphone must
     * already be handed back by the time that happens.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        releaseAudio()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** Give back the microphone, the wakelock and audio focus. Idempotent. */
    private fun releaseAudio() {
        mic?.stop()
        mic = null
        val tap = debugTap
        debugTap = null
        if (tap != null) decodeExecutor.execute { tap.close() }
        focusRequest?.let {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it)
        }
        focusRequest = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseAudio()
        decodeExecutor.shutdown()
        // Stop returns to the idle screen (import a file, or start listening again), so
        // the pause flags must not survive into it and re-arm on the next session.
        state.value = state.value.copy(listening = false, paused = false, userPaused = false)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

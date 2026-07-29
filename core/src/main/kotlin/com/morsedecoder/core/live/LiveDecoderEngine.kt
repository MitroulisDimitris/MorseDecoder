package com.morsedecoder.core.live

import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.dsp.Goertzel
import com.morsedecoder.core.dsp.RollingPercentiles
import com.morsedecoder.core.dsp.Spectrum
import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.model.KalmanUnit
import com.morsedecoder.core.model.ObservationFit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/**
 * The live decoding engine (milestone 3): push PCM in, get an append-only stream of
 * characters, lock telemetry, and re-lock markers out.
 *
 * Platform-free by design — the Android layer owns AudioRecord, services and UI;
 * everything decodable about the live path lives here so AC-8/9/10 run as plain
 * JUnit against synthetic audio.
 *
 * Decoding starts when listening starts (P-5): the engine has no memory of audio
 * before the first [feed].
 */
class LiveDecoderEngine(
    private val sampleRate: Int = 16_000,
    private val cfg: Config = Config(),
) {

    data class Config(
        val windowMs: Double = 10.0,
        val hopMs: Double = 2.5,           // FR-2.7
        val sweepIntervalMs: Double = 100.0,
        val fftSize: Int = 4096,
        val bandMinHz: Double = 300.0,     // FR-2.1
        val bandMaxHz: Double = 1200.0,
        val acquireSweeps: Int = 3,
        val trackEmaAlpha: Double = 0.02,  // FR-2.3: slow, must not chase chirp
        val trackWindowHz: Double = 15.0,  // FR-2.4: hysteresis window
        /**
         * Total tracking drift allowed from the anchor set at lock time. Without this
         * the EMA can walk down a keying-sideband skirt onto a different signal —
         * an unmarked re-lock in slow motion. Real transmitter drift is Hz-scale.
         */
        val trackClampHz: Double = 20.0,
        /**
         * Minimum distance for an automatic re-lock target: anything beyond the
         * tracking clamp is a distinct signal. (The 100 Hz figure in FR-2.14 governs
         * the candidate *display*, whose sustain rule exists to stop chip flicker.)
         */
        val relockMinDistHz: Double = 40.0,
        val mainsVetoHz: Double = 5.0,     // FR-2.5
        val mainsOverride: Boolean = false,

        // ---- Schmitt keying thresholds, as a fraction of the floor..peak envelope span.
        // The manual dB bias (FR-9.5) scales these, and the clamps bound how far it can
        // move them — which is what makes the control's travel finite and showable.
        val thresholdOnFraction: Double = 0.50,
        val thresholdOffFraction: Double = 0.25,
        val thresholdOnMinFraction: Double = 0.05,
        val thresholdOnMaxFraction: Double = 0.95,
        /** p95/p20 envelope spread below which the low-signal state holds (FR-3.7). */
        val keyingSpreadRatio: Double = 15.0,
        val floorWindowMs: Double = 10_000.0, // FR-3.1: long vs the slowest element
        val candidateMinRatio: Double = 4.0,  // FR-2.14: >= 6 dB above floor
        val candidateMinDistHz: Double = 100.0,
        val candidateSustainMs: Double = 2_000.0,
        val maxCandidates: Int = 2,           // FR-2.15
        val relockHoldoffMs: Double = 15_000.0, // FR-2.10.4
        val relockPoorElements: Int = 20,       // FR-2.10.1 — elements, never seconds
        val relockFitThreshold: Double = 0.45,
        val relockScoreMargin: Double = 1.5,    // FR-2.10.3
        val bootstrapMinMarks: Int = 8,
        val commitHorizonChars: Int = 2,        // NFR-2 Viterbi horizon

        // ---- Output gate (P-4 honest failure / FR-5.8) -------------------------------
        // Suppresses character emission when the signal does not fit a coherent Morse
        // timing model — this is what stops ambient room sound from decoding as garbage.
        //
        // These four numbers are the primary tunables. `fitQuality` is modelFitQuality,
        // a 0..1 score (1.0 = a clean, well-separated dot/dash two-cluster fit). To
        // calibrate against the real world: run real recordings and ambient/noise clips,
        // read `fitQuality` off the engine, and set the two thresholds between the value
        // genuine hand-sent code sustains and the value ambient noise produces. The gap
        // between suppress/resume is a deliberate hysteresis dead-band so output does not
        // flicker on a momentary dip. Counts are in marks (elements), never seconds, so
        // silence can never move the gate — consistent with the re-lock guards.
        /** Master switch. false = emit raw regardless of fit (for A/B against real metrics). */
        val outputGateEnabled: Boolean = true,
        /** fitQuality at or below which a mark counts toward suppression. */
        val emitFitSuppressBelow: Double = 0.35,
        /** fitQuality at or above which a mark counts toward resuming. Must be > suppress. */
        val emitFitResumeAbove: Double = 0.55,
        /** consecutive poor-fit marks before emission is suppressed. */
        val emitSuppressAfterMarks: Int = 6,
        /** consecutive good-fit marks before emission resumes. */
        val emitResumeAfterMarks: Int = 3,
        // ------------------------------------------------------------------------------
    )

    enum class LockState { SEARCHING, ACQUIRING, LOCKED, DEGRADED }
    enum class RelockTrigger { MANUAL, CANDIDATE, AUTOMATIC }

    sealed interface Event {
        data class CharDecoded(val char: RobustTimingDecoder.DecodedChar) : Event
        /** FR-2.13: every re-lock, manual or automatic, is marked. */
        data class Relock(val fromHz: Double, val toHz: Double, val atMs: Double, val trigger: RelockTrigger) : Event
        data class LowSignal(val active: Boolean, val atMs: Double) : Event
    }

    // ---- public status (FR-2.9, FR-3.6, FR-5.8) ----
    var lockState = LockState.SEARCHING; private set
    var toneHz = 0.0; private set
    var snrDb = 0.0; private set
    var fitQuality = 1.0; private set
    var lowSignal = false; private set
    /**
     * FR-5.8/P-4: true when the fit-quality output gate is holding characters back
     * because the recent signal does not fit a coherent Morse timing model (e.g. ambient
     * room sound). Distinct from [lowSignal], which is a loudness/SNR problem.
     */
    var outputSuppressed = false; private set
    private var poorFitStreak = 0
    private var goodFitStreak = 0

    // Debug: a rolling window of the fit values the gate actually judged (ready marks
    // only, so the neutral pre-ready 1.0 does not pollute it). Feeds the on-device
    // fitQuality readout used to calibrate the gate thresholds against real audio.
    private val fitLog = DoubleArray(64)
    private var fitLogCount = 0
    private var fitLogIdx = 0
    /** Snapshot of recent per-mark fit values, oldest-to-newest order not guaranteed. */
    fun recentFits(): DoubleArray = fitLog.copyOf(fitLogCount)
    /** The gate thresholds in force, exposed so the debug readout can mark them. */
    val gateSuppressBelow: Double get() = cfg.emitFitSuppressBelow
    val gateResumeAbove: Double get() = cfg.emitFitResumeAbove
    var pinned = false                     // FR-2.6
    /** FR-9.5: manual WPM pin. Null = automatic. */
    var pinnedWpm: Double? = null; private set

    /**
     * FR-9.5: manual Schmitt threshold bias in dB (positive = less sensitive). Clamped
     * to the range over which it actually moves the threshold — past [thresholdDbMin]
     * the on-fraction clamp swallows the change, so a control that kept counting down
     * would be lying about having an effect.
     */
    var thresholdAdjustDb: Double = 0.0
        set(value) {
            field = value.coerceIn(thresholdDbMin, thresholdDbMax)
        }

    /** Lowest bias that still lowers the threshold; below it the clamp binds. */
    val thresholdDbMin: Double
        get() = 10.0 * log10(cfg.thresholdOnMinFraction / cfg.thresholdOnFraction)

    /** Highest bias that still raises the threshold. */
    val thresholdDbMax: Double
        get() = 10.0 * log10(cfg.thresholdOnMaxFraction / cfg.thresholdOnFraction)

    // ---- keying-threshold telemetry (FR-9.5 aiming aid) --------------------------------
    // All three are heights on the same scale the spectrum strip uses for its bars:
    // decibels above the signal's own noise floor, normalised over a 30 dB range. The
    // strip's floor is the FFT band median and these use the envelope floor, so this is a
    // comparison of headroom-above-noise, not of absolute levels — which is exactly the
    // question the control answers: is the threshold under the tone, and how much room is
    // left below it?
    /** Current keying threshold. */
    var thresholdNorm = 0f; private set
    /** Where the threshold would sit at [thresholdDbMin] — the floor of the control. */
    var thresholdMinNorm = 0f; private set
    /** Current envelope level at the locked tone. */
    var envelopeNorm = 0f; private set
    var lockedSinceMs = 0.0; private set
    val elapsedLockMs: Double get() = if (lockState >= LockState.LOCKED) nowMs() - lockedSinceMs else 0.0

    /**
     * Estimated sending speed, or null when there is no usable estimate. A tracker
     * pinned against the [KalmanUnit.MAX_WPM] ceiling is reporting a clamp, not a
     * measurement, so it reads as "no estimate" rather than as a suspiciously round
     * 60 WPM — the readout must never show a speed the decoder does not believe.
     */
    val wpm: Double? get() = kalman?.let { if (it.atSpeedCeiling) null else it.wpm }

    /**
     * P-4 honest failure: the input implies a speed above [KalmanUnit.MAX_WPM], which no
     * sender produces. Either a bootstrap was thrown out for it or a live tracker is being
     * held off the ceiling; [wpm] reads as nothing in both cases, and this says why.
     */
    val speedImplausible: Boolean get() = kalman?.atSpeedCeiling ?: bootstrapTooFast
    val candidates: List<Double>
        get() = candidateList.filter { nowMs() - it.firstSeenMs >= cfg.candidateSustainMs }
            .sortedByDescending { it.ratio }
            .take(cfg.maxCandidates)
            .map { it.freqHz }

    val events = ArrayList<Event>()
    val transcript = ArrayList<RobustTimingDecoder.DecodedChar>()

    /**
     * FR-9.4 aiming aid: latest band spectrum, [spectrumBins] values normalised 0..1
     * over cfg.bandMinHz..bandMaxHz. Empty until the first sweep.
     */
    var spectrum: FloatArray = FloatArray(0); private set
    val spectrumBins = 64

    fun nowMs(): Double = totalSamples * 1000.0 / sampleRate

    // ---- internals ----
    private val win = (cfg.windowMs * sampleRate / 1000.0).toInt()
    private val hop = (cfg.hopMs * sampleRate / 1000.0).toInt()
    private val ringSize = 16_384
    private val ring = FloatArray(ringSize)
    private var totalSamples = 0L
    private var nextFrameStart = 0L
    private var nextSweepMs = 0.0
    private val frameScratch = FloatArray(win)

    private var goertzel: Goertzel? = null
    private val envRing = RollingPercentiles((cfg.floorWindowMs / cfg.hopMs).toInt())

    // acquisition
    private var acqFreq = 0.0
    private var acqHits = 0
    private var acqMisses = 0

    // keying / run-length state
    private var keyState = false
    private var runStartSample = 0L
    private val pendingRuns = ArrayDeque<FrontEnd.Run>()

    // decoding state
    private var kalman: KalmanUnit? = null
    private var markMix: GaussianMixture? = null
    private var gapMix: GaussianMixture? = null
    private var decoder: RobustTimingDecoder? = null
    private var drained = 0
    private val bootstrapRuns = ArrayList<FrontEnd.Run>()
    /** Last bootstrap was discarded for implying a speed above the ceiling. */
    private var bootstrapTooFast = false
    private var needResync = true          // FR-4.2: discard a partial leading character
    private val obsFit = ObservationFit()
    private var poorElements = 0
    private var lastLockChangeMs = -1e12

    private class Candidate(var freqHz: Double, val firstSeenMs: Double, var lastSeenMs: Double, var ratio: Double)
    private val candidateList = ArrayList<Candidate>()

    private class Peak(val freqHz: Double, val ratio: Double, val power: Double)

    // ---- input ----

    fun feed(samples: FloatArray, count: Int = samples.size) {
        require(count <= ringSize - win) { "chunk too large; feed in <= ${ringSize - win} sample slices" }
        for (i in 0 until count) {
            ring[((totalSamples + i) % ringSize).toInt()] = samples[i]
        }
        totalSamples += count

        if (lockState == LockState.LOCKED || lockState == LockState.DEGRADED) {
            while (nextFrameStart + win <= totalSamples) {
                processFrame(nextFrameStart)
                nextFrameStart += hop
            }
        } else {
            nextFrameStart = totalSamples // no envelope until a lock exists
        }

        while (nowMs() >= nextSweepMs) {
            if (totalSamples >= cfg.fftSize) sweep()
            nextSweepMs += cfg.sweepIntervalMs
        }
    }

    /** End-of-stream for file-fed usage and tests; a live session simply stops feeding. */
    fun finish() {
        closeCurrentRun()
        while (pendingRuns.isNotEmpty()) handleElement(pendingRuns.removeFirst())
        decoder?.flush()
        drainDecoder()
    }

    // ---- manual controls ----

    /** FR-2.11: manual re-lock, bypasses all guards. */
    fun manualRelock(): Boolean {
        if (totalSamples < cfg.fftSize) return false
        val peaks = findPeaks()
        val best = peaks.firstOrNull { allowedLockFreq(it.freqHz) } ?: return false
        if (lockState == LockState.LOCKED || lockState == LockState.DEGRADED) {
            switchLock(best.freqHz, RelockTrigger.MANUAL)
        } else {
            lockOn(best.freqHz)
        }
        return true
    }

    /** FR-2.16: candidate selection switches immediately. Explicit user action, so no mains veto. */
    fun selectCandidate(freqHz: Double) {
        if (lockState == LockState.LOCKED || lockState == LockState.DEGRADED) {
            switchLock(freqHz, RelockTrigger.CANDIDATE)
        } else {
            lockOn(freqHz)
        }
    }

    /** Direct frequency entry / test hook. Counts as a manual lock change. */
    fun forceLock(freqHz: Double) {
        if (lockState == LockState.LOCKED || lockState == LockState.DEGRADED) {
            switchLock(freqHz, RelockTrigger.MANUAL)
        } else {
            lockOn(freqHz)
        }
    }

    /**
     * FR-7.1 toggle. Recreates the decoder with current models; the un-committed
     * tail is dropped (nothing emitted changes — P-2). Off by default (P-3).
     */
    fun setLexiconEnabled(enabled: Boolean) {
        if (enabled == lexiconEnabled) return
        lexiconEnabled = enabled
        val k = kalman ?: return
        val mm = markMix ?: return
        val gm = gapMix ?: return
        drainDecoder()
        decoder = RobustTimingDecoder(
            k, mm, gm,
            RobustTimingDecoder.Config(commitHorizonChars = cfg.commitHorizonChars),
            prior = if (enabled) com.morsedecoder.core.assist.CwLexiconPrior() else null,
        )
        drained = 0
        needResync = true
    }

    var lexiconEnabled = false
        private set

    /**
     * FR-9.5: pin the WPM. Overrides and freezes the Kalman unit; if no decoder
     * exists yet the pin substitutes for the bootstrap entirely. Null unpins.
     */
    fun pinWpm(wpm: Double?) {
        if (wpm == null) {
            pinnedWpm = null
            kalman?.frozen = false
            return
        }
        pinnedWpm = wpm
        val unit = 1200.0 / wpm
        val existing = kalman
        if (existing != null) {
            existing.overrideTo(unit)
            existing.frozen = true
        } else {
            val k = KalmanUnit(unit, (0.05 * unit) * (0.05 * unit))
            k.frozen = true
            kalman = k
            val mm = structuralMarkMix()
            val gm = structuralGapMix()
            markMix = mm
            gapMix = gm
            val dec = RobustTimingDecoder(
                k, mm, gm,
                RobustTimingDecoder.Config(commitHorizonChars = cfg.commitHorizonChars),
            )
            decoder = dec
            drained = 0
            for (r in bootstrapRuns) dec.feed(r)
            bootstrapRuns.clear()
            drainDecoder()
        }
    }

    // ---- frame path ----

    private fun processFrame(frameStart: Long) {
        val g = goertzel ?: return
        for (k in 0 until win) {
            frameScratch[k] = ring[((frameStart + k) % ringSize).toInt()]
        }
        val e = g.power(frameScratch, 0)
        envRing.add(e)
        if (!envRing.ready) return

        val floor = envRing.p20
        val peak = envRing.p95
        val spread = peak / max(floor, 1e-15)

        val lowNow = peak < 1e-12 || spread < cfg.keyingSpreadRatio
        if (lowNow != lowSignal) {
            lowSignal = lowNow
            events.add(Event.LowSignal(lowNow, nowMs()))
            if (lowNow) {
                closeCurrentRun()
                keyState = false
                needResync = true // whatever was mid-air is broken; FR-3.5 long-dropout rule
            }
            refreshDegraded()
        }
        snrDb = 10.0 * log10(max(spread, 1e-9))

        val bias = Math.pow(10.0, thresholdAdjustDb / 10.0) // FR-9.5 manual threshold
        val onFraction = (cfg.thresholdOnFraction * bias)
            .coerceIn(cfg.thresholdOnMinFraction, cfg.thresholdOnMaxFraction)
        val onThr = floor + onFraction * (peak - floor)
        val offThr = floor + (cfg.thresholdOffFraction * bias).coerceIn(0.02, 0.90) * (peak - floor)

        // Published before the low-signal bail-out: when nothing is decoding, where the
        // threshold sits relative to the signal is precisely what the user needs to see.
        thresholdNorm = normAboveFloor(onThr, floor)
        thresholdMinNorm = normAboveFloor(floor + cfg.thresholdOnMinFraction * (peak - floor), floor)
        envelopeNorm = normAboveFloor(e, floor)

        if (lowSignal) return // FR-3.7: the whole response — no compensation

        val newState = if (keyState) e > offThr else e > onThr
        if (newState != keyState) {
            val startMs = runStartSample * 1000.0 / sampleRate
            val durMs = (frameStart - runStartSample) * 1000.0 / sampleRate
            if (durMs > 0) pushRun(FrontEnd.Run(keyState, startMs, durMs))
            keyState = newState
            runStartSample = frameStart
        }
    }

    private fun closeCurrentRun() {
        val end = nextFrameStart
        if (end > runStartSample) {
            val startMs = runStartSample * 1000.0 / sampleRate
            val durMs = (end - runStartSample) * 1000.0 / sampleRate
            pushRun(FrontEnd.Run(keyState, startMs, durMs))
            runStartSample = end
        }
    }

    /**
     * Streaming glitch pruning: marks below 0.3 units are impulse debris (FR-3.4),
     * gaps below 0.5 units are dropouts to bridge (FR-3.5). Runs resolve once their
     * successor survives the gate.
     */
    private fun pushRun(run: FrontEnd.Run) {
        pendingRuns.addLast(run)
        val u = kalman?.unitMs
        while (pendingRuns.size >= 3) {
            val a = pendingRuns[0]
            val b = pendingRuns[1]
            val c = pendingRuns[2]
            val gate = if (u == null) 10.0 else if (b.isMark) 0.3 * u else 0.5 * u
            if (b.durationMs < gate) {
                val merged = FrontEnd.Run(a.isMark, a.startMs, a.durationMs + b.durationMs + c.durationMs)
                repeat(3) { pendingRuns.removeFirst() }
                pendingRuns.addFirst(merged)
            } else {
                val out = pendingRuns.removeFirst()
                val outGate = if (u == null) 10.0 else if (out.isMark) 0.3 * u else 0.5 * u
                if (out.durationMs >= outGate) handleElement(out)
            }
        }
    }

    // ---- element path ----

    private fun handleElement(run: FrontEnd.Run) {
        val unit = kalman?.unitMs ?: 60.0
        if (needResync) {
            // FR-4.2: drop everything up to the first clear boundary gap so a partial
            // character cannot contaminate the models.
            if (!run.isMark && run.durationMs >= 2.0 * unit) needResync = false
            return
        }

        // observation-window fit + the element-counted guard input (FR-2.10.1)
        kalman?.let { k ->
            if (run.isMark) {
                obsFit.add(ln(run.durationMs / k.unitMs))
                fitQuality = obsFit.value()
                if (obsFit.ready) {
                    fitLog[fitLogIdx] = fitQuality
                    fitLogIdx = (fitLogIdx + 1) % fitLog.size
                    if (fitLogCount < fitLog.size) fitLogCount++
                }
                refreshDegraded()
            }
        }
        if (obsFit.ready && fitQuality < cfg.relockFitThreshold) poorElements++ else poorElements = 0

        // Output gate (P-4 / FR-5.8): only judge once the observation window is ready —
        // fitQuality is a neutral 1.0 before then, which must not be read as "coherent".
        //
        // fitQuality is noisy on incoherent input (random windows occasionally split into
        // two clusters and read high), so a single good mark must NOT clear the poor
        // count — only a sustained *run* of good-fit marks does. That run is also what
        // lifts suppression. Clean code holds a long good run and never suppresses; ambient
        // noise cannot sustain the run, so it clamps and stays clamped.
        if (cfg.outputGateEnabled && obsFit.ready) {
            if (fitQuality >= cfg.emitFitResumeAbove) {
                goodFitStreak++
                if (goodFitStreak >= cfg.emitResumeAfterMarks) {
                    poorFitStreak = 0
                    outputSuppressed = false
                }
            } else {
                goodFitStreak = 0
                if (fitQuality <= cfg.emitFitSuppressBelow) {
                    poorFitStreak++
                    if (poorFitStreak >= cfg.emitSuppressAfterMarks) outputSuppressed = true
                }
            }
        }

        val dec = decoder
        if (dec == null) {
            bootstrapRuns.add(run)
            if (bootstrapRuns.count { it.isMark } >= cfg.bootstrapMinMarks) initFromBootstrap()
        } else {
            dec.feed(run)
            drainDecoder()
        }
    }

    /** Streaming bootstrap (FR-5.1 role): first elements seed the models, then flow on. */
    private fun initFromBootstrap() {
        val marks = bootstrapRuns.filter { it.isMark }.map { ln(it.durationMs) }
        var c1 = marks.min()
        var c2 = marks.max()
        repeat(20) {
            var s1 = 0.0; var n1 = 0
            var s2 = 0.0; var n2 = 0
            for (v in marks) {
                if (v - c1 <= c2 - v) { s1 += v; n1++ } else { s2 += v; n2++ }
            }
            if (n1 > 0) c1 = s1 / n1
            if (n2 > 0) c2 = s2 / n2
        }
        val u0 = kotlin.math.exp(c1)
        // A bootstrap implying a speed above the sanity ceiling is not a slow sender we
        // mis-measured — it is noise that happened to cluster. Reject it outright rather
        // than seeding a tracker that will then defend the wrong unit (see KalmanUnit).
        if (u0 < KalmanUnit.MIN_UNIT_MS || u0 > 500.0) {
            bootstrapTooFast = u0 < KalmanUnit.MIN_UNIT_MS
            bootstrapRuns.clear() // junk; keep listening
            return
        }
        bootstrapTooFast = false
        val dashOffset = if (c2 - c1 >= ln(1.8)) c2 - c1 else ln(3.0)
        val k = KalmanUnit(u0, (0.25 * u0) * (0.25 * u0))
        val mm = GaussianMixture.of(doubleArrayOf(0.0, dashOffset), 0.25)
        val gm = structuralGapMix()
        kalman = k
        markMix = mm
        gapMix = gm
        val dec = RobustTimingDecoder(
            k, mm, gm,
            RobustTimingDecoder.Config(commitHorizonChars = cfg.commitHorizonChars),
            prior = currentPrior(),
        )
        decoder = dec
        drained = 0
        for (r in bootstrapRuns) dec.feed(r)
        bootstrapRuns.clear()
        drainDecoder()
    }

    private fun drainDecoder() {
        val dec = decoder ?: return
        while (drained < dec.output.size) {
            val ch = dec.output[drained++]
            // P-4: while the fit gate is suppressing, incoherent characters are dropped
            // here — never emitted, so the append-only transcript (P-2) is untouched; the
            // present-tense principle (P-5) makes a never-shown character a non-event.
            if (cfg.outputGateEnabled && outputSuppressed) continue
            transcript.add(ch)
            events.add(Event.CharDecoded(ch))
        }
    }

    // ---- sweep path ----

    private fun sweep() {
        val peaks = findPeaks()
        when (lockState) {
            LockState.SEARCHING -> {
                val best = peaks.firstOrNull { allowedLockFreq(it.freqHz) }
                if (best != null) {
                    acqFreq = best.freqHz
                    acqHits = 1
                    acqMisses = 0
                    lockState = LockState.ACQUIRING
                }
            }
            LockState.ACQUIRING -> {
                val hit = peaks.firstOrNull { abs(it.freqHz - acqFreq) < 20.0 }
                if (hit != null) {
                    acqFreq = 0.7 * acqFreq + 0.3 * hit.freqHz
                    if (++acqHits >= cfg.acquireSweeps) lockOn(acqFreq)
                } else if (++acqMisses >= 3) {
                    lockState = LockState.SEARCHING
                }
            }
            LockState.LOCKED, LockState.DEGRADED -> {
                // FR-2.3 slow EMA + FR-2.4 hysteresis: only a peak near the lock moves
                // it, and never beyond the anchor clamp.
                val near = peaks.filter { abs(it.freqHz - toneHz) <= cfg.trackWindowHz }
                    .maxByOrNull { it.power }
                if (near != null) {
                    val target = near.freqHz.coerceIn(
                        lockAnchorHz - cfg.trackClampHz,
                        lockAnchorHz + cfg.trackClampHz,
                    )
                    toneHz += cfg.trackEmaAlpha * (target - toneHz)
                    rebuildGoertzelIfNeeded()
                }
                updateCandidates(peaks)
                autoRelockCheck(peaks, near)
            }
        }
    }

    private fun findPeaks(): List<Peak> {
        val n = cfg.fftSize
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val start = totalSamples - n
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            re[i] = ring[((start + i) % ringSize).toInt()] * w
        }
        Spectrum.fft(re, im)
        val binHz = sampleRate.toDouble() / n
        val lo = (cfg.bandMinHz / binHz).toInt().coerceAtLeast(1)
        val hi = (cfg.bandMaxHz / binHz).toInt().coerceAtMost(n / 2 - 2)
        val power = DoubleArray(hi - lo + 1) { re[lo + it] * re[lo + it] + im[lo + it] * im[lo + it] }

        val sorted = power.copyOf()
        sorted.sort()
        val floor = max(sorted[sorted.size / 2], 1e-18)

        // Spectrum snapshot for the UI strip (FR-9.4): max-pooled to spectrumBins,
        // log-normalised over a 30 dB range above the band floor.
        val snap = FloatArray(spectrumBins)
        for (b in power.indices) {
            val bin = (b * spectrumBins / power.size).coerceAtMost(spectrumBins - 1)
            val v = normAboveFloor(power[b], floor)
            if (v > snap[bin]) snap[bin] = v
        }
        spectrum = snap

        val peaks = ArrayList<Peak>()
        for (b in 1 until power.size - 1) {
            if (power[b] > floor * 3.0 && power[b] >= power[b - 1] && power[b] >= power[b + 1]) {
                val la = ln(max(power[b - 1], 1e-30))
                val lb = ln(max(power[b], 1e-30))
                val lc = ln(max(power[b + 1], 1e-30))
                val denom = la - 2.0 * lb + lc
                val delta = if (denom == 0.0) 0.0 else (0.5 * (la - lc) / denom).coerceIn(-0.5, 0.5)
                peaks.add(Peak((lo + b + delta) * binHz, power[b] / floor, power[b]))
            }
        }
        return peaks.sortedByDescending { it.power }.take(8)
    }

    private fun updateCandidates(peaks: List<Peak>) {
        val now = nowMs()
        for (p in peaks) {
            if (p.ratio < cfg.candidateMinRatio) continue
            if (abs(p.freqHz - toneHz) < cfg.candidateMinDistHz) continue // FR-2.14
            val existing = candidateList.firstOrNull { abs(it.freqHz - p.freqHz) < 30.0 }
            if (existing != null) {
                existing.freqHz = 0.8 * existing.freqHz + 0.2 * p.freqHz
                existing.lastSeenMs = now
                existing.ratio = p.ratio
            } else {
                candidateList.add(Candidate(p.freqHz, now, now, p.ratio))
            }
        }
        candidateList.removeAll { now - it.lastSeenMs > 1_000.0 }
    }

    /**
     * FR-2.10: an automatic re-lock fires only when ALL guards pass. The bias is
     * deliberately toward holding a questionable lock (D-7); crucially the trigger
     * counts ELEMENTS, never seconds, so silence can never re-lock (AC-9).
     */
    private fun autoRelockCheck(peaks: List<Peak>, near: Peak?) {
        if (pinned) return                                          // FR-2.10.5
        if (lowSignal) return                                       // FR-2.10.2
        if (poorElements < cfg.relockPoorElements) return           // FR-2.10.1
        if (nowMs() - lastLockChangeMs < cfg.relockHoldoffMs) return // FR-2.10.4
        val lockScore = near?.ratio ?: 1.0
        val alt = peaks
            .filter { abs(it.freqHz - toneHz) >= cfg.relockMinDistHz && allowedLockFreq(it.freqHz) }
            .maxByOrNull { it.ratio } ?: return
        if (alt.ratio > lockScore * cfg.relockScoreMargin) {        // FR-2.10.3
            switchLock(alt.freqHz, RelockTrigger.AUTOMATIC)
        }
    }

    // ---- lock transitions ----

    private var lockAnchorHz = 0.0

    private fun lockOn(freqHz: Double) {
        toneHz = freqHz
        lockAnchorHz = freqHz
        rebuildGoertzelIfNeeded(force = true)
        resetSignalPath()
        lockState = LockState.LOCKED
        lockedSinceMs = nowMs()
        lastLockChangeMs = nowMs()
        needResync = true
    }

    private fun switchLock(toHz: Double, trigger: RelockTrigger) {
        drainDecoder() // committed characters survive; the un-committed tail is from the old lock
        events.add(Event.Relock(toneHz, toHz, nowMs(), trigger)) // FR-2.13
        toneHz = toHz
        lockAnchorHz = toHz
        rebuildGoertzelIfNeeded(force = true)
        resetSignalPath()

        // FR-2.12: reset the duration mixtures — elements gathered under a wrong lock
        // must not contaminate the new model. Retain the Kalman estimate as a prior
        // (the sender's speed did not change), but not its certainty.
        val k = kalman
        if (k != null && k.unitMs in 25.0..300.0) {
            k.inflateVariance(9.0)
            val mm = structuralMarkMix()
            val gm = structuralGapMix()
            markMix = mm
            gapMix = gm
            decoder = RobustTimingDecoder(
                k, mm, gm,
                RobustTimingDecoder.Config(commitHorizonChars = cfg.commitHorizonChars),
            )
            drained = 0
        } else {
            kalman = null
            decoder = null
        }
        bootstrapRuns.clear()
        bootstrapTooFast = false
        obsFit.clear()
        fitQuality = 1.0
        poorElements = 0
        outputSuppressed = false
        poorFitStreak = 0
        goodFitStreak = 0
        fitLogCount = 0
        fitLogIdx = 0
        needResync = true
        candidateList.clear()
        lockState = LockState.LOCKED
        lockedSinceMs = nowMs()
        lastLockChangeMs = nowMs()
        pinnedWpm?.let { pinWpm(it) } // a WPM pin survives re-locks (FR-9.5)
    }

    private fun resetSignalPath() {
        envRing.clear()
        thresholdNorm = 0f
        thresholdMinNorm = 0f
        envelopeNorm = 0f
        pendingRuns.clear()
        keyState = false
        runStartSample = totalSamples
        nextFrameStart = totalSamples
        if (lowSignal) {
            lowSignal = false
            events.add(Event.LowSignal(false, nowMs()))
        }
    }

    private var goertzelFreq = -1.0

    private fun rebuildGoertzelIfNeeded(force: Boolean = false) {
        if (force || abs(toneHz - goertzelFreq) > 1.5) {
            goertzel = Goertzel(sampleRate, toneHz, win)
            goertzelFreq = toneHz
        }
    }

    private fun refreshDegraded() {
        if (lockState != LockState.LOCKED && lockState != LockState.DEGRADED) return
        lockState = if (lowSignal || (obsFit.ready && fitQuality < 0.35)) LockState.DEGRADED else LockState.LOCKED
    }

    private fun allowedLockFreq(f: Double): Boolean = cfg.mainsOverride || !isMainsHarmonic(f)

    /** FR-2.5: never auto-lock within ±5 Hz of a 50/60 Hz mains harmonic. */
    private fun isMainsHarmonic(f: Double): Boolean {
        val d50 = abs(f - 50.0 * Math.round(f / 50.0))
        val d60 = abs(f - 60.0 * Math.round(f / 60.0))
        return d50 <= cfg.mainsVetoHz || d60 <= cfg.mainsVetoHz
    }

    /**
     * The one level scale the UI reads: decibels above a noise floor, normalised over a
     * 30 dB range. Spectrum bars and keying-threshold lines both go through here, which
     * is what lets them be drawn on the same axis and compared.
     */
    private fun normAboveFloor(value: Double, floor: Double): Float =
        (ln(max(value, 1e-30) / max(floor, 1e-30)) / ln(1000.0)).coerceIn(0.0, 1.0).toFloat()

    private fun structuralMarkMix() = GaussianMixture.of(doubleArrayOf(0.0, ln(3.0)), 0.3)
    private fun structuralGapMix() = GaussianMixture.of(doubleArrayOf(0.0, ln(3.0), ln(7.0)), 0.3)

    private fun currentPrior() =
        if (lexiconEnabled) com.morsedecoder.core.assist.CwLexiconPrior() else null
}

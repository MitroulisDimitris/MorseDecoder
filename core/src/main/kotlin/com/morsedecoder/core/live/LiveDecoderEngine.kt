package com.morsedecoder.core.live

import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.dsp.Goertzel
import com.morsedecoder.core.dsp.KeyingSpread
import com.morsedecoder.core.dsp.RollingPercentiles
import com.morsedecoder.core.dsp.Spectrum
import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.model.KalmanUnit
import com.morsedecoder.core.model.ObservationFit
import com.morsedecoder.core.model.OutputPlausibility
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
        /**
         * FR-2.7: adapt the analysis window once a speed estimate exists.
         *
         * A Goertzel over N samples is roughly sampleRate/N wide, so the 10 ms default is
         * a ~100 Hz filter. That is right at 40 WPM, where a dot is 30 ms and the window
         * must stay short enough to resolve one — and 17x wider than necessary at 7 WPM,
         * where it admits noise across 100 Hz to measure a 171 ms element.
         *
         * false = fixed 10 ms, the pre-FR-2.7 behaviour, kept for A/B in the bench.
         */
        /**
         * Hold output back while the speed estimate is pinned at the physical ceiling
         * (D-11). false = emit anyway, for A/B in the bench.
         */
        val suppressAtSpeedClamp: Boolean = true,
        val adaptiveWindow: Boolean = true,
        /** Window as a fraction of the dot. A quarter leaves four frames inside a dot. */
        val windowDotFraction: Double = 0.25,
        val windowMinMs: Double = 10.0,
        /**
         * Ceiling on the window. Beyond this the filter narrows past the tracker's own
         * tolerance (`trackWindowHz` = 15 Hz) and a lock sitting slightly off — which
         * D-10 says happens — would lose the signal it is currently getting away with.
         */
        val windowMaxMs: Double = 40.0,
        val sweepIntervalMs: Double = 100.0,
        val fftSize: Int = 4096,
        val bandMinHz: Double = Spectrum.DEFAULT_BAND_MIN_HZ,  // FR-2.1
        val bandMaxHz: Double = Spectrum.DEFAULT_BAND_MAX_HZ,
        val acquireSweeps: Int = 3,
        /**
         * Require a peak to be keyed before locking onto it (D-13). The loudest thing in
         * the band is often not Morse, and a lock on an unkeyed carrier is a dead end:
         * no elements flow, so the element-counted re-lock guards never fire and the
         * search never resumes. Screening at acquisition is the only place this is cheap.
         *
         * false = select on power alone, the pre-D-13 behaviour, kept for A/B comparison.
         */
        val requireKeyingToLock: Boolean = true,
        val lockKeyingSpread: Double = KeyingSpread.LOCK_MIN_SPREAD,
        /** Audio probed when scoring a peak's keying. Must fit the ring. */
        val keyingProbeMs: Double = 600.0,
        // ---- Dead-end recovery (D-13) -------------------------------------------------
        // Both guards below obey one invariant: **a lock that has decoded a character is
        // never dropped by them.** A working lock that later degrades is the existing
        // element-counted re-lock's job (FR-2.10); these two exist only for a lock that
        // has never produced anything, which no other mechanism can correct — no elements
        // flow, so the element counters never advance and the search never resumes. That
        // is the silent failure the field report exhibited: LOCKED, confident, decoding
        // nothing, indefinitely.
        //
        // The invariant is also what keeps AC-9 intact. Gating on `lowSignal` is *not*
        // sufficient protection: it is computed over a 10 s window, so it lags real silence
        // by up to 10 s — long enough for a sweep counter to trip mid-pause.
        /** Consecutive sweeps an unproven lock may carry signal without keying. ~8 s. */
        val unkeyedLockSweeps: Int = 80,
        /** How long an unproven lock may hold at all. [Double.MAX_VALUE] disables it. */
        val unprovenLockMs: Double = 15_000.0,
        // -------------------------------------------------------------------------------
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
        /**
         * D-9 stalled-lock recovery: how long a lock may commit **no character at all**
         * before a keyed signal elsewhere in the band may take it. [Double.MAX_VALUE]
         * disables it, which is how the ablation and the D-7 regression tests turn it off.
         *
         * This is the one guard in FR-2.10 counted in **seconds**, and it is only sound
         * because of the condition it is conjoined with. The element counters cannot reach
         * this case: the operator moves frequency, the old lock produces no elements at all,
         * so `poorElements` never advances and `lowSignal` suspends the check anyway
         * (`known-issues` D-9 — measured at 2 characters for a whole 75 s transmission).
         * A timer is the only thing that observes "nothing is happening here".
         *
         * **What keeps silence safe is the keyed alternative, not the clock** (AC-9). A
         * 30 s pause offers no keyed peak, so the second condition never holds and the
         * timer alone can never fire. 10 s on its own would be unsafe — a very slow
         * Farnsworth sender leaves gaps that long between words.
         */
        val stalledLockMs: Double = 10_000.0,
        val bootstrapMinMarks: Int = 8,
        /**
         * Widest dash/dot ratio a bootstrap seed may claim before it is discarded as a
         * split on glitch debris rather than on dots and dashes. See [initFromBootstrap];
         * D-3 puts real fists at 2:1-2.5:1 against a 3:1 prior, so this is loose on
         * purpose — its job is to catch splits no fist could produce, not to police them.
         */
        val bootstrapMaxRatio: Double = 4.5,
        val commitHorizonChars: Int = 2,        // NFR-2 Viterbi horizon

        // ---- Periodic re-bootstrap (D-2) ---------------------------------------------
        // The live path seeds its unit once, from the first 8 marks, and tracks from there.
        // When that seed is wrong the streaming tracker cannot escape it: a wrong unit
        // mis-segments the stream, and the mis-segmentation feeds the tracker measurements
        // that keep the wrong unit alive. A batch re-fit clusters the recent *marks*
        // directly (dots vs dashes), which is independent of the decoder's segmentation, so
        // it reads the true unit even while the decode is confused. Adopted only on a
        // material disagreement, so a healthy lock is never disturbed (the reset costs the
        // uncommitted tail). Element-counted, never timed — silence cannot trigger it (AC-9).
        /** false = the pre-D-2 commit-once behaviour, kept for A/B in the bench. */
        val rebootstrapEnabled: Boolean = true,
        /** Check cadence, in committed marks since the last check. */
        val rebootstrapEveryMarks: Int = 16,
        /** Marks of history the batch re-fit clusters over. */
        val rebootstrapWindowMarks: Int = 48,
        /** Adopt only when the batch and tracked units differ by more than this ratio. */
        val rebootstrapMaterialRatio: Double = 1.3,

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

        // ---- Output plausibility (D-1) -----------------------------------------------
        // The gate above cannot see a unit-scale error: it keys off `fitQuality`, and
        // Morse timing is scale-free, so a wrong scale leaves every timing score intact.
        // These two describe the one signal that does see it — the decoded characters
        // themselves. It reports and never suppresses; see [OutputPlausibility].
        /** Committed characters the plausibility statistic averages over. */
        val plausibilityWindowChars: Int = OutputPlausibility.DEFAULT_WINDOW,
        /** Mean marks per character below which the transcript cannot be real text. */
        val implausibleMarksPerChar: Double = OutputPlausibility.DEFAULT_IMPLAUSIBLE_BELOW,
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
    /**
     * modelFitQuality over the recent observation window, or **null when too few marks
     * have been seen to have an opinion** (D-5).
     *
     * It used to report a neutral 1.0 until the window filled, which the UI rendered as
     * `fit 1.00` — identical to a flawless decode. A lock producing nothing at all
     * therefore displayed maximum confidence, and measurements showed characters being
     * emitted with literally zero fit samples behind them. "No data" and "perfect" must
     * not be the same value; nullable is the representation that forces every caller to
     * decide which it is holding.
     */
    val fitQuality: Double? get() = if (obsFit.ready) fitQualityRaw else null

    /** The raw score, neutral before the window is ready. Internal gates guard on readiness. */
    private var fitQualityRaw = 1.0
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
    /**
     * Search-band edges (FR-2.1), adjustable while running: the ceiling is a user setting
     * because the right value depends on the recording chain, not on the decoder. Takes
     * effect on the next sweep — no lock is lost, and a lock left outside the new band
     * returns to SEARCHING rather than freezing where it can no longer be tracked.
     */
    var bandMinHz: Double = cfg.bandMinHz
        set(value) { field = value.coerceIn(50.0, bandMaxHz - 100.0) }
    var bandMaxHz: Double = cfg.bandMaxHz
        set(value) { field = value.coerceIn(bandMinHz + 100.0, sampleRate / 2.0 - 100.0) }

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

    /**
     * P-4 honest failure, the case no timing signal can reach: **the decoded characters
     * cannot be text** (`known-issues.md` D-1).
     *
     * A unit an octave short shatters every character into single elements, and because
     * Morse timing is scale-free `fitQuality` reads a serene 1.00 throughout — measured at
     * 297 % CER with `outputSuppressed` false and the lock LOCKED. This is the signal that
     * notices, and it is deliberately **only** a signal: nothing is suppressed on it. See
     * [OutputPlausibility] for the measured margin and why it is not a gate.
     */
    val outputImplausible: Boolean get() = plausibility.implausible

    /**
     * Mean marks per committed character over the recent window — the number behind
     * [outputImplausible]. Null until the window fills; real text sits near 3.4.
     */
    val marksPerChar: Double? get() = plausibility.marksPerChar

    /** The threshold in force, exposed so a debug readout can mark it. */
    val implausibleBelowMarksPerChar: Double get() = plausibility.threshold
    val candidates: List<Double>
        get() = candidateList
            .filter { nowMs() - it.firstSeenMs >= cfg.candidateSustainMs }
            // Never offer a tone the user has excluded from the search: a chip that
            // switches the lock outside the range would contradict the range itself.
            // Entries can outlive a narrowing by up to their eviction timeout.
            .filter { it.freqHz >= bandMinHz && it.freqHz <= bandMaxHz }
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

    /**
     * The processing clock, driven by [curSample] — the sample position of the event being
     * processed, not the write head. This is what makes the decode a pure function of the
     * sample stream rather than of the caller's buffer size (N-2): a sweep at sample P
     * timestamps at P whether P arrived alone or in the middle of a large chunk. Between
     * feeds [curSample] equals [totalSamples], so external readers see the current time.
     */
    fun nowMs(): Double = curSample * 1000.0 / sampleRate

    // ---- internals ----
    private val maxWin = (cfg.windowMaxMs * sampleRate / 1000.0).toInt()
    private var win = (cfg.windowMs * sampleRate / 1000.0).toInt()
    private val hop = (cfg.hopMs * sampleRate / 1000.0).toInt()
    private val ringSize = 16_384
    private val ring = FloatArray(ringSize)
    /** Write head: total samples ever received. */
    private var totalSamples = 0L
    /** Processing cursor: the position of the frame or sweep currently being handled. */
    private var curSample = 0L
    private var nextFrameStart = 0L
    private val sweepIntervalSamples = max(1, (cfg.sweepIntervalMs * sampleRate / 1000.0).toInt())
    /** Absolute sample position of the next sweep — fixed on the stream, not on chunks. */
    private var nextSweepSample = sweepIntervalSamples.toLong()
    private val frameScratch = FloatArray(maxWin)   // sized for the widest window FR-2.7 allows

    private var goertzel: Goertzel? = null
    private val envRing = RollingPercentiles((cfg.floorWindowMs / cfg.hopMs).toInt())

    // Keying probe (D-13): the most recent audio, copied out of the ring so the shared
    // KeyingSpread can score a candidate frequency. Allocated once — this runs per peak
    // per sweep during acquisition.
    private val probeSamples =
        (cfg.keyingProbeMs * sampleRate / 1000.0).toInt().coerceAtMost(ringSize)
    private val probeScratch = FloatArray(probeSamples)

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
    private val plausibility = OutputPlausibility(
        window = cfg.plausibilityWindowChars,
        implausibleBelow = cfg.implausibleMarksPerChar,
    )
    private var poorElements = 0
    private var lastLockChangeMs = -1e12
    /** Consecutive sweeps the current lock has carried signal without keying (D-13). */
    private var unkeyedSweeps = 0
    /** Characters emitted under the current lock — zero means it has proven nothing. */
    private var charsSinceLock = 0
    /**
     * When the current lock last committed a character, or when it was taken. Drives the
     * D-9 stalled-lock check; committed characters rather than elements, because in the
     * stranded state the front end still produces elements from noise at the dead frequency.
     */
    private var lastCharMs = 0.0
    /** Recent post-bootstrap mark log-durations, for the D-2 batch re-fit. */
    private val refitMarks = ArrayDeque<Double>()
    private var marksSinceRefit = 0
    /** Count of adopted re-bootstraps under the current lock — telemetry / test hook. */
    var rebootstraps = 0; private set

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

        // Process frame and sweep events in sample-position order, each reading the window
        // that ends at its own position, so the transcript is a pure function of the sample
        // stream and not of how it was split into feed() calls (N-2). A frame occupies
        // [nextFrameStart, nextFrameStart+win); a sweep at S analyses the FFT window ending
        // at S. Frames only exist once locked, so before a lock only sweeps fire — and a
        // sweep that locks mid-chunk starts the frame grid at its own position, so the rest
        // of the chunk frames from there.
        while (true) {
            val framePos = if (lockState == LockState.LOCKED || lockState == LockState.DEGRADED)
                nextFrameStart + win else Long.MAX_VALUE
            val sweepPos = nextSweepSample
            val next = minOf(framePos, sweepPos)
            if (next > totalSamples) break
            if (framePos <= sweepPos) {
                curSample = framePos
                processFrame(nextFrameStart)
                nextFrameStart += hop
            } else {
                curSample = sweepPos
                // Skip only if the window is not (yet) available: before fftSize samples, or
                // beyond the ring on a pathologically large chunk. Neither occurs for real
                // AudioRecord buffers or the bench.
                if (sweepPos >= cfg.fftSize && sweepPos - cfg.fftSize >= totalSamples - ringSize) {
                    sweep()
                }
                nextSweepSample += sweepIntervalSamples
            }
        }
        curSample = totalSamples
        // No envelope until a lock exists: keep the frame grid at the write head so a lock
        // begins framing from the lock instant (set precisely in resetSignalPath).
        if (lockState != LockState.LOCKED && lockState != LockState.DEGRADED) {
            nextFrameStart = totalSamples
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

    /**
     * FR-2.11: manual re-lock, bypasses all guards. The keying screen is a preference
     * here rather than a requirement — the user asked for a lock, so give them the best
     * available one even if nothing in the band is currently keying.
     */
    fun manualRelock(): Boolean {
        if (totalSamples < cfg.fftSize) return false
        val peaks = findPeaks()
        val allowed = peaks.filter { allowedLockFreq(it.freqHz) }
        val best = allowed.firstOrNull { isKeyed(it.freqHz) }
            ?: allowed.firstOrNull()
            ?: return false
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

    /**
     * FR-2.7: match the analysis window to the speed once one is known.
     *
     * Hysteresis on both sides, because a rebuild throws away the Goertzel table and
     * perturbs the envelope distribution the percentile floor is built from; there is no
     * value in chasing a Kalman estimate that is still settling.
     */
    private fun updateWindowForSpeed() {
        if (!cfg.adaptiveWindow) return
        val unit = kalman?.unitMs ?: return
        val targetMs = (unit * cfg.windowDotFraction)
            .coerceIn(cfg.windowMinMs, cfg.windowMaxMs)
        val target = (targetMs * sampleRate / 1000.0).toInt().coerceIn(8, maxWin)
        if (target > win * 1.25 || target < win * 0.8) {
            win = target
            goertzel = Goertzel(sampleRate, toneHz, win)
            goertzelFreq = toneHz
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
                fitQualityRaw = obsFit.value()
                if (obsFit.ready) {
                    fitLog[fitLogIdx] = fitQualityRaw
                    fitLogIdx = (fitLogIdx + 1) % fitLog.size
                    if (fitLogCount < fitLog.size) fitLogCount++
                }
                refreshDegraded()
            }
        }
        if (obsFit.ready && fitQualityRaw < cfg.relockFitThreshold) poorElements++ else poorElements = 0

        // Output gate (P-4 / FR-5.8): only judge once the observation window is ready —
        // fitQuality is a neutral 1.0 before then, which must not be read as "coherent".
        //
        // fitQuality is noisy on incoherent input (random windows occasionally split into
        // two clusters and read high), so a single good mark must NOT clear the poor
        // count — only a sustained *run* of good-fit marks does. That run is also what
        // lifts suppression. Clean code holds a long good run and never suppresses; ambient
        // noise cannot sustain the run, so it clamps and stays clamped.
        if (cfg.outputGateEnabled && obsFit.ready) {
            if (fitQualityRaw >= cfg.emitFitResumeAbove) {
                goodFitStreak++
                if (goodFitStreak >= cfg.emitResumeAfterMarks) {
                    poorFitStreak = 0
                    outputSuppressed = false
                }
            } else {
                goodFitStreak = 0
                if (fitQualityRaw <= cfg.emitFitSuppressBelow) {
                    poorFitStreak++
                    if (poorFitStreak >= cfg.emitSuppressAfterMarks) outputSuppressed = true
                }
            }
        }

        updateWindowForSpeed()

        val dec = decoder
        if (dec == null) {
            bootstrapRuns.add(run)
            if (bootstrapRuns.count { it.isMark } >= cfg.bootstrapMinMarks) initFromBootstrap()
        } else {
            dec.feed(run)
            drainDecoder()
            // D-2: re-fit the unit from a batch of recent marks and adopt on a material
            // disagreement. Skipped while pinned (FR-9.5 owns the unit) and counted in marks,
            // so a pause cannot trigger it (AC-9).
            if (cfg.rebootstrapEnabled && run.isMark && !pinned && pinnedWpm == null) {
                refitMarks.addLast(ln(run.durationMs))
                while (refitMarks.size > cfg.rebootstrapWindowMarks) refitMarks.removeFirst()
                if (++marksSinceRefit >= cfg.rebootstrapEveryMarks) {
                    marksSinceRefit = 0
                    maybeRebootstrap()
                }
            }
        }
    }

    /** 1-D 2-means over log durations → (lowerMean, upperMean). Shared by bootstrap and re-fit. */
    private fun twoMeans(values: List<Double>): Pair<Double, Double> {
        var c1 = values.min()
        var c2 = values.max()
        repeat(20) {
            var s1 = 0.0; var n1 = 0
            var s2 = 0.0; var n2 = 0
            for (v in values) {
                if (v - c1 <= c2 - v) { s1 += v; n1++ } else { s2 += v; n2++ }
            }
            if (n1 > 0) c1 = s1 / n1
            if (n2 > 0) c2 = s2 / n2
        }
        return c1 to c2
    }

    /**
     * D-2 batch re-fit: cluster the recent marks and, if the resulting unit disagrees
     * materially with the tracked one, adopt it. The clustering sees dots and dashes
     * directly, so it is immune to the mis-segmentation a wrong unit produces — which is the
     * exact trap the streaming tracker cannot climb out of on its own.
     */
    private fun maybeRebootstrap() {
        val k = kalman ?: return
        if (refitMarks.size < cfg.bootstrapMinMarks) return
        // 2-means over the recent mark log-durations — the same clustering `initFromBootstrap`
        // uses, so the re-fit is the bootstrap re-run over a wider, more representative window.
        // It sees dots and dashes directly, so it is immune to the mis-segmentation a wrong
        // unit produces — the exact trap the streaming tracker cannot climb out of on its own.
        val (c1, c2) = twoMeans(refitMarks.toList())
        val dashOffset = c2 - c1
        // Must be two dot/dash clusters at a fist-plausible ratio, not a single smear.
        if (dashOffset < ln(1.8) || dashOffset > ln(cfg.bootstrapMaxRatio)) return
        val u0 = kotlin.math.exp(c1)
        if (u0 < KalmanUnit.MIN_UNIT_MS || u0 > 500.0) return
        // Only when the tracked unit is genuinely elsewhere — otherwise the tracker is fine
        // and a reset would only cost the uncommitted tail (P-2) for nothing.
        if (abs(ln(u0) - ln(k.unitMs)) < ln(cfg.rebootstrapMaterialRatio)) return
        adoptRebootstrap(k, u0, dashOffset)
    }

    /**
     * Adopt the re-fitted unit **in place**, keeping the decoder, its lattice and the
     * committed transcript. The models are relative to the unit (z = ln(dur/unit)) and the
     * decoder holds these same `GaussianMixture` references, so resetting the means to
     * structural values plus overriding the Kalman is a complete re-seed with no seam: no
     * decoder rebuild, no dropped tail, and the uncommitted marks — true durations — simply
     * re-score against the corrected unit on the next commit. Cheaper and less lossy than
     * the re-lock path, which is right because the tone did not change, only the timebase.
     */
    private fun adoptRebootstrap(k: KalmanUnit, u0: Double, dashOffset: Double) {
        k.overrideTo(u0)
        // Hold the batch unit fairly firmly: `overrideTo` sets a tight (5 %) variance, and a
        // modest inflation keeps the tracker able to follow real drift without immediately
        // re-chasing the per-character bias that a wrong bootstrap decodes into. Inflating
        // hard (×9) undid most of the correction within a few characters.
        k.inflateVariance(2.25) // 5 % -> ~7.5 %
        markMix?.let { mm ->
            mm.means[0] = 0.0; mm.means[1] = dashOffset
            mm.sigmas[0] = 0.25; mm.sigmas[1] = 0.25
            mm.weights[0] = 0.5; mm.weights[1] = 0.5
        }
        gapMix?.let { gm ->
            gm.means[0] = 0.0; gm.means[1] = ln(3.0); gm.means[2] = ln(7.0)
            gm.sigmas[0] = 0.3; gm.sigmas[1] = 0.3; gm.sigmas[2] = 0.3
            gm.weights[0] = 1.0 / 3; gm.weights[1] = 1.0 / 3; gm.weights[2] = 1.0 / 3
        }
        obsFit.clear()
        // The characters behind the statistic were decoded against the unit just replaced,
        // so they describe a timebase that no longer exists (D-1).
        plausibility.clear()
        fitQualityRaw = 1.0
        poorElements = 0
        rebootstraps++
    }

    /** Streaming bootstrap (FR-5.1 role): first elements seed the models, then flow on. */
    private fun initFromBootstrap() {
        val marks = bootstrapRuns.filter { it.isMark }.map { ln(it.durationMs) }
        val (c1, c2) = twoMeans(marks)
        // The split has to describe dots and dashes, and a ratio no real fist produces
        // means it does not. `u0` is the *lower* cluster mean, so one glitch below the dots
        // sets the entire timebase on its own: measured on `hand-13-rough`, a 25 ms
        // fragment of a 130 ms dash formed the whole lower cluster by itself and seeded
        // 48 WPM against a true 13, after which every dash read as a run of dots.
        //
        // Membership is deliberately not the test. A singleton lower cluster is normal on
        // a dash-heavy sample — seven dashes and one dot is a legitimate seed at ~3:1 —
        // so rejecting on cluster size alone would throw away good bootstraps. What marks
        // this case out is the *distance*, not the count.
        //
        // The floor below already refuses splits under 1.8:1 as "no two clusters here";
        // this is the same judgement at the other end. Rejecting costs one further
        // bootstrap attempt and nothing else, because the marks keep arriving.
        if (c2 - c1 > ln(cfg.bootstrapMaxRatio)) {
            bootstrapTooFast = false   // not a speed claim; the split is simply not Morse
            bootstrapRuns.clear()      // junk; keep listening
            return
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
            // A unit pinned against the speed clamp is a bound being enforced, not a
            // measurement (D-11), so every character derived from it is derived from a
            // non-measurement. D-11 already refuses to *display* a clamped speed; the
            // same reasoning refuses to emit characters computed with one.
            //
            // Deliberately narrower than "outside the 5-40 calibrated band". FR-5.7 asks
            // for best-effort decoding there, and measurement backs it: 50 WPM synthetic
            // code decodes at 0 % CER while RTTY at 52.9 WPM is garbage. Speed carries no
            // information about correctness in that overlap. Only the clamp does, because
            // it means the estimator stopped measuring.
            if (cfg.suppressAtSpeedClamp && speedImplausible) continue
            transcript.add(ch)
            charsSinceLock++
            lastCharMs = nowMs()
            plausibility.add(ch.elements.count { it.isMark })
            events.add(Event.CharDecoded(ch))
        }
    }

    // ---- sweep path ----

    private fun sweep() {
        val peaks = findPeaks()
        when (lockState) {
            LockState.SEARCHING -> {
                // Strongest peak that is both allowed and keyed (D-13). Power alone would
                // happily settle on hum or a speaker resonance and then never recover.
                val best = peaks.firstOrNull { allowedLockFreq(it.freqHz) && isKeyed(it.freqHz) }
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
                // The band ceiling is a live setting; a lock left outside it can no longer
                // be tracked, so resume searching rather than sit on a frequency the
                // sweep no longer looks at.
                if (toneHz < bandMinHz || toneHz > bandMaxHz) {
                    lockState = LockState.SEARCHING
                    candidateList.clear()
                    unkeyedSweeps = 0
                    return
                }
                // Dead-end recovery (D-13), part two: a lock that has never decoded
                // anything is not a lock worth keeping, however quiet its frequency is.
                if (charsSinceLock == 0 && nowMs() - lockedSinceMs > cfg.unprovenLockMs) {
                    lockState = LockState.SEARCHING
                    candidateList.clear()
                    unkeyedSweeps = 0
                    return
                }
                // Dead-end recovery (D-13): signal present at the lock, but not keying.
                // Only ever applied to a lock that has never decoded anything — see the
                // invariant on [Config.unkeyedLockSweeps]. lowSignal alone is not enough
                // protection here: it is computed over a 10 s window and so lags real
                // silence by up to 10 s, which is long enough to trip this guard.
                if (cfg.requireKeyingToLock && charsSinceLock == 0 && !lowSignal && !isKeyed(toneHz)) {
                    if (++unkeyedSweeps >= cfg.unkeyedLockSweeps) {
                        unkeyedSweeps = 0
                        lockState = LockState.SEARCHING
                        candidateList.clear()
                        return
                    }
                } else {
                    unkeyedSweeps = 0
                }
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
                stalledLockCheck()
            }
        }
    }

    private fun findPeaks(): List<Peak> {
        val n = cfg.fftSize
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val start = curSample - n // window ends at the event position, not the chunk head (N-2)
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            re[i] = ring[((start + i) % ringSize).toInt()] * w
        }
        Spectrum.fft(re, im)
        val binHz = sampleRate.toDouble() / n
        val lo = (bandMinHz / binHz).toInt().coerceAtLeast(1)
        val hi = (bandMaxHz / binHz).toInt().coerceAtMost(n / 2 - 2)
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

    /**
     * D-13: is this frequency actually being keyed? Scored over the last [Config.keyingProbeMs]
     * of audio. Returns false when there is not yet enough audio to tell, so acquisition
     * waits for evidence instead of guessing — about half a second at startup.
     */
    private fun isKeyed(freqHz: Double): Boolean {
        if (!cfg.requireKeyingToLock) return true
        return keyingSpreadAt(freqHz) >= cfg.lockKeyingSpread
    }

    /**
     * Envelope spread at [freqHz] over the audio ending at the current processing position;
     * 0.0 when too little exists. Anchored to [curSample] so a sweep's keying test reads the
     * same window it found the peak in, regardless of chunking (N-2).
     */
    fun keyingSpreadAt(freqHz: Double): Double {
        if (curSample < probeSamples) return 0.0
        val start = curSample - probeSamples
        for (i in 0 until probeSamples) {
            probeScratch[i] = ring[((start + i) % ringSize).toInt()]
        }
        return KeyingSpread.of(probeScratch, sampleRate, freqHz)
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
        // An automatic re-lock must land on something keyed (D-13): moving the lock to
        // another unkeyed carrier trades one dead end for another.
        val alt = peaks
            .filter { abs(it.freqHz - toneHz) >= cfg.relockMinDistHz && allowedLockFreq(it.freqHz) }
            .sortedByDescending { it.ratio }
            .firstOrNull { isKeyed(it.freqHz) } ?: return
        if (alt.ratio > lockScore * cfg.relockScoreMargin) {        // FR-2.10.3
            switchLock(alt.freqHz, RelockTrigger.AUTOMATIC)
        }
    }

    /**
     * D-9: the tone-jump deadlock. The operator moves frequency and the lock never follows,
     * because the two guards that should notice cannot: `poorElements` counts elements and
     * an abandoned frequency produces none, while FR-2.10.2 suspends the check entirely
     * under `lowSignal`, which holds throughout. Measured: a 612 → 1150 Hz jump yields
     * **2 characters** for a whole 75 s transmission, with the lock sitting at 612 Hz.
     *
     * So this deliberately bypasses both — it is the case they cannot see — and rests
     * instead on a conjunction the guards can trust: nothing has been decoded here for
     * [Config.stalledLockMs], **and** something elsewhere in the band is being keyed.
     *
     * The keying test is what makes it safe, and it is **not** the candidate test. FR-2.14
     * candidacy is 6 dB + 100 Hz + 2 s sustain with no keying check at all, and a healthy
     * 612 Hz decode offers spurious candidates at 1680, 840, 435 and 1017 Hz throughout —
     * any of which could otherwise take a working lock during a pause. [keyedNow] is the
     * discriminator, and silence supplies no keyed peak, which is what holds AC-9.
     *
     * Two conditions here were bought with a broken AC-9 rather than reasoned in advance,
     * and both matter:
     *
     * - **The current lock must itself be silent.** Without this the rule fired 0.6 s after
     *   the *same station* resumed from a 30 s pause, because the stall clock had long
     *   expired and the returning signal threw a peak past the distance test. A keyed
     *   frequency is not stranded, whatever the transcript says.
     * - **The alternative must be ≥ [Config.candidateMinDistHz] away, not
     *   [Config.relockMinDistHz].** The peak that stole that lock sat 40.4 Hz off — a
     *   keying sideband of the signal already being tracked, clearing the 40 Hz bar by
     *   0.4 Hz. FR-2.14's 100 Hz is the distance at which a peak is a *distinct signal*,
     *   which is precisely the claim this rule has to make before taking a lock away.
     *
     * The pin still wins (FR-2.10.5) and the hold-off still applies (FR-2.10.4), so a fresh
     * lock actually gets 15 s rather than 10 before this can consider it.
     *
     * **This spends the D-7 asymmetry deliberately** — a proven lock can now be taken away
     * during a legitimate pause if another station happens to be keying. That trade was
     * declined when D-7 was written and accepted by the owner on 2026-08-09, on the grounds
     * that a stranded session is the more common and more damaging failure.
     */
    private fun stalledLockCheck() {
        if (pinned) return                                           // FR-2.10.5
        if (nowMs() - lastCharMs < cfg.stalledLockMs) return
        if (nowMs() - lastLockChangeMs < cfg.relockHoldoffMs) return // FR-2.10.4
        // A frequency that is being keyed is not stranded, whatever the transcript says —
        // it may be mid-bootstrap, or its output gated. Only silence *here* means the
        // operator has gone, and this is what stops the rule confusing "the station paused
        // and came back" with "the station moved" (AC-9).
        if (keyedNow(toneHz)) {
            lastCharMs = nowMs()
            return
        }
        // The target must be an established **candidate**, not merely this sweep's best
        // peak. One sweep is not evidence enough to take a lock away: measured on
        // `hand-13-rough`, a transient at 731 Hz out-ranked the real 588 Hz signal on a
        // single sweep and won the lock, costing 18 characters.
        //
        // `candidates` is the existing FR-2.14 machinery for exactly this question —
        // 30 Hz matching with an EMA across sweeps, 2 s of sustain, 1 s eviction, ordered
        // by strength. Re-deriving it here with a private counter failed on peak jitter:
        // the readings wander ±45 Hz between sweeps, so a naive same-frequency test never
        // accumulates. Its 100 Hz minimum distance is also the right bar rather than a
        // tolerated side effect: it is the distance at which FR-2.14 is willing to call a
        // peak a separate signal, and separateness is this rule's entire premise.
        val alt = candidates.firstOrNull { allowedLockFreq(it) && keyedNow(it) } ?: return
        switchLock(alt, RelockTrigger.AUTOMATIC)
    }

    /**
     * The keying test with no ablation switch in front of it.
     *
     * [isKeyed] short-circuits to true when [Config.requireKeyingToLock] is off, which is
     * right for a *preference* between peaks. For the D-9 rule keying is the whole safety
     * argument, so turning it off would leave a bare 10 s timer able to move a lock — the
     * exact thing FR-2.10.1 exists to prevent.
     */
    private fun keyedNow(freqHz: Double): Boolean =
        keyingSpreadAt(freqHz) >= cfg.lockKeyingSpread

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
        unkeyedSweeps = 0
        charsSinceLock = 0
        lastCharMs = nowMs()
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
        plausibility.clear()
        fitQualityRaw = 1.0
        poorElements = 0
        outputSuppressed = false
        poorFitStreak = 0
        goodFitStreak = 0
        fitLogCount = 0
        fitLogIdx = 0
        needResync = true
        candidateList.clear()
        unkeyedSweeps = 0
        charsSinceLock = 0
        lastCharMs = nowMs()
        lockState = LockState.LOCKED
        lockedSinceMs = nowMs()
        lastLockChangeMs = nowMs()
        pinnedWpm?.let { pinWpm(it) } // a WPM pin survives re-locks (FR-9.5)
    }

    private fun resetSignalPath() {
        envRing.clear()
        thresholdNorm = 0f
        envelopeNorm = 0f
        pendingRuns.clear()
        keyState = false
        // A new lock starts its own re-fit history (D-2): marks from a wrong tone must not
        // seed a re-bootstrap on the right one.
        refitMarks.clear()
        marksSinceRefit = 0
        rebootstraps = 0
        // Anchor the run and frame grids to the processing position (the lock instant when a
        // sweep locks), not the write head, so the envelope framing is chunk-invariant (N-2).
        runStartSample = curSample
        nextFrameStart = curSample
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
        lockState = if (lowSignal || (obsFit.ready && fitQualityRaw < 0.35)) LockState.DEGRADED else LockState.LOCKED
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

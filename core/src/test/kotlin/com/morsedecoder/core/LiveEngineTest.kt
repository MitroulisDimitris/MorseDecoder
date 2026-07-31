package com.morsedecoder.core

import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.live.LiveDecoderEngine.Event
import com.morsedecoder.core.live.LiveDecoderEngine.LockState
import com.morsedecoder.core.dsp.KeyingSpread
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.model.KalmanUnit
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Milestone-3 live path: lock acquisition, re-lock guards, low-signal state.
 * AC-8, AC-9, AC-10 live here — all pure JUnit against synthetic audio.
 */
class LiveEngineTest {

    private fun feedInChunks(engine: LiveDecoderEngine, samples: FloatArray, chunk: Int = 1024) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(chunk, samples.size - i)
            val slice = FloatArray(n)
            System.arraycopy(samples, i, slice, 0, n)
            engine.feed(slice, n)
            i += n
        }
    }

    private fun text(engine: LiveDecoderEngine): String = buildString {
        for ((i, c) in engine.transcript.withIndex()) {
            if (i > 0 && c.isWordStart) append(' ')
            append(c.token)
        }
    }

    private fun relocks(engine: LiveDecoderEngine) = engine.events.filterIsInstance<Event.Relock>()

    @Test
    fun `live clean stream locks, converges (AC-7) and decodes with low CER`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 612.0, seed = 41L)
        )
        val engine = LiveDecoderEngine()
        feedInChunks(engine, gen.samples)
        engine.finish()

        assertTrue(engine.lockState == LockState.LOCKED || engine.lockState == LockState.DEGRADED)
        assertTrue(abs(engine.toneHz - 612.0) < 10.0) { "locked at ${engine.toneHz}" }

        val decoded = text(engine)
        // The first character or two are legitimately lost to acquisition + FR-4.2.
        val cer = TextMetrics.cer(gen.normalizedText, decoded)
        assertTrue(cer < 0.06) { "live CER = $cer; decoded '$decoded'" }

        // AC-7: unit within 10 % once 20 elements have been observed.
        var elements = 0
        for (c in engine.transcript) {
            elements += c.elements.size
            if (elements >= 20) {
                assertTrue(abs(c.unitMsGlobal - 60.0) / 60.0 < 0.10) {
                    "AC-7 live unit after $elements elements = ${c.unitMsGlobal}"
                }
                break
            }
        }
        assertTrue(elements >= 20)
    }

    @Test
    fun `AC-9 a good lock survives 30 s of silence with zero automatic re-locks`() {
        val genA = SyntheticMorseGenerator.generate(
            Config(text = "CQ CQ CQ DE SV1ABC SV1ABC K", wpm = 20.0, toneHz = 612.0, seed = 43L)
        )
        val genB = SyntheticMorseGenerator.generate(
            Config(text = "R R TNX 73", wpm = 20.0, toneHz = 612.0, seed = 44L)
        )
        val silence = FloatArray(30 * 16_000)

        val engine = LiveDecoderEngine()
        feedInChunks(engine, genA.samples)
        val charsBeforeSilence = engine.transcript.size
        assertTrue(charsBeforeSilence > 5) { "expected a working lock before the pause" }

        feedInChunks(engine, silence)
        assertEquals(0, relocks(engine).size) { "silence must never re-lock (D-7)" }
        assertTrue(engine.lockState == LockState.LOCKED || engine.lockState == LockState.DEGRADED) {
            "lock dropped during silence: ${engine.lockState}"
        }

        feedInChunks(engine, genB.samples)
        engine.finish()
        assertEquals(0, relocks(engine).size)
        assertTrue(engine.transcript.size > charsBeforeSilence) {
            "decoding did not resume after the pause"
        }
    }

    @Test
    fun `AC-8 low-signal state fires instead of emitting garbage`() {
        for (snr in listOf(-12.0, -6.0, 0.0)) {
            val gen = SyntheticMorseGenerator.generate(
                Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 612.0, snrDb = snr, seed = 47L)
            )
            val engine = LiveDecoderEngine()
            feedInChunks(engine, gen.samples)
            engine.finish()
            val decoded = text(engine)
            if (decoded.isNotEmpty()) {
                val cer = TextMetrics.cer(gen.normalizedText, decoded)
                assertTrue(cer < 0.25) { "AC-8 at $snr dB: emitted CER $cer — should have gone low-signal" }
            }
            // else: low-signal held output back entirely — the honest response (P-4)
        }
    }

    /**
     * The wrong-lock scenario the guards exist for: a lock sitting between two real
     * signals hears both interleaved — elements flow, the clusters collapse, and
     * `modelFitQuality` is the one number that catches it (D-8).
     */
    private fun wrongLockBetweenTwoSignals(): FloatArray {
        val genA = SyntheticMorseGenerator.generate(
            Config(
                text = AcceptanceTest.LONG_TEXT + " " + AcceptanceTest.LONG_TEXT,
                wpm = 20.0, toneHz = 612.0, amplitude = 0.5, snrDb = 14.0, seed = 53L,
            )
        )
        val genB = SyntheticMorseGenerator.generate(
            Config(
                text = AcceptanceTest.LONG_TEXT + " " + AcceptanceTest.LONG_TEXT,
                wpm = 26.0, toneHz = 800.0, amplitude = 0.4, seed = 54L,
            )
        )
        val n = maxOf(genA.samples.size, genB.samples.size)
        val mixed = FloatArray(n)
        for (i in 0 until n) {
            var v = 0f
            if (i < genA.samples.size) v += genA.samples[i]
            if (i < genB.samples.size) v += genB.samples[i]
            mixed[i] = v.coerceIn(-1f, 1f)
        }
        return mixed
    }

    @Test
    fun `AC-10 wrong lock degrades fit and auto re-lock recovers`() {
        val engine = LiveDecoderEngine()
        engine.forceLock(706.0) // deliberately between the two signals
        feedInChunks(engine, wrongLockBetweenTwoSignals())
        engine.finish()

        val auto = relocks(engine).filter { it.trigger == LiveDecoderEngine.RelockTrigger.AUTOMATIC }
        assertTrue(auto.isNotEmpty()) {
            "no automatic re-lock; state=${engine.lockState} tone=${engine.toneHz} " +
                "fit=${engine.fitQuality} snr=${engine.snrDb} lowSignal=${engine.lowSignal} " +
                "chars=${engine.transcript.size}"
        }
        assertTrue(abs(engine.toneHz - 612.0) < 15.0 || abs(engine.toneHz - 800.0) < 15.0) {
            "recovered to ${engine.toneHz}, expected one of the real signals"
        }
        // Decoding after recovery is real decoding again.
        val afterRelock = engine.transcript.filter { it.tStartMs > auto.first().atMs }
        assertTrue(afterRelock.size > 10) { "only ${afterRelock.size} chars after re-lock" }
        val recoveredFit = engine.fitQuality
        assertTrue(recoveredFit != null && recoveredFit > 0.45) { "fit did not recover: $recoveredFit" }
    }

    @Test
    fun `pin suppresses automatic re-lock (FR-2-6)`() {
        val engine = LiveDecoderEngine()
        engine.forceLock(706.0)
        engine.pinned = true
        feedInChunks(engine, wrongLockBetweenTwoSignals())
        engine.finish()
        assertEquals(0, relocks(engine).count { it.trigger == LiveDecoderEngine.RelockTrigger.AUTOMATIC })
    }

    @Test
    fun `second signal appears as candidate and switching decodes it (FR-2-14 FR-2-16)`() {
        val genA = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 18.0, toneHz = 612.0, amplitude = 0.5, seed = 59L)
        )
        val genB = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 22.0, toneHz = 890.0, amplitude = 0.22, seed = 61L)
        )
        val n = maxOf(genA.samples.size, genB.samples.size)
        val mixed = FloatArray(n)
        for (i in 0 until n) {
            var v = 0f
            if (i < genA.samples.size) v += genA.samples[i]
            if (i < genB.samples.size) v += genB.samples[i]
            mixed[i] = v.coerceIn(-1f, 1f)
        }

        val engine = LiveDecoderEngine()
        // Feed enough to lock the stronger signal and let the candidate qualify (>= 2 s sustain).
        feedInChunks(engine, mixed.copyOfRange(0, 10 * 16_000))
        assertTrue(abs(engine.toneHz - 612.0) < 15.0) { "locked ${engine.toneHz}, expected the stronger signal" }
        assertTrue(engine.candidates.any { abs(it - 890.0) < 20.0 }) {
            "no candidate near 890 Hz: ${engine.candidates}"
        }

        val before = engine.transcript.size
        engine.selectCandidate(890.0)
        feedInChunks(engine, mixed.copyOfRange(10 * 16_000, n))
        engine.finish()

        val marks = relocks(engine)
        assertEquals(1, marks.size)
        assertEquals(LiveDecoderEngine.RelockTrigger.CANDIDATE, marks[0].trigger)
        assertTrue(abs(engine.toneHz - 890.0) < 15.0) { "post-switch tone ${engine.toneHz}" }
        assertTrue(engine.transcript.size > before + 5) { "no decode from the selected signal" }
    }

    @Test
    fun `mains hum is never auto-locked without an override (FR-2-5)`() {
        // Unkeyed 600 Hz hum: a mains harmonic with no keying sidebands. (A keyed
        // signal AT 600 Hz legitimately locks via its sidebands and tracks in —
        // the veto governs the bare harmonic, which is what hum looks like.)
        val hum = FloatArray(8 * 16_000) { (0.4 * sin(2.0 * PI * 600.0 * it / 16_000)).toFloat() }

        val vetoed = LiveDecoderEngine()
        feedInChunks(vetoed, hum)
        assertEquals(LockState.SEARCHING, vetoed.lockState) {
            "locked mains hum at ${vetoed.toneHz} without override"
        }

        // The override lifts the *frequency* veto, which is what this test is about.
        // D-13's keying screen is a separate gate and would reject bare hum on its own
        // merits, so it is switched off here to keep the assertion aimed at FR-2.5.
        val overridden = LiveDecoderEngine(
            cfg = LiveDecoderEngine.Config(mainsOverride = true, requireKeyingToLock = false)
        )
        feedInChunks(overridden, hum)
        assertTrue(
            overridden.lockState == LockState.LOCKED || overridden.lockState == LockState.DEGRADED
        ) { "override should allow the 600 Hz lock" }
        assertTrue(abs(overridden.toneHz - 600.0) < 10.0)
    }

    /**
     * D-13: the loudest peak in the band is not necessarily Morse. A strong unkeyed
     * carrier sitting beside a weaker keyed signal must not win the lock — locking the
     * carrier is a dead end, because no elements flow and the element-counted re-lock
     * guards can therefore never fire to correct it.
     */
    @Test
    fun `a loud unkeyed carrier never beats a weaker keyed signal (D-13)`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 18.0, toneHz = 1462.0, amplitude = 0.25, seed = 83L)
        )
        // Twice the amplitude of the Morse, and inside the band — but never keyed.
        val mixed = FloatArray(gen.samples.size) {
            (gen.samples[it] + 0.5 * sin(2.0 * PI * 1123.0 * it / 16_000)).toFloat().coerceIn(-1f, 1f)
        }

        val engine = LiveDecoderEngine()
        feedInChunks(engine, mixed)
        engine.finish()

        assertTrue(abs(engine.toneHz - 1462.0) < 20.0) {
            "locked ${engine.toneHz}, expected the keyed 1462 Hz signal not the louder carrier"
        }
        assertTrue(engine.transcript.size > 20) { "only ${engine.transcript.size} chars decoded" }

        // Guard against a vacuous test: the carrier really is the stronger peak, so
        // power-only selection genuinely fails here.
        val powerOnly = LiveDecoderEngine(
            cfg = LiveDecoderEngine.Config(requireKeyingToLock = false)
        )
        feedInChunks(powerOnly, mixed)
        powerOnly.finish()
        assertTrue(abs(powerOnly.toneHz - 1123.0) < 20.0) {
            "fixture does not exercise D-13: power-only selection locked ${powerOnly.toneHz}"
        }
    }

    /**
     * D-13 dead-end recovery: a lock that has never decoded anything must be abandoned so
     * the real signal can be found. Nothing else can correct it — no elements flow, so the
     * element-counted re-lock guards never advance. This is the shape of the field report:
     * locked on nothing while the Morse sat elsewhere in the band.
     */
    @Test
    fun `a lock that never decodes is abandoned and the real signal found (D-13)`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 18.0, toneHz = 1462.0, snrDb = 14.0, seed = 103L)
        )
        val engine = LiveDecoderEngine()
        // A manual lock on a frequency carrying nothing: the dead end, by construction.
        feedInChunks(engine, gen.samples.copyOfRange(0, 16_000))
        engine.forceLock(700.0)
        feedInChunks(engine, gen.samples.copyOfRange(16_000, gen.samples.size))
        engine.finish()

        assertTrue(abs(engine.toneHz - 1462.0) < 20.0) {
            "still on ${engine.toneHz}; the dead lock was never abandoned"
        }
        assertTrue(engine.transcript.size > 20) { "only ${engine.transcript.size} chars after recovery" }

        // Guard against a vacuous test: with recovery disabled the dead lock persists.
        val stuck = LiveDecoderEngine(
            cfg = LiveDecoderEngine.Config(
                unprovenLockMs = Double.MAX_VALUE,
                unkeyedLockSweeps = Int.MAX_VALUE,
            )
        )
        feedInChunks(stuck, gen.samples.copyOfRange(0, 16_000))
        stuck.forceLock(700.0)
        feedInChunks(stuck, gen.samples.copyOfRange(16_000, gen.samples.size))
        stuck.finish()
        assertTrue(abs(stuck.toneHz - 700.0) < 40.0) {
            "fixture does not exercise recovery: without it the lock moved to ${stuck.toneHz}"
        }
    }

    /** The keying score is the whole basis of D-13, so pin its two extremes. */
    @Test
    fun `keying spread separates a keyed tone from a bare carrier (D-13)`() {
        val keyed = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 1462.0, seed = 89L)
        )
        val engine = LiveDecoderEngine()
        feedInChunks(engine, keyed.samples)
        assertTrue(engine.keyingSpreadAt(1462.0) >= KeyingSpread.LOCK_MIN_SPREAD) {
            "keyed tone scored ${engine.keyingSpreadAt(1462.0)}, below the lock bar"
        }

        val carrier = FloatArray(8 * 16_000) { (0.4 * sin(2.0 * PI * 1123.0 * it / 16_000)).toFloat() }
        val steady = LiveDecoderEngine()
        feedInChunks(steady, carrier)
        assertTrue(steady.keyingSpreadAt(1123.0) < KeyingSpread.LOCK_MIN_SPREAD) {
            "bare carrier scored ${steady.keyingSpreadAt(1123.0)}, above the lock bar"
        }
    }

    /** The band ceiling is a live setting; a lock outside it must not silently persist. */
    @Test
    fun `lowering the band ceiling below the lock resumes searching`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 1462.0, seed = 97L)
        )
        val engine = LiveDecoderEngine()
        feedInChunks(engine, gen.samples.copyOfRange(0, 6 * 16_000))
        assertTrue(abs(engine.toneHz - 1462.0) < 20.0) { "did not lock 1462 Hz: ${engine.toneHz}" }

        engine.bandMaxHz = 1200.0
        feedInChunks(engine, gen.samples.copyOfRange(6 * 16_000, 8 * 16_000))
        // The lock must not persist outside the band. Re-acquiring somewhere legal is
        // fine — what would be wrong is sitting on 1462 Hz that the sweep no longer sees.
        assertTrue(engine.toneHz <= 1200.0) {
            "kept a ${engine.toneHz} Hz lock after the ceiling dropped to 1200"
        }
    }

    /**
     * The search range is user-draggable, so its clamps are load-bearing: a range dragged
     * shut would leave the decoder searching nothing, with no way back except reset.
     */
    @Test
    fun `search range keeps a 100 Hz minimum span in both directions`() {
        val engine = LiveDecoderEngine()
        engine.bandMinHz = 1990.0                       // driven up against the ceiling
        assertTrue(engine.bandMaxHz - engine.bandMinHz >= 100.0) {
            "span collapsed to ${engine.bandMaxHz - engine.bandMinHz} Hz"
        }

        val engine2 = LiveDecoderEngine()
        engine2.bandMaxHz = 310.0                       // driven down against the floor
        assertTrue(engine2.bandMaxHz - engine2.bandMinHz >= 100.0) {
            "span collapsed to ${engine2.bandMaxHz - engine2.bandMinHz} Hz"
        }
    }

    /** A narrowed range must not leave a chip offering the tone it just excluded. */
    @Test
    fun `candidates outside the search range are not offered`() {
        val genA = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 18.0, toneHz = 612.0, amplitude = 0.5, seed = 107L)
        )
        val genB = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 22.0, toneHz = 890.0, amplitude = 0.22, seed = 109L)
        )
        val n = maxOf(genA.samples.size, genB.samples.size)
        val mixed = FloatArray(n) {
            var v = 0f
            if (it < genA.samples.size) v += genA.samples[it]
            if (it < genB.samples.size) v += genB.samples[it]
            v.coerceIn(-1f, 1f)
        }

        val engine = LiveDecoderEngine()
        feedInChunks(engine, mixed.copyOfRange(0, 10 * 16_000))
        assertTrue(engine.candidates.any { abs(it - 890.0) < 20.0 }) {
            "fixture did not produce the 890 Hz candidate: ${engine.candidates}"
        }

        engine.bandMaxHz = 800.0
        assertTrue(engine.candidates.none { it > 800.0 }) {
            "still offering ${engine.candidates} after the range excluded them"
        }
    }

    /**
     * A tone above the old 1200 Hz ceiling — the field report that prompted F1, where a
     * phone recording carried its CW note at 1882 Hz.
     *
     * The fixture carries broadband noise on purpose. A *noiseless* keyed tone is still
     * decodable from below the ceiling, because the raised-cosine key transitions put
     * skirt energy across the whole spectrum and that skirt carries the full timing
     * envelope. Real recordings do not behave that way: their in-band content is room
     * noise uncorrelated with the keying, which is why the real clip failed outright.
     * Noise here is what makes the fixture resemble the reported failure.
     */
    @Test
    fun `a tone above the old 1200 Hz ceiling now decodes (F1)`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 18.0, toneHz = 1882.0, snrDb = 12.0, seed = 101L)
        )
        val engine = LiveDecoderEngine()
        feedInChunks(engine, gen.samples)
        engine.finish()

        assertTrue(abs(engine.toneHz - 1882.0) < 20.0) { "locked ${engine.toneHz}" }
        val cer = TextMetrics.cer(gen.normalizedText, text(engine))
        assertTrue(cer < 0.12) { "CER $cer at 1882 Hz; decoded '${text(engine)}'" }

        // Guard against a vacuous test: under the old ceiling this is largely undecodable.
        val old = LiveDecoderEngine(cfg = LiveDecoderEngine.Config(bandMaxHz = 1200.0))
        feedInChunks(old, gen.samples)
        old.finish()
        assertTrue(old.transcript.size < engine.transcript.size / 4) {
            "old band decoded ${old.transcript.size} of ${engine.transcript.size} chars — " +
                "fixture does not exercise F1"
        }
    }

    /**
     * Ambient, non-Morse sound: a tone whose envelope switches on and off at random
     * durations, so the mark distribution is broad and unimodal — no dot/dash structure.
     * This is what a mic pointed at a noisy room produces. `modelFitQuality` stays low,
     * and the output gate (P-4/FR-5.8) must hold characters back rather than emit garbage.
     */
    private fun ambientTone(seed: Long, seconds: Int = 25, envStep: Double = 0.03): FloatArray {
        val rng = java.util.Random(seed)
        val sr = 16_000
        val out = FloatArray(seconds * sr)
        // A dominant tone (so it locks) under a smooth random-walk envelope that crosses
        // the keying thresholds at random — the mark durations never form dot/dash
        // clusters. This is how a real noisy room looks, unlike clean binary keying.
        // A larger [envStep] fluctuates faster, producing the very short bogus marks that
        // drive the unit estimate toward implausible speeds.
        var env = 0.25
        for (i in out.indices) {
            env += (rng.nextDouble() - 0.5) * envStep
            env = env.coerceIn(0.0, 0.5)
            out[i] = (env * sin(2.0 * PI * 685.0 * i / sr)).toFloat()
        }
        return out
    }

    @Test
    fun `ambient non-Morse sound is gated, not decoded as garbage (P-4 FR-5-8)`() {
        val ambient = ambientTone(seed = 71L)

        val gated = LiveDecoderEngine()
        feedInChunks(gated, ambient)
        gated.finish()

        // Same input with the gate switched off — the raw path this bug reported.
        val raw = LiveDecoderEngine(cfg = LiveDecoderEngine.Config(outputGateEnabled = false))
        feedInChunks(raw, ambient)
        raw.finish()

        // Guard against a vacuous test: the raw path must actually emit garbage here.
        assertTrue(raw.transcript.size > 10) {
            "fixture did not exercise the bug; raw emitted only ${raw.transcript.size} chars"
        }
        // The gate must engage on incoherent input and hold it back.
        assertTrue(gated.outputSuppressed) { "fit gate never engaged; fit=${gated.fitQuality}" }
        assertTrue(gated.transcript.size <= raw.transcript.size / 4 + 2) {
            "gate let through ${gated.transcript.size} of ${raw.transcript.size} raw chars"
        }
    }

    /**
     * Ambient noise used to drive the unit estimate down until the readout showed 100+
     * WPM — a speed no sender produces. The glitch gate scales with the unit, so a short
     * unit admits more noise as marks and shortens it further; the ceiling breaks that
     * loop. A clamped estimate is not a measurement, so `wpm` reports null, not 60.
     */
    @Test
    fun `speed estimate never exceeds the 60 WPM ceiling on ambient noise`() {
        var ceilingHit = false
        for (seed in longArrayOf(71L, 12L, 907L)) {
            val engine = LiveDecoderEngine()
            feedInChunks(engine, ambientTone(seed, envStep = 0.08))
            engine.finish()
            val reported = engine.wpm
            assertTrue(reported == null || reported <= KalmanUnit.MAX_WPM) {
                "seed $seed reported $reported WPM"
            }
            if (engine.speedImplausible) ceilingHit = true
        }
        // Guard against a vacuous test: this fixture must actually push past the ceiling,
        // which is what put 100+ WPM on the readout before the clamp existed.
        assertTrue(ceilingHit) { "fixture no longer drives the estimate past the ceiling" }
    }

    @Test
    fun `a real 20 WPM stream is unaffected by the speed ceiling`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 612.0, seed = 41L)
        )
        val engine = LiveDecoderEngine()
        feedInChunks(engine, gen.samples)
        engine.finish()
        val reported = engine.wpm
        assertTrue(reported != null && abs(reported - 20.0) < 2.0) { "reported $reported WPM" }
    }

    /** The `thr` control's travel is finite; the setter must not accept values past it. */
    @Test
    fun `threshold adjust clamps to its usable range`() {
        val engine = LiveDecoderEngine()
        engine.thresholdAdjustDb = -50.0
        assertEquals(engine.thresholdDbMin, engine.thresholdAdjustDb, 1e-9)
        engine.thresholdAdjustDb = 50.0
        assertEquals(engine.thresholdDbMax, engine.thresholdAdjustDb, 1e-9)
    }

    /**
     * The threshold rule is only readable against the bars if it is published on the same
     * 0..1 scale they are. Only the live threshold is drawn — a second rule for the
     * control's travel limit implied a second threshold and was removed.
     */
    @Test
    fun `threshold telemetry stays on the spectrum bar scale`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 18.0, toneHz = 700.0, seed = 3L)
        )
        val engine = LiveDecoderEngine()
        feedInChunks(engine, gen.samples)
        engine.finish()

        assertTrue(engine.thresholdNorm in 0f..1f) { "thresholdNorm ${engine.thresholdNorm}" }
        assertTrue(engine.thresholdNorm > 0f) { "no threshold telemetry was published" }
    }

    @Test
    fun `steady carrier produces no characters`() {
        val samples = FloatArray(10 * 16_000) { (0.4 * sin(2.0 * PI * 612.0 * it / 16_000)).toFloat() }
        val engine = LiveDecoderEngine()
        feedInChunks(engine, samples)
        engine.finish()
        assertEquals(0, engine.transcript.size)
    }
}

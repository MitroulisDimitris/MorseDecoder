package com.morsedecoder.core

import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.live.LiveDecoderEngine.Event
import com.morsedecoder.core.live.LiveDecoderEngine.LockState
import com.morsedecoder.core.metrics.TextMetrics
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
        assertTrue(engine.fitQuality > 0.45) { "fit did not recover: ${engine.fitQuality}" }
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

        val overridden = LiveDecoderEngine(cfg = LiveDecoderEngine.Config(mainsOverride = true))
        feedInChunks(overridden, hum)
        assertTrue(
            overridden.lockState == LockState.LOCKED || overridden.lockState == LockState.DEGRADED
        ) { "override should allow the 600 Hz lock" }
        assertTrue(abs(overridden.toneHz - 600.0) < 10.0)
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

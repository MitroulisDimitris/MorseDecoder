package com.morsedecoder.core

import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Properties that must hold for **every** input, rather than accuracy at chosen points.
 *
 * The acceptance criteria sample ten places in a large space. These cover the space
 * itself: whatever the audio, the decoder must be deterministic, must not depend on how
 * the caller happens to slice the stream, must never rewrite what it has already emitted,
 * and must not crash on input no sane microphone would produce. One invariant failing is
 * usually a whole class of scenarios failing at once, which is what makes them worth more
 * per test than another CER point.
 */
class InvariantTest {

    private val text = "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599"

    private fun signal(seed: Long = 31L) = SyntheticMorseGenerator.generate(
        Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = seed)
    )

    private fun decode(audio: FloatArray, chunk: Int): Pair<String, LiveDecoderEngine> {
        val engine = LiveDecoderEngine()
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            engine.feed(audio.copyOfRange(i, i + n), n)
            i += n
        }
        engine.finish()
        val out = buildString {
            for ((k, c) in engine.transcript.withIndex()) {
                if (k > 0 && c.isWordStart) append(' ')
                append(c.token)
            }
        }
        return out to engine
    }

    /** NFR-5. Nothing about the decoder may depend on wall-clock time or ambient state. */
    @Test
    fun `decoding is deterministic`() {
        val gen = signal()
        val (a, ea) = decode(gen.samples, 1024)
        val (b, eb) = decode(gen.samples, 1024)
        assertEquals(a, b) { "two runs over identical input disagreed" }
        assertEquals(ea.transcript.size, eb.transcript.size)
        assertEquals(ea.toneHz, eb.toneHz, 1e-9)
    }

    /**
     * The live path is fed by `AudioRecord`, whose buffer size is chosen by the device —
     * `MicCapture` asks for >= 250 ms and takes what it is given. So chunk size is a
     * property of the user's phone, and a decoder sensitive to it would work on one
     * handset and not another.
     *
     * Exact equality is not achievable and not required: sweeps fire on sample counts, so
     * a larger chunk lands several sweeps on the same ring contents. What must hold is
     * that the *result* barely moves. Measured spread across 160–8192 samples is 44–46
     * characters and 612.1–612.5 Hz on this fixture.
     */
    @Test
    fun `decoding barely depends on the caller's chunk size`() {
        val gen = signal()
        val reference = decode(gen.samples, 4096)
        val refCer = TextMetrics.cer(gen.normalizedText, reference.first)

        for (chunk in intArrayOf(160, 256, 512, 1024, 2048, 8192)) {
            val (textOut, engine) = decode(gen.samples, chunk)
            val cer = TextMetrics.cer(gen.normalizedText, textOut)
            assertTrue(abs(cer - refCer) < 0.06) {
                "chunk $chunk gave CER $cer vs reference $refCer"
            }
            assertTrue(abs(engine.transcript.size - reference.second.transcript.size) <= 4) {
                "chunk $chunk emitted ${engine.transcript.size} chars vs ${reference.second.transcript.size}"
            }
            assertTrue(abs(engine.toneHz - 612.0) < 10.0) {
                "chunk $chunk locked ${engine.toneHz}"
            }
        }
    }

    /**
     * P-2, mechanically. The transcript is append-only: whatever has been emitted stays
     * emitted, unchanged, in the same order. Nothing in the decoder may revise history —
     * not on re-lock, not on an error prosign, not when the model improves.
     */
    @Test
    fun `the transcript is append-only as audio arrives`() {
        val gen = signal()
        val engine = LiveDecoderEngine()
        var previous = emptyList<String>()
        var i = 0
        while (i < gen.samples.size) {
            val n = minOf(2048, gen.samples.size - i)
            engine.feed(gen.samples.copyOfRange(i, i + n), n)
            i += n
            val now = engine.transcript.map { it.token }
            assertTrue(now.size >= previous.size) {
                "transcript shrank from ${previous.size} to ${now.size}"
            }
            assertEquals(previous, now.take(previous.size)) {
                "an already-emitted character changed"
            }
            previous = now
        }
    }

    /**
     * A microphone can deliver anything: a driver glitch, an unplugged interface, a
     * denormal-flushing DSP, an overloaded preamp. None of it may crash the decode thread,
     * hang it, or produce output — this runs in a foreground service where a throw is a
     * dead session with the screen off.
     */
    @Test
    fun `adversarial input neither crashes nor decodes`() {
        val cases = mapOf(
            "all-NaN" to FloatArray(16_000) { Float.NaN },
            "all-positive-infinity" to FloatArray(16_000) { Float.POSITIVE_INFINITY },
            "all-negative-infinity" to FloatArray(16_000) { Float.NEGATIVE_INFINITY },
            "dc-offset" to FloatArray(16_000 * 3) { 0.9f },
            "full-scale-square" to FloatArray(16_000 * 3) { if ((it / 80) % 2 == 0) 1f else -1f },
            "denormals" to FloatArray(16_000 * 3) { 1e-38f },
            "alternating-extremes" to FloatArray(16_000 * 3) { if (it % 2 == 0) 1f else -1f },
            "single-sample" to FloatArray(1) { 0.5f },
            "empty" to FloatArray(0),
        )
        for ((name, audio) in cases) {
            val engine = LiveDecoderEngine()
            var i = 0
            while (i < audio.size) {
                val n = minOf(4096, audio.size - i)
                engine.feed(audio.copyOfRange(i, i + n), n)
                i += n
            }
            engine.finish()
            assertEquals(0, engine.transcript.size) { "$name decoded ${engine.transcript.size} chars" }
            assertTrue(engine.toneHz.isFinite()) { "$name left toneHz = ${engine.toneHz}" }
            assertTrue(engine.snrDb.isFinite()) { "$name left snrDb = ${engine.snrDb}" }
            engine.fitQuality?.let { assertTrue(it.isFinite()) { "$name left fitQuality = $it" } }
            engine.wpm?.let { assertTrue(it.isFinite() && it > 0) { "$name reported wpm $it" } }
        }
    }

    /** A partial chunk at end of stream must not lose or duplicate the tail. */
    @Test
    fun `ragged final chunk does not disturb the decode`() {
        val gen = signal()
        val whole = decode(gen.samples, 4096)
        // Trim to a deliberately awkward length so the last chunk is a few samples.
        val ragged = gen.samples.copyOfRange(0, gen.samples.size - 4093)
        val (out, _) = decode(ragged, 4096)
        val cer = TextMetrics.cer(gen.normalizedText, out)
        assertTrue(cer < TextMetrics.cer(gen.normalizedText, whole.first) + 0.06) {
            "trimming the tail changed CER to $cer"
        }
    }

    /** Feeding the same audio twice must roughly double the output, not stall or reset. */
    @Test
    fun `a long session keeps decoding rather than degrading`() {
        val gen = signal()
        val engine = LiveDecoderEngine()
        val counts = ArrayList<Int>()
        repeat(4) {
            var i = 0
            while (i < gen.samples.size) {
                val n = minOf(4096, gen.samples.size - i)
                engine.feed(gen.samples.copyOfRange(i, i + n), n)
                i += n
            }
            counts.add(engine.transcript.size)
        }
        engine.finish()
        for (k in 1 until counts.size) {
            assertTrue(counts[k] - counts[k - 1] > 20) {
                "pass ${k + 1} added only ${counts[k] - counts[k - 1]} characters: $counts"
            }
        }
    }
}

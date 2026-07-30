package com.morsedecoder.core

import com.morsedecoder.core.export.PlainTextTranscript
import com.morsedecoder.core.export.PlainTextTranscript.Item
import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Milestone-4 engine support: spectrum snapshot, WPM pin, plain-text vocabulary. */
class UiSupportTest {

    private fun feed(engine: LiveDecoderEngine, samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(2048, samples.size - i)
            engine.feed(samples.copyOfRange(i, i + n), n)
            i += n
        }
    }

    @Test
    fun `spectrum snapshot shows the locked peak in the right bin (FR-9-4)`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = "CQ CQ DE SV1ABC K", wpm = 20.0, toneHz = 612.0, seed = 71L)
        )
        val engine = LiveDecoderEngine()
        feed(engine, gen.samples)
        val spectrum = engine.spectrum
        assertEquals(engine.spectrumBins, spectrum.size)
        // Derived from the engine's band rather than a hardcoded 300-1200: the ceiling
        // is configurable now (D-13), and the strip's mapping follows it.
        val span = engine.bandMaxHz - engine.bandMinHz
        val expected = ((612.0 - engine.bandMinHz) / span * engine.spectrumBins).toInt()
        // Asserted as "the tone's bin is among the tallest" rather than "argmax lands
        // here": key-transition skirt energy saturates several bins on a noiseless tone,
        // and a plain argmax then reports whichever saturated bin comes first. Being one
        // of the tallest is the property the strip actually needs — it is what puts the
        // lock marker over a tall bar.
        val peak = spectrum.max()
        assertTrue(spectrum[expected] >= peak - 0.05f) {
            "bin $expected holds ${spectrum[expected]}, tallest is $peak"
        }
    }

    @Test
    fun `pinned wpm freezes the unit against contrary evidence (FR-9-5)`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 612.0, seed = 73L)
        )
        val engine = LiveDecoderEngine()
        engine.pinWpm(15.0)
        feed(engine, gen.samples)
        engine.finish()
        assertEquals(15.0, engine.wpm!!, 1e-9) { "pin did not hold" }
        assertTrue(engine.transcript.isNotEmpty()) // still decoding, just with a fixed unit
    }

    @Test
    fun `pinning the correct wpm decodes cleanly without bootstrap`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = "CQ CQ DE SV1ABC K", wpm = 20.0, toneHz = 612.0, seed = 79L)
        )
        val engine = LiveDecoderEngine()
        engine.pinWpm(20.0)
        feed(engine, gen.samples)
        engine.finish()
        assertEquals(20.0, engine.wpm!!, 1e-9)
        assertTrue(engine.transcript.size > 10)
    }

    @Test
    fun `plain-text vocabulary renders per FR-9-10`() {
        val items = listOf(
            Item.Char("R", false), Item.Char("R", false),
            Item.Char("Q", true), Item.Char("T", false), Item.Char("H", false),
            Item.Char("<HH>", false),
            Item.Char("Q", false), Item.Char("T", false), Item.Char("H", false),
            Item.Char("<?>", true),
            Item.Relock,
            Item.Char("7", false), Item.Char("3", false),
            Item.Char("<SK>", true),
        )
        assertEquals("RR QTH [HH] QTH <?> | 73 SK", PlainTextTranscript.format(items))
    }
}

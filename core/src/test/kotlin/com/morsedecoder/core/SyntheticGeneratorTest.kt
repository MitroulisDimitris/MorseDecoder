package com.morsedecoder.core

import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.ElementKind
import com.morsedecoder.core.synth.SyntheticMorseGenerator.SpeedDrift
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyntheticGeneratorTest {

    @Test
    fun `deterministic - same config yields identical samples (NFR-5)`() {
        val config = Config(
            text = "CQ CQ DE SV1ABC K",
            wpm = 18.0,
            snrDb = 5.0,
            jitterFraction = 0.15,
            charBiasFraction = 0.05,
            drift = SpeedDrift.SineWobble(0.15, 0.05),
            impulsesPerSecond = 0.5,
            fadingDepth = 0.3,
            seed = 42L,
        )
        val a = SyntheticMorseGenerator.generate(config)
        val b = SyntheticMorseGenerator.generate(config)
        assertArrayEquals(a.samples, b.samples)
        assertEquals(a.elements, b.elements)
    }

    @Test
    fun `timing impairments do not disturb the clean timeline reference`() {
        // Same seed, noise on vs off: the element timeline must be identical, so that
        // impairment comparisons (AC-5 style) vary exactly one thing at a time.
        val clean = SyntheticMorseGenerator.generate(Config(text = "PARIS", seed = 7L))
        val noisy = SyntheticMorseGenerator.generate(Config(text = "PARIS", seed = 7L, snrDb = 0.0))
        assertEquals(clean.elements, noisy.elements)
    }

    @Test
    fun `machine timing is exact at 20 wpm`() {
        val gen = SyntheticMorseGenerator.generate(Config(text = "PARIS", wpm = 20.0))
        val dots = gen.elements.filter { it.kind == ElementKind.MARK_DOT }
        val dashes = gen.elements.filter { it.kind == ElementKind.MARK_DASH }
        assertTrue(dots.isNotEmpty() && dashes.isNotEmpty())
        dots.forEach { assertEquals(60.0, it.durationMs, 1e-9) }
        dashes.forEach { assertEquals(180.0, it.durationMs, 1e-9) }

        // PARIS = 50 units including the trailing word gap; standalone word = 43 units.
        val total = gen.elements.sumOf { it.durationMs }
        assertEquals(43 * 60.0, total, 1e-6)
    }

    @Test
    fun `linear ramp drifts the wpm track (AC-6 harness)`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(
                text = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG " +
                    "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",
                wpm = 15.0,
                drift = SpeedDrift.LinearRamp(endWpm = 25.0, durationS = 60.0),
            )
        )
        val first = gen.wpmTrack.first().second
        val last = gen.wpmTrack.last().second
        assertEquals(15.0, first, 0.01)
        assertTrue(last > first + 2.0) { "expected ramp, got $first -> $last" }
        // monotone non-decreasing under a pure ramp
        gen.wpmTrack.zipWithNext().forEach { (a, b) ->
            assertTrue(b.second >= a.second - 1e-9)
        }
    }

    @Test
    fun `farnsworth stretches spacing but not elements`() {
        val std = SyntheticMorseGenerator.generate(Config(text = "AB AB", wpm = 20.0))
        val fw = SyntheticMorseGenerator.generate(Config(text = "AB AB", wpm = 20.0, farnsworthSpacingWpm = 10.0))
        val stdDots = std.elements.filter { it.kind == ElementKind.MARK_DOT }.map { it.durationMs }
        val fwDots = fw.elements.filter { it.kind == ElementKind.MARK_DOT }.map { it.durationMs }
        assertEquals(stdDots, fwDots) // element speed unchanged
        val stdWordGap = std.elements.first { it.kind == ElementKind.GAP_WORD }.durationMs
        val fwWordGap = fw.elements.first { it.kind == ElementKind.GAP_WORD }.durationMs
        assertEquals(2.0, fwWordGap / stdWordGap, 1e-9) // spacing at half speed = twice as long
    }

    @Test
    fun `normalization uppercases and drops unmapped characters`() {
        assertEquals("CQ DE SV1ABC", SyntheticMorseGenerator.normalize("cq   de\tsv1abc"))
        assertEquals("AB", SyntheticMorseGenerator.normalize("a~b")) // ~ has no Morse mapping
    }
}

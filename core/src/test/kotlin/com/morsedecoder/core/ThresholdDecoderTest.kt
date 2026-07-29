package com.morsedecoder.core

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.ThresholdDecoder
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Milestone-1 exit: the baseline decoder against machine-sent synthetic signal,
 * 10–25 WPM (§7.1). The robust decoder of milestone 2 inherits this rig and adds
 * the hand-sent and drifting cases.
 */
class ThresholdDecoderTest {

    private val decoder = ThresholdDecoder()

    private fun decode(config: Config): Pair<String, ThresholdDecoder.Result> {
        val gen = SyntheticMorseGenerator.generate(config)
        val result = decoder.decode(FloatArrayPcmSource(gen.samples, gen.sampleRate))
        return Pair(gen.normalizedText, result)
    }

    @Test
    fun `clean machine-sent 20 wpm decodes exactly`() {
        val (ref, result) = decode(Config(text = "CQ CQ DE SV1ABC K", wpm = 20.0, toneHz = 612.0))
        assertEquals(ref, result.text)
    }

    @Test
    fun `clean machine-sent 10 wpm decodes exactly`() {
        val (ref, result) = decode(Config(text = "CQ TEST DE W1AW", wpm = 10.0, toneHz = 700.0))
        assertEquals(ref, result.text)
    }

    @Test
    fun `clean machine-sent 25 wpm decodes exactly`() {
        val (ref, result) = decode(Config(text = "THE QUICK BROWN FOX 0123456789", wpm = 25.0, toneHz = 500.0))
        assertEquals(ref, result.text)
    }

    @Test
    fun `machine-sent at +10 dB SNR stays under 2 percent CER (AC-1 baseline)`() {
        val (ref, result) = decode(
            Config(
                text = "CQ CQ CQ DE SV1ABC SV1ABC PSE K THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 599 73",
                wpm = 20.0,
                toneHz = 612.0,
                snrDb = 10.0,
                seed = 3L,
            )
        )
        val cer = TextMetrics.cer(ref, result.text)
        assertTrue(cer < 0.02) { "CER $cer too high; decoded: '${result.text}'" }
    }

    @Test
    fun `tone estimate lands within 10 Hz`() {
        val (_, result) = decode(Config(text = "CQ CQ DE SV1ABC K", wpm = 20.0, toneHz = 612.0, snrDb = 10.0))
        assertTrue(abs(result.toneHz - 612.0) < 10.0) { "estimated ${result.toneHz} Hz" }
    }

    @Test
    fun `unit estimate lands within 10 percent at 20 wpm`() {
        val (_, result) = decode(Config(text = "CQ CQ DE SV1ABC K", wpm = 20.0, toneHz = 612.0))
        assertTrue(abs(result.unitMs - 60.0) / 60.0 < 0.10) { "unit ${result.unitMs} ms" }
    }

    @Test
    fun `silence produces empty output, not garbage (P-4 baseline)`() {
        val silence = FloatArray(16_000 * 2)
        val result = decoder.decode(FloatArrayPcmSource(silence, 16_000))
        assertEquals("", result.text)
    }

    @Test
    fun `noise only produces empty output, not garbage (P-4 baseline)`() {
        val rng = java.util.Random(11L)
        val noise = FloatArray(16_000 * 3) { (0.1 * rng.nextGaussian()).toFloat() }
        val result = decoder.decode(FloatArrayPcmSource(noise, 16_000))
        assertEquals("", result.text) { "noise decoded to: '${result.text}'" }
    }
}

package com.morsedecoder.core

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln

class RobustDecoderTest {

    private val decoder = MorseDecoder()

    private fun decode(config: Config): Pair<String, MorseDecoder.FileResult> {
        val gen = SyntheticMorseGenerator.generate(config)
        return Pair(gen.normalizedText, decoder.decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate)))
    }

    @Test
    fun `clean machine-sent decodes exactly`() {
        val (ref, result) = decode(Config(text = "CQ CQ DE SV1ABC K", wpm = 20.0, toneHz = 612.0))
        assertEquals(ref, result.text)
    }

    @Test
    fun `prosign sent without inter-character gap decodes as one token (FR-6-4)`() {
        val (ref, result) = decode(Config(text = "73 <SK>", wpm = 18.0, toneHz = 600.0))
        assertEquals("73 <SK>", ref)
        assertEquals(ref, result.text)
    }

    @Test
    fun `farnsworth timing decodes despite stretched spacing (FR-5-2)`() {
        val (ref, result) = decode(
            Config(text = "CQ CQ DE SV1ABC K", wpm = 15.0, farnsworthSpacingWpm = 8.0, toneHz = 600.0)
        )
        val cer = TextMetrics.cer(ref, result.text)
        assertTrue(cer < 0.05) { "Farnsworth CER = $cer; decoded '${result.text}'" }
    }

    @Test
    fun `hesitation gaps do not derail the decode`() {
        val (ref, result) = decode(
            Config(
                text = AcceptanceTest.LONG_TEXT,
                wpm = 18.0,
                toneHz = 600.0,
                snrDb = 12.0,
                jitterFraction = 0.10,
                hesitation = SyntheticMorseGenerator.Hesitation(
                    wordBoundaryProb = 0.3, midCharProb = 0.03, meanExtraUnits = 2.5
                ),
                seed = 23L,
            )
        )
        val cer = TextMetrics.cer(ref, result.text)
        assertTrue(cer < 0.15) { "hesitation CER = $cer" }
    }

    @Test
    fun `modelFitQuality is high on clean signal (FR-5-8)`() {
        val (_, result) = decode(Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 612.0))
        assertTrue(result.modelFitQuality > 0.8) { "fit = ${result.modelFitQuality}" }
    }

    @Test
    fun `modelFitQuality is low when durations do not cluster (D-8)`() {
        // Uniform random log-durations have no dot/dash structure.
        val rng = java.util.Random(29L)
        val z = DoubleArray(200) { ln(20.0 + 280.0 * rng.nextDouble()) - ln(60.0) }
        val mix = GaussianMixture.fit(z, doubleArrayOf(0.0, ln(3.0)), initSigma = 0.3)
        assertTrue(RobustTimingDecoder.fitQuality(mix) < 0.5) {
            "fit = ${RobustTimingDecoder.fitQuality(mix)}"
        }
    }

    @Test
    fun `confidence is high on clean signal (FR-6-6)`() {
        val (_, result) = decode(Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 612.0))
        val meanConf = result.chars.map { it.confidence }.average()
        assertTrue(meanConf > 0.85) { "mean confidence = $meanConf" }
    }

    @Test
    fun `band edges 5 and 40 wpm decode clean and unflagged (D-1)`() {
        val (ref5, res5) = decode(Config(text = "PARIS", wpm = 5.0, toneHz = 600.0))
        assertEquals(ref5, res5.text)
        assertFalse(res5.outOfCalibratedRange)

        val (ref40, res40) = decode(Config(text = "PARIS PARIS", wpm = 40.0, toneHz = 600.0))
        assertEquals(ref40, res40.text)
        assertFalse(res40.outOfCalibratedRange)
    }

    @Test
    fun `outside the calibrated band is flagged, not hidden (FR-5-7)`() {
        val (_, result) = decode(Config(text = "PARIS PARIS PARIS", wpm = 50.0, toneHz = 600.0))
        assertTrue(result.outOfCalibratedRange) {
            "unit=${result.unitMs} wpm=${result.wpm} fit=${result.modelFitQuality} text='${result.text}'"
        }
    }

    @Test
    fun `silence and noise produce empty output (P-4)`() {
        val silence = FloatArray(16_000 * 2)
        assertEquals("", decoder.decodeFile(FloatArrayPcmSource(silence, 16_000)).text)

        val rng = java.util.Random(11L)
        val noise = FloatArray(16_000 * 3) { (0.1 * rng.nextGaussian()).toFloat() }
        assertEquals("", decoder.decodeFile(FloatArrayPcmSource(noise, 16_000)).text)
    }

    @Test
    fun `off-frequency lock yields no confident output`() {
        val gen = SyntheticMorseGenerator.generate(
            Config(text = AcceptanceTest.LONG_TEXT, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 31L)
        )
        val result = decoder.decodeFile(
            FloatArrayPcmSource(gen.samples, gen.sampleRate),
            toneHzOverride = 612.0 + 180.0,
        )
        assertTrue(result.text.isEmpty() || result.modelFitQuality < 0.5) {
            "off-frequency decoded '${result.text}' with fit ${result.modelFitQuality}"
        }
    }

    @Test
    fun `generator normalize keeps prosign tokens`() {
        assertEquals("73 <SK>", SyntheticMorseGenerator.normalize("73 <sk>"))
        assertEquals("AB", SyntheticMorseGenerator.normalize("a<b")) // stray '<' dropped
    }
}

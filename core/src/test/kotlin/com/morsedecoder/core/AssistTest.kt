package com.morsedecoder.core

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.export.CutNumbers
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Milestone-6: assists — all off by default, each measurably useful when on. */
class AssistTest {

    private fun decode(config: Config, assists: MorseDecoder.Assists): Pair<String, MorseDecoder.FileResult> {
        val gen = SyntheticMorseGenerator.generate(config)
        val result = MorseDecoder(assists = assists)
            .decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
        return Pair(gen.normalizedText, result)
    }

    private val handSent = Config(
        text = AcceptanceTest.LONG_TEXT,
        wpm = 18.0,
        toneHz = 650.0,
        snrDb = 10.0,
        dashDotRatio = 2.2,
        jitterFraction = 0.20,
        charBiasFraction = 0.05,
        seed = 7L, // identical to the AC-3 stream
    )

    @Test
    fun `AC-4 lexicon assist brings hand-sent CER under 6 percent`() {
        val (refRaw, raw) = decode(handSent, MorseDecoder.Assists())
        val cerRaw = TextMetrics.cer(refRaw, raw.text)

        val (ref, assisted) = decode(handSent, MorseDecoder.Assists(lexicon = true))
        val cer = TextMetrics.cer(ref, assisted.text)

        assertTrue(cer < 0.06) { "AC-4 CER = $cer (raw was $cerRaw); decoded '${assisted.text}'" }
        assertTrue(cer <= cerRaw + 1e-9) { "lexicon made things worse: $cerRaw -> $cer" }
    }

    @Test
    fun `lexicon leaves callsigns alone (FR-7-2)`() {
        val config = Config(
            text = "CQ CQ DE SV1ABC SV1ABC K",
            wpm = 18.0, toneHz = 650.0, snrDb = 12.0,
            jitterFraction = 0.12, seed = 83L,
        )
        val (_, result) = decode(config, MorseDecoder.Assists(lexicon = true))
        assertTrue(result.text.contains("SV1ABC")) {
            "callsign corrupted by the prior: '${result.text}'"
        }
    }

    @Test
    fun `prior-changed characters are flagged assisted (P-3)`() {
        val (_, assisted) = decode(handSent, MorseDecoder.Assists(lexicon = true))
        val (_, raw) = decode(handSent, MorseDecoder.Assists())
        if (assisted.text != raw.text) {
            assertTrue(assisted.chars.any { it.assisted }) {
                "outputs differ but nothing is marked assisted"
            }
        }
        // raw mode never marks anything
        assertTrue(raw.chars.none { it.assisted })
    }

    @Test
    fun `cut numbers expand only in digit context (FR-7-3)`() {
        assertEquals(listOf("5", "9", "9"), CutNumbers.expandWord(listOf("5", "N", "N")))
        assertEquals(listOf("1", "0", "0"), CutNumbers.expandWord(listOf("1", "T", "T")))
        assertNull(CutNumbers.expandWord(listOf("T", "N", "X")))   // no digit — a word
        assertNull(CutNumbers.expandWord(listOf("7", "3")))        // nothing to map
        assertNull(CutNumbers.expandWord(listOf("5", "N", "<?>"))) // undecodable blocks
        assertNull(CutNumbers.expandWord(listOf("R", "5", "T")))   // non-mappable letter
    }

    @Test
    fun `cut numbers in file decode text (FR-7-3)`() {
        val config = Config(text = "UR RST 5NN 5NN", wpm = 20.0, toneHz = 612.0, seed = 89L)
        val (_, raw) = decode(config, MorseDecoder.Assists())
        assertEquals("UR RST 5NN 5NN", raw.text)
        val (_, cut) = decode(config, MorseDecoder.Assists(cutNumbers = true))
        assertEquals("UR RST 599 599", cut.text)
    }
}

package com.morsedecoder.core

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.SpeedDrift
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * §6 acceptance criteria for the milestone-2 robust decoder. All in raw mode —
 * there are no assists yet, which is also the shipping default (P-1/P-3).
 */
class AcceptanceTest {

    private val decoder = MorseDecoder()

    private fun decode(config: Config): Triple<String, MorseDecoder.FileResult, SyntheticMorseGenerator.GeneratedSignal> {
        val gen = SyntheticMorseGenerator.generate(config)
        val result = decoder.decodeFile(FloatArrayPcmSource(gen.samples, gen.sampleRate))
        return Triple(gen.normalizedText, result, gen)
    }

    private fun cerOf(config: Config): Double {
        val (ref, result, _) = decode(config)
        return TextMetrics.cer(ref, result.text)
    }

    companion object {
        const val LONG_TEXT =
            "CQ CQ CQ DE SV1ABC SV1ABC K THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG " +
                "0123456789 UR RST 599 QTH ATHENS TNX FER CALL 73 ES GL"
    }

    @Test
    fun `AC-1 machine-sent +10 dB raw mode CER under 2 percent`() {
        val cer = cerOf(Config(text = LONG_TEXT, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 3L))
        assertTrue(cer < 0.02) { "AC-1 CER = $cer" }
    }

    @Test
    fun `AC-2 machine-sent 0 dB raw mode CER under 10 percent`() {
        val cer = cerOf(Config(text = LONG_TEXT, wpm = 20.0, toneHz = 612.0, snrDb = 0.0, seed = 5L))
        assertTrue(cer < 0.10) { "AC-2 CER = $cer" }
    }

    @Test
    fun `AC-3 hand-sent 20 percent jitter +10 dB raw mode CER under 12 percent`() {
        val cer = cerOf(
            Config(
                text = LONG_TEXT,
                wpm = 18.0,
                toneHz = 650.0,
                snrDb = 10.0,
                dashDotRatio = 2.2,       // real fist, D-3
                jitterFraction = 0.20,
                charBiasFraction = 0.05,
                seed = 7L,
            )
        )
        assertTrue(cer < 0.12) { "AC-3 CER = $cer" }
    }

    @Test
    fun `AC-5 drifting stream costs at most 2 points of CER vs static`() {
        val base = Config(
            text = LONG_TEXT,
            wpm = 17.0,
            toneHz = 600.0,
            snrDb = 12.0,
            jitterFraction = 0.12,
            seed = 11L,
        )
        val cerStatic = cerOf(base)
        val cerDrift = cerOf(base.copy(drift = SpeedDrift.LinearRamp(endWpm = 24.0, durationS = 60.0)))
        assertTrue(cerDrift - cerStatic <= 0.02 + 1e-9) {
            "AC-5 static=$cerStatic drift=$cerDrift penalty=${cerDrift - cerStatic}"
        }
    }

    @Test
    fun `AC-6 tracked wpm stays within 10 percent of truth through a ramp`() {
        val (_, result, gen) = decode(
            Config(
                text = LONG_TEXT + " " + LONG_TEXT,
                wpm = 15.0,
                toneHz = 600.0,
                jitterFraction = 0.05,
                drift = SpeedDrift.LinearRamp(endWpm = 25.0, durationS = 60.0),
                seed = 13L,
            )
        )
        assertTrue(result.wpmTrack.isNotEmpty())
        val truth = gen.wpmTrack
        var checked = 0
        for ((tS, wpm) in result.wpmTrack) {
            if (tS < 5.0) continue // convergence window, AC-7 territory
            // nearest ground-truth sample; generator times exclude the lead-in, decoder
            // times include it — the 0.25 s offset is far below the ramp timescale
            val truthWpm = truth.minByOrNull { abs(it.first - (tS - 0.25)) }!!.second
            assertTrue(abs(wpm - truthWpm) / truthWpm < 0.10) {
                "AC-6 at t=$tS: tracked $wpm vs truth $truthWpm"
            }
            checked++
        }
        assertTrue(checked > 20) { "AC-6 too few track points checked ($checked)" }
    }

    @Test
    fun `AC-7 unit estimate within 10 percent by 20 observed elements`() {
        val (_, result, _) = decode(
            Config(text = "PARIS PARIS PARIS PARIS", wpm = 20.0, toneHz = 600.0, seed = 17L)
        )
        var elements = 0
        for (c in result.chars) {
            elements += c.elements.size
            if (elements >= 20) {
                assertTrue(abs(c.unitMsGlobal - 60.0) / 60.0 < 0.10) {
                    "AC-7 unit after $elements elements = ${c.unitMsGlobal}"
                }
                return
            }
        }
        assertTrue(false) { "AC-7 stream never reached 20 elements" }
    }
}

package com.morsedecoder.core.tools

import com.morsedecoder.core.audio.WavIo
import com.morsedecoder.core.fixtures.NonMorseAudio
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Hesitation
import com.morsedecoder.core.synth.SyntheticMorseGenerator.SpeedDrift
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Harness utility, not a test: writes every test fixture out as a WAV so a person can
 * listen to what the suite is actually asserting against. A no-op unless the output
 * directory is set.
 *
 *   FIXTURE_WAV_DIR=fixtures gradlew :core:test --tests "*FixtureWavTest*"
 *
 * Worth doing periodically rather than never. A fixture named `speech` that does not
 * sound like speech makes every assertion resting on it weaker than it reads, and no
 * amount of green ticks will reveal that — only ears will. The same goes the other way:
 * if `hand-13-rough` sounds like something no operator would ever send, the 250 % error
 * rate it produces is not the indictment it appears to be.
 *
 * Writes an `index.txt` alongside so the files are self-describing.
 */
class FixtureWavTest {

    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    /** The bench matrix, so the positive cases can be heard next to the negative ones. */
    private fun morseFixtures(): List<Triple<String, String, Config>> = listOf(
        Triple("morse-clean-20", "machine, +20 dB — the easy case",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 20.0, seed = 11L)),
        Triple("morse-20@10", "machine, +10 dB (AC-1 point)",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L)),
        Triple("morse-20@0", "machine, 0 dB (AC-2 point)",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 0.0, seed = 13L)),
        Triple("morse-20@-5", "machine, -5 dB — below the promised floor",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = -5.0, seed = 14L)),
        Triple("morse-hand-18", "20 % jitter, 2.2:1 fist (AC-3 point)",
            Config(text = text, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
                dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L)),
        Triple("morse-hand-13-rough", "25 % jitter, heavy bias, +5 dB — decodes at 250 % CER",
            Config(text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
                dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L)),
        Triple("morse-slow-7", "7 WPM — bottom of the calibrated band",
            Config(text = text, wpm = 7.0, toneHz = 612.0, snrDb = 10.0, seed = 17L)),
        Triple("morse-fast-35", "35 WPM — keyer territory",
            Config(text = text, wpm = 35.0, toneHz = 612.0, snrDb = 10.0, seed = 18L)),
        Triple("morse-farnsworth", "18 WPM elements, 8 WPM spacing",
            Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
                farnsworthSpacingWpm = 8.0, seed = 19L)),
        Triple("morse-drift-ramp", "15 → 25 WPM over 60 s",
            Config(text = text, wpm = 15.0, toneHz = 612.0, snrDb = 10.0,
                drift = SpeedDrift.LinearRamp(25.0, 60.0), seed = 20L)),
        Triple("morse-wobble", "±15 % speed wobble at 0.05 Hz",
            Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
                drift = SpeedDrift.SineWobble(0.15, 0.05), seed = 21L)),
        Triple("morse-hesitation", "human pauses at word and character boundaries",
            Config(text = text, wpm = 16.0, toneHz = 612.0, snrDb = 10.0,
                hesitation = Hesitation(), seed = 22L)),
        Triple("morse-qsb-impulse", "fading + static crashes — file path decodes at 168 % CER",
            Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 8.0,
                fadingDepth = 0.6, fadingRateHz = 0.15, impulsesPerSecond = 2.0, seed = 23L)),
        Triple("morse-tone-1882", "1882 Hz — the field report's frequency",
            Config(text = text, wpm = 18.0, toneHz = 1882.0, snrDb = 10.0, seed = 24L)),
        Triple("morse-tone-700-mains", "700 Hz — a mains multiple, see known-issues D-3",
            Config(text = text, wpm = 18.0, toneHz = 700.0, snrDb = 10.0, seed = 26L)),
    )

    private val nonMorseNotes = mapOf(
        "silence" to "digital silence",
        "white-noise" to "structureless; must decode to nothing",
        "pink-noise" to "1/f, closer to real room ambience",
        "steady-carrier" to "narrowband and loud but never keyed",
        "mains-hum" to "50 Hz and harmonics, a badly grounded shack",
        "speech" to "DEFECT: decodes as 26 characters of Morse",
        "music" to "sustained tones with vibrato; correctly silent",
        "rtty" to "DEFECT: decodes as 70 characters — the hardest case",
        "knocking" to "DEFECT on the file path: 30 characters",
        "clipping" to "DEFECT: overloaded input, 10 characters",
        "speech+hum" to "DEFECT: 22 characters",
        "music+noise" to "DEFECT: 50 characters",
    )

    @Test
    fun `write fixture wavs when requested`() {
        val dir = System.getenv("FIXTURE_WAV_DIR") ?: return
        val out = File(dir)
        out.mkdirs()

        val index = StringBuilder()
        index.append("Test fixtures as audio — 16 kHz mono WAV.\n")
        index.append("Generated by FixtureWavTest; deterministic, so these are exactly what the suite runs.\n\n")

        index.append("NOT MORSE — the decoder must stay silent on these (NonMorseTest)\n")
        index.append("-".repeat(78)).append('\n')
        for ((name, audio) in NonMorseAudio.corpus(20.0)) {
            val file = File(out, "$name.wav")
            WavIo.write(file, audio, NonMorseAudio.SAMPLE_RATE)
            val line = "%-16s %5.1fs  %s".format(name, audio.size / 16_000.0, nonMorseNotes[name] ?: "")
            index.append(line).append('\n')
            println("FIXTURE ${file.absolutePath}")
        }

        index.append("\nMORSE — the decoder should decode these (AcceptanceTest, DecoderBench)\n")
        index.append("-".repeat(78)).append('\n')
        for ((name, note, cfg) in morseFixtures()) {
            val gen = SyntheticMorseGenerator.generate(cfg)
            val file = File(out, "$name.wav")
            WavIo.write(file, gen.samples, gen.sampleRate)
            index.append("%-22s %5.1fs  %s\n".format(name, gen.samples.size / 16_000.0, note))
            println("FIXTURE ${file.absolutePath}")
        }

        index.append("\nAll Morse fixtures send the same text:\n  ").append(text).append('\n')
        File(out, "index.txt").writeText(index.toString())
        println("FIXTURE index: ${File(out, "index.txt").absolutePath}")
    }
}

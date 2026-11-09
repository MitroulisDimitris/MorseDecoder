package com.morsedecoder.core.tools

import com.morsedecoder.core.audio.WavIo
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Hesitation
import com.morsedecoder.core.synth.SyntheticMorseGenerator.SpeedDrift
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Harness utility, not a test: writes the ten **device-test clips** that
 * `tools/device-run.sh` plays through a loudspeaker while the app listens on a phone, plus
 * a 30 s silent clip for the quiet-room capture. A no-op unless the directory is set.
 *
 *   PHONE_CLIPS_OUT=device-test/clips gradlew :core:test --tests "*PhoneClipsTool*"
 *
 * Deterministic: the same seeds give the same audio on any machine, so the clips do not
 * have to be committed (committing audio is still an open decision, `progress.md`).
 * These are the clips every 2026-11-09 device measurement used.
 *
 * Writes, next to the WAVs:
 * - `truth.tsv` — `<clip>.wav<TAB><reference text>`, for `tools/score-device.py`;
 * - `cases.tsv` — the same references pointed at `../run/<clip>.wav`, for replaying a
 *   device run's captures with `RealAudioBench` (`REAL_DIR=<this dir>`, after
 *   `tools/device-run.sh <this dir>/../run ...`). The committed copy is `device-test/clips/`,
 *   so a run lands in `device-test/run/`, which is gitignored.
 *
 * Each clip carries 3 s of silence at both ends: the device run measures how the app
 * behaves before the signal starts and after it stops, not just during it.
 */
class PhoneClipsTool {
    private val sr = 16_000

    private fun silence(s: Double) = FloatArray((s * sr).toInt())
    private fun cat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }
    private fun gen(c: Config) = SyntheticMorseGenerator.generate(c)

    @Test
    fun `write device-test clips when requested`() {
        val dir = System.getenv("PHONE_CLIPS_OUT")
        assumeTrue(dir != null, "PHONE_CLIPS_OUT not set")
        val out = File(dir!!).apply { mkdirs() }
        val truth = StringBuilder()
        fun save(name: String, samples: FloatArray, text: String) {
            WavIo.write(File(out, "$name.wav"), cat(silence(3.0), samples, silence(3.0)), sr)
            truth.append("$name.wav\t$text\n")
        }
        fun one(name: String, c: Config) {
            val g = gen(c); save(name, g.samples, g.normalizedText)
        }

        WavIo.write(File(out, "00-room-quiet.wav"), silence(30.0), sr)
        truth.append("00-room-quiet.wav\t\n")

        one("01-machine-20wpm-700hz", Config(
            text = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789",
            wpm = 20.0, toneHz = 700.0, seed = 1))
        one("02-hand-15wpm-600hz", Config(
            text = "CQ CQ CQ DE K1ABC K1ABC K UR RST 599 NAME JOHN QTH BOSTON HW?",
            wpm = 15.0, toneHz = 600.0, dashDotRatio = 2.5, jitterFraction = 0.15,
            charBiasFraction = 0.06, hesitation = Hesitation(0.3, 0.02, 2.0), seed = 2))
        one("03-rough-13wpm-650hz", Config(
            text = "PACK MY BOX WITH FIVE DOZEN LIQUOR JUGS 73",
            wpm = 13.0, toneHz = 650.0, dashDotRatio = 2.1, jitterFraction = 0.25,
            charBiasFraction = 0.12, hesitation = Hesitation(0.4, 0.05, 3.0), seed = 3))
        one("04-machine-25wpm-800hz-noise", Config(
            text = "VVV VVV TEST DE K1ABC WX HERE IS SUNNY TEMP 18C",
            wpm = 25.0, toneHz = 800.0, snrDb = 3.0, seed = 4))
        one("05-machine-35wpm-900hz", Config(
            text = "SPHINX OF BLACK QUARTZ JUDGE MY VOW 1234567890",
            wpm = 35.0, toneHz = 900.0, seed = 5))
        one("06-hand-7wpm-500hz", Config(
            text = "HELLO WORLD THIS IS A SLOW TEST 42",
            wpm = 7.0, toneHz = 500.0, dashDotRatio = 2.4, jitterFraction = 0.18,
            charBiasFraction = 0.08, hesitation = Hesitation(0.3, 0.03, 2.0), seed = 6))
        one("07-fading-18wpm-1850hz", Config(
            text = "CQ DX DE DL4XYZ DL4XYZ ALL BEST 73 SK",
            wpm = 18.0, toneHz = 1850.0, snrDb = 8.0, dashDotRatio = 2.6, jitterFraction = 0.12,
            fadingDepth = 0.8, fadingRateHz = 0.15, impulsesPerSecond = 0.5, seed = 7))

        val a = gen(Config(text = "FIRST TONE TEST ONE TWO THREE", wpm = 20.0, toneHz = 600.0, seed = 8))
        val b = gen(Config(text = "SECOND TONE TEST FOUR FIVE SIX", wpm = 20.0, toneHz = 1100.0, seed = 9))
        save("08-tone-jump-600-to-1100hz", cat(a.samples, silence(5.0), b.samples),
            "${a.normalizedText} ${b.normalizedText}")

        val c1 = gen(Config(text = "BEFORE THE SILENCE 111", wpm = 15.0, toneHz = 700.0,
            dashDotRatio = 2.5, jitterFraction = 0.15, seed = 10))
        val c2 = gen(Config(text = "AFTER THE SILENCE 222", wpm = 15.0, toneHz = 700.0,
            dashDotRatio = 2.5, jitterFraction = 0.15, seed = 11))
        save("09-silence-30s-700hz", cat(c1.samples, silence(30.0), c2.samples),
            "${c1.normalizedText} ${c2.normalizedText}")

        one("10-ramp-15to25wpm-750hz", Config(
            text = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",
            wpm = 15.0, toneHz = 750.0, drift = SpeedDrift.LinearRamp(25.0, 40.0),
            jitterFraction = 0.08, seed = 12))

        File(out, "truth.tsv").writeText(truth.toString())
        File(out, "cases.tsv").writeText(
            truth.lineSequence().filter { it.isNotBlank() }.joinToString("\n") { "../run/$it" } + "\n",
        )
    }
}

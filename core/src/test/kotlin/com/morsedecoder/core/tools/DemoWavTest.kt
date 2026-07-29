package com.morsedecoder.core.tools

import com.morsedecoder.core.audio.WavIo
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Harness utility, not a test: writes a demo WAV for on-device acoustic testing when
 * DEMO_WAV_OUT is set in the environment. A no-op in normal suite runs.
 *
 *   DEMO_WAV_OUT=path [DEMO_WAV_TEXT=...] [DEMO_WAV_WPM=20] gradlew :core:test --tests "*DemoWavTest*"
 */
class DemoWavTest {

    @Test
    fun `write demo wav when requested`() {
        val out = System.getenv("DEMO_WAV_OUT") ?: return
        val text = System.getenv("DEMO_WAV_TEXT")
            ?: "CQ CQ CQ DE SV1ABC SV1ABC K THE QUICK BROWN FOX 599 73 <SK>"
        val wpm = System.getenv("DEMO_WAV_WPM")?.toDoubleOrNull() ?: 20.0
        val gen = SyntheticMorseGenerator.generate(
            Config(text = text, wpm = wpm, toneHz = 612.0, amplitude = 0.8, seed = 99L)
        )
        WavIo.write(File(out), gen.samples, gen.sampleRate)
        println("demo wav: ${gen.samples.size / 16_000.0} s -> $out")
    }
}

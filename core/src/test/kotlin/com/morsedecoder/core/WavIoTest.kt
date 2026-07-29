package com.morsedecoder.core

import com.morsedecoder.core.audio.WavIo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WavIoTest {

    @Test
    fun `pcm16 round trip preserves samples within quantisation`() {
        val sr = 16_000
        val original = FloatArray(sr) { (0.4 * sin(2.0 * PI * 600.0 * it / sr)).toFloat() }
        val wav = WavIo.read(WavIo.writePcm16(original, sr))
        assertEquals(sr, wav.sampleRate)
        assertEquals(original.size, wav.samples.size)
        for (i in original.indices) {
            assertTrue(abs(original[i] - wav.samples[i]) <= 1.5f / 32768f) {
                "sample $i differs: ${original[i]} vs ${wav.samples[i]}"
            }
        }
    }

    @Test
    fun `resample changes length proportionally`() {
        val input = FloatArray(44_100) { (it % 100) / 100f }
        val out = WavIo.resample(input, 44_100, 16_000)
        assertEquals(16_000, out.size)
    }

    @Test
    fun `resample identity when rates match`() {
        val input = FloatArray(100) { it.toFloat() }
        assertTrue(WavIo.resample(input, 16_000, 16_000) === input)
    }
}

package com.morsedecoder.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV reader/writer for the JVM harness.
 *
 * On Android, arbitrary formats go through MediaExtractor/MediaCodec (FR-1.3); this
 * class exists so the desktop test rig and corpus tooling can read real recordings
 * and dump synthetic signals without any Android dependency.
 *
 * Reads PCM16, PCM8, PCM32-float; folds multi-channel to mono by averaging.
 * Writes PCM16 mono.
 */
object WavIo {

    data class Wav(val samples: FloatArray, val sampleRate: Int)

    fun read(file: File): Wav = read(file.readBytes())

    fun read(bytes: ByteArray): Wav {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44) { "Not a WAV file: too short (${bytes.size} bytes)" }
        require(tag(buf, 0) == "RIFF" && tag(buf, 8) == "WAVE") { "Not a RIFF/WAVE file" }

        var format = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = tag(buf, pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bitsPerSample = buf.getShort(body + 14).toInt()
                    if (format == 0xFFFE && size >= 40) {
                        // WAVE_FORMAT_EXTENSIBLE: the real format is in the subformat GUID.
                        format = buf.getShort(body + 24).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataSize = minOf(size, bytes.size - body)
                }
            }
            pos = body + size + (size and 1) // chunks are word-aligned
        }

        require(dataOffset >= 0) { "WAV has no data chunk" }
        require(channels > 0 && sampleRate > 0) { "WAV has no fmt chunk" }

        val frames: Int
        val mono: FloatArray
        when {
            format == 1 && bitsPerSample == 16 -> {
                frames = dataSize / (2 * channels)
                mono = FloatArray(frames)
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until channels) {
                        acc += buf.getShort(dataOffset + 2 * (i * channels + c)) / 32768f
                    }
                    mono[i] = acc / channels
                }
            }
            format == 1 && bitsPerSample == 8 -> {
                frames = dataSize / channels
                mono = FloatArray(frames)
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until channels) {
                        acc += ((bytes[dataOffset + i * channels + c].toInt() and 0xFF) - 128) / 128f
                    }
                    mono[i] = acc / channels
                }
            }
            format == 3 && bitsPerSample == 32 -> {
                frames = dataSize / (4 * channels)
                mono = FloatArray(frames)
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until channels) {
                        acc += buf.getFloat(dataOffset + 4 * (i * channels + c))
                    }
                    mono[i] = acc / channels
                }
            }
            else -> throw IllegalArgumentException(
                "Unsupported WAV encoding: format=$format bits=$bitsPerSample (PCM16/PCM8/float32 supported)"
            )
        }
        return Wav(mono, sampleRate)
    }

    fun writePcm16(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)                       // PCM
        buf.putShort(1)                       // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)            // byte rate
        buf.putShort(2)                       // block align
        buf.putShort(16)                      // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        file.writeBytes(writePcm16(samples, sampleRate))
    }

    /** Linear-interpolation resampler. Adequate for narrowband CW; not for hi-fi. */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return samples
        val outLen = ((samples.size.toLong() * toRate) / fromRate).toInt()
        val out = FloatArray(outLen)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val src = i * step
            val i0 = src.toInt()
            val i1 = minOf(i0 + 1, samples.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = samples[i0] * (1f - frac) + samples[i1] * frac
        }
        return out
    }

    private fun tag(buf: ByteBuffer, offset: Int): String {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = buf.get(offset + i)
        return String(b, Charsets.US_ASCII)
    }
}

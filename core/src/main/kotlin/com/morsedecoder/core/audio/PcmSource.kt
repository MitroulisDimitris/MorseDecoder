package com.morsedecoder.core.audio

/**
 * Pull-based mono PCM source (FR-1.4).
 *
 * Both the live microphone path and the file-import path feed the decoder through this
 * interface; the decoder core has no knowledge of which source it is consuming.
 * Samples are floats in [-1, 1].
 */
interface PcmSource {
    val sampleRate: Int

    /**
     * Fill [buffer] starting at [offset] with up to [length] samples.
     * Returns the number of samples written, or -1 at end of stream.
     */
    fun read(buffer: FloatArray, offset: Int = 0, length: Int = buffer.size): Int
}

/** In-memory source — synthetic signals and decoded files. */
class FloatArrayPcmSource(
    private val samples: FloatArray,
    override val sampleRate: Int,
) : PcmSource {
    private var position = 0

    override fun read(buffer: FloatArray, offset: Int, length: Int): Int {
        if (position >= samples.size) return -1
        val n = minOf(length, samples.size - position)
        System.arraycopy(samples, position, buffer, offset, n)
        position += n
        return n
    }
}

/** Drain the source to a single array. File-mode decoding is two-pass (FM-1), so this is the norm there. */
fun PcmSource.readAllSamples(): FloatArray {
    val chunk = FloatArray(16 * 1024)
    val out = ArrayList<FloatArray>()
    var total = 0
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        out.add(chunk.copyOf(n))
        total += n
    }
    val result = FloatArray(total)
    var pos = 0
    for (part in out) {
        System.arraycopy(part, 0, result, pos, part.size)
        pos += part.size
    }
    return result
}

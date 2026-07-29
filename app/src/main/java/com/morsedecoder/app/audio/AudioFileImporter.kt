package com.morsedecoder.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.audio.WavIo
import java.nio.ByteOrder

/**
 * File import (FR-1.3): SAF Uri -> MediaExtractor/MediaCodec -> 16 kHz mono floats.
 * Handles whatever the platform can decode (WAV, MP3, M4A/AAC, OGG, FLAC at minimum).
 * The result feeds the same PcmSource abstraction as the mic (FR-1.4).
 */
object AudioFileImporter {

    private const val TARGET_RATE = 16_000

    fun load(context: Context, uri: Uri): FloatArrayPcmSource {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val fmt = format ?: throw IllegalArgumentException("no audio track in file")
            extractor.selectTrack(trackIndex)

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val pcm = ArrayList<FloatArray>()
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.order(ByteOrder.nativeOrder())
                        val shorts = out.asShortBuffer()
                        val frames = shorts.remaining() / channels
                        // fold to mono while converting
                        val mono = FloatArray(frames)
                        for (f in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until channels) acc += shorts.get(f * channels + c) / 32768f
                            mono[f] = acc / channels
                        }
                        if (mono.isNotEmpty()) pcm.add(mono)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            codec.stop()
            codec.release()

            var total = 0
            for (part in pcm) total += part.size
            val all = FloatArray(total)
            var pos = 0
            for (part in pcm) {
                System.arraycopy(part, 0, all, pos, part.size)
                pos += part.size
            }
            val resampled = WavIo.resample(all, sampleRate, TARGET_RATE)
            return FloatArrayPcmSource(resampled, TARGET_RATE)
        } finally {
            extractor.release()
        }
    }
}

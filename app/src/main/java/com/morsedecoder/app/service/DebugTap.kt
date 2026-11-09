package com.morsedecoder.app.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.morsedecoder.core.live.LiveDecoderEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device-test instrumentation. **Debug builds only** — [create] returns null on any build
 * that is not debuggable, so a release build carries none of this behaviour.
 *
 * Two things, both for measuring the app on a real phone (`known-issues.md` G-2), where
 * the screen cannot be read by a script (the spectrum never idles, so `uiautomator dump`
 * fails) and the transcript box shows two lines:
 *
 * - **Event log.** Every decoded character, every re-lock with its trigger, and a status
 *   line every 2 s, on logcat tag [TAG]. `tools/device-run.sh` collects and scores it.
 * - **Capture dump.** When the marker file `dump_pcm` exists in the app's external files
 *   directory, the exact samples handed to the engine are written as a 16 kHz mono WAV to
 *   `captures/`. Replaying that file through `LiveDecoderEngine` on the JVM reproduces the
 *   device's input bit for bit, which is what makes a fix measurable without a phone.
 *   Enable with `adb shell touch /sdcard/Android/data/com.morsedecoder.app/files/dump_pcm`.
 *
 * The WAV header is rewritten after every chunk, so a file cut short by `am force-stop`
 * (no `onDestroy`) is still valid up to the last chunk.
 */
class DebugTap private constructor(
    private val wav: RandomAccessFile?,
    val wavFile: File?,
    /**
     * Mic source to use instead of UNPROCESSED, from a marker file `audio_source_<NAME>`
     * (NAME one of MIC, CAMCORDER, VOICE_RECOGNITION, UNPROCESSED, DEFAULT). For A/B-testing
     * what each source does to keying on a given phone (`known-issues.md` D-12).
     */
    val audioSourceOverride: Int? = null,
) {

    private var lastStatusMs = 0L
    private var dataBytes = 0L
    private val buf = ByteBuffer.allocate(2 * 4096).order(ByteOrder.LITTLE_ENDIAN)

    /** Called on the decode thread, in feed order, before the engine sees the chunk. */
    fun audio(samples: FloatArray, count: Int) {
        val f = wav ?: return
        var i = 0
        while (i < count) {
            val n = minOf(count - i, 4096)
            buf.clear()
            for (k in 0 until n) {
                val s = (samples[i + k] * 32768f).toInt().coerceIn(-32768, 32767)
                buf.putShort(s.toShort())
            }
            f.seek(HEADER + dataBytes)
            f.write(buf.array(), 0, n * 2)
            dataBytes += n * 2
            i += n
        }
        writeHeader(f, dataBytes)
    }

    fun char(c: com.morsedecoder.core.decode.RobustTimingDecoder.DecodedChar, atMs: Double) {
        Log.i(TAG, "CH|%.0f|%s%s".format(atMs, if (c.isWordStart) " " else "", c.token))
    }

    fun relock(ev: LiveDecoderEngine.Event.Relock) {
        Log.i(TAG, "RELOCK|${ev.trigger}|%.1f|%.1f|%.0f".format(ev.fromHz, ev.toHz, ev.atMs))
    }

    fun status(e: LiveDecoderEngine) {
        val now = System.currentTimeMillis()
        if (now - lastStatusMs < 2_000) return
        lastStatusMs = now
        Log.i(
            TAG,
            "ST|t=%.0f|lock=%s|tone=%.0f|wpm=%s|fit=%s|low=%b|supp=%b|implaus=%b".format(
                e.nowMs(), e.lockState, e.toneHz, e.wpm?.let { "%.1f".format(it) } ?: "-",
                e.fitQuality?.let { "%.2f".format(it) } ?: "-",
                e.lowSignal, e.outputSuppressed, e.speedImplausible,
            ),
        )
    }

    fun close() {
        runCatching { wav?.close() }
        wavFile?.let { Log.i(TAG, "DUMP|${it.absolutePath}|${dataBytes / 2} samples") }
    }

    companion object {
        const val TAG = "MorseBench"
        private const val HEADER = 44L
        private const val RATE = 16_000

        fun create(context: Context): DebugTap? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            // Instrumentation must never take the app down: any failure here means no tap.
            return runCatching { createUnsafe(context) }
                .onFailure { Log.w(TAG, "debug tap disabled: $it") }
                .getOrNull()
        }

        private val SOURCES = mapOf(
            "MIC" to android.media.MediaRecorder.AudioSource.MIC,
            "CAMCORDER" to android.media.MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_RECOGNITION" to android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "UNPROCESSED" to android.media.MediaRecorder.AudioSource.UNPROCESSED,
            "DEFAULT" to android.media.MediaRecorder.AudioSource.DEFAULT,
        )

        private fun createUnsafe(context: Context): DebugTap {
            val dir = context.getExternalFilesDir(null)
            // The source is chosen by the marker's *name* (`audio_source_MIC`), never its
            // contents: a file adb creates here belongs to the shell user, and the app may
            // see it exist but not open it (EACCES).
            val source = SOURCES.entries.firstOrNull { (name, _) ->
                dir != null && File(dir, "audio_source_$name").exists()
            }?.value
            if (source != null) Log.i(TAG, "SOURCE_OVERRIDE|$source")
            if (dir == null || !File(dir, "dump_pcm").exists()) return DebugTap(null, null, source)
            val out = File(dir, "captures").apply { mkdirs() }
            val file = File(out, "capture-${System.currentTimeMillis()}.wav")
            val raf = RandomAccessFile(file, "rw")
            writeHeader(raf, 0)
            Log.i(TAG, "DUMP_START|${file.absolutePath}")
            return DebugTap(raf, file, source)
        }

        private fun writeHeader(f: RandomAccessFile, dataBytes: Long) {
            val h = ByteBuffer.allocate(HEADER.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
            h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
            f.seek(0)
            f.write(h.array())
        }
    }
}

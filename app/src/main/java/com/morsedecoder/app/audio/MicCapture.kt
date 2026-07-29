package com.morsedecoder.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone capture (FR-1.1): 16 kHz mono PCM16 pushed to [onAudio] as floats.
 *
 * FR-1.2: prefers the UNPROCESSED source; falls back to VOICE_RECOGNITION. Where the
 * device still attaches AGC or noise suppression to the session, both are explicitly
 * disabled — AGC destroys the envelope and NS can gate the tone entirely.
 */
class MicCapture(
    private val onAudio: (FloatArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission") // caller holds RECORD_AUDIO (NFR-7 flow)
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("16 kHz mono capture unsupported on this device")
            return false
        }
        val bufBytes = maxOf(minBuf, SAMPLE_RATE / 4 * 2) // >= 250 ms

        var rec = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("microphone unavailable")
            return false
        }
        record = rec

        // FR-1.2: kill session effects where the platform attached them anyway.
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = false }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = false }
        }

        running.set(true)
        rec.startRecording()
        thread = Thread({ captureLoop(rec) }, "mic-capture").apply { start() }
        return true
    }

    private fun captureLoop(rec: AudioRecord) {
        val shorts = ShortArray(1280) // 80 ms
        val floats = FloatArray(shorts.size)
        var errorStreak = 0
        while (running.get()) {
            if (paused.get()) {
                Thread.sleep(50)
                continue
            }
            val n = rec.read(shorts, 0, shorts.size)
            if (n > 0) {
                errorStreak = 0
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                onAudio(floats, n)
            } else {
                // FR-1.6: never fail silently — the mic was likely claimed elsewhere.
                if (++errorStreak == 3) onError("microphone read failed (claimed by another app?)")
                Thread.sleep(100)
            }
        }
    }

    /** FR-1.6: pause cleanly on audio-focus loss, resume when it returns. */
    fun setPaused(value: Boolean) {
        paused.set(value)
    }

    /** FR-1.5: is the active route a Bluetooth SCO microphone? */
    fun isBluetoothScoRoute(): Boolean {
        val device = record?.routedDevice ?: return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    fun stop() {
        running.set(false)
        thread?.join(1_000)
        thread = null
        agc?.release(); agc = null
        ns?.release(); ns = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}

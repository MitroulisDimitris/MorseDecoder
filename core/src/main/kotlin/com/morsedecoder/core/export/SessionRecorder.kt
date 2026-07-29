package com.morsedecoder.core.export

import com.morsedecoder.core.decode.RobustTimingDecoder

/**
 * Accumulates one session for export (FR-10.1/10.4). The decoder core knows nothing
 * about wall clocks (NFR-5) — the caller supplies epoch time and zone; everything
 * else is decoder output plus per-character telemetry snapshots.
 */
class SessionRecorder(
    val startedAtEpochMs: Long,
    /** IANA/offset zone id for the startedAt rendering, e.g. "+03:00" or "Europe/Athens". */
    val zoneId: String,
    /** "microphone" or "file". */
    val sourceType: String,
    /** Filename for file sources; null for the microphone. */
    val sourceName: String?,
) {
    data class RecordedChar(
        val char: RobustTimingDecoder.DecodedChar,
        val toneHz: Double,
        val snrDb: Double,
    )

    data class RelockRecord(
        val charIndex: Int,     // sits BEFORE the character at this index
        val tOffsetMs: Double,
        val fromHz: Double,
        val toHz: Double,
        val trigger: String,    // "manual" | "candidate" | "automatic"
    )

    val chars = ArrayList<RecordedChar>()
    val relocks = ArrayList<RelockRecord>()
    var assistLexicon = false
    var assistCutNumbers = false

    fun addChar(char: RobustTimingDecoder.DecodedChar, toneHz: Double, snrDb: Double) {
        chars.add(RecordedChar(char, toneHz, snrDb))
    }

    fun addRelock(tOffsetMs: Double, fromHz: Double, toHz: Double, trigger: String) {
        relocks.add(RelockRecord(chars.size, tOffsetMs, fromHz, toHz, trigger))
    }

    /** Session duration: end of the last element, or zero for an empty session. */
    val durationMs: Double
        get() = chars.lastOrNull()?.char?.elements?.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0.0

    val wpmRange: Pair<Double, Double>?
        get() {
            if (chars.isEmpty()) return null
            val wpms = chars.map { 1200.0 / it.char.unitMsGlobal }
            return Pair(wpms.min(), wpms.max())
        }
}

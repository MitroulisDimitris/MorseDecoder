package com.morsedecoder.core.export

import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.morse.MorseAlphabet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one serialiser behind all three destinations (FR-10.2). Two formats:
 * `.txt` (FR-10.3, human) and `.json` (FR-10.4, the corpus-collection mechanism —
 * raw measured durations AND the symbols the decoder assigned, so a changed DSP can
 * be re-run against the same measurements).
 *
 * Low confidence is omitted from `.txt` (FR-10.5) and preserved in `.json`.
 * Hand-rolled JSON: the schema is small, fixed by docs/export-formats.md, and adding
 * a serialisation dependency to :core for it would be a poor trade.
 */
object SessionExport {

    const val SCHEMA_VERSION = 1
    const val LOW_CONFIDENCE = 0.6

    private val tokenToPattern: Map<String, String> =
        MorseAlphabet.patternToToken.entries.associate { (pattern, token) -> token to pattern }

    // ---- plain text (FR-10.3) ----

    fun toText(rec: SessionRecorder, includeHeader: Boolean): String {
        val body = PlainTextTranscript.format(itemsOf(rec))
        if (!includeHeader) return body
        val started = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(rec.startedAtEpochMs).atZone(ZoneId.of(rec.zoneId))
        )
        val range = rec.wpmRange
        return buildString {
            appendLine("# Morse decoder session")
            appendLine("# started    $started")
            appendLine("# source     ${rec.sourceName ?: rec.sourceType}")
            appendLine("# duration   ${hhmmss(rec.durationMs)}")
            if (range != null) {
                appendLine("# wpm range  ${fmt1(range.first)} - ${fmt1(range.second)}")
            }
            appendLine()
            append(body)
        }
    }

    /** Transcript items with re-lock bars interleaved at their recorded positions. */
    private fun itemsOf(rec: SessionRecorder): List<PlainTextTranscript.Item> {
        val items = ArrayList<PlainTextTranscript.Item>()
        var relockIdx = 0
        val rawWords = charsAsWords(rec)
        var charIndex = 0
        for (word in rawWords) {
            val expanded = if (rec.assistCutNumbers) CutNumbers.expandWord(word.map { it.char.token }) else null
            for ((wi, rc) in word.withIndex()) {
                while (relockIdx < rec.relocks.size && rec.relocks[relockIdx].charIndex == charIndex) {
                    items.add(PlainTextTranscript.Item.Relock)
                    relockIdx++
                }
                val token = expanded?.get(wi) ?: rc.char.token
                items.add(PlainTextTranscript.Item.Char(token, wi == 0 && charIndex > 0))
                charIndex++
            }
        }
        while (relockIdx < rec.relocks.size) {
            items.add(PlainTextTranscript.Item.Relock)
            relockIdx++
        }
        return items
    }

    private fun charsAsWords(rec: SessionRecorder): List<List<SessionRecorder.RecordedChar>> {
        val words = ArrayList<List<SessionRecorder.RecordedChar>>()
        var cur = ArrayList<SessionRecorder.RecordedChar>()
        for (rc in rec.chars) {
            if (rc.char.isWordStart && cur.isNotEmpty()) {
                words.add(cur)
                cur = ArrayList()
            }
            cur.add(rc)
        }
        if (cur.isNotEmpty()) words.add(cur)
        return words
    }

    // ---- structured json (FR-10.4) ----

    fun toJson(rec: SessionRecorder, appVersion: String): String {
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")

        val started = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(rec.startedAtEpochMs).atZone(ZoneId.of(rec.zoneId))
        )
        sb.append("  \"session\": {\n")
        sb.append("    \"startedAt\": ").append(js(started)).append(",\n")
        sb.append("    \"durationMs\": ").append(rec.durationMs.toLong()).append(",\n")
        sb.append("    \"source\": { \"type\": ").append(js(rec.sourceType))
            .append(", \"name\": ").append(rec.sourceName?.let { js(it) } ?: "null").append(" },\n")
        sb.append("    \"appVersion\": ").append(js(appVersion)).append(",\n")
        sb.append("    \"assists\": { \"lexicon\": ").append(rec.assistLexicon)
            .append(", \"cutNumbers\": ").append(rec.assistCutNumbers).append(" },\n")
        val range = rec.wpmRange
        sb.append("    \"wpmRange\": ")
            .append(if (range != null) "[${fmt1(range.first)}, ${fmt1(range.second)}]" else "null")
            .append("\n  },\n")

        sb.append("  \"characters\": [\n")
        for ((i, rc) in rec.chars.withIndex()) {
            val c = rc.char
            sb.append("    {\n")
            sb.append("      \"index\": ").append(i).append(",\n")
            sb.append("      \"char\": ").append(js(c.token)).append(",\n")
            sb.append("      \"confidence\": ").append(fmt2(c.confidence)).append(",\n")
            sb.append("      \"tOffsetMs\": ").append(c.tStartMs.toLong()).append(",\n")
            sb.append("      \"elements\": [\n")
            val pattern = tokenToPattern[c.token]
            var markIdx = 0
            for ((ei, el) in c.elements.withIndex()) {
                if (el.isMark) {
                    val symbol = pattern?.getOrNull(markIdx)?.let { if (it == '.') "dot" else "dash" } ?: "unknown"
                    markIdx++
                    sb.append("        { \"type\": \"mark\", \"durationMs\": ")
                        .append(Math.round(el.durationMs)).append(", \"symbol\": ").append(js(symbol)).append(" }")
                } else {
                    val role = if (pattern != null) "intra" else "unknown"
                    sb.append("        { \"type\": \"gap\",  \"durationMs\": ")
                        .append(Math.round(el.durationMs)).append(", \"role\": ").append(js(role)).append(" }")
                }
                sb.append(if (ei < c.elements.size - 1) ",\n" else "\n")
            }
            sb.append("      ],\n")
            sb.append("      \"unitMsLocal\": ").append(fmt1(c.unitMsLocal)).append(",\n")
            sb.append("      \"unitMsGlobal\": ").append(fmt1(c.unitMsGlobal)).append(",\n")
            sb.append("      \"toneHz\": ").append(Math.round(rc.toneHz)).append(",\n")
            sb.append("      \"snrDb\": ").append(fmt1(rc.snrDb)).append(",\n")
            sb.append("      \"modelFitQuality\": ").append(fmt2(c.fitQuality)).append("\n")
            sb.append(if (i < rec.chars.size - 1) "    },\n" else "    }\n")
        }
        sb.append("  ],\n")

        sb.append("  \"annotations\": [\n")
        val annotations = ArrayList<String>()
        for ((i, rc) in rec.chars.withIndex()) {
            val c = rc.char
            if (c.token == RobustTimingDecoder.UNDECODABLE) {
                annotations.add("    { \"type\": \"undecodable\", \"charIndex\": $i }")
            }
            if (c.token == "<HH>") {
                annotations.add(
                    "    { \"type\": \"errorProsign\", \"charIndex\": $i, \"tOffsetMs\": ${c.tStartMs.toLong()} }"
                )
            }
            if (c.confidence < LOW_CONFIDENCE) {
                annotations.add(
                    "    { \"type\": \"lowConfidence\", \"charIndex\": $i, \"confidence\": ${fmt2(c.confidence)} }"
                )
            }
        }
        for (r in rec.relocks) {
            annotations.add(
                "    { \"type\": \"relock\", \"charIndex\": ${r.charIndex}, \"tOffsetMs\": ${r.tOffsetMs.toLong()}, " +
                    "\"fromHz\": ${Math.round(r.fromHz)}, \"toHz\": ${Math.round(r.toHz)}, \"trigger\": ${js(r.trigger)} }"
            )
        }
        sb.append(annotations.joinToString(",\n"))
        if (annotations.isNotEmpty()) sb.append('\n')
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    // ---- helpers ----

    private fun hhmmss(ms: Double): String {
        val s = (ms / 1000).toLong()
        return "%02d:%02d:%02d".format(Locale.ROOT, s / 3600, (s / 60) % 60, s % 60)
    }

    private fun fmt1(v: Double): String = "%.1f".format(Locale.ROOT, v)
    private fun fmt2(v: Double): String = "%.2f".format(Locale.ROOT, v)

    private fun js(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(Locale.ROOT, ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

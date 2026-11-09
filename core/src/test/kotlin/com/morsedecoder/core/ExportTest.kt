package com.morsedecoder.core

import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.export.SessionExport
import com.morsedecoder.core.export.SessionRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Milestone-5: the serialisers behind all three export destinations (FR-10). */
class ExportTest {

    private fun char(
        token: String,
        confidence: Double = 0.94,
        tStartMs: Double = 1840.0,
        isWordStart: Boolean = false,
        elements: List<FrontEnd.Run> = listOf(
            FrontEnd.Run(true, 1840.0, 182.0),
            FrontEnd.Run(false, 2022.0, 61.0),
            FrontEnd.Run(true, 2083.0, 59.0),
            FrontEnd.Run(false, 2142.0, 63.0),
            FrontEnd.Run(true, 2205.0, 178.0),
            FrontEnd.Run(false, 2383.0, 58.0),
            FrontEnd.Run(true, 2441.0, 62.0),
        ),
    ) = RobustTimingDecoder.DecodedChar(
        token = token, confidence = confidence, tStartMs = tStartMs,
        unitMsLocal = 60.4, unitMsRaw = 59.8, unitMsGlobal = 61.2, fitQuality = 0.91,
        isWordStart = isWordStart, elements = elements,
    )

    private fun session(): SessionRecorder {
        val rec = SessionRecorder(0L, "+03:00", "microphone", null)
        rec.addChar(char("C"), 612.0, 14.2)
        rec.addChar(char("Q", tStartMs = 2600.0), 612.0, 14.2)
        rec.addRelock(3000.0, 612.0, 745.0, "manual")
        rec.addChar(char("K", confidence = 0.38, tStartMs = 3600.0, isWordStart = true), 745.0, 12.0)
        rec.addChar(char("<HH>", tStartMs = 4400.0), 745.0, 12.0)
        return rec
    }

    @Test
    fun `json carries the schema of export-formats md (FR-10-4)`() {
        val json = SessionExport.toJson(session(), appVersion = "1.0.0")
        for (expected in listOf(
            "\"schemaVersion\": 1",
            "\"startedAt\": \"1970-01-01T03:00:00+03:00\"",
            "\"source\": { \"type\": \"microphone\", \"name\": null }",
            "\"assists\": { \"lexicon\": false, \"cutNumbers\": false }",
            "\"char\": \"C\"",
            // C = dash dot dash dot: raw durations AND the assigned symbols (corpus reuse)
            "{ \"type\": \"mark\", \"durationMs\": 182, \"symbol\": \"dash\" }",
            "{ \"type\": \"gap\",  \"durationMs\": 61, \"role\": \"intra\" }",
            "\"unitMsLocal\": 60.4",
            "\"unitMsGlobal\": 61.2",
            "\"toneHz\": 612",
            "\"snrDb\": 14.2",
            "\"modelFitQuality\": 0.91",
            // annotations: exactly the FR-9.10 vocabulary
            "{ \"type\": \"lowConfidence\", \"charIndex\": 2, \"confidence\": 0.38 }",
            "{ \"type\": \"errorProsign\", \"charIndex\": 3, \"tOffsetMs\": 4400 }",
            "{ \"type\": \"relock\", \"charIndex\": 2, \"tOffsetMs\": 3000, \"fromHz\": 612, \"toHz\": 745, \"trigger\": \"manual\" }",
        )) {
            assertTrue(json.contains(expected)) { "missing: $expected\nin:\n$json" }
        }
        // structural sanity
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test
    fun `txt has the header and the annotation vocabulary (FR-10-3)`() {
        val txt = SessionExport.toText(session(), includeHeader = true)
        assertTrue(txt.startsWith("# Morse decoder session")) { txt }
        assertTrue(txt.contains("# started    1970-01-01T03:00:00+03:00")) { txt }
        assertTrue(txt.contains("# source     microphone")) { txt }
        assertTrue(txt.contains("# duration   00:00:02")) { txt }
        // relock before K (word start), HH bracketed; low confidence OMITTED (FR-10.5)
        assertTrue(txt.endsWith("CQ | K [HH]")) { txt }
    }

    @Test
    fun `clipboard form is the txt body without the header (FR-10-3)`() {
        assertEquals("CQ | K [HH]", SessionExport.toText(session(), includeHeader = false))
    }

    @Test
    fun `cut numbers export expanded when the assist is on`() {
        val rec = SessionRecorder(0L, "+03:00", "microphone", null)
        rec.addChar(char("5", tStartMs = 100.0), 612.0, 14.0)
        rec.addChar(char("N", tStartMs = 400.0), 612.0, 14.0)
        rec.addChar(char("N", tStartMs = 700.0), 612.0, 14.0)
        rec.addChar(char("T", tStartMs = 1200.0, isWordStart = true), 612.0, 14.0)
        rec.addChar(char("N", tStartMs = 1500.0), 612.0, 14.0)
        rec.addChar(char("X", tStartMs = 1800.0), 612.0, 14.0)
        assertEquals("5NN TNX", SessionExport.toText(rec, includeHeader = false))
        rec.assistCutNumbers = true
        assertEquals("599 TNX", SessionExport.toText(rec, includeHeader = false))
    }
}

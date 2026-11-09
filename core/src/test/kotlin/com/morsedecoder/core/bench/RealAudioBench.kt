package com.morsedecoder.core.bench

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.audio.WavIo
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.metrics.TextMetrics
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Real-audio bench — both decode paths over recordings that live **outside the repo**.
 * Asserts nothing; skipped unless `REAL_DIR` is set.
 *
 *   REAL_DIR=/path/to/clips gradlew :core:test --tests "*RealAudioBench*" -i
 *   REAL_CHUNK=4096 ...      # feed size for the live path (default 1280 = the app's)
 *   REAL_CASES=other.tsv ... # case list, relative to REAL_DIR (default cases.tsv)
 *   REAL_REPORT=table.md ... # also write a markdown table, for TEST-STATUS.md
 *
 * The case list is tab-separated, `<wav path relative to REAL_DIR>\t<reference text>`;
 * an empty reference reports CER as n/a. `#` lines are comments. WAVs are read as-is, so
 * convert anything else to 16-bit PCM first.
 *
 * ### Why this exists
 *
 * `known-issues.md` G-1: every fixture in the suite is synthetic, and synthetic audio
 * does not fail the way real audio does. The decisive inputs here are the captures that
 * `tools/device-run.sh` pulls off a phone — the exact samples `MicCapture` handed the
 * engine (`DebugTap`) — because replaying one reproduces the device's decode on the JVM.
 * The audio is not committed (an open owner decision, `progress.md`), which is why the
 * location is a variable and not a fixture path.
 *
 * CER is normalised the same way as `tools/score-device.py`: upper case, spaces and
 * unsupported characters removed. Word spacing is a separate question from character
 * accuracy, and counting it would make a device run and this bench disagree.
 */
class RealAudioBench {

    private fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9?/.,=+\\-]"), "")

    private data class Run(
        val text: String, val tone: Double, val wpm: Double?, val chars: Int, val extra: String,
    )

    private fun live(x: FloatArray, chunk: Int): Run {
        val e = LiveDecoderEngine()
        var i = 0
        while (i < x.size) {
            val n = minOf(chunk, x.size - i)
            e.feed(x.copyOfRange(i, i + n), n)
            i += n
        }
        e.finish()
        val t = buildString {
            for ((k, c) in e.transcript.withIndex()) {
                if (k > 0 && c.isWordStart) append(' ')
                append(c.token)
            }
        }
        val auto = e.events.filterIsInstance<LiveDecoderEngine.Event.Relock>()
            .filter { it.trigger == LiveDecoderEngine.RelockTrigger.AUTOMATIC }
        val relocks = auto.joinToString(" ") { "%.0f@%.0fs".format(it.toHz, it.atMs / 1000) }
        return Run(t, e.toneHz, e.wpm, e.transcript.size,
            "lock=${e.lockState.name.take(4)} w=${"%.0f".format(e.weightMs)} relocks=${auto.size} $relocks".trimEnd())
    }

    private fun file(x: FloatArray, sr: Int): Run {
        val r = MorseDecoder().decodeFile(FloatArrayPcmSource(x, sr))
        return Run(r.text, r.toneHz, r.wpm, r.chars.size, "")
    }

    @Test
    fun bench() {
        val root = System.getenv("REAL_DIR")
        assumeTrue(root != null, "REAL_DIR not set")
        val dir = File(root!!)
        val chunk = System.getenv("REAL_CHUNK")?.toInt() ?: 1280
        val cases = File(dir, System.getenv("REAL_CASES") ?: "cases.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

        println("\nREAL-AUDIO BENCH  dir=$dir  live chunk=$chunk")
        println("%-34s %-4s %6s %6s %6s %5s  %s".format("file", "path", "CER%", "tone", "wpm", "chars", "decoded / notes"))
        val totals = mutableMapOf<String, MutableList<Double>>()
        // Markdown rows for TEST-STATUS.md: one row per clip, live (the app's path) first.
        val report = StringBuilder(
            "| clip | live CER | file CER | live tone Hz | live WPM | live decoded (start) |\n" +
                "|---|---|---|---|---|---|\n",
        )
        for (line in cases) {
            val parts = line.split('\t')
            val path = parts[0]
            val ref = parts.getOrElse(1) { "" }
            val wavFile = File(dir, path)
            if (!wavFile.exists()) { println("%-34s missing".format(path.takeLast(34))); continue }
            val wav = WavIo.read(wavFile)
            val runs = listOf("file" to file(wav.samples, wav.sampleRate), "live" to live(wav.samples, chunk))
            fun cerOf(r: Run) = if (ref.isBlank()) "—" else "%.1f %%".format(TextMetrics.cer(norm(ref), norm(r.text)) * 100)
            val lv = runs[1].second
            report.append("| %s | %s | %s | %.0f | %s | `%s` |\n".format(
                File(path).nameWithoutExtension, cerOf(lv), cerOf(runs[0].second), lv.tone,
                lv.wpm?.let { "%.1f".format(it) } ?: "—",
                lv.text.take(40).ifBlank { "(nothing)" }.replace("|", "/")))
            for ((p, r) in runs) {
                val cer = if (ref.isBlank()) Double.NaN else TextMetrics.cer(norm(ref), norm(r.text))
                if (!cer.isNaN()) totals.getOrPut(p) { mutableListOf() }.add(cer)
                println("%-34s %-4s %6s %6.0f %6s %5d  %s  %s".format(
                    path.takeLast(34), p, if (cer.isNaN()) "n/a" else "%.1f".format(cer * 100),
                    r.tone, r.wpm?.let { "%.1f".format(it) } ?: "-", r.chars,
                    r.text.take(70), r.extra))
            }
        }
        for ((p, v) in totals) {
            println("mean CER %-4s %5.1f %%   worst %5.1f %%   n=%d".format(p, v.average() * 100, v.max() * 100, v.size))
        }
        System.getenv("REAL_REPORT")?.let { out ->
            val mean = { p: String -> totals[p]?.let { "%.1f %%".format(it.average() * 100) } ?: "—" }
            report.append("| **mean** | **${mean("live")}** | **${mean("file")}** | | | |\n")
            File(out).writeText(report.toString())
            println("report written to $out")
        }
    }
}

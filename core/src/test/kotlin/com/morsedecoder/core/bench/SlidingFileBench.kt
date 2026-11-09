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
 * Architecture probe: would the live path do better as a **sliding file decode** than as
 * the streaming [LiveDecoderEngine]? Asserts nothing; skipped unless `SLIDE_DIR` is set.
 *
 *   SLIDE_DIR=<dir> [SLIDE_CASES=cases.tsv] [SLIDE_WIN_S=30] [SLIDE_STEP_S=1] [SLIDE_LAG_S=2]
 *     gradlew :core:test --tests "*SlidingFileBench*" -i
 *
 * Every `STEP` seconds of audio, the two-pass [MorseDecoder] decodes the last `WIN` seconds,
 * and characters whose start lies between the previous commit point and `now - LAG` are
 * appended. Nothing appended is ever revised, so the transcript stays append-only: the
 * price is `LAG` seconds of display latency. Case list format as [RealAudioBench].
 */
class SlidingFileBench {

    private fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9?/.,=+\\-]"), "")

    private fun live(x: FloatArray): String {
        val e = LiveDecoderEngine()
        var i = 0
        while (i < x.size) { val n = minOf(1280, x.size - i); e.feed(x.copyOfRange(i, i + n), n); i += n }
        e.finish()
        return e.transcript.joinToString("") { (if (it.isWordStart) " " else "") + it.token }
    }

    private fun sliding(x: FloatArray, sr: Int, winS: Double, stepS: Double, lagS: Double): Pair<String, Double> {
        val out = StringBuilder()
        var committedMs = 0.0
        val totalMs = x.size * 1000.0 / sr
        var tMs = stepS * 1000
        var worstCallMs = 0.0
        while (true) {
            val last = tMs >= totalMs
            val now = minOf(tMs, totalMs)
            val w0 = maxOf(0.0, now - winS * 1000)
            val a = (w0 * sr / 1000).toInt()
            val b = (now * sr / 1000).toInt()
            val t0 = System.nanoTime()
            val r = runCatching { MorseDecoder().decodeFile(FloatArrayPcmSource(x.copyOfRange(a, b), sr)) }.getOrNull()
            worstCallMs = maxOf(worstCallMs, (System.nanoTime() - t0) / 1e6)
            val upTo = if (last) Double.MAX_VALUE else now - lagS * 1000
            if (r != null) {
                for (c in r.chars) {
                    val abs = w0 + c.tStartMs
                    if (abs >= committedMs && abs < upTo) out.append(c.token)
                }
            }
            committedMs = maxOf(committedMs, upTo)
            if (last) break
            tMs += stepS * 1000
        }
        return out.toString() to worstCallMs
    }

    @Test
    fun bench() {
        val root = System.getenv("SLIDE_DIR")
        assumeTrue(root != null, "SLIDE_DIR not set")
        val dir = File(root!!)
        val win = System.getenv("SLIDE_WIN_S")?.toDouble() ?: 30.0
        val step = System.getenv("SLIDE_STEP_S")?.toDouble() ?: 1.0
        val lag = System.getenv("SLIDE_LAG_S")?.toDouble() ?: 2.0
        val cases = File(dir, System.getenv("SLIDE_CASES") ?: "cases.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        println("\nSLIDING-FILE BENCH  win=${win}s step=${step}s lag=${lag}s")
        println("%-34s %7s %7s %7s %8s  %s".format("file", "live", "slide", "file", "worst ms", "slide text"))
        val tot = mutableMapOf<String, MutableList<Double>>()
        for (line in cases) {
            val p = line.split('\t')
            val f = File(dir, p[0])
            if (!f.exists()) continue
            val ref = norm(p.getOrElse(1) { "" })
            val wav = WavIo.read(f)
            val lv = live(wav.samples)
            val (sl, worst) = sliding(wav.samples, wav.sampleRate, win, step, lag)
            val fl = MorseDecoder().decodeFile(FloatArrayPcmSource(wav.samples, wav.sampleRate)).text
            fun c(k: String, s: String): String {
                if (ref.isEmpty()) return "${norm(s).length}ch"
                val v = TextMetrics.cer(ref, norm(s)); tot.getOrPut(k) { mutableListOf() }.add(v)
                return "%.1f".format(v * 100)
            }
            println("%-34s %7s %7s %7s %8.0f  %s".format(p[0].takeLast(34), c("live", lv), c("slide", sl),
                c("file", fl), worst, sl.take(60)))
        }
        for ((k, v) in tot) println("mean CER %-5s %5.1f %%  worst %5.1f %%  n=%d".format(k, v.average() * 100, v.max() * 100, v.size))
    }
}

package com.morsedecoder.core.bench

import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.ElementKind
import com.morsedecoder.core.synth.SyntheticMorseGenerator.TimedElement
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * Bootstrap diagnostic — answers the question known-issues D-6 leaves open: is the
 * two-means seed in [LiveDecoderEngine.initFromBootstrap] splitting dots from dashes, or
 * splitting the dot cluster in half?
 *
 *   DIAG=1 gradlew :core:test --tests "*BootstrapDiagnostic*" -i
 *
 * Asserts nothing and is skipped unless `DIAG` is set. It is an instrument, not a guard.
 *
 * ### How it sees the bootstrap without touching the engine
 *
 * `bootstrapRuns` is cleared the instant the seed is computed, so polling for it races.
 * Instead the engine is run once with `bootstrapMinMarks` set past reach: the seed never
 * fires, and every run the segmenter produces accumulates in that list instead. Nothing
 * upstream of the seed depends on the threshold, and `pushRun`'s glitch gate is the
 * pre-bootstrap constant (10 ms) either way, so the runs collected are the ones the real
 * engine would have seeded from. A second, unmodified engine is then run to the moment of
 * the seed and its `kalman.unitMs` read back, which confirms the replication rather than
 * assuming it.
 *
 * Ground truth comes from the generator's own element list, matched to runs by time
 * overlap, so a mark can be reported as what it actually was rather than what its duration
 * suggests.
 */
class BootstrapDiagnostic {

    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    private data class Case(val name: String, val cfg: Config)

    private fun cases() = listOf(
        Case("hand-13-rough", Config(text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
            dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L)),
        // Controls: a healthy bootstrap and a middling one, so the numbers above have
        // something to be compared against.
        Case("hand-18", Config(text = text, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
            dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L)),
        Case("clean-20", Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 20.0, seed = 11L)),
    )

    // ---- reflection ----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <T> peek(target: Any, name: String): T {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as T
    }

    // ---- the seed, replicated verbatim from initFromBootstrap -----------------------

    private data class Seed(val c1: Double, val c2: Double) {
        val u0: Double get() = exp(c1)
        val dashOffset: Double get() = if (c2 - c1 >= ln(1.8)) c2 - c1 else ln(3.0)
        /** What the seed believes the dash/dot ratio is. */
        val ratio: Double get() = exp(dashOffset)
    }

    private fun twoMeans(markMs: List<Double>): Seed {
        val marks = markMs.map { ln(it) }
        var c1 = marks.min()
        var c2 = marks.max()
        repeat(20) {
            var s1 = 0.0; var n1 = 0
            var s2 = 0.0; var n2 = 0
            for (v in marks) {
                if (v - c1 <= c2 - v) { s1 += v; n1++ } else { s2 += v; n2++ }
            }
            if (n1 > 0) c1 = s1 / n1
            if (n2 > 0) c2 = s2 / n2
        }
        return Seed(c1, c2)
    }

    // ---- ground-truth attribution ---------------------------------------------------

    private data class Truth(val label: String, val trueMs: Double, val covered: Double)

    /** What a run actually was, by time overlap with the generator's element list. */
    private fun attribute(run: FrontEnd.Run, marks: List<TimedElement>): Truth {
        val end = run.startMs + run.durationMs
        val hits = marks.filter { it.startMs < end && it.endMs > run.startMs }
        if (hits.isEmpty()) return Truth("noise", 0.0, 0.0)
        if (hits.size > 1) {
            val span = hits.sumOf { it.durationMs }
            return Truth("merge:${hits.size}", span, run.durationMs / span)
        }
        val t = hits[0]
        val overlap = minOf(end, t.endMs) - maxOf(run.startMs, t.startMs)
        val covered = overlap / t.durationMs
        val kind = if (t.kind == ElementKind.MARK_DOT) "dot" else "dash"
        return Truth(if (covered < 0.6) "frag:$kind" else kind, t.durationMs, covered)
    }

    private fun median(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sorted()[v.size / 2]

    // ---- run ------------------------------------------------------------------------

    /** Collect every run the segmenter produces, by making the seed unreachable. */
    private fun allRuns(cfg: Config, samples: FloatArray): List<FrontEnd.Run> {
        // Dead-end recovery is disabled alongside: a collector that never seeds also never
        // emits a character, so D-13 would judge the lock unproven, re-lock, and clear the
        // very list being collected (`LiveDecoderEngine.kt:938`). Neither switch can reach
        // the first eight marks, which arrive seconds before either could fire.
        val engine = LiveDecoderEngine(
            cfg = LiveDecoderEngine.Config(
                bootstrapMinMarks = Int.MAX_VALUE,
                unprovenLockMs = Double.MAX_VALUE,
                unkeyedLockSweeps = Int.MAX_VALUE,
            ),
        )
        feed(engine, samples, 4096)
        engine.finish()
        return peek<ArrayList<FrontEnd.Run>>(engine, "bootstrapRuns").toList()
    }

    /**
     * Run an unmodified engine only as far as the seed, and report the unit it took.
     *
     * Chunked identically to [allRuns] and for a load-bearing reason: while unlocked,
     * `feed` resets the frame grid to the chunk boundary and the sweep loop is evaluated
     * only after the whole chunk lands, so the acquisition instant — and with it the first
     * runs — moves with the caller's buffer size. Comparing across two chunk sizes
     * compares two different signals. [chunkSensitivity] measures how much that matters.
     */
    private fun seededUnitMs(samples: FloatArray, chunk: Int = 4096): Double? {
        val engine = LiveDecoderEngine()
        var i = 0
        while (i < samples.size) {
            val n = minOf(chunk, samples.size - i)
            engine.feed(samples.copyOfRange(i, i + n), n)
            i += n
            val dec = peek<Any?>(engine, "decoder")
            if (dec != null) {
                val k = peek<Any?>(engine, "kalman") ?: return null
                val f = k.javaClass.getDeclaredField("unitMs")
                f.isAccessible = true
                return f.get(k) as Double
            }
        }
        return null
    }

    private fun feed(engine: LiveDecoderEngine, samples: FloatArray, chunk: Int) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(chunk, samples.size - i)
            engine.feed(samples.copyOfRange(i, i + n), n)
            i += n
        }
    }

    /**
     * How much of the decode is decided by the caller's buffer size alone. Same audio,
     * same build, same config — only the slice length changes.
     */
    private fun chunkSensitivity(case: Case, samples: FloatArray, reference: String) {
        println()
        println("chunk sensitivity (same audio, only the feed slice differs):")
        println("  %-10s %-8s %-10s %-9s %s".format("chunk", "chars", "CER", "seed u0", "wpm"))
        for (chunk in intArrayOf(512, 1024, 2048, 4096, 8192)) {
            val engine = LiveDecoderEngine()
            feed(engine, samples, chunk)
            engine.finish()
            val decoded = buildString {
                for ((k, c) in engine.transcript.withIndex()) {
                    if (k > 0 && c.isWordStart) append(' ')
                    append(c.token)
                }
            }
            val u0 = seededUnitMs(samples, chunk)
            println("  %-10d %-8d %-10s %-9s %s".format(
                chunk,
                engine.transcript.size,
                "%.1f %%".format(com.morsedecoder.core.metrics.TextMetrics.cer(reference, decoded) * 100),
                u0?.let { "%.1f ms".format(it) } ?: "none",
                engine.wpm?.let { "%.1f".format(it) } ?: "—"))
        }
    }

    @Test
    fun diagnose() {
        if (System.getenv("DIAG") == null) return

        for (case in cases()) {
            val gen = SyntheticMorseGenerator.generate(case.cfg)
            val trueMarks = gen.elements.filter { it.isMark }
            val trueDots = trueMarks.filter { it.kind == ElementKind.MARK_DOT }
            val trueDashes = trueMarks.filter { it.kind == ElementKind.MARK_DASH }
            val trueUnit = 1200.0 / case.cfg.wpm

            println()
            println("=".repeat(94))
            println("${case.name}  —  ${case.cfg.wpm} WPM, unit ${"%.1f".format(trueUnit)} ms, " +
                "ratio ${case.cfg.dashDotRatio}:1, jitter ${(case.cfg.jitterFraction * 100).toInt()} %")
            println("=".repeat(94))

            println("ground truth   dots %3d  median %6.1f ms  [%.0f–%.0f]".format(
                trueDots.size, median(trueDots.map { it.durationMs }),
                trueDots.minOf { it.durationMs }, trueDots.maxOf { it.durationMs }))
            println("               dash %3d  median %6.1f ms  [%.0f–%.0f]".format(
                trueDashes.size, median(trueDashes.map { it.durationMs }),
                trueDashes.minOf { it.durationMs }, trueDashes.maxOf { it.durationMs }))

            val runs = allRuns(case.cfg, gen.samples)
            val markRuns = runs.filter { it.isMark }
            println("segmenter      %d mark runs against %d true marks  (%.2f×)  median %.1f ms".format(
                markRuns.size, trueMarks.size,
                markRuns.size.toDouble() / trueMarks.size, median(markRuns.map { it.durationMs })))

            // The seed input: the first `bootstrapMinMarks` marks, exactly as the engine
            // would have taken them.
            val n = LiveDecoderEngine.Config().bootstrapMinMarks
            val seedRuns = markRuns.take(n)
            if (seedRuns.size < n) {
                println("!! only ${seedRuns.size} marks available")
                continue
            }

            println()
            println("the %d marks the seed is computed from:".format(n))
            println("  %-6s %-9s %-10s %-8s %s".format("#", "durMs", "was", "trueMs", "covered"))
            for ((k, r) in seedRuns.withIndex()) {
                val t = attribute(r, trueMarks)
                println("  %-6d %-9.1f %-10s %-8.1f %.2f".format(
                    k + 1, r.durationMs, t.label, t.trueMs, t.covered))
            }

            val seed = twoMeans(seedRuns.map { it.durationMs })
            val lower = seedRuns.filter { ln(it.durationMs) - seed.c1 <= seed.c2 - ln(it.durationMs) }
            val upper = seedRuns - lower.toSet()

            println()
            println("two-means      c1 %.1f ms (%d marks)   c2 %.1f ms (%d marks)   ratio %.2f:1".format(
                exp(seed.c1), lower.size, exp(seed.c2), upper.size, seed.ratio))
            println("               lower cluster was: ${lower.joinToString(", ") { attribute(it, trueMarks).label }}")
            println("               upper cluster was: ${upper.joinToString(", ") { attribute(it, trueMarks).label }}")

            val u0 = seed.u0
            println()
            println("seeded unit    u0 %.1f ms  →  %.1f WPM   (true %.1f ms, %.1f WPM)   error %.2f×".format(
                u0, 1200.0 / u0, trueUnit, case.cfg.wpm, trueUnit / u0))

            // The engine's own number, read at the first moment it exists. It is the seed
            // after `initFromBootstrap` has replayed the bootstrap runs through the
            // decoder, so the Kalman has already taken its first updates — a few percent
            // of drift from the raw seed is agreement, not disagreement.
            val actual = seededUnitMs(gen.samples)
            val agrees = actual != null && kotlin.math.abs(actual - u0) / u0 < 0.15
            println("engine took    ${actual?.let { "%.1f ms  →  %.1f WPM".format(it, 1200.0 / it) } ?: "no seed"}" +
                "   ${if (agrees) "(agrees, post-replay)" else "(DIVERGES)"}")

            // The counterfactual: what the seed would have been had it seen the true
            // durations of those same elements rather than the segmenter's.
            val ideal = twoMeans(seedRuns.map { attribute(it, trueMarks).trueMs }.filter { it > 0 })
            println("if fed truth   u0 %.1f ms  →  %.1f WPM   ratio %.2f:1".format(
                ideal.u0, 1200.0 / ideal.u0, ideal.ratio))

            chunkSensitivity(case, gen.samples, gen.normalizedText)
        }
        println()
    }
}

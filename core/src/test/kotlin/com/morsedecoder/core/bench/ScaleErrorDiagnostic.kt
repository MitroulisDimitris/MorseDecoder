package com.morsedecoder.core.bench

import com.morsedecoder.core.fixtures.NonMorseAudio
import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Hesitation
import com.morsedecoder.core.synth.SyntheticMorseGenerator.SpeedDrift
import org.junit.jupiter.api.Test

/**
 * What could see a unit-scale error (`known-issues.md` D-1), measured before anything is
 * built on it.
 *
 *   DIAG=1 gradlew :core:test --tests "*ScaleErrorDiagnostic*" -i
 *
 * ### The question
 *
 * `fitQuality` cannot see a scale error and no threshold on it ever will: Morse timing is
 * **scale-free**. Marks are 1 and 3 units, gaps 1, 3 and 7, and every one of those is a
 * ratio. Multiply the unit by two and every ratio survives — which is why `fitQuality`
 * (separation x ratio x tightness) reads 1.00 on a decode that is 297 % wrong. Any
 * detector has to break the scale-freedom somewhere, and there is only one place where it
 * is genuinely broken: **the decoded text**. Real text is not 90 % `E`/`T`/`I`/`M`; a
 * transcript decoded against a unit an octave short is nothing else, because every dash
 * reads as a run of dots and every character shatters.
 *
 * ### Why this table and not a threshold
 *
 * Lesson 5 of `progress.md`: the D-8 structure gate, fitted to these same 16 synthetic
 * rows, lands on a threshold that rejects genuine hand-sent Morse and accepts RTTY. So a
 * statistic is only worth building on if it separates with **margin** across the whole
 * corpus at once — the healthy scenarios, the rough fists, the non-Morse fixtures, and the
 * injected scale errors — and the margin is what this prints. Nothing here asserts.
 *
 * The positive population is injected through FR-9.5's WPM pin (step 4a), because no
 * fixture bootstraps a scale error any more. The one real instance on record is the field
 * recording, at 30.6 WPM against a true 17.7, whose transcript reads
 * `IEE ETI E TEE ETEE HT TE` — the same signature, which is the evidence that this
 * statistic is not an artefact of the injection.
 */
class ScaleErrorDiagnostic {

    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    private data class Scenario(val name: String, val cfg: Config)

    private fun scenarios(): List<Scenario> = listOf(
        Scenario("clean-20", Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 20.0, seed = 11L)),
        Scenario("ac1-20@10", Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L)),
        Scenario("ac2-20@0", Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 0.0, seed = 13L)),
        Scenario("noise-20@-5", Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = -5.0, seed = 14L)),
        Scenario("hand-18", Config(text = text, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
            dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L)),
        Scenario("hand-13-rough", Config(text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
            dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L)),
        Scenario("slow-7", Config(text = text, wpm = 7.0, toneHz = 612.0, snrDb = 10.0, seed = 17L)),
        Scenario("fast-35", Config(text = text, wpm = 35.0, toneHz = 612.0, snrDb = 10.0, seed = 18L)),
        Scenario("farnsworth", Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
            farnsworthSpacingWpm = 8.0, seed = 19L)),
        Scenario("drift-ramp", Config(text = text, wpm = 15.0, toneHz = 612.0, snrDb = 10.0,
            drift = SpeedDrift.LinearRamp(25.0, 60.0), seed = 20L)),
        Scenario("wobble", Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
            drift = SpeedDrift.SineWobble(0.15, 0.05), seed = 21L)),
        Scenario("hesitation", Config(text = text, wpm = 16.0, toneHz = 612.0, snrDb = 10.0,
            hesitation = Hesitation(), seed = 22L)),
        Scenario("qsb-impulse", Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 8.0,
            fadingDepth = 0.6, fadingRateHz = 0.15, impulsesPerSecond = 2.0, seed = 23L)),
        Scenario("tone-1882", Config(text = text, wpm = 18.0, toneHz = 1882.0, snrDb = 10.0, seed = 24L)),
        Scenario("tone-420", Config(text = text, wpm = 18.0, toneHz = 420.0, snrDb = 10.0, seed = 25L)),
        Scenario("tone-700-mains", Config(text = text, wpm = 18.0, toneHz = 700.0, snrDb = 10.0, seed = 26L)),
    )

    private class Stats(
        val chars: Int,
        val cer: Double,
        val wpm: Double?,
        val fit: Double?,
        val suppressed: Boolean,
        /** Mean marks per committed character. Real text sits near 3; a scale error near 1. */
        val meanMarks: Double,
        /** Share of committed characters carrying a single mark — `E` and `T`. */
        val singles: Double,
        /** Share whose token is one of E/T/I/M — D-1's "90 % E/T/I/M" phrasing. */
        val etim: Double,
        /**
         * Mean per-character lattice confidence (FR-6.6). Not a scale-error signal — it is
         * here to answer the *other* half of D-1: does anything the decoder already
         * computes correlate with how wrong the transcript is?
         */
        val conf: Double,
        /**
         * Lowest rolling-window mean over the stream, per window size. This — not the
         * whole-transcript mean — is what a live flag actually judges, and it is where a
         * false positive would come from: a healthy decode that dips through a run of
         * short words.
         */
        val rollingMin: Map<Int, Double>,
        /** The same running minimum, taken over an EWMA of the given nominal length. */
        val ewmaMin: Map<Int, Double>,
    )

    private val alphas = listOf(24, 32, 48)

    private val windows = listOf(16, 24, 32, 48)

    /**
     * Lowest value an exponentially-weighted mean reaches, after a warmup of `1/alpha`
     * characters. A boxcar gives every character in the window equal say and drops it
     * abruptly; a scale error is a *sustained* condition, so the question is whether
     * weighting history more smoothly separates the two populations better.
     */
    private fun ewmaMin(marks: List<Int>, alpha: Double): Double {
        val warmup = (1.0 / alpha).toInt()
        if (marks.size <= warmup) return Double.NaN
        var e = marks.take(warmup).average()
        var min = Double.MAX_VALUE
        for (i in warmup until marks.size) {
            e += alpha * (marks[i] - e)
            min = minOf(min, e)
        }
        return min
    }

    /** Lowest mean over any [window] consecutive characters; NaN if the stream is shorter. */
    private fun rollingMin(marks: List<Int>, window: Int): Double {
        if (marks.size < window) return Double.NaN
        var sum = marks.take(window).sum()
        var min = sum.toDouble() / window
        for (i in window until marks.size) {
            sum += marks[i] - marks[i - window]
            min = minOf(min, sum.toDouble() / window)
        }
        return min
    }

    private fun run(samples: FloatArray, reference: String, pinWpm: Double?): Stats {
        val engine = LiveDecoderEngine()
        if (pinWpm != null) engine.pinWpm(pinWpm)
        var i = 0
        while (i < samples.size) {
            val n = minOf(4096, samples.size - i)
            engine.feed(samples.copyOfRange(i, i + n), n)
            i += n
        }
        engine.finish()
        val decoded = buildString {
            for ((k, c) in engine.transcript.withIndex()) {
                if (k > 0 && c.isWordStart) append(' ')
                append(c.token)
            }
        }
        val t = engine.transcript
        val marks = t.map { c -> c.elements.count { it.isMark } }
        return Stats(
            chars = t.size,
            // Nothing was sent on the non-Morse fixtures, so there is no rate to quote.
            cer = if (reference.isEmpty()) Double.NaN else TextMetrics.cer(reference, decoded),
            wpm = engine.wpm,
            fit = engine.fitQuality,
            suppressed = engine.outputSuppressed,
            meanMarks = if (marks.isEmpty()) Double.NaN else marks.average(),
            singles = if (marks.isEmpty()) Double.NaN else marks.count { it <= 1 }.toDouble() / marks.size,
            etim = if (t.isEmpty()) Double.NaN else t.count { it.token in setOf("E", "T", "I", "M") }
                .toDouble() / t.size,
            conf = if (t.isEmpty()) Double.NaN else t.map { it.confidence }.average(),
            rollingMin = windows.associateWith { rollingMin(marks, it) },
            ewmaMin = alphas.associateWith { ewmaMin(marks, 1.0 / it) },
        )
    }

    private fun num(v: Double, dp: Int) = if (v.isNaN()) "n/a" else "%.${dp}f".format(v)

    private fun row(label: String, s: Stats) {
        println(
            "%-22s %5d %7s %7s %7s %7s %8s %8s %s  %s".format(
                label,
                s.chars,
                if (s.cer.isNaN()) "n/a" else "%.1f".format(100 * s.cer),
                s.wpm?.let { "%.1f".format(it) } ?: "-",
                s.fit?.let { "%.2f".format(it) } ?: "-",
                num(s.meanMarks, 2),
                num(s.conf, 3),
                num(s.etim, 3),
                windows.joinToString(" ") { "%7s".format(num(s.rollingMin.getValue(it), 2)) } +
                    "  " + alphas.joinToString(" ") { "%7s".format(num(s.ewmaMin.getValue(it), 2)) },
                if (s.suppressed) "suppressed" else "",
            )
        )
    }

    private fun header(title: String) {
        println()
        println(title)
        println(
            "%-22s %5s %7s %7s %7s %7s %8s %8s %s".format(
                "fixture", "chars", "CER%", "wpm", "fit", "marks", "conf", "etim",
                windows.joinToString(" ") { "%7s".format("box$it") } +
                    "  " + alphas.joinToString(" ") { "%7s".format("ewma$it") },
            )
        )
        println("-".repeat(120))
    }

    @Test
    fun diagnose() {
        if (System.getenv("DIAG") == null) return
        val scenarios = scenarios()
        val audio = scenarios.associate { it.name to SyntheticMorseGenerator.generate(it.cfg) }

        println()
        println("=".repeat(96))
        println("SCALE-ERROR DIAGNOSTIC — what separates a wrong unit from a right one (D-1)")
        println("marks = mean marks per committed char, singles = share with one mark, etim = share E/T/I/M")
        println("=".repeat(96))

        val healthy = ArrayList<Stats>()
        header("A · HEALTHY — the decoder's own unit (the false-positive population)")
        for (sc in scenarios) {
            val gen = audio.getValue(sc.name)
            val s = run(gen.samples, gen.normalizedText, null)
            healthy.add(s)
            row(sc.name, s)
        }

        val fast = ArrayList<Stats>()
        header("B · PINNED 2x FAST — an injected octave error (the true-positive population)")
        for (sc in scenarios) {
            val gen = audio.getValue(sc.name)
            val trueWpm = sc.cfg.wpm
            val s = run(gen.samples, gen.normalizedText, 2.0 * trueWpm)
            fast.add(s)
            row("${sc.name} @${"%.0f".format(2 * trueWpm)}", s)
        }

        val slow = ArrayList<Stats>()
        header("C · PINNED 2x SLOW — the other direction, which the lattice largely absorbs")
        for (sc in scenarios) {
            val gen = audio.getValue(sc.name)
            val s = run(gen.samples, gen.normalizedText, 0.5 * sc.cfg.wpm)
            slow.add(s)
            row("${sc.name} @${"%.0f".format(0.5 * sc.cfg.wpm)}", s)
        }

        header("D · NON-MORSE — nothing was sent, so any output is spurious")
        for ((name, samples) in NonMorseAudio.corpus()) {
            row(name, run(samples, "", null))
        }

        println()
        println("=".repeat(96))
        println("SEPARATION — over rows that emitted at least 20 characters")
        println("=".repeat(96))
        fun band(label: String, rows: List<Stats>, pick: (Stats) -> Double) {
            val v = rows.filter { it.chars >= 20 }.map(pick).filter { !it.isNaN() }.sorted()
            if (v.isEmpty()) { println("%-28s (no rows)".format(label)); return }
            println(
                "%-28s n=%-3d min %.3f   p50 %.3f   max %.3f".format(
                    label, v.size, v.first(), v[v.size / 2], v.last()
                )
            )
        }
        for ((stat, pick) in listOf<Pair<String, (Stats) -> Double>>(
            "meanMarks" to { it.meanMarks },
            "singles" to { it.singles },
            "etim" to { it.etim },
            "confidence" to { it.conf },
        )) {
            println()
            band("$stat · healthy", healthy, pick)
            band("$stat · pinned 2x fast", fast, pick)
            band("$stat · pinned 2x slow", slow, pick)
        }

        // The decision table: a live flag judges a rolling window, so the margin that
        // matters is between the *lowest* healthy window and the *highest* scale-error one.
        println()
        println("-".repeat(96))
        println("ROLLING-WINDOW MARGIN — the gap a threshold has to sit in")
        println("(the 2x-slow column is the merge mode — bad decodes this statistic does NOT see)")
        val estimators: List<Pair<String, (Stats) -> Double>> =
            windows.map { w -> "boxcar $w" to { s: Stats -> s.rollingMin.getValue(w) } } +
                alphas.map { a -> "ewma   $a" to { s: Stats -> s.ewmaMin.getValue(a) } }
        for ((name, pick) in estimators) {
            fun mins(rows: List<Stats>) = rows.map(pick).filter { !it.isNaN() }
            val h = mins(healthy)
            val f = mins(fast)
            val s = mins(slow)
            println(
                "%-10s  healthy min %.2f (n=%d)   2x-fast max %.2f (n=%d)   margin %.2fx   2x-slow max %.2f".format(
                    name,
                    h.minOrNull() ?: Double.NaN, h.size,
                    f.maxOrNull() ?: Double.NaN, f.size,
                    (h.minOrNull() ?: Double.NaN) / (f.maxOrNull() ?: Double.NaN),
                    s.maxOrNull() ?: Double.NaN,
                )
            )
        }
        println()
    }
}

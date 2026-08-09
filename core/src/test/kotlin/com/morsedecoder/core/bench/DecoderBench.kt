package com.morsedecoder.core.bench

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.MorseDecoder
import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Hesitation
import com.morsedecoder.core.synth.SyntheticMorseGenerator.SpeedDrift
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Decoder bench — a wide, fast sweep of the decoder against a scenario matrix, printed as
 * a table. Not a test: it asserts nothing and is skipped unless `BENCH` is set.
 *
 *   BENCH=1 gradlew :core:test --tests "*DecoderBench*" -i
 *   BENCH=1 BENCH_CSV=bench.csv gradlew :core:test --tests "*DecoderBench*" -i
 *
 * ### Why this exists
 *
 * The §6 acceptance criteria are pass/fail at ten fixed points. That makes them good
 * regression guards and poor instruments: they cannot say whether a change made the
 * decoder *broadly* better or worse, only whether it crossed one of ten lines. A change
 * can degrade every scenario by 4 points and still show ten green ticks.
 *
 * So this sweeps the space the ACs sample — speed, SNR, fist quality, drift, tone
 * frequency, interference — and reports character error rate for each, with no thresholds
 * and no verdicts. Reading two runs side by side is the point.
 *
 * ### Ablation, not archaeology
 *
 * Every behaviour added in D-13 is config-gated, so "was this better before?" is answered
 * by re-running the same build with a flag flipped rather than by checking out an old
 * commit. Each [Variant] below turns off exactly one thing, which is what lets a
 * regression be attributed rather than merely detected.
 *
 * The file path is run once per scenario as a reference column: it is two-pass (FM-1) and
 * has no warmup, so it is the ceiling the streaming path is trying to reach.
 */
class DecoderBench {

    // Shorter than AcceptanceTest.LONG_TEXT: the bench trades a little CER resolution for
    // being runnable in the time a person will actually wait.
    private val text =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"

    private data class Scenario(val name: String, val note: String, val cfg: Config)

    private data class Variant(val name: String, val cfg: LiveDecoderEngine.Config)

    private data class Run(
        val cer: Double,
        val chars: Int,
        val toneErrHz: Double,
        val wpm: Double?,
        val lock: String,
        val suppressed: Boolean,
    )

    // ---- the matrix ---------------------------------------------------------------

    private fun scenarios(): List<Scenario> = listOf(
        Scenario("clean-20", "machine, +20 dB — the easy case",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 20.0, seed = 11L)),
        Scenario("ac1-20@10", "machine, +10 dB (AC-1 point)",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 10.0, seed = 12L)),
        Scenario("ac2-20@0", "machine, 0 dB (AC-2 point)",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = 0.0, seed = 13L)),
        Scenario("noise-20@-5", "machine, -5 dB — below the promised floor",
            Config(text = text, wpm = 20.0, toneHz = 612.0, snrDb = -5.0, seed = 14L)),
        Scenario("hand-18", "20 % jitter, 2.2:1 fist (AC-3 point)",
            Config(text = text, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
                dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 15L)),
        Scenario("hand-13-rough", "25 % jitter, heavy per-char bias, +5 dB",
            Config(text = text, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
                dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L)),
        Scenario("slow-7", "7 WPM — near the bottom of the calibrated band",
            Config(text = text, wpm = 7.0, toneHz = 612.0, snrDb = 10.0, seed = 17L)),
        Scenario("fast-35", "35 WPM — keyer territory",
            Config(text = text, wpm = 35.0, toneHz = 612.0, snrDb = 10.0, seed = 18L)),
        Scenario("farnsworth", "18 WPM elements, 8 WPM spacing",
            Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
                farnsworthSpacingWpm = 8.0, seed = 19L)),
        Scenario("drift-ramp", "15 → 25 WPM over 60 s (AC-5/6 shape)",
            Config(text = text, wpm = 15.0, toneHz = 612.0, snrDb = 10.0,
                drift = SpeedDrift.LinearRamp(25.0, 60.0), seed = 20L)),
        Scenario("wobble", "±15 % at 0.05 Hz",
            Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 10.0,
                drift = SpeedDrift.SineWobble(0.15, 0.05), seed = 21L)),
        Scenario("hesitation", "human pauses at word and character boundaries",
            Config(text = text, wpm = 16.0, toneHz = 612.0, snrDb = 10.0,
                hesitation = Hesitation(), seed = 22L)),
        Scenario("qsb-impulse", "fading + static crashes — the on-air case",
            Config(text = text, wpm = 18.0, toneHz = 612.0, snrDb = 8.0,
                fadingDepth = 0.6, fadingRateHz = 0.15, impulsesPerSecond = 2.0, seed = 23L)),
        Scenario("tone-1882", "the field report's frequency, above the old ceiling",
            Config(text = text, wpm = 18.0, toneHz = 1882.0, snrDb = 10.0, seed = 24L)),
        Scenario("tone-420", "low in the band",
            Config(text = text, wpm = 18.0, toneHz = 420.0, snrDb = 10.0, seed = 25L)),
        // 700 Hz is a multiple of both 50 and 60, so FR-2.5's veto forbids auto-locking it.
        // Included deliberately: it is the most common real sidetone and known-issues D-3
        // predicts it cannot lock. The bench is where that prediction gets checked.
        Scenario("tone-700-mains", "700 Hz — a mains multiple, see known-issues D-3",
            Config(text = text, wpm = 18.0, toneHz = 700.0, snrDb = 10.0, seed = 26L)),
    )

    private fun variants(): List<Variant> {
        val d = LiveDecoderEngine.Config()
        return listOf(
            Variant("current", d),
            Variant("fixedWin", d.copy(adaptiveWindow = false)),   // pre-FR-2.7
            Variant("noKeyGate", d.copy(requireKeyingToLock = false)),
            Variant("noDeadEnd", d.copy(unprovenLockMs = Double.MAX_VALUE, unkeyedLockSweeps = Int.MAX_VALUE)),
            Variant("noStall", d.copy(stalledLockMs = Double.MAX_VALUE)),   // D-9 / D-23
            Variant("band1200", d.copy(bandMaxHz = 1200.0)),
            Variant("noFitGate", d.copy(outputGateEnabled = false)),
            Variant(
                "preD13",
                d.copy(
                    bandMaxHz = 1200.0,
                    requireKeyingToLock = false,
                    unprovenLockMs = Double.MAX_VALUE,
                    unkeyedLockSweeps = Int.MAX_VALUE,
                ),
            ),
        )
    }

    // ---- running ------------------------------------------------------------------

    private fun live(
        samples: FloatArray,
        reference: String,
        expectedToneHz: Double,
        cfg: LiveDecoderEngine.Config,
    ): Run {
        val engine = LiveDecoderEngine(cfg = cfg)
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
        return Run(
            cer = TextMetrics.cer(reference, decoded),
            chars = engine.transcript.size,
            toneErrHz = if (engine.toneHz > 0) abs(engine.toneHz - expectedToneHz) else Double.NaN,
            wpm = engine.wpm,
            lock = engine.lockState.name.take(4),
            suppressed = engine.outputSuppressed,
        )
    }

    private data class FileVariant(val name: String, val frontEnd: () -> FrontEnd)

    /** The file path changed in D-13 too — band and tone selection — so it gets ablated as well. */
    private fun fileVariants(): List<FileVariant> = listOf(
        FileVariant("current") { FrontEnd() },
        FileVariant("band1200") { FrontEnd(bandMaxHz = 1200.0) },
        FileVariant("noKeyPref") { FrontEnd(preferKeyedTone = false) },
        FileVariant("preD13") { FrontEnd(bandMaxHz = 1200.0, preferKeyedTone = false) },
    )

    private fun file(
        samples: FloatArray,
        rate: Int,
        reference: String,
        expectedToneHz: Double,
        frontEnd: FrontEnd = FrontEnd(),
    ): Run {
        val r = MorseDecoder(frontEnd).decodeFile(FloatArrayPcmSource(samples, rate))
        return Run(
            cer = TextMetrics.cer(reference, r.text),
            chars = r.chars.size,
            toneErrHz = if (r.toneHz > 0) abs(r.toneHz - expectedToneHz) else Double.NaN,
            wpm = r.wpm,
            lock = "file",
            suppressed = false,
        )
    }

    private fun pct(cer: Double) = if (cer.isNaN()) "  n/a" else "%5.1f".format(cer * 100)

    private fun summarise(label: String, names: List<String>, totals: Map<String, List<Double>>) {
        val mean = StringBuilder("%-16s".format(label))
        for (n in names) mean.append("%10s".format(pct((totals[n] ?: emptyList()).average())))
        println(mean)
    }

    @Test
    fun bench() {
        if (System.getenv("BENCH") == null) return
        val liveVars = variants()
        val fileVars = fileVariants()
        val csv = StringBuilder("scenario,path,variant,cer,chars,toneErrHz,wpm,lock,suppressed\n")
        val scenarios = scenarios()

        // Generate once per scenario and reuse everywhere: synthesis dominates runtime.
        val audio = scenarios.associate { it.name to SyntheticMorseGenerator.generate(it.cfg) }

        val liveTotals = HashMap<String, MutableList<Double>>()
        val fileTotals = HashMap<String, MutableList<Double>>()
        val notes = ArrayList<String>()

        println()
        println("=".repeat(100))
        println("DECODER BENCH — character error rate %, lower is better. Over 100 % means insertions.")
        println("Variants are single-change ablations; 'preD13' reverts all of them at once.")
        println("=".repeat(100))

        println()
        println("FILE PATH (two-pass, FM-1)")
        println("%-16s".format("scenario") + fileVars.joinToString("") { "%10s".format(it.name) })
        println("-".repeat(100))
        for (sc in scenarios) {
            val gen = audio.getValue(sc.name)
            val row = StringBuilder("%-16s".format(sc.name))
            for (v in fileVars) {
                val r = file(gen.samples, gen.sampleRate, gen.normalizedText, sc.cfg.toneHz, v.frontEnd())
                fileTotals.getOrPut(v.name) { ArrayList() }.add(r.cer)
                row.append("%9s%s".format(pct(r.cer), if (r.chars == 0) "!" else " "))
                csv.append("${sc.name},file,${v.name},${"%.4f".format(r.cer)},${r.chars},${"%.1f".format(r.toneErrHz)},${r.wpm ?: ""},file,false\n")
                if (v.name == "current" && r.toneErrHz.isFinite() && r.toneErrHz > 25) {
                    notes.add("file/${sc.name}: locked ${"%.0f".format(r.toneErrHz)} Hz off target")
                }
            }
            println(row)
        }
        println("-".repeat(100))
        summarise("MEAN", fileVars.map { it.name }, fileTotals)

        println()
        println("LIVE PATH (streaming)")
        println("%-16s".format("scenario") + liveVars.joinToString("") { "%10s".format(it.name) })
        println("-".repeat(100))
        for (sc in scenarios) {
            val gen = audio.getValue(sc.name)
            val row = StringBuilder("%-16s".format(sc.name))
            for (v in liveVars) {
                val r = live(gen.samples, gen.normalizedText, sc.cfg.toneHz, v.cfg)
                liveTotals.getOrPut(v.name) { ArrayList() }.add(r.cer)
                // A marker earns its place only when something is off: '!' nothing emitted,
                // '~' the output gate is holding characters back.
                val flag = when {
                    r.chars == 0 -> "!"
                    r.suppressed -> "~"
                    else -> " "
                }
                row.append("%9s%s".format(pct(r.cer), flag))
                csv.append("${sc.name},live,${v.name},${"%.4f".format(r.cer)},${r.chars},${"%.1f".format(r.toneErrHz)},${r.wpm ?: ""},${r.lock},${r.suppressed}\n")
                if (v.name == "current") {
                    if (r.chars == 0) notes.add("live/${sc.name}: emitted nothing (lock=${r.lock})")
                    else if (r.toneErrHz.isFinite() && r.toneErrHz > 25) {
                        notes.add("live/${sc.name}: locked ${"%.0f".format(r.toneErrHz)} Hz off target")
                    }
                }
            }
            println(row)
        }
        println("-".repeat(100))
        summarise("MEAN", liveVars.map { it.name }, liveTotals)
        // Worst case matters more than the mean for a decoder: one scenario at 250 % is a
        // broken capability, not a slightly worse average.
        val worst = StringBuilder("%-16s".format("WORST"))
        for (v in liveVars) worst.append("%10s".format(pct(liveTotals[v.name]?.maxOrNull() ?: Double.NaN)))
        println(worst)

        println("=".repeat(100))
        println("legend:  ! emitted nothing   ~ output gate engaged")
        if (notes.isNotEmpty()) {
            println()
            println("anomalies in the 'current' column:")
            for (n in notes) println("  - $n")
        }

        System.getenv("BENCH_CSV")?.let {
            java.io.File(it).writeText(csv.toString())
            println("\nCSV written to $it")
        }
        println()
    }
}

package com.morsedecoder.core.bench

import com.morsedecoder.core.audio.FloatArrayPcmSource
import com.morsedecoder.core.decode.FrontEnd
import com.morsedecoder.core.decode.RobustTimingDecoder
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.model.KalmanUnit
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import com.morsedecoder.core.synth.SyntheticMorseGenerator.GeneratedSignal
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * AC-6 track-source diagnostic — plan step 6b, the one measurement that gates it.
 *
 *   DIAG=1 gradlew :core:test --tests "*Ac6TrackDiagnostic*" -i
 *
 * Asserts nothing and is skipped unless `DIAG` is set. It is an instrument, not a guard.
 *
 * ### The question
 *
 * N-5 measured that freezing the pass-2 Kalman on the file path is worth 27.9 CER points on
 * `hand-13-rough` and costs nothing on the drift scenarios — the decode should run against
 * the locked pass-1 unit. But `wpmTrack` is currently built from `unitMsGlobal` (the Kalman
 * estimate at commit), and a frozen Kalman reports one constant speed, which cannot satisfy
 * AC-6 (every track point within 10 % of the generator's ramp).
 *
 * The proposed separation (progress.md N-5) is to decode against the frozen unit but build
 * the reported track from `DecodedChar.unitMsLocal` — the per-character shrunk least-squares
 * fit, which follows local speed. Whether a track derived that way holds AC-6 within 10 % is
 * the thing this measures before any code changes.
 *
 * For each of the two track sources under both Kalman modes it reports, on the AC-6 ramp:
 *   - the worst |tracked − truth|/truth over the checked points (AC-6 wants < 10 %)
 *   - how many points were checked (AC-6 wants > 20)
 *   - the CER, so the reporting change is visibly decoupled from the decode
 */
class Ac6TrackDiagnostic {

    // AC-6's exact configuration (AcceptanceTest.LONG_TEXT, inlined so this is portable).
    private val longText =
        "CQ CQ CQ DE SV1ABC SV1ABC K THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG " +
            "0123456789 UR RST 599 QTH ATHENS TNX FER CALL 73 ES GL"

    private val ac6 = Config(
        text = "$longText $longText",
        wpm = 15.0,
        toneHz = 600.0,
        jitterFraction = 0.05,
        drift = SyntheticMorseGenerator.SpeedDrift.LinearRamp(endWpm = 25.0, durationS = 60.0),
        seed = 13L,
    )

    // The rough fist N-5 is about, to confirm the CER win survives in this replicated path.
    private val handText =
        "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"
    private val handRough = Config(
        text = handText, wpm = 13.0, toneHz = 588.0, snrDb = 5.0,
        dashDotRatio = 2.1, jitterFraction = 0.25, charBiasFraction = 0.12, seed = 16L,
    )

    /** One committed character with every candidate speed we might report from. */
    private data class Pt(
        val tS: Double,
        val wpmGlobal: Double,   // tracked Kalman at commit (what FileResult.wpmTrack uses)
        val wpmLocal: Double,    // shrunk local fit
        val wpmRaw: Double,      // unshrunk per-character LS fit — the honest local speed
    )

    private enum class Src(val label: String) {
        GLOBAL("unitMsGlobal"), LOCAL("unitMsLocal"), RAW("unitMsRaw"),
        RAW_EMA("raw+EMA(.3)"), RAW_KALMAN("raw+reportKalman"),
    }

    private fun decode(cfg: Config, freezeDecodeUnit: Boolean, lexicon: Boolean = false): Pair<List<Pt>, String> {
        val gen = SyntheticMorseGenerator.generate(cfg)
        val fe = FrontEnd().process(FloatArrayPcmSource(gen.samples, gen.sampleRate), null)
        val fit = fitFrom(fe.runs, fe.bootstrapUnitMs)
        val k = KalmanUnit(fit.unitMs, (0.25 * fit.unitMs) * (0.25 * fit.unitMs))
        val dec = RobustTimingDecoder(
            k, fit.markMix, fit.gapMix,
            cfg = RobustTimingDecoder.Config(freezeDecodeUnit = freezeDecodeUnit),
            prior = if (lexicon) com.morsedecoder.core.assist.CwLexiconPrior() else null,
        )
        for (r in fe.runs) dec.feed(r)
        dec.flush()
        val pts = dec.output.map {
            Pt(it.tStartMs / 1000.0, 1200.0 / it.unitMsGlobal, 1200.0 / it.unitMsLocal, 1200.0 / it.unitMsRaw)
        }
        val text = buildString {
            for ((i, c) in dec.output.withIndex()) {
                if (i > 0 && c.isWordStart) append(' ')
                append(c.token)
            }
        }
        return pts to text
    }

    /** Derive the reported wpm series for a source, smoothing the raw variants online. */
    private fun series(pts: List<Pt>, src: Src, seedUnit: Double): List<Pair<Double, Double>> {
        // A reporting-only Kalman over the raw per-character measurements: same dynamics as
        // the decoder's tracker but never consumed by the lattice (the "separate the jobs"
        // candidate). Seeded at the pass-1 unit like the real one.
        val rk = KalmanUnit(seedUnit, (0.25 * seedUnit) * (0.25 * seedUnit))
        var ema = seedUnit
        return pts.map { p ->
            val wpm = when (src) {
                Src.GLOBAL -> p.wpmGlobal
                Src.LOCAL -> p.wpmLocal
                Src.RAW -> p.wpmRaw
                Src.RAW_EMA -> { ema = 0.3 * (1200.0 / p.wpmRaw) + 0.7 * ema; 1200.0 / ema }
                Src.RAW_KALMAN -> {
                    val m = 1200.0 / p.wpmRaw
                    rk.predict()
                    if (m > 0.6 * rk.unitMs && m < 1.7 * rk.unitMs) rk.update(m, (0.06 * m) * (0.06 * m))
                    rk.wpm
                }
            }
            p.tS to wpm
        }
    }

    /** AC-6's own check over a reported series. Returns (worstDev, checked). */
    private fun ac6Check(series: List<Pair<Double, Double>>, truth: List<Pair<Double, Double>>): Pair<Double, Int> {
        var worst = 0.0
        var checked = 0
        for ((tS, wpm) in series) {
            if (tS < 5.0) continue
            val truthWpm = truth.minByOrNull { abs(it.first - (tS - 0.25)) }!!.second
            val dev = abs(wpm - truthWpm) / truthWpm
            if (dev > worst) worst = dev
            checked++
        }
        return worst to checked
    }

    @Test
    fun diagnose() {
        if (System.getenv("DIAG") == null) return

        println()
        println("=".repeat(96))
        println("AC-6 ramp (15 -> 25 WPM), report-track sources under the SHIPPING decode modes")
        println("=".repeat(96))
        val genRef = SyntheticMorseGenerator.generate(ac6)
        val truth = genRef.wpmTrack
        val ref = genRef.normalizedText
        val seed = fitFrom(
            FrontEnd().process(FloatArrayPcmSource(genRef.samples, genRef.sampleRate), null).runs,
            FrontEnd().process(FloatArrayPcmSource(genRef.samples, genRef.sampleRate), null).bootstrapUnitMs,
        ).unitMs

        for (freeze in listOf(false, true)) {
            val (pts, text) = decode(ac6, freeze)
            val cer = TextMetrics.cer(ref, text) * 100
            val mode = if (freeze) "freezeDecodeUnit=TRUE (6b: locked decode, Kalman reports)"
                       else "freezeDecodeUnit=false (shipped: Kalman drives decode)"
            println()
            println("  $mode   CER ${"%.1f".format(cer)} %")
            for (src in Src.values()) {
                val (worst, checked) = ac6Check(series(pts, src, seed), truth)
                val pass = worst < 0.10 && checked > 20
                println("    track from %-18s worst dev %6.1f %%   points %3d   AC-6 %s".format(
                    src.label, worst * 100, checked, if (pass) "PASS" else "FAIL"))
            }
        }

        println()
        println("=".repeat(96))
        println("hand-13-rough — CER by decode mode (the report track does not affect the decode)")
        println("=".repeat(96))
        val refH = SyntheticMorseGenerator.generate(handRough).normalizedText
        for (freeze in listOf(false, true)) {
            val (_, text) = decode(handRough, freeze)
            val cer = TextMetrics.cer(refH, text) * 100
            println("  freezeDecodeUnit=${freeze.toString().padEnd(5)}   CER ${"%.1f".format(cer)} %")
        }

        // AC-4's exact fist on the LONG text — the scenario that regressed. ResidualDiagnostic
        // row B used a shorter text and read this as free; it is not (N-4: two texts disagree).
        println()
        println("hand-18 on the AC-4 LONG text (seed 7) — the regression to weigh against the win")
        val ac4 = Config(
            text = longText, wpm = 18.0, toneHz = 650.0, snrDb = 10.0,
            dashDotRatio = 2.2, jitterFraction = 0.20, charBiasFraction = 0.05, seed = 7L,
        )
        val refA = SyntheticMorseGenerator.generate(ac4).normalizedText
        for (freeze in listOf(false, true)) {
            val (_, raw) = decode(ac4, freeze, lexicon = false)
            val (_, ast) = decode(ac4, freeze, lexicon = true)
            println("  freezeDecodeUnit=${freeze.toString().padEnd(5)}   raw ${"%.1f".format(TextMetrics.cer(refA, raw) * 100)} %   lexicon ${"%.1f".format(TextMetrics.cer(refA, ast) * 100)} %  (AC-4 wants lexicon < 6 %)")
        }
        // qsb-impulse: on HEAD `atSpeedCeiling` vetoes it to nothing (P-4, correct). Does the
        // freeze make it emit at a *sane* speed (honest, a real 18 WPM transmission decoded
        // poorly) or at a railed one (dishonest verbosity, a P-4 regression)?
        println()
        println("qsb-impulse via the real decodeFile — is the emission honest?")
        val decText = "CQ CQ DE SV1ABC K THE QUICK BROWN FOX 0123456789 UR RST 599 QTH ATHENS 73 ES GL"
        val qsb = Config(text = decText, wpm = 18.0, toneHz = 612.0, snrDb = 8.0,
            fadingDepth = 0.6, fadingRateHz = 0.15, impulsesPerSecond = 2.0, seed = 23L)
        val qgen = SyntheticMorseGenerator.generate(qsb)
        val res = com.morsedecoder.core.decode.MorseDecoder().decodeFile(
            FloatArrayPcmSource(qgen.samples, qgen.sampleRate))
        println("  reported ${"%.1f".format(res.wpm)} WPM (true 18)   outOfRange=${res.outOfCalibratedRange}   " +
            "chars ${res.text.count { it != ' ' }} vs ${qgen.normalizedText.count { it != ' ' }} sent   " +
            "CER ${"%.1f".format(TextMetrics.cer(qgen.normalizedText, res.text) * 100)} %")
        println("  text: ${res.text.take(72)}")
        println()
    }

    // ---- pass 1, exactly as MorseDecoder.decodeFile (shared shape with ResidualDiagnostic) ----

    private data class Fit(val unitMs: Double, val markMix: GaussianMixture, val gapMix: GaussianMixture)

    private fun fitFrom(runs: List<FrontEnd.Run>, bootstrapUnitMs: Double): Fit {
        val marks = runs.filter { it.isMark }.map { it.durationMs }
        val z = DoubleArray(marks.size) { ln(marks[it] / bootstrapUnitMs) }
        val markMix = GaussianMixture.fit(z, twoMeansSeed(z), initSigma = 0.3)
        val shift = markMix.means[0]
        val u0 = bootstrapUnitMs * exp(shift)
        markMix.means[0] = 0.0
        markMix.means[1] -= shift
        val zGaps = runs.asSequence().filter { !it.isMark }
            .map { ln(it.durationMs / u0) }.filter { it <= ln(10.0) }.toList().toDoubleArray()
        val gapMix = GaussianMixture.fit(zGaps, doubleArrayOf(0.0, ln(3.0), ln(7.0)), initSigma = 0.3)
        if (gapMix.means[2] - gapMix.means[1] < 0.5) {
            val w1 = gapMix.weights[1]; val w2 = gapMix.weights[2]
            val merged = (w1 * gapMix.means[1] + w2 * gapMix.means[2]) / (w1 + w2)
            gapMix.means[1] = merged
            gapMix.sigmas[1] = maxOf(gapMix.sigmas[1], gapMix.sigmas[2])
            gapMix.weights[1] = w1 + w2
            gapMix.weights[2] = 0.0
            gapMix.means[2] = merged + 0.85
        }
        return Fit(u0, markMix, gapMix)
    }

    private fun twoMeansSeed(values: DoubleArray): DoubleArray {
        var c1 = values.min()
        var c2 = values.max()
        repeat(20) {
            var s1 = 0.0; var n1 = 0
            var s2 = 0.0; var n2 = 0
            for (v in values) {
                if (v - c1 <= c2 - v) { s1 += v; n1++ } else { s2 += v; n2++ }
            }
            if (n1 > 0) c1 = s1 / n1
            if (n2 > 0) c2 = s2 / n2
        }
        return if (c2 - c1 < ln(1.5)) doubleArrayOf(c1, c1 + ln(3.0)) else doubleArrayOf(c1, c2)
    }
}

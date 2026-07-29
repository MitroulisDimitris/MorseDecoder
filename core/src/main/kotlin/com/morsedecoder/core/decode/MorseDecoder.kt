package com.morsedecoder.core.decode

import com.morsedecoder.core.audio.PcmSource
import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.model.KalmanUnit
import kotlin.math.exp
import kotlin.math.ln

/**
 * File-mode decoding facade, two-pass per FM-1: sweep the file for the tone and the
 * duration clusters first, then decode with parameters already locked. No bootstrap
 * warmup mid-stream, no mid-stream re-lock. File mode is not constrained by the live
 * path (FM-3) — the live decoder in M3 reuses the same core with a streaming warmup.
 */
class MorseDecoder(
    private val frontEnd: FrontEnd = FrontEnd(),
    private val assists: Assists = Assists(),
) {

    /** FR-7: every assist individually toggleable and OFF by default (P-3). */
    data class Assists(
        val lexicon: Boolean = false,
        val cutNumbers: Boolean = false,
    )

    data class FileResult(
        val text: String,
        val toneHz: Double,
        /** Final global unit estimate, ms. */
        val unitMs: Double,
        val wpm: Double,
        /** Whole-file envelope SNR estimate, dB. */
        val snrDb: Double,
        val chars: List<RobustTimingDecoder.DecodedChar>,
        /** (timeSeconds, wpm) per committed character — the per-segment track of FM-2. */
        val wpmTrack: List<Pair<Double, Double>>,
        val modelFitQuality: Double,
        /** FR-5.7: speed outside 5-40 WPM — decoded best-effort, targets void. */
        val outOfCalibratedRange: Boolean,
    )

    fun decodeFile(source: PcmSource, toneHzOverride: Double? = null): FileResult {
        val fe = frontEnd.process(source, toneHzOverride)
        val runs = fe.runs
        val markDurations = runs.filter { it.isMark }.map { it.durationMs }
        if (markDurations.size < 4) return empty(fe.toneHz)

        // Pass 1b — batch duration clusters (FM-1), normalised by the bootstrap unit.
        // EM is seeded from the data (2-means), not from the bootstrap scale: the
        // autocorrelation bootstrap is only good to a factor of ~2, and a blind seed
        // that far off lets one component swallow both clusters.
        val zMarks = DoubleArray(markDurations.size) { ln(markDurations[it] / fe.bootstrapUnitMs) }
        val markSeed = twoMeansSeed(zMarks)
        val markMix = GaussianMixture.fit(zMarks, markSeed, initSigma = 0.3)

        // Re-anchor so the dot component sits exactly at z = 0; the residual shift
        // becomes the refined unit estimate.
        val shift = markMix.means[0]
        val u0 = fe.bootstrapUnitMs * exp(shift)
        markMix.means[0] = 0.0
        markMix.means[1] -= shift

        // Calibrated-band handling (D-1/FR-5.7): far outside any plausible keying,
        // this is threshold chatter, not Morse — emit nothing rather than garbage (P-4).
        // Moderately outside 5-40 WPM: decode best-effort and flag. The band carries a
        // ~6 % margin so signals sent exactly at the 5/40 WPM edges are not flagged.
        if (u0 < 15.0 || u0 > 500.0) return empty(fe.toneHz)
        val outOfRange = u0 !in 28.0..255.0

        // Honest-failure gate (P-4): if the two-cluster model cannot describe the
        // measured marks at all, this is not decodable keying. The live path grows a
        // full low-signal state (FR-3.7) in M3; file mode simply declines.
        if (RobustTimingDecoder.fitQuality(markMix) < 0.12) return empty(fe.toneHz)

        val zGaps = runs.asSequence()
            .filter { !it.isMark }
            .map { ln(it.durationMs / u0) }
            .filter { it <= ln(10.0) } // FR-4.3: beyond ~10 units, not model material
            .toList()
            .toDoubleArray()
        // Gap seed is structural, not data-driven: gaps are normalised by u0, which the
        // mark fit just anchored, so intra/inter/word sit at 1, ~3, ~7 units by
        // construction. (A quantile seed fails here — with ~70 % intra gaps the upper
        // quantiles can land inside the intra cluster and split it.)
        val gapMix = GaussianMixture.fit(zGaps, doubleArrayOf(0.0, ln(3.0), ln(7.0)), initSigma = 0.3)

        // If the inter and word components landed on one cluster (Farnsworth pushes
        // real word gaps past the FR-4.3 cutoff, so no word cluster exists to fit),
        // merge them; the decoder falls back to a structural word threshold.
        if (gapMix.means[2] - gapMix.means[1] < 0.5) {
            val w1 = gapMix.weights[1]
            val w2 = gapMix.weights[2]
            val merged = (w1 * gapMix.means[1] + w2 * gapMix.means[2]) / (w1 + w2)
            gapMix.means[1] = merged
            gapMix.sigmas[1] = maxOf(gapMix.sigmas[1], gapMix.sigmas[2])
            gapMix.weights[1] = w1 + w2
            gapMix.weights[2] = 0.0
            gapMix.means[2] = merged + 0.85
        }

        // Pass 2 — decode with locked parameters; Kalman still tracks drift (FM-2).
        val kalman = KalmanUnit(u0, (0.25 * u0) * (0.25 * u0))
        val prior = if (assists.lexicon) com.morsedecoder.core.assist.CwLexiconPrior() else null
        val decoder = RobustTimingDecoder(kalman, markMix, gapMix, prior = prior)
        for (run in runs) decoder.feed(run)
        decoder.flush()

        val chars = decoder.output
        val text = assembleText(chars, assists.cutNumbers)
        return FileResult(
            text = text,
            toneHz = fe.toneHz,
            unitMs = kalman.unitMs,
            wpm = kalman.wpm,
            snrDb = fe.snrDb,
            chars = chars,
            wpmTrack = chars.map { Pair(it.tStartMs / 1000.0, 1200.0 / it.unitMsGlobal) },
            modelFitQuality = RobustTimingDecoder.fitQuality(markMix),
            outOfCalibratedRange = outOfRange,
        )
    }

    /** Text assembly, with cut-number expansion applied per word when enabled (FR-7.3). */
    private fun assembleText(chars: List<RobustTimingDecoder.DecodedChar>, cutNumbers: Boolean): String {
        val words = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (c in chars) {
            if (c.isWordStart && cur.isNotEmpty()) {
                words.add(cur)
                cur = ArrayList()
            }
            cur.add(c.token)
        }
        if (cur.isNotEmpty()) words.add(cur)
        return words.joinToString(" ") { word ->
            val tokens = if (cutNumbers) com.morsedecoder.core.export.CutNumbers.expandWord(word) ?: word else word
            tokens.joinToString("")
        }
    }

    /** 1-D 2-means seed for the mark EM; falls back to a 3:1 spread if degenerate. */
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

    private fun empty(toneHz: Double) = FileResult(
        text = "",
        toneHz = toneHz,
        unitMs = 0.0,
        wpm = 0.0,
        snrDb = 0.0,
        chars = emptyList(),
        wpmTrack = emptyList(),
        modelFitQuality = 0.0,
        outOfCalibratedRange = false,
    )
}

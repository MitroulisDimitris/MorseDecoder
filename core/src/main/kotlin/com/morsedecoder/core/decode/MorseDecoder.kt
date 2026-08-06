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
        if (u0 < 15.0 || u0 > 500.0) return empty(fe.toneHz)

        // Honest-failure gate (P-4): if the two-cluster model cannot describe the
        // measured marks at all, this is not decodable keying. The live path grows a
        // full low-signal state (FR-3.7) in M3; file mode simply declines.
        //
        // "At all" is meant literally, and the threshold used to be 0.12. Measured across
        // the bench matrix and the whole non-Morse corpus, `fitQuality` ranks a rough but
        // genuine fist *below* every impostor it lets through:
        //
        //     hand-13-rough  0.0987  declined      speech    0.4741  decoded, 31 chars
        //     music          0.0000  declined      knocking  0.4033  decoded, 30 chars
        //     speech+hum     0.0000  declined      clipping  0.7386  decoded, 19 chars
        //     music+noise    0.0000  declined      RTTY      0.5228  (stopped elsewhere)
        //
        // So no threshold separates the two populations — the ordering is wrong, not the
        // cut point, and 0.12 was picking the one genuine transmission out of the set.
        // Every correct rejection is a *hard* zero: music and music+noise fail on ratio
        // (7.0-7.3:1, past fitQuality's 5.5 ceiling), speech+hum on separation (1.29,
        // under its 1.5 floor). Those are structural impossibilities, not close calls,
        // and they are all this gate can honestly claim to detect.
        //
        // The margin between 0 and 0.12 was therefore doing nothing except rejecting
        // `hand-13-rough`, which fits at u0 89.2 ms against a true 92.3 (1.04x) and
        // decodes at 64.6 % CER — poor, and the business of known-issues D-6, but a
        // decode. Seeding is not the lever: fed the true unit it still scores 0.1071.
        //
        // The score survives on `modelFitQuality` for the UI to annotate, which is where
        // a weak-but-possible fit belongs. It is not evidence for a veto.
        if (RobustTimingDecoder.fitQuality(markMix) <= 0.0) return empty(fe.toneHz)

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

        // Pass 2 — decode with the pass-1 unit locked (FM-1: "parameters already locked").
        // The Kalman keeps tracking, but only to report FM-2's per-segment speed track; it
        // no longer feeds back into the lattice, where following `hand-13-rough`'s 12 %
        // per-character bias cost 27.9 CER points and bought nothing on the drift scenarios
        // (N-5, step 6b). `freezeDecodeUnit` is the split; AC-6 still holds because the
        // reported track (`unitMsGlobal`) is the tracked unit, not the frozen decode one.
        //
        // Raw mode only. Measured, the freeze improves the raw decode across the board
        // (`hand-13-rough` 64.6 -> 36.7 %, `hand-18` 8.7 -> 7.9 %), but on `hand-18` it
        // moves the residual errors to ones the lexicon prior cannot flip, so with the assist
        // engaged the tracking decode + prior reaches 5.6 % where the frozen one stalls at
        // 7.9 % (AC-4). The prior is an adaptive layer (FR-7.1); when it is on, let pass 2
        // track. The default ships raw (P-1/P-3), so the default gets the freeze.
        val prior = if (assists.lexicon) com.morsedecoder.core.assist.CwLexiconPrior() else null
        val kalman = KalmanUnit(u0, (0.25 * u0) * (0.25 * u0))
        val decoder = RobustTimingDecoder(
            kalman, markMix, gapMix,
            cfg = RobustTimingDecoder.Config(freezeDecodeUnit = prior == null),
            prior = prior,
        )
        for (run in runs) decoder.feed(run)
        decoder.flush()

        // A unit pinned at the speed clamp is a bound being enforced, not a measurement
        // (D-11) — every character derived from it comes from a non-measurement, so the
        // file path declines rather than reporting them. Narrower than "outside 5-40",
        // which FR-5.7 asks to decode best-effort and which measurement shows carries no
        // information about correctness: 50 WPM code decodes perfectly at that speed.
        if (kalman.atSpeedCeiling) return empty(fe.toneHz)

        val chars = decoder.output
        val text = assembleText(chars, assists.cutNumbers)

        // FR-5.7, computed from the unit actually reported (D-4). It was previously
        // derived from `u0`, the pass-1 bootstrap, while `wpm` below comes from the
        // pass-2 Kalman — so the flag described a different number than the one on
        // screen. A drifting decode could bootstrap inside the band, finish at 59 WPM,
        // and display that with no warning at all.
        //
        // Bounds are in unit-milliseconds with a ~6 % margin, so a signal sent at
        // exactly 5 or 40 WPM is not flagged by measurement noise alone.
        val outOfRange = kalman.unitMs !in 28.0..255.0

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

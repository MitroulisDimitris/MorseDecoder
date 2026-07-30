package com.morsedecoder.core.decode

import com.morsedecoder.core.audio.PcmSource
import com.morsedecoder.core.audio.readAllSamples
import com.morsedecoder.core.dsp.Bootstrap
import com.morsedecoder.core.dsp.Goertzel
import com.morsedecoder.core.dsp.KeyingSpread
import com.morsedecoder.core.dsp.Spectrum

/**
 * Audio → timed element runs, shared shape for file mode (and the live path in M3).
 *
 * Tone sweep (FR-2.1) → Goertzel envelope (FR-2.2) → median impulse filter (FR-3.3)
 * → percentile noise floor (FR-3.1) → linear-power Schmitt trigger (FR-3.2) →
 * run-length encoding (FR-4.1) → sub-element glitch pruning (FR-3.4) with gates set
 * by the autocorrelation bootstrap (FR-5.1).
 */
class FrontEnd(
    private val windowMs: Double = 10.0,
    private val hopMs: Double = 2.5,
    private val bandMinHz: Double = Spectrum.DEFAULT_BAND_MIN_HZ,
    private val bandMaxHz: Double = Spectrum.DEFAULT_BAND_MAX_HZ,
) {

    /** One mark or gap with its position on the session clock. */
    data class Run(val isMark: Boolean, val startMs: Double, val durationMs: Double)

    data class Output(
        val toneHz: Double,
        val runs: List<Run>,
        val bootstrapUnitMs: Double,
        /** Whole-file envelope SNR estimate (p95/p20 spread), dB. */
        val snrDb: Double = 0.0,
    )

    fun process(source: PcmSource, toneHzOverride: Double? = null): Output {
        val samples = source.readAllSamples()
        val sr = source.sampleRate

        val toneHz = toneHzOverride
            ?: chooseTone(samples, sr)
            ?: return Output(0.0, emptyList(), 60.0)

        val win = (windowMs * sr / 1000.0).toInt()
        val hop = (hopMs * sr / 1000.0).toInt()
        if (samples.size < win) return Output(toneHz, emptyList(), 60.0)
        val goertzel = Goertzel(sr, toneHz, win)
        val frames = (samples.size - win) / hop + 1
        val env = DoubleArray(frames) { goertzel.power(samples, it * hop) }
        medianFilter3(env)

        val sorted = env.sorted()
        val floor = sorted[(0.20 * (sorted.size - 1)).toInt()]
        val peak = sorted[(0.95 * (sorted.size - 1)).toInt()]
        // No-keying guard. Pure noise has a p95/p20 envelope spread of ~6-13x even
        // after the median filter, so the bar sits at 15x (~12 dB); genuine keying at
        // the AC-2 floor (0 dB broadband) still clears ~50x.
        if (peak < 1e-12 || peak < floor * 15.0) return Output(toneHz, emptyList(), 60.0)
        val snrDb = 10.0 * kotlin.math.log10(peak / floor.coerceAtLeast(1e-15))

        // Linear-power Schmitt thresholds — see ThresholdDecoder for the bias rationale.
        val onThr = floor + 0.50 * (peak - floor)
        val offThr = floor + 0.25 * (peak - floor)
        val bits = BooleanArray(frames)
        var state = false
        for (i in 0 until frames) {
            state = if (state) env[i] > offThr else env[i] > onThr
            bits[i] = state
        }

        val bootUnit = Bootstrap.estimateUnitMs(env, hopMs)

        var runs = rle(bits)
        // Glitch gate from the bootstrap unit; capped so a 2x-high bootstrap can
        // never eat a genuine 40 WPM dot (30 ms).
        val gateMs = (0.25 * bootUnit).coerceAtMost(20.0)
        runs = pruneRuns(runs, gateMs)
        runs = trimSilence(runs)
        return Output(toneHz, runs, bootUnit, snrDb)
    }

    /**
     * Pick the tone to demodulate: the strongest peak that is actually being keyed.
     *
     * A recording contains plenty of narrowband energy that is not Morse — hum, speaker
     * resonance, traffic rumble — and any of it can be louder than the CW note. Scoring
     * the shortlist by keying rather than by power is what stops the whole decode being
     * spent on the wrong frequency.
     *
     * Falls back to the loudest candidate when nothing clears the keying bar, so a
     * marginal recording still gets decoded on a best-effort basis; the no-keying guard
     * below and `modelFitQuality` remain the honest-failure path (P-4).
     */
    private fun chooseTone(samples: FloatArray, sr: Int): Double? {
        val candidates = Spectrum.candidateTones(samples, sr, bandMinHz, bandMaxHz)
        if (candidates.isEmpty()) return Spectrum.estimateToneHz(samples, sr, bandMinHz, bandMaxHz)
        val keyed = candidates.firstOrNull {
            KeyingSpread.of(samples, sr, it) >= KeyingSpread.LOCK_MIN_SPREAD
        }
        return keyed ?: candidates.first()
    }

    private fun rle(bits: BooleanArray): List<Run> {
        val runs = ArrayList<Run>()
        var cur = bits[0]
        var startFrame = 0
        for (i in 1 until bits.size) {
            if (bits[i] != cur) {
                runs.add(Run(cur, startFrame * hopMs, (i - startFrame) * hopMs))
                cur = bits[i]
                startFrame = i
            }
        }
        runs.add(Run(cur, startFrame * hopMs, (bits.size - startFrame) * hopMs))
        return runs
    }

    private fun trimSilence(runs: List<Run>): List<Run> {
        val first = runs.indexOfFirst { it.isMark }
        val last = runs.indexOfLast { it.isMark }
        return if (first < 0) emptyList() else runs.subList(first, last + 1)
    }

    /** Merge runs shorter than [minMs] into their neighbours, keeping the time axis intact. */
    private fun pruneRuns(runs: List<Run>, minMs: Double): List<Run> {
        val work = runs.toMutableList()
        while (true) {
            var shortest = -1
            for (i in work.indices) {
                if (work[i].durationMs < minMs &&
                    (shortest < 0 || work[i].durationMs < work[shortest].durationMs)
                ) shortest = i
            }
            if (shortest < 0 || work.size == 1) break
            when (shortest) {
                0 -> {
                    val merged = Run(work[1].isMark, work[0].startMs, work[0].durationMs + work[1].durationMs)
                    work.removeAt(0); work[0] = merged
                }
                work.size - 1 -> {
                    val p = work[shortest - 1]
                    work[shortest - 1] = Run(p.isMark, p.startMs, p.durationMs + work[shortest].durationMs)
                    work.removeAt(shortest)
                }
                else -> {
                    val p = work[shortest - 1]
                    val nx = work[shortest + 1]
                    work[shortest - 1] = Run(p.isMark, p.startMs, p.durationMs + work[shortest].durationMs + nx.durationMs)
                    work.removeAt(shortest + 1)
                    work.removeAt(shortest)
                }
            }
        }
        return work
    }

    private fun medianFilter3(x: DoubleArray) {
        if (x.size < 3) return
        val copy = x.copyOf()
        for (i in 1 until x.size - 1) {
            val a = copy[i - 1]; val b = copy[i]; val c = copy[i + 1]
            x[i] = maxOf(minOf(a, b), minOf(maxOf(a, b), c))
        }
    }
}

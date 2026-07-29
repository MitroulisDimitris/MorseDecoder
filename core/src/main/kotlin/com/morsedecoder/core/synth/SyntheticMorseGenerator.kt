package com.morsedecoder.core.synth

import com.morsedecoder.core.morse.MorseAlphabet
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic Morse generator — the measurement harness everything else is tested
 * against (§6, milestone 1). Fully deterministic for a given config: three seeded
 * random streams (timing, per-character bias, noise) are kept separate so that e.g.
 * changing SNR does not change the element timeline. That property is what makes
 * AC-5-style comparisons (same stream, different impairments) meaningful.
 */
object SyntheticMorseGenerator {

    /** Speed drift over the session (§6: "drift models, not just static WPM plus jitter"). */
    sealed interface SpeedDrift {
        data object None : SpeedDrift

        /** e.g. 15 → 25 WPM over 60 s, then hold. */
        data class LinearRamp(val endWpm: Double, val durationS: Double) : SpeedDrift

        /** e.g. ±15 % at 0.05 Hz. */
        data class SineWobble(val amplitudeFraction: Double, val rateHz: Double) : SpeedDrift
    }

    /** Hesitation injection at word boundaries and mid-character (§6). */
    data class Hesitation(
        val wordBoundaryProb: Double = 0.3,
        val midCharProb: Double = 0.05,
        val meanExtraUnits: Double = 3.0,
    )

    data class Config(
        val text: String,
        val wpm: Double = 20.0,
        val toneHz: Double = 600.0,
        val sampleRate: Int = 16_000,
        /** Broadband SNR in dB: key-down tone power vs. white-noise power over the full band. Null = no noise. */
        val snrDb: Double? = null,
        /** Real fists land at 2:1–2.5:1 (D-3); 3.0 is machine-sent. */
        val dashDotRatio: Double = 3.0,
        /** Per-element multiplicative log-normal jitter, sigma as a fraction (0.2 = "20 % jitter", AC-3). */
        val jitterFraction: Double = 0.0,
        /** Per-character systematic bias sigma; each symbol gets a fixed multiplier for the whole session (§6). */
        val charBiasFraction: Double = 0.0,
        /** Farnsworth: element speed stays [wpm], inter-char/word gaps stretch to this slower speed. Null = standard timing. */
        val farnsworthSpacingWpm: Double? = null,
        /** Sinusoidal amplitude fading: depth in [0,1], rate in Hz. */
        val fadingDepth: Double = 0.0,
        val fadingRateHz: Double = 0.1,
        /** Impulse noise (static crashes / key clicks): mean events per second. */
        val impulsesPerSecond: Double = 0.0,
        val hesitation: Hesitation? = null,
        val drift: SpeedDrift = SpeedDrift.None,
        /** Raised-cosine key envelope rise/fall. */
        val riseFallMs: Double = 5.0,
        val amplitude: Double = 0.5,
        val leadInMs: Double = 250.0,
        val tailMs: Double = 250.0,
        val seed: Long = 1L,
    )

    enum class ElementKind { MARK_DOT, MARK_DASH, GAP_INTRA, GAP_INTER, GAP_WORD }

    data class TimedElement(val kind: ElementKind, val startMs: Double, val durationMs: Double) {
        val isMark: Boolean get() = kind == ElementKind.MARK_DOT || kind == ElementKind.MARK_DASH
        val endMs: Double get() = startMs + durationMs
    }

    data class GeneratedSignal(
        val samples: FloatArray,
        val sampleRate: Int,
        /** Uppercased, whitespace-collapsed version of the input — the CER reference. */
        val normalizedText: String,
        /** Ground-truth element timeline, including all timing impairments. */
        val elements: List<TimedElement>,
        /** (timeSeconds, wpm) sampled at each character start — ground truth for AC-6. */
        val wpmTrack: List<Pair<Double, Double>>,
    )

    fun generate(config: Config): GeneratedSignal {
        require(config.wpm > 0) { "wpm must be positive" }
        require(config.dashDotRatio > 1.0) { "dashDotRatio must exceed 1" }

        val rngTiming = Random(config.seed)
        val rngBias = Random(config.seed xor 0x62696173L)   // "bias"
        val rngNoise = Random(config.seed xor 0x6e6f6973L)  // "nois"

        // Per-character systematic bias: drawn for every symbol in a fixed alphabet
        // order so the map does not depend on the text (§6: held constant across a session).
        val charBias = HashMap<Char, Double>()
        for (c in MorseAlphabet.charPatterns.keys.sorted()) {
            charBias[c] = if (config.charBiasFraction > 0) {
                kotlin.math.exp(config.charBiasFraction * rngBias.nextGaussian())
            } else 1.0
        }

        val normalized = normalize(config.text)
        val elements = ArrayList<TimedElement>()
        val wpmTrack = ArrayList<Pair<Double, Double>>()

        var tMs = config.leadInMs

        fun wpmAt(ms: Double): Double {
            val t = (ms - config.leadInMs) / 1000.0
            return when (val d = config.drift) {
                is SpeedDrift.None -> config.wpm
                is SpeedDrift.LinearRamp ->
                    config.wpm + (d.endWpm - config.wpm) * min(1.0, t / d.durationS)
                is SpeedDrift.SineWobble ->
                    config.wpm * (1.0 + d.amplitudeFraction * sin(2.0 * PI * d.rateHz * t))
            }
        }

        fun jitter(): Double =
            if (config.jitterFraction > 0) kotlin.math.exp(config.jitterFraction * rngTiming.nextGaussian()) else 1.0

        fun expExtra(meanUnits: Double, unitMs: Double): Double =
            -ln(1.0 - rngTiming.nextDouble()) * meanUnits * unitMs

        val words = tokenizeWords(config.text)
        for ((wi, word) in words.withIndex()) {
            for ((ci, sym) in word.withIndex()) {
                val pattern = sym.pattern
                val unitMs = 1200.0 / wpmAt(tMs)
                val spacingUnitMs = config.farnsworthSpacingWpm?.let { 1200.0 / it } ?: unitMs
                val bias = if (sym.display.length == 1) charBias[sym.display[0]] ?: 1.0 else 1.0
                wpmTrack.add(Pair((tMs - config.leadInMs) / 1000.0, 1200.0 / unitMs))

                for ((ei, sym) in pattern.withIndex()) {
                    if (ei > 0) {
                        var gap = unitMs * bias * jitter()
                        val hes = config.hesitation
                        if (hes != null && rngTiming.nextDouble() < hes.midCharProb) {
                            gap += expExtra(hes.meanExtraUnits, unitMs)
                        }
                        elements.add(TimedElement(ElementKind.GAP_INTRA, tMs, gap))
                        tMs += gap
                    }
                    val dur = when (sym) {
                        '.' -> unitMs * bias * jitter()
                        else -> unitMs * config.dashDotRatio * bias * jitter()
                    }
                    elements.add(
                        TimedElement(if (sym == '.') ElementKind.MARK_DOT else ElementKind.MARK_DASH, tMs, dur)
                    )
                    tMs += dur
                }

                if (ci < word.size - 1) {
                    val gap = 3.0 * spacingUnitMs * jitter()
                    elements.add(TimedElement(ElementKind.GAP_INTER, tMs, gap))
                    tMs += gap
                }
            }
            if (wi < words.size - 1) {
                val unitMs = 1200.0 / wpmAt(tMs)
                val spacingUnitMs = config.farnsworthSpacingWpm?.let { 1200.0 / it } ?: unitMs
                var gap = 7.0 * spacingUnitMs * jitter()
                val hes = config.hesitation
                if (hes != null && rngTiming.nextDouble() < hes.wordBoundaryProb) {
                    gap += expExtra(hes.meanExtraUnits, unitMs)
                }
                elements.add(TimedElement(ElementKind.GAP_WORD, tMs, gap))
                tMs += gap
            }
        }

        // ---- render ----
        val sr = config.sampleRate
        val totalMs = tMs + config.tailMs
        val n = (totalMs * sr / 1000.0).roundToInt()
        val samples = FloatArray(n)

        val rampN = (config.riseFallMs * sr / 1000.0).roundToInt().coerceAtLeast(1)
        val omega = 2.0 * PI * config.toneHz / sr
        val fadeOmega = 2.0 * PI * config.fadingRateHz / sr

        // Keying model matches real transmitters: the envelope starts rising at
        // key-down and starts falling at key-up, with the release extending past the
        // nominal end into the gap. The half-amplitude points then sit exactly one
        // rise-time/2 after key-down and key-up, so threshold-measured durations are
        // unbiased. (Ramping the release inside the mark would shorten every measured
        // mark by a full ramp — a systematic bias the decoder cannot remove.)
        for (el in elements) {
            if (!el.isMark) continue
            val s0 = (el.startMs * sr / 1000.0).roundToInt().coerceIn(0, n)
            val nominal = (el.endMs * sr / 1000.0).roundToInt().coerceIn(0, n) - s0
            if (nominal <= 0) continue
            val total = min(nominal + rampN, n - s0)
            for (i in 0 until total) {
                val idx = s0 + i
                val attack = if (i < rampN) 0.5 * (1.0 - cos(PI * i / rampN)) else 1.0
                val release = if (i >= nominal) 0.5 * (1.0 + cos(PI * (i - nominal) / rampN)) else 1.0
                var gain = config.amplitude * attack * release
                if (config.fadingDepth > 0) {
                    gain *= 1.0 - config.fadingDepth * (0.5 + 0.5 * sin(fadeOmega * idx))
                }
                samples[idx] = (gain * sin(omega * idx)).toFloat()
            }
        }

        // Broadband white noise at the requested SNR (key-down tone power reference).
        val toneRms = config.amplitude / sqrt(2.0)
        if (config.snrDb != null) {
            val noiseSigma = toneRms / Math.pow(10.0, config.snrDb / 20.0)
            for (i in samples.indices) {
                samples[i] = (samples[i] + noiseSigma * rngNoise.nextGaussian()).toFloat()
            }
        }

        // Impulse noise: Poisson arrivals, ~3 ms wideband bursts well above the tone.
        if (config.impulsesPerSecond > 0) {
            val burstLen = (0.003 * sr).roundToInt()
            val sigma = 6.0 * toneRms
            var t = 0.0
            while (true) {
                t += -ln(1.0 - rngNoise.nextDouble()) / config.impulsesPerSecond
                val at = (t * sr).roundToInt()
                if (at >= n) break
                for (i in at until min(at + burstLen, n)) {
                    samples[i] = (samples[i] + sigma * rngNoise.nextGaussian()).toFloat()
                }
            }
        }

        // hard clip guard
        for (i in samples.indices) samples[i] = samples[i].coerceIn(-1f, 1f)

        return GeneratedSignal(samples, sr, normalized, elements, wpmTrack)
    }

    /**
     * Uppercase, drop characters with no Morse mapping, collapse whitespace.
     * Prosign tokens like `<SK>` survive as single sending units (FR-6.4).
     */
    fun normalize(text: String): String =
        tokenizeWords(text).joinToString(" ") { word -> word.joinToString("") { it.display } }

    private data class Sym(val display: String, val pattern: String)

    private fun tokenizeWords(text: String): List<List<Sym>> =
        text.split(WHITESPACE).map { tokenizeWord(it) }.filter { it.isNotEmpty() }

    private fun tokenizeWord(word: String): List<Sym> {
        val syms = ArrayList<Sym>()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            if (c == '<') {
                val end = word.indexOf('>', i)
                val name = if (end > i) word.substring(i + 1, end).uppercase() else null
                val pattern = name?.let { MorseAlphabet.prosignPatterns[it] }
                if (pattern != null) {
                    syms.add(Sym("<$name>", pattern))
                    i = end + 1
                    continue
                }
                i++ // stray '<' with no prosign behind it
            } else {
                val u = c.uppercaseChar()
                val p = MorseAlphabet.charPatterns[u]
                if (p != null) syms.add(Sym(u.toString(), p))
                i++
            }
        }
        return syms
    }

    private val WHITESPACE = Regex("\\s+")
}

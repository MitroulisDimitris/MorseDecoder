package com.morsedecoder.core.fixtures

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import java.util.Random

/**
 * Audio that is **not** Morse, for the P-4 side of the suite.
 *
 * Most of what a phone microphone hears is not Morse code, so most of what the decoder
 * must do well is decline. The positive tests measure how accurately it decodes; these
 * fixtures measure whether it can be persuaded to decode something that was never sent —
 * which is the failure users actually report, because garbage output is worse than no
 * output (P-4).
 *
 * Each fixture is adversarial in a specific way rather than merely random:
 *
 * - [whiteNoise], [pinkNoise] — no structure at all; the floor case.
 * - [steadyCarrier], [mainsHum] — narrowband and loud, but never keyed.
 * - [speech] — syllabic rhythm at roughly the rate of slow Morse, which is why speech is
 *   the classic false trigger.
 * - [music] — sustained narrowband tones with vibrato; keyed-looking note attacks.
 * - [rtty] — the hardest case. Narrowband, genuinely on/off keyed, at a constant rate.
 *   It passes every "is this keyed?" test there is, and is separated from Morse only by
 *   its element durations being uniform where Morse's are bimodal.
 * - [knocking] — impulsive, wideband, irregular: doors, keyboards, handling noise.
 * - [clipping] — an overloaded input stage, where everything becomes a square wave.
 *
 * All are deterministic in [seed] (NFR-5).
 */
object NonMorseAudio {

    const val SAMPLE_RATE = 16_000

    private fun samples(seconds: Double) = (seconds * SAMPLE_RATE).toInt()

    fun silence(seconds: Double = 20.0): FloatArray = FloatArray(samples(seconds))

    fun whiteNoise(seconds: Double = 20.0, amplitude: Double = 0.25, seed: Long = 1L): FloatArray {
        val rng = Random(seed)
        return FloatArray(samples(seconds)) { (rng.nextGaussian() * amplitude).toFloat() }
    }

    /** 1/f noise — closer to real room ambience than white, and heavier where CW lives. */
    fun pinkNoise(seconds: Double = 20.0, amplitude: Double = 0.3, seed: Long = 2L): FloatArray {
        val rng = Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        return FloatArray(samples(seconds)) {
            val w = rng.nextGaussian()
            b0 = 0.99765 * b0 + w * 0.0990460
            b1 = 0.96300 * b1 + w * 0.2965164
            b2 = 0.57000 * b2 + w * 1.0526913
            ((b0 + b1 + b2 + w * 0.1848) * amplitude * 0.2).toFloat()
        }
    }

    fun steadyCarrier(freqHz: Double = 685.0, seconds: Double = 20.0, amplitude: Double = 0.4): FloatArray =
        FloatArray(samples(seconds)) { (amplitude * sin(2.0 * PI * freqHz * it / SAMPLE_RATE)).toFloat() }

    /** 50 Hz and its harmonics, the way a badly grounded shack actually sounds. */
    fun mainsHum(seconds: Double = 20.0, fundamental: Double = 50.0, amplitude: Double = 0.35): FloatArray {
        val n = samples(seconds)
        val out = FloatArray(n)
        for (h in 1..24) {
            val f = fundamental * h
            if (f > 3000.0) break
            val a = amplitude / h
            for (i in 0 until n) {
                out[i] += (a * sin(2.0 * PI * f * i / SAMPLE_RATE)).toFloat()
            }
        }
        return out
    }

    /**
     * Speech-like: three formants under a syllabic envelope at ~4 Hz with sentence pauses.
     * Not intelligible — the point is the *rhythm*, which is close enough to slow Morse
     * that anything keying on envelope structure alone will bite.
     */
    fun speech(seconds: Double = 20.0, amplitude: Double = 0.4, seed: Long = 3L): FloatArray {
        val rng = Random(seed)
        val n = samples(seconds)
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val speaking = rng.nextInt(10) > 2          // occasional sentence gaps
            val burst = (0.08 + rng.nextDouble() * 0.22) * SAMPLE_RATE
            val len = burst.toInt().coerceAtMost(n - i)
            if (speaking) {
                // Formants wander per syllable, as a vowel does.
                val f1 = 300.0 + rng.nextDouble() * 500.0
                val f2 = 900.0 + rng.nextDouble() * 900.0
                val f3 = 2200.0 + rng.nextDouble() * 700.0
                for (k in 0 until len) {
                    val t = (i + k).toDouble() / SAMPLE_RATE
                    // Raised-cosine syllable envelope: no clicks, unlike keying.
                    val env = 0.5 * (1.0 - kotlin.math.cos(2.0 * PI * k / len))
                    val v = 0.6 * sin(2.0 * PI * f1 * t) +
                        0.3 * sin(2.0 * PI * f2 * t) +
                        0.1 * sin(2.0 * PI * f3 * t)
                    out[i + k] = (amplitude * env * v).toFloat()
                }
            }
            i += len
        }
        return out
    }

    /**
     * Music-like: a slow chord progression with vibrato. Note attacks look like key-down
     * edges and the tones are narrowband, so this exercises tone lock rather than timing.
     */
    fun music(seconds: Double = 20.0, amplitude: Double = 0.35, seed: Long = 4L): FloatArray {
        val rng = Random(seed)
        val n = samples(seconds)
        val out = FloatArray(n)
        val scale = doubleArrayOf(440.0, 494.0, 523.0, 587.0, 659.0, 698.0, 784.0)
        var i = 0
        while (i < n) {
            val len = ((0.4 + rng.nextDouble() * 0.8) * SAMPLE_RATE).toInt().coerceAtMost(n - i)
            val root = scale[rng.nextInt(scale.size)]
            for (k in 0 until len) {
                val t = (i + k).toDouble() / SAMPLE_RATE
                val attack = 1.0 - exp(-k / (0.02 * SAMPLE_RATE))
                val decay = exp(-k / (0.6 * SAMPLE_RATE))
                val vib = 1.0 + 0.004 * sin(2.0 * PI * 5.0 * t)
                var v = 0.0
                for ((h, w) in listOf(1.0 to 1.0, 2.0 to 0.4, 3.0 to 0.2)) {
                    v += w * sin(2.0 * PI * root * h * vib * t)
                }
                out[i + k] += (amplitude * attack * decay * v).toFloat()
            }
            i += len
        }
        return out
    }

    /**
     * RTTY-like FSK: 170 Hz shift, 45.45 baud. The adversarial case — narrowband and
     * genuinely keyed, so every "is this being keyed?" test says yes. Only the *uniform*
     * element durations distinguish it from Morse, which is precisely what the two-cluster
     * timing model exists to notice.
     */
    fun rtty(
        markHz: Double = 915.0,
        shiftHz: Double = 170.0,
        baud: Double = 45.45,
        seconds: Double = 20.0,
        amplitude: Double = 0.4,
        seed: Long = 5L,
    ): FloatArray {
        val rng = Random(seed)
        val n = samples(seconds)
        val out = FloatArray(n)
        val bitSamples = (SAMPLE_RATE / baud).toInt()
        var i = 0
        var phase = 0.0
        while (i < n) {
            val f = if (rng.nextBoolean()) markHz else markHz - shiftHz
            val len = bitSamples.coerceAtMost(n - i)
            for (k in 0 until len) {
                phase += 2.0 * PI * f / SAMPLE_RATE
                out[i + k] = (amplitude * sin(phase)).toFloat()
            }
            i += len
        }
        return out
    }

    /** Doors, keyboards, handling noise: wideband impulses at irregular intervals. */
    fun knocking(seconds: Double = 20.0, amplitude: Double = 0.7, seed: Long = 6L): FloatArray {
        val rng = Random(seed)
        val n = samples(seconds)
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            i += (rng.nextDouble() * 0.9 * SAMPLE_RATE).toInt() + 800
            if (i >= n) break
            val ring = (0.03 * SAMPLE_RATE).toInt()
            val f = 200.0 + rng.nextDouble() * 900.0
            for (k in 0 until ring) {
                if (i + k >= n) break
                val env = exp(-k / (0.006 * SAMPLE_RATE))
                out[i + k] += (amplitude * env * sin(2.0 * PI * f * k / SAMPLE_RATE)).toFloat()
            }
        }
        return out
    }

    /** An overloaded input stage: everything becomes a square wave with clipping edges. */
    fun clipping(seconds: Double = 20.0, seed: Long = 7L): FloatArray {
        val base = speech(seconds, amplitude = 1.0, seed = seed)
        return FloatArray(base.size) { (base[it] * 8f).coerceIn(-1f, 1f) }
    }

    /** Mixes a fixture into another at a given relative level. */
    fun mix(a: FloatArray, b: FloatArray, bGain: Double = 1.0): FloatArray {
        val n = maxOf(a.size, b.size)
        return FloatArray(n) {
            var v = 0.0
            if (it < a.size) v += a[it]
            if (it < b.size) v += b[it] * bGain
            v.coerceIn(-1.0, 1.0).toFloat()
        }
    }

    /** Every fixture, named, for corpus-wide sweeps. */
    fun corpus(seconds: Double = 20.0): List<Pair<String, FloatArray>> = listOf(
        "silence" to silence(seconds),
        "white-noise" to whiteNoise(seconds),
        "pink-noise" to pinkNoise(seconds),
        "steady-carrier" to steadyCarrier(seconds = seconds),
        "mains-hum" to mainsHum(seconds),
        "speech" to speech(seconds),
        "music" to music(seconds),
        "rtty" to rtty(seconds = seconds),
        "knocking" to knocking(seconds),
        "clipping" to clipping(seconds),
        "speech+hum" to mix(speech(seconds), mainsHum(seconds), 0.5),
        "music+noise" to mix(music(seconds), pinkNoise(seconds), 0.4),
    )

    /** Guard for the fixtures themselves: silence excepted, these must carry real level. */
    fun rms(x: FloatArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return kotlin.math.sqrt(s / x.size)
    }

    fun peak(x: FloatArray): Double = x.maxOfOrNull { abs(it).toDouble() } ?: 0.0
}

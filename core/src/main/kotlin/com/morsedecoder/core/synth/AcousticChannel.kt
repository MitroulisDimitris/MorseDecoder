package com.morsedecoder.core.synth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The path from a loudspeaker, through a room, into a phone microphone.
 *
 * ### Why this exists
 *
 * Every fixture in the corpus is generated at the *source*: a keyed tone plus additive
 * noise, fading, impulses and timing impairment. That models a receiver's audio output
 * fed to the decoder by wire. It does not model the way the app is actually used — a
 * phone held in a room, pointed at a speaker — and the difference is not cosmetic.
 *
 * Measured on the bench, machine-sent code at the source decodes at **2.5 %** CER live and
 * **0.0 %** on the file path. Reported from a phone pointed at a Morse video, the same
 * class of signal produces a correct tone lock, a plausible WPM and unusable text. Nothing
 * in the synthetic matrix reproduces that, which is why the bench read healthy throughout.
 *
 * ### The mechanism this is built to expose
 *
 * Morse decoding rests on the ratio between marks and gaps, and a room breaks that ratio
 * **asymmetrically**:
 *
 * - A reverberant tail outlives key-up, so every mark is heard longer than it was sent and
 *   every gap shorter. Dots and dashes are lengthened by roughly the same absolute amount,
 *   so the dash:dot *ratio* barely moves — the fitted unit and the reported WPM stay
 *   plausible while the gap lattice slides underneath them.
 * - Capture AGC raises its gain during key-up, lifting the room noise floor towards the
 *   keying threshold, which fills short gaps from the other direction.
 *
 * The decoder estimates the unit **from marks only** (`decisions.md` D-2), so it inherits
 * the reverb-inflated mark length and then measures gaps against it. A 3-unit
 * inter-character gap read against an inflated unit becomes a 1-unit intra-character gap,
 * characters merge, and the transcript is wrong at a correct tone and a sane speed.
 *
 * That failure is invisible to every signal the decoder publishes. `fitQuality` asks
 * whether the two-cluster mark model fits its own measurements, and it still does.
 * `OutputPlausibility` is one-sided by construction — its own documentation records that a
 * too-*long* unit moves marks-per-character *up*, into the range real text occupies. So
 * this channel is expected to land squarely in the gap between the two, which is the
 * reason to build it rather than argue about it.
 *
 * ### Not a room simulator
 *
 * Schroeder's four combs and two allpasses, one-pole band limits, and a peak-following
 * AGC. It is O(n), deterministic, and makes no claim to acoustic accuracy. The claim it
 * does make is that the *sign and rough scale* of the mark/gap distortion are right, which
 * is all a fixture needs in order to stop the corpus being silent about this failure.
 *
 * Calibrate it against a real recording before trusting any threshold fitted to it —
 * `progress.md` lesson 5 is specifically about what happens otherwise.
 */
object AcousticChannel {

    /**
     * @param rt60S       Reverberation time: seconds for the tail to fall 60 dB. A desk beside
     *                    a laptop is ~0.25 s, a hard-floored room 0.5–0.8 s. 0 disables reverb.
     * @param reverbMix   Fraction of the output taken from the reverberant field. 0 is
     *                    anechoic; 0.3 is a phone a foot from the speaker; 0.6 is across the room.
     * @param highPassHz  Speaker roll-off. A laptop or phone speaker gives up below ~250 Hz.
     * @param lowPassHz   Microphone/codec roll-off. 0 disables.
     * @param agc         Capture AGC, or null for none.
     */
    data class Config(
        val rt60S: Double = 0.0,
        val reverbMix: Double = 0.0,
        val highPassHz: Double = 0.0,
        val lowPassHz: Double = 0.0,
        val agc: Agc? = null,
    )

    /**
     * A peak-following automatic gain control, the kind a phone applies to microphone input.
     *
     * The decode-relevant behaviour is the **release**: during key-up the follower decays,
     * the gain climbs, and the room noise floor is amplified toward the keying threshold.
     * A release slower than an inter-character gap therefore fills gaps selectively — long
     * ones more than short ones — which is a distortion no static SNR figure describes.
     */
    data class Agc(
        /** Output level the controller drives towards. */
        val target: Double = 0.25,
        /** Seconds to respond to a level increase. Fast, as capture AGC is. */
        val attackS: Double = 0.010,
        /** Seconds to respond to a level decrease. This is the parameter that fills gaps. */
        val releaseS: Double = 0.400,
        /** Ceiling on the gain the controller may apply, so silence is not amplified without bound. */
        val maxGain: Double = 12.0,
    )

    /** Comb delays in samples at 16 kHz, mutually prime, ~21–34 ms — small-room early reflections. */
    private val COMB_DELAYS_16K = intArrayOf(331, 401, 467, 547)

    /** Allpass delays in samples at 16 kHz: ~7 ms and ~2 ms, for diffusion only. */
    private val ALLPASS_DELAYS_16K = intArrayOf(113, 37)

    private const val ALLPASS_G = 0.7

    /**
     * Speaker → room → microphone diaphragm: reverberation and the transducer band limits.
     *
     * This is deliberately **not** the whole channel. Room noise is additive at the
     * microphone, so it belongs between this stage and [capture] — which is where the
     * generator's own `snrDb` already sits. Applying AGC before the noise would let the
     * controller ride a noise floor that does not exist yet, and the interaction between
     * AGC release and the noise floor is the entire point of modelling either.
     */
    fun room(input: FloatArray, sampleRate: Int, cfg: Config): FloatArray {
        var x = input
        if (cfg.rt60S > 0.0 && cfg.reverbMix > 0.0) x = reverb(x, sampleRate, cfg.rt60S, cfg.reverbMix)
        if (cfg.highPassHz > 0.0) x = onePoleHighPass(x, sampleRate, cfg.highPassHz)
        if (cfg.lowPassHz > 0.0) x = onePoleLowPass(x, sampleRate, cfg.lowPassHz)
        return x
    }

    /** The capture chain, applied last: AGC over whatever reached the microphone. */
    fun capture(input: FloatArray, sampleRate: Int, cfg: Config): FloatArray =
        cfg.agc?.let { agc(input, sampleRate, it) } ?: input

    /**
     * Schroeder reverb: parallel feedback combs for the decay, series allpasses for diffusion.
     *
     * Comb feedback is solved from the requested RT60 — a comb of delay `d` and gain `g`
     * decays 60 dB in `d * ln(1e-3) / ln(g)` samples — so `rt60S` means what it says rather
     * than being a dial to turn.
     */
    private fun reverb(x: FloatArray, sampleRate: Int, rt60S: Double, mix: Double): FloatArray {
        val scale = sampleRate / 16_000.0
        val combs = COMB_DELAYS_16K.map { max(1, (it * scale).toInt()) }
        val allpasses = ALLPASS_DELAYS_16K.map { max(1, (it * scale).toInt()) }
        val rt60N = rt60S * sampleRate

        val wet = FloatArray(x.size)
        for (d in combs) {
            // g such that the comb loses 60 dB over rt60N samples.
            val g = 10.0.pow(-3.0 * d / rt60N).coerceIn(0.0, 0.999)
            val buf = FloatArray(d)
            var i = 0
            for (n in x.indices) {
                val delayed = buf[i]
                val v = x[n] + g * delayed
                buf[i] = v.toFloat()
                wet[n] += delayed
                i++
                if (i == d) i = 0
            }
        }
        // Parallel combs sum coherently on the direct hit; divide it back out.
        val inv = 1.0f / combs.size
        for (n in wet.indices) wet[n] *= inv

        for (d in allpasses) {
            val buf = FloatArray(d)
            var i = 0
            for (n in wet.indices) {
                val delayed = buf[i]
                val v = wet[n] + ALLPASS_G * delayed
                buf[i] = v.toFloat()
                wet[n] = (delayed - ALLPASS_G * v).toFloat()
                i++
                if (i == d) i = 0
            }
        }

        // Normalise the wet path to the dry RMS before mixing. Without this the comb bank's
        // output level grows with `rt60S` — a longer tail means more energy circulating — so
        // `reverbMix` would not mean the same thing at two different RT60s and the sweep
        // would be confounded. Measured before this was added: the CER response to `mix` was
        // sharply non-monotonic (12.7 % at mix 0.80 against 29.1 % at 0.65), which is a
        // property of the model's gain staging, not of any room.
        val dryRms = rms(x)
        val wetRms = rms(wet)
        if (wetRms > 1e-9) {
            val g = (dryRms / wetRms).toFloat()
            for (n in wet.indices) wet[n] *= g
        }

        val out = FloatArray(x.size)
        val dryGain = 1.0 - mix
        for (n in x.indices) out[n] = (dryGain * x[n] + mix * wet[n]).toFloat()
        return out
    }

    private fun rms(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return kotlin.math.sqrt(s / max(1, x.size))
    }

    /**
     * Peak follower with asymmetric time constants, driving a gain toward [Agc.target].
     *
     * The follower tracks the signal envelope; the gain is the ratio the controller wants,
     * slewed at the same rates. Both stages are smoothed so the gain does not step at a key
     * edge — a stepping gain would put a click into the envelope and be a different defect.
     */
    private fun agc(x: FloatArray, sampleRate: Int, cfg: Agc): FloatArray {
        val aAtt = coef(cfg.attackS, sampleRate)
        val aRel = coef(cfg.releaseS, sampleRate)
        val out = FloatArray(x.size)
        var env = 0.0
        var gain = 1.0
        // Below this the follower is reading numerical dust, and target/env would explode.
        val floor = 1e-5
        for (n in x.indices) {
            val a = abs(x[n]).toDouble()
            env = if (a > env) a + (env - a) * aAtt else a + (env - a) * aRel
            val want = min(cfg.maxGain, cfg.target / max(env, floor))
            // Gain *down* is the attack case: the signal got louder than the controller likes.
            gain = if (want < gain) want + (gain - want) * aAtt else want + (gain - want) * aRel
            out[n] = (x[n] * gain).toFloat()
        }
        return out
    }

    /** One-pole smoothing coefficient for a time constant of [tauS] seconds. */
    private fun coef(tauS: Double, sampleRate: Int): Double =
        if (tauS <= 0.0) 0.0 else exp(-1.0 / (tauS * sampleRate))

    private fun onePoleLowPass(x: FloatArray, sampleRate: Int, cutoffHz: Double): FloatArray {
        val a = coef(1.0 / (2.0 * Math.PI * cutoffHz), sampleRate)
        val out = FloatArray(x.size)
        var y = 0.0
        for (n in x.indices) {
            y = x[n] + (y - x[n]) * a
            out[n] = y.toFloat()
        }
        return out
    }

    private fun onePoleHighPass(x: FloatArray, sampleRate: Int, cutoffHz: Double): FloatArray {
        val a = coef(1.0 / (2.0 * Math.PI * cutoffHz), sampleRate)
        val out = FloatArray(x.size)
        var y = 0.0
        for (n in x.indices) {
            y = x[n] + (y - x[n]) * a
            out[n] = (x[n] - y).toFloat()
        }
        return out
    }
}

package com.morsedecoder.core.bench

import com.morsedecoder.core.audio.WavIo
import com.morsedecoder.core.dsp.Goertzel
import com.morsedecoder.core.dsp.KeyingSpread
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What room noise looks like to the two noise guards — measured, not assumed.
 * Asserts nothing; skipped unless `DIAG_WAV` is set.
 *
 *   DIAG_WAV=room.wav[,clip.wav...] gradlew :core:test --tests "*NoiseFloorDiagnostic*" -i
 *
 * For each file and each frequency on a 300–2000 Hz grid it reports:
 *
 * - **spread10s** — p95/p20 of the 10 ms Goertzel envelope over 10 s windows: the
 *   statistic the live path's low-signal guard compares with `keyingSpreadRatio`
 *   (`LiveDecoderEngine.processFrame`). For Gaussian noise the power is exponential and
 *   the ratio is ln 20 / ln 1.25 ≈ 13.4, a hair under the 15 the guard uses.
 * - **keyed%** — fraction of 600 ms probes where `KeyingSpread.of` clears
 *   `LOCK_MIN_SPREAD`: how often acquisition would accept that frequency as keyed.
 *
 * Run it on a capture of a quiet room (`tools/device-run.sh` with a silent clip) to see
 * the noise side, and on a capture with Morse playing to see the signal side; the
 * thresholds belong in the gap between them, if there is one.
 */
class NoiseFloorDiagnostic {

    private fun pct(a: DoubleArray, p: Double): Double {
        val s = a.sortedArray(); return s[((s.size - 1) * p).toInt()]
    }

    @Test
    fun diagnose() {
        val list = System.getenv("DIAG_WAV")
        assumeTrue(list != null, "DIAG_WAV not set")
        for (path in list!!.split(',')) {
            val wav = WavIo.read(File(path))
            val x = wav.samples
            val sr = wav.sampleRate
            val win = sr / 100           // 10 ms
            val hop = sr / 400           // 2.5 ms
            val tenS = 10 * sr
            val probe = (0.6 * sr).toInt()
            println("\nNOISE-FLOOR DIAGNOSTIC  ${File(path).name}  %.1f s".format(x.size.toDouble() / sr))
            println("%6s  %-34s  %-28s  %s".format("Hz", "spread10s min/med/max", "probe spread p50/p90/max", "keyed%"))
            val allSpreads = ArrayList<Double>()
            var f = 300.0
            while (f <= 2000.0) {
                val g = Goertzel(sr, f, win)
                val frames = (x.size - win) / hop
                val env = DoubleArray(frames) { g.power(x, it * hop) }
                val per10 = tenS / hop
                val spreads = ArrayList<Double>()
                var s = 0
                while (s + per10 <= frames) {
                    val w = env.copyOfRange(s, s + per10)
                    spreads.add(pct(w, 0.95) / maxOf(pct(w, 0.20), 1e-18))
                    s += per10 / 2
                }
                allSpreads.addAll(spreads)
                val probes = ArrayList<Double>()
                var p = 0
                while (p + probe <= x.size) {
                    probes.add(KeyingSpread.of(x.copyOfRange(p, p + probe), sr, f))
                    p += probe / 2
                }
                val pa = probes.toDoubleArray()
                val keyed = probes.count { it >= KeyingSpread.LOCK_MIN_SPREAD } * 100.0 / maxOf(1, probes.size)
                val sa = spreads.toDoubleArray()
                println("%6.0f  %-34s  %-28s  %5.1f".format(
                    f,
                    if (sa.isEmpty()) "-" else "%7.1f %7.1f %7.1f".format(sa.min(), pct(sa, 0.5), sa.max()),
                    "%7.1f %7.1f %8.1f".format(pct(pa, 0.5), pct(pa, 0.9), pa.max()),
                    keyed,
                ))
                f += 50.0
            }
            if (allSpreads.isNotEmpty()) {
                val a = allSpreads.toDoubleArray()
                println("ALL FREQUENCIES spread10s: p5 %.1f  p50 %.1f  p95 %.1f  max %.1f   (guard: < 15 means low signal)"
                    .format(pct(a, 0.05), pct(a, 0.5), pct(a, 0.95), a.max()))
            }
        }
    }
}

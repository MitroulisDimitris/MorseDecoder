package com.morsedecoder.core.model

/**
 * Can the decoded output be text at all? (`known-issues.md` D-1, plan step 4.)
 *
 * ### Why this exists, and why it cannot be a timing statistic
 *
 * Morse timing is **scale-free**. Marks are 1 and 3 units, gaps 1, 3 and 7, and every one
 * of those is a ratio — so multiplying the unit by two preserves all of them. That is why
 * `modelFitQuality` reports a perfect 1.00 on a decode measured at 297 % CER (D-1): it
 * asks whether the two-cluster model fits its own measurements, and a wrong *scale* leaves
 * that question untouched. No threshold on any timing score can see the error, because
 * timing genuinely does not contain it.
 *
 * The scale-freedom breaks in exactly one place: **the decoded characters**. A unit an
 * octave short turns every dash into a run of dots, so characters shatter and the
 * transcript becomes `TA C Q TEE E EEE EEET ETTTT`. Real text is not built from single
 * elements; that transcript is 98.5 % `E`/`T`/`I`/`M`. So the statistic is simply the mean
 * number of **marks per committed character**, which sits near 3.4 on real text and falls
 * to 1.0 when the timebase is an octave out.
 *
 * ### It reports; it never suppresses
 *
 * Deliberate, for two reasons. The window is 48 characters, so by the time this can speak
 * the wrong characters are already emitted and P-2 forbids retracting them — it can label
 * the state, not prevent it. And the threshold is fitted to a synthetic corpus, which
 * `progress.md` lesson 5 shows is how the D-8 structure gate came to reject genuine
 * hand-sent Morse and accept RTTY. A wrong flag is a wrong flag; a wrong *gate* silently
 * deletes a real transmission.
 *
 * ### What it does not catch
 *
 * A unit that is too **long** merges characters instead of shattering them, which moves
 * this statistic *up*, into a range real text also occupies (measured: 3.73 against a
 * healthy maximum of 3.44). This is a one-sided test and only the fast side is detectable.
 *
 * ### Where the numbers come from
 *
 * `ScaleErrorDiagnostic`, over the 16 bench scenarios, the same 16 with the unit pinned an
 * octave fast, and the non-Morse corpus. Measured minima of the rolling window:
 *
 * | population | lowest window seen | binding fixture |
 * |---|---|---|
 * | healthy | **2.38** | `hesitation` (`hand-13-rough` next at 2.60) |
 * | pinned an octave fast | **1.58** | `hand-13-rough @26` (the rest ≤ 1.15) |
 *
 * [DEFAULT_IMPLAUSIBLE_BELOW] sits at the geometric middle of that gap: 1.20x above every
 * measured scale error, 1.25x below every measured healthy decode. **The margin is 1.5x
 * and the two rows that bind it are the two most human fixtures in the corpus** — which is
 * the whole of lesson 5's warning, and the second reason this only ever reports.
 *
 * The window length is the only parameter that matters; the shape is not. An EWMA of the
 * same nominal length separates identically (1.53x against the boxcar's 1.50x), and every
 * shorter window is worse — at 16 characters the populations **invert**, because a healthy
 * `hesitation` decode dips to 1.13 while a genuine scale error on a rough fist sits at
 * 1.19. Anything that reacts fast enough to be useful early is measuring noise.
 */
class OutputPlausibility(
    private val window: Int = DEFAULT_WINDOW,
    private val implausibleBelow: Double = DEFAULT_IMPLAUSIBLE_BELOW,
) {
    private val ring = IntArray(window)
    private var count = 0
    private var idx = 0
    private var sum = 0
    private var worstSeen = Double.NaN

    /** True once a full window of committed characters has been observed. */
    val ready: Boolean get() = count >= window

    /**
     * Mean marks per character over the last [window] characters, or **null before the
     * window fills** — "no opinion" and "plausible" must not be the same value, for the
     * same reason `fitQuality` is nullable (D-5).
     */
    val marksPerChar: Double? get() = if (ready) sum.toDouble() / window else null

    /** The lowest full-window value seen since the last [clear]; null if never ready. */
    val worstMarksPerChar: Double? get() = if (worstSeen.isNaN()) null else worstSeen

    /** The transcript cannot be text: see the class docs. False until the window fills. */
    val implausible: Boolean get() = ready && sum.toDouble() / window < implausibleBelow

    /** The threshold in force, so a debug readout can mark it. */
    val threshold: Double get() = implausibleBelow

    /** [marks] is the number of marks (not elements) in one committed character. */
    fun add(marks: Int) {
        if (count == window) sum -= ring[idx] else count++
        ring[idx] = marks
        idx = (idx + 1) % window
        sum += marks
        if (ready) {
            val v = sum.toDouble() / window
            if (worstSeen.isNaN() || v < worstSeen) worstSeen = v
        }
    }

    /**
     * Forget everything. The statistic describes characters decoded against one timebase,
     * so a re-lock or an adopted re-bootstrap invalidates the history rather than adding
     * to it.
     */
    fun clear() {
        count = 0
        idx = 0
        sum = 0
        worstSeen = Double.NaN
        ring.fill(0)
    }

    companion object {
        /**
         * Characters the statistic averages over. Long, because every shorter window
         * measured worse and the shortest inverted — see the class docs. The failure this
         * detects fills the window unusually fast: a scale error emits 3-4x as many
         * characters per second as a correct decode (202 against 61 on the same audio),
         * so 48 characters is roughly 10 s of a broken decode against 35 s of a good one.
         */
        const val DEFAULT_WINDOW = 48

        /** Mean marks per character below which the output cannot be real text. */
        const val DEFAULT_IMPLAUSIBLE_BELOW = 1.9
    }
}

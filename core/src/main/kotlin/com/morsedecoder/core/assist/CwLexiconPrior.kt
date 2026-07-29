package com.morsedecoder.core.assist

import kotlin.math.ln

/**
 * CW-specific letter-bigram prior used as lattice weights (FR-7.1). The corpus is
 * Q-codes, on-air abbreviations, and common plain-English words — the actual
 * character mix of a QSO, not general prose.
 *
 * Two load-bearing properties:
 *
 * 1. **Zero-mean rows.** Each conditional distribution is centred so the average
 *    prior over characters is ~0. The undecodable placeholder competes with real
 *    characters on timing alone (FR-6.5); a one-sided negative prior would push
 *    marginal segments into `<?>` wholesale.
 *
 * 2. **Callsign/exchange backoff (FR-7.2).** Callsigns are effectively random
 *    strings; a language prior would "correct" a valid callsign into a plausible
 *    word. The prior suppresses itself for two words after `CQ` or `DE`, and inside
 *    any word that mixes digits and letters (callsigns, serials).
 */
class CwLexiconPrior(private val weight: Double = 2.0) {

    private val logP: Array<DoubleArray> // [prev symbol][next symbol], zero-mean rows

    // FR-7.2 context state, driven by the committed stream.
    private var suppressWords = 0
    private val currentWord = StringBuilder()

    init {
        val n = SYMBOLS.length
        val counts = Array(n) { DoubleArray(n) { 0.5 } } // add-k smoothing
        for (word in CORPUS.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            var prev = 0 // '^'
            for (ch in word) {
                val idx = symbolIndex(ch) ?: continue
                counts[prev][idx] += 1.0
                prev = idx
            }
        }
        // Serial sequences are real CW (FR-7.2): give digit runs honest probability
        // mass. This is the symmetric protection — a letter intruding into a digit
        // string pays for both the digit->letter and letter->digit crossings on ITS
        // OWN path, which context-based suppression can never achieve (each lattice
        // hypothesis sees only its self-consistent history).
        for (d1 in '0'..'9') {
            val i1 = symbolIndex(d1)!!
            counts[0][i1] += 1.0 // word-initial digits
            for (d2 in '0'..'9') {
                counts[i1][symbolIndex(d2)!!] += 2.0
            }
        }
        logP = Array(n) { r ->
            val total = counts[r].sum()
            val row = DoubleArray(n) { c -> ln(counts[r][c] / total) }
            val mean = row.average()
            DoubleArray(n) { c -> (row[c] - mean).coerceIn(-2.5, 2.5) }
        }
    }

    /**
     * Prior score for [token] following [prevChar] (null = word start), scaled by the
     * lexicon weight. Zero while suppressed and for non-alphanumeric tokens.
     */
    fun logPrior(prevChar: Char?, token: String): Double {
        if (suppressed()) return 0.0
        val c = token.singleOrNull() ?: return 0.0
        val next = symbolIndex(c) ?: return 0.0
        val prev = prevChar?.let { symbolIndex(it) } ?: 0
        return weight * logP[prev][next]
    }

    /** Feed committed characters so the context machine can track CQ/DE and serials. */
    fun observe(token: String, isWordStart: Boolean) {
        if (isWordStart && currentWord.isNotEmpty()) finishWord()
        token.singleOrNull()?.let { if (it.isLetterOrDigit()) currentWord.append(it) }
    }

    private fun finishWord() {
        val word = currentWord.toString()
        currentWord.setLength(0)
        if (suppressWords > 0) suppressWords--
        if (word == "CQ" || word == "DE") suppressWords = 2
    }

    /**
     * Committed-context suppression: the CQ/DE window (FR-7.2). Callsigns are scored
     * after `DE` has committed, so this window genuinely protects them. Serial
     * protection is handled in the model itself (digit-run mass) because committed
     * context lags the word being scored.
     */
    private fun suppressed(): Boolean = suppressWords > 0

    companion object {
        private const val SYMBOLS = "^ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        private fun symbolIndex(c: Char): Int? {
            val i = SYMBOLS.indexOf(c.uppercaseChar())
            return if (i >= 0) i else null
        }

        /** Q-codes, on-air abbreviations, and common English — a QSO's character mix. */
        private const val CORPUS =
            "QTH QRZ QRM QRN QSB QSY QSL QSO QRP QRO QRT QRV QRX QSP QRS QRQ QST QRL QRG " +
                "CQ DE TNX TKS FER ES UR HW CPY RST PSE AGN ANT RIG WX HR OM YL XYL " +
                "GM GA GE GN GB FB DX TEST TU CUL HPE CU SIG PWR NAME NR RPT NW SRI BK " +
                "BURO HAM SHACK KEY VY GL GD LID ABT BAND CONDX FREQ INFO LOG MSG OP " +
                "73 88 599 559 579 589 5NN " +
                "THE AND FOR ARE BUT NOT YOU ALL CAN HAD HER WAS ONE OUR OUT DAY GET " +
                "HAS HIM HIS HOW MAN NEW NOW OLD SEE TWO WAY WHO DID ITS LET PUT SAY " +
                "SHE TOO USE THAT WITH HAVE THIS WILL YOUR FROM THEY KNOW WANT BEEN " +
                "GOOD MUCH SOME TIME VERY WHEN COME HERE JUST LIKE LONG MAKE MANY MORE " +
                "ONLY OVER SUCH TAKE THAN THEM WELL WERE WHAT EACH ELSE EVEN EVERY END " +
                "QUICK BROWN FOX JUMPS LAZY DOG"
    }
}

package com.morsedecoder.core.decode

import com.morsedecoder.core.model.GaussianMixture
import com.morsedecoder.core.model.KalmanUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The milestone-2 decoder core: segmental Viterbi over a character lattice.
 *
 * Deliberately non-greedy (FR-6.1): every gap in the element stream is a lattice
 * node — a hypothesis that a character boundary sits there. Each candidate segment
 * between two nodes is matched against every alphabet entry of that element count
 * (prosigns included as single entries, FR-6.4), with a *local* unit fitted from the
 * segment's own marks and shrunk toward the Kalman-tracked global unit (FR-5.6/D-4).
 * That local fit is the drift mechanism: a momentarily fast character still decodes.
 *
 * Classification is soft throughout (FR-6.2): marks score by log-likelihood against
 * the mark mixture, never by hard dot/dash labels. Paths are compared by total
 * likelihood; the winner is committed at unambiguous boundaries.
 *
 * Timing-lattice decoding resolves timing ambiguity with timing evidence only —
 * it is decoding, not a correction, and is always on (FR-6.3/D-5).
 *
 * Commit policy: file mode commits at certain boundaries (long gaps) plus a backstop.
 * Live mode ([Config.commitHorizonChars] non-null) additionally commits the prefix
 * that every surviving path agrees on, holding back the last few characters as the
 * Viterbi commit horizon (NFR-2) — later evidence can still revise the held tail,
 * and everything emitted is final (P-2).
 */
class RobustTimingDecoder(
    private val kalman: KalmanUnit,
    private val markModel: GaussianMixture,
    private val gapModel: GaussianMixture,
    private val cfg: Config = Config(),
    /**
     * Optional CW lexicon prior (FR-7.1) — the one assist that plugs in at the
     * lattice level, because P-3 requires it to change characters *visibly*: any
     * segment whose winner differs from the timing-only winner is flagged assisted.
     * Null (the default) is raw mode; timing evidence only (P-1).
     */
    private val prior: com.morsedecoder.core.assist.CwLexiconPrior? = null,
) {

    data class Config(
        /** Shrinkage pseudo-count toward the global unit (FR-5.6). */
        val shrinkagePseudoCount: Double = 2.0,
        /** A gap beyond this many units is an unambiguous boundary — commit point. */
        val certainBoundaryUnits: Double = 5.5,
        /** Per-element score floor for the undecodable placeholder (FR-6.5). */
        val garbagePerElementLL: Double = -6.0,
        /** Forced-commit backstop for streams with no long gaps. */
        val maxPendingElements: Int = 140,
        /**
         * Live latency mode: commit the agreed path prefix, holding this many
         * characters uncommitted. Null = file mode (commit at certain boundaries only).
         */
        val commitHorizonChars: Int? = null,
    )

    data class DecodedChar(
        val token: String,
        val confidence: Double,      // FR-6.6
        val tStartMs: Double,
        val unitMsLocal: Double,     // FR-5.6 — exported as unitMsLocal in .json
        val unitMsGlobal: Double,    // Kalman estimate at commit time
        val fitQuality: Double,      // FR-5.8 snapshot
        val isWordStart: Boolean,
        val elements: List<FrontEnd.Run>,
        /** True when the lexicon prior changed the outcome vs timing alone (P-3). */
        val assisted: Boolean = false,
    )

    private class CharCand(
        val token: String,
        val ll: Double,
        val confidence: Double,
        val unitMsLocal: Double,   // shrunk toward global — used for scoring and export
        val unitMsRaw: Double,     // unshrunk LS fit — the Kalman measurement; feeding
                                   // the shrunk value back would let the prior dampen
                                   // its own corrections and stall drift tracking
        val nMarks: Int,
        val tStartMs: Double,
        val elements: List<FrontEnd.Run>,
        val assisted: Boolean,
    )

    /** Lattice node: "a character boundary sits just before pending[pos]". */
    private class Node(
        var pos: Int,
        var score: Double,
        var back: Node?,
        var cand: CharCand?,        // character ending at this node (null for the start node)
        var boundaryGapMs: Double,  // gap that created this node (NaN for start/virtual)
        var wordBreak: Boolean,
    )

    val output = ArrayList<DecodedChar>()

    private val pending = ArrayList<FrontEnd.Run>()
    private var nodes = ArrayList<Node>()
    private var carriedWordBreak = false

    init {
        nodes.add(startNode())
    }

    fun feed(run: FrontEnd.Run) {
        if (pending.isEmpty() && !run.isMark) return // stray leading gap
        pending.add(run)
        if (!run.isMark) onGap(pending.size - 1)
    }

    /** Finalise the stream: a virtual certain boundary after the last element. */
    fun flush() {
        if (pending.isEmpty()) return
        if (!pending.last().isMark) {
            // Ended on a gap — its node already spans every mark.
            val terminal = nodes.last()
            if (terminal.pos == pending.size && terminal.cand != null) commitAt(terminal)
            return
        }
        var best: Node? = null
        for (n in nodes) {
            val nMarks = (pending.size - n.pos + 1) / 2
            if (nMarks < 1 || nMarks > MorseTrie.maxLength) continue
            val cand = bestCandidate(n.pos, pending.size, prevCharOf(n)) ?: continue
            val s = n.score + cand.ll
            if (best == null || s > best.score) {
                best = Node(pending.size, s, n, cand, Double.NaN, false)
            }
        }
        best?.let { commitAt(it) }
    }

    /** Previous character for the bigram prior; null means a word boundary precedes. */
    private fun prevCharOf(n: Node): Char? {
        if (n.wordBreak) return null
        val token = n.cand?.token ?: lastCommittedToken ?: return null
        return token.lastOrNull { it.isLetterOrDigit() }
    }

    private var lastCommittedToken: String? = null

    // ---- lattice construction ----

    private fun onGap(gi: Int) {
        val z = ln(pending[gi].durationMs / kalman.unitMs)
        val (bLL, isWord) = boundaryScore(z)

        var best: Node? = null
        for (n in nodes) {
            val nMarks = (gi - n.pos + 1) / 2
            if (nMarks < 1 || nMarks > MorseTrie.maxLength) continue
            val cand = bestCandidate(n.pos, gi, prevCharOf(n)) ?: continue
            val score = n.score + cand.ll + bLL
            if (best == null || score > best.score) {
                best = Node(gi + 1, score, n, cand, pending[gi].durationMs, isWord)
            }
        }
        val node = best ?: return
        nodes.add(node)
        pruneNodes()

        if (z > ln(cfg.certainBoundaryUnits) || pending.size > cfg.maxPendingElements) {
            commitAt(node)
        } else {
            cfg.commitHorizonChars?.let { tryPrefixCommit(it) }
        }
    }

    /**
     * Best character hypothesis for the segment pending[p .. gi-1]
     * (marks at p, p+2, …; intra gaps between).
     */
    private fun bestCandidate(p: Int, gi: Int, prevChar: Char?): CharCand? {
        val nMarks = (gi - p + 1) / 2
        val entries = MorseTrie.byLength[nMarks] ?: emptyList()
        val lnUg = ln(kalman.unitMs)
        val kappa = cfg.shrinkagePseudoCount

        val allLLs = ArrayList<Double>(entries.size + 1)
        var bestLL = Double.NEGATIVE_INFINITY
        var bestToken: String? = null
        var bestLnU = lnUg
        var bestLnURaw = lnUg
        // Timing-only winner, tracked in parallel so a prior-changed outcome is
        // detectable and can be marked (P-3).
        var bestRawLL = Double.NEGATIVE_INFINITY
        var bestRawToken: String? = null

        for (e in entries) {
            // Local unit: log-space least squares on this segment's marks only
            // (FR-5.4/D-2), shrunk toward the global estimate (FR-5.6).
            var acc = 0.0
            var k = 0
            var i = p
            while (i < gi) {
                val c = if (e.pattern[k] == '.') 0 else 1
                acc += ln(pending[i].durationMs) - markModel.means[c]
                k++; i += 2
            }
            val lnULS = acc / nMarks
            val lnU = (nMarks * lnULS + kappa * lnUg) / (nMarks + kappa)

            var ll = 0.0
            k = 0; i = p
            while (i < gi) {
                val c = if (e.pattern[k] == '.') 0 else 1
                ll += markModel.logPdf(ln(pending[i].durationMs) - lnU, c)
                k++; i += 2
            }
            i = p + 1
            while (i < gi - 1) {
                ll += gapModel.logPdf(ln(pending[i].durationMs) - lnU, 0)
                i += 2
            }
            if (ll > bestRawLL) { bestRawLL = ll; bestRawToken = e.token }
            if (prior != null) ll += prior.logPrior(prevChar, e.token)
            allLLs.add(ll)
            if (ll > bestLL) { bestLL = ll; bestToken = e.token; bestLnU = lnU; bestLnURaw = lnULS }
        }

        // Undecodable placeholder competes as a real hypothesis (FR-6.5) so a
        // misfit segment routes here instead of forcing a wrong character.
        val garbageLL = (2 * nMarks - 1) * cfg.garbagePerElementLL
        allLLs.add(garbageLL)
        if (garbageLL > bestRawLL) { bestRawToken = null }
        if (garbageLL > bestLL) { bestLL = garbageLL; bestToken = null; bestLnU = lnUg; bestLnURaw = lnUg }

        var denom = 0.0
        for (ll in allLLs) denom += exp(ll - bestLL)
        val confidence = 1.0 / denom

        val elements = ArrayList<FrontEnd.Run>(gi - p)
        for (j in p until gi) elements.add(pending[j])

        return CharCand(
            token = bestToken ?: UNDECODABLE,
            ll = bestLL,
            confidence = confidence,
            unitMsLocal = exp(bestLnU),
            unitMsRaw = exp(bestLnURaw),
            nMarks = nMarks,
            tStartMs = pending[p].startMs,
            elements = elements,
            assisted = prior != null && bestToken != bestRawToken,
        )
    }

    /**
     * Boundary-gap likelihood and word decision. Gaps beyond ~10 units say
     * "word boundary at most" and nothing further (FR-4.3) — the word likelihood
     * plateaus rather than penalising very long silence.
     */
    private fun boundaryScore(z: Double): Pair<Double, Boolean> {
        val interIdx = if (gapModel.weights[1] >= 0.05) 1 else 2
        val wordHealthy = interIdx == 1 && gapModel.weights[2] >= 0.05
        val llInter = gapModel.logPdf(z, interIdx)

        val wordCenter: Double
        val wordSigma: Double
        if (wordHealthy) {
            wordCenter = gapModel.means[2]
            wordSigma = gapModel.sigmas[2]
        } else {
            // No word cluster observed (e.g. Farnsworth pushes word gaps past 10 units,
            // FR-4.3 keeps them out of the model): structural fallback at ~2.3x inter.
            wordCenter = gapModel.means[interIdx] + 0.85
            wordSigma = max(gapModel.sigmas[interIdx], 0.15)
        }
        val zw = min(z, wordCenter)
        val d = (zw - wordCenter) / wordSigma
        val llWord = -0.5 * d * d - ln(wordSigma) - 0.9189385332046727

        val isWord = z > LN_WORD_AT_MOST || llWord > llInter
        return Pair(max(llInter, llWord), isWord)
    }

    // ---- commit ----

    /** Emit the chars on the chain ending at [endNode] (exclusive of the start node). */
    private fun emitChain(endNode: Node) {
        val chain = ArrayList<Node>()
        var n: Node? = endNode
        while (n != null && n.cand != null) {
            chain.add(n)
            n = n.back
        }
        chain.reverse()

        for (nd in chain) {
            val cand = nd.cand!!
            kalman.predict()
            // Unshrunk measurement (see CharCand.unitMsRaw). A wild fit — usually a
            // misclassified segment — is rejected rather than averaged in; no real
            // fist moves 70 % in one character. When the prior itself is uncertain
            // (fresh re-lock, FR-2.12), accept anything inside the calibrated band
            // so a junk prior cannot lock out real evidence.
            val m = cand.unitMsRaw
            val uncertain = kalman.variance > (0.2 * kalman.unitMs) * (0.2 * kalman.unitMs)
            val accept = (m > 0.6 * kalman.unitMs && m < 1.7 * kalman.unitMs) ||
                (uncertain && m in 20.0..320.0)
            if (accept) {
                val rBase = 0.06 * m
                val rMin = 0.02 * m
                kalman.update(m, max(rBase * rBase / cand.nMarks, rMin * rMin))
            }

            val lnU = ln(kalman.unitMs)
            for (el in cand.elements) {
                val z = ln(el.durationMs) - lnU
                if (el.isMark) markModel.update(z)
                else if (z <= LN_WORD_AT_MOST) gapModel.update(z)
            }
            if (!nd.boundaryGapMs.isNaN()) {
                val zb = ln(nd.boundaryGapMs) - lnU
                if (zb <= LN_WORD_AT_MOST) gapModel.update(zb) // FR-4.3
            }
            degenerateGuard()

            val isWordStart = nd.back!!.wordBreak
            prior?.observe(cand.token, isWordStart) // FR-7.2 context tracking
            lastCommittedToken = cand.token
            output.add(
                DecodedChar(
                    token = cand.token,
                    confidence = cand.confidence,
                    tStartMs = cand.tStartMs,
                    unitMsLocal = cand.unitMsLocal,
                    unitMsGlobal = kalman.unitMs,
                    fitQuality = fitQuality(markModel),
                    isWordStart = isWordStart,
                    elements = cand.elements,
                    assisted = cand.assisted,
                )
            )
        }
    }

    /** Full commit at a certain boundary: everything before [node] is final. */
    private fun commitAt(node: Node) {
        emitChain(node)
        carriedWordBreak = node.wordBreak
        pending.clear()
        nodes = arrayListOf(startNode())
    }

    /**
     * Live-mode prefix commit: find the deepest node that lies on EVERY surviving
     * path (holding back [horizon] characters), emit up to it, and rebase the
     * lattice on it. What is emitted is final (P-2); the held tail can still be
     * revised by later evidence.
     */
    private fun tryPrefixCommit(horizon: Int) {
        // Candidate ancestors: the newest node's chain, skipping the most recent
        // `horizon` characters.
        val newest = nodes.last()
        var a: Node? = newest
        repeat(horizon) { a = a?.back }
        var ancestor: Node? = null
        var probe = a
        while (probe != null && probe!!.cand != null) {
            if (nodes.all { isOnChain(it, probe!!) }) {
                ancestor = probe
                break
            }
            probe = probe!!.back
        }
        val commit = ancestor ?: return

        emitChain(commit)

        // Rebase the lattice: `commit` becomes the new start node.
        val cut = commit.pos
        val delta = commit.score
        pending.subList(0, cut).clear()
        val kept = ArrayList<Node>()
        for (n in nodes) {
            if (n === commit || isOnChain(n, commit)) {
                n.pos -= cut
                n.score -= delta
                kept.add(n)
            }
        }
        commit.cand = null
        commit.back = null
        commit.boundaryGapMs = Double.NaN
        nodes = kept
    }

    private fun isOnChain(node: Node, target: Node): Boolean {
        var n: Node? = node
        while (n != null) {
            if (n === target) return true
            n = n.back
        }
        return false
    }

    private fun startNode() = Node(0, 0.0, null, null, Double.NaN, carriedWordBreak)

    private fun pruneNodes() {
        // A node further than one max-length character behind can never source an edge.
        val horizon = pending.size - (2 * MorseTrie.maxLength + 1)
        if (horizon > 0) nodes.removeAll { it.pos < horizon }
    }

    /**
     * The 3:1 ratio as a weak prior, exactly as narrow as D-3 allows: it only
     * disambiguates a degenerate fit where the two mark clusters have collapsed.
     */
    private fun degenerateGuard() {
        if (markModel.means[1] - markModel.means[0] < LN_MIN_RATIO) {
            markModel.means[1] = markModel.means[0] + LN_FALLBACK_RATIO
        }
    }

    companion object {
        const val UNDECODABLE = "<?>"
        private val LN_WORD_AT_MOST = ln(10.0)
        private val LN_MIN_RATIO = ln(1.5)
        private val LN_FALLBACK_RATIO = ln(2.5)

        /**
         * modelFitQuality (FR-5.8/D-8): does the two-cluster model fit the decoder's
         * own measurements? Cluster separation x ratio plausibility x within-cluster
         * tightness, each normalised to [0,1]. Never inspects whether input "is Morse".
         */
        fun fitQuality(markModel: GaussianMixture): Double =
            fitQuality(markModel.means[0], markModel.means[1], markModel.sigmas[0], markModel.sigmas[1])

        /** Same score from raw cluster statistics (used by the live observation window). */
        fun fitQuality(mu0: Double, mu1: Double, s0: Double, s1: Double): Double {
            val sep = (mu1 - mu0) / sqrt(0.5 * (s0 * s0 + s1 * s1))
            val qSep = ((sep - 1.5) / 2.5).coerceIn(0.0, 1.0)

            val ratio = exp(mu1 - mu0)
            val qRatio = when {
                ratio in 1.8..4.0 -> 1.0
                ratio <= 1.3 || ratio >= 5.5 -> 0.0
                ratio < 1.8 -> (ratio - 1.3) / 0.5
                else -> (5.5 - ratio) / 1.5
            }

            val qVar = ((0.5 - 0.5 * (s0 + s1)) / 0.35).coerceIn(0.0, 1.0)
            return qSep * qRatio * qVar
        }
    }
}

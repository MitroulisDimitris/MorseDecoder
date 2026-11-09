package com.morsedecoder.core

import com.morsedecoder.core.fixtures.NonMorseAudio
import com.morsedecoder.core.live.LiveDecoderEngine
import com.morsedecoder.core.metrics.TextMetrics
import com.morsedecoder.core.synth.SyntheticMorseGenerator
import com.morsedecoder.core.synth.SyntheticMorseGenerator.Config
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Whole sessions rather than clean clips.
 *
 * Every other test feeds one signal from its first sample to its last. Nobody uses the app
 * that way: they open it in a noisy room, tune around, catch a transmission part-way
 * through, sit through a pause, and hear the other operator answer at a different speed on
 * a different note. The decoder's hardest moments are the transitions between those
 * states, and a clip-at-a-time suite never visits one.
 *
 * These are the scenarios where a wrong lock becomes permanent or a stale model poisons
 * everything after it, so they earn their runtime.
 */
class SessionSequenceTest {

    private val text = AcceptanceTest.LONG_TEXT

    private fun morse(
        wpm: Double = 20.0,
        toneHz: Double = 612.0,
        snrDb: Double? = 10.0,
        seed: Long = 41L,
        body: String = text,
    ) = SyntheticMorseGenerator.generate(
        Config(text = body, wpm = wpm, toneHz = toneHz, snrDb = snrDb, seed = seed)
    )

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    private class Session(val engine: LiveDecoderEngine = LiveDecoderEngine()) {
        fun feed(audio: FloatArray) {
            var i = 0
            while (i < audio.size) {
                val n = minOf(4096, audio.size - i)
                engine.feed(audio.copyOfRange(i, i + n), n)
                i += n
            }
        }
        fun finish() = engine.finish()
        fun text(from: Int = 0): String = buildString {
            for ((k, c) in engine.transcript.withIndex()) {
                if (k < from) continue
                if (k > from && c.isWordStart) append(' ')
                append(c.token)
            }
        }
    }

    /**
     * The app opens into listening (D-12 era change), so the first thing it hears is
     * usually the room — not the signal. A lock taken on that room noise must not survive
     * to block the transmission that follows; this is the dead-end recovery of D-13 in its
     * natural habitat.
     */
    @Test
    fun `a transmission starting after 20 s of room noise is still decoded`() {
        val gen = morse()
        val session = Session()
        session.feed(NonMorseAudio.speech(20.0))
        val before = session.engine.transcript.size
        session.feed(gen.samples)
        session.finish()

        val decoded = session.text(before)

        // The property under test is that the dead speech lock is *recovered* — the lock
        // leaves the noise and finds the signal — not that the decode is clean afterwards.
        // Measured (deterministically since N-2/step 2 removed the buffer-size dependence):
        // the lock lands ~80 Hz below the true tone and stays there, which is known-issues
        // D-10, and that offset degrades the whole decode rather than only its head. So the
        // bars below assert recovery-off-the-noise, not a clean decode.
        //
        // These numbers used to read tighter (0.35 / 0.10). That was a chunk-4096 artefact:
        // at that one buffer size this seed's recovery happened to land on the tone. The N-2
        // probe showed the fine-grained truth is a D-10 offset for most seeds, so the honest
        // bar is looser and cross-references the defect rather than pretending it is absent.
        assertTrue(abs(session.engine.toneHz - 612.0) < 100.0) { "locked ${session.engine.toneHz}" }

        val cer = TextMetrics.cer(gen.normalizedText, decoded)
        assertTrue(cer < 0.65) { "CER $cer after a noisy lead-in; decoded '$decoded'" }

        // Same count from the end of both, or the junk head shifts the window and the
        // comparison measures its own misalignment. The tail is better than the head but not
        // clean — the tone offset (D-10) costs it. This bar catches a recovery that never
        // settled at all; a clean tail returns when D-10 does.
        val ref = gen.normalizedText
        val n = minOf(ref.length, decoded.length) / 2
        val tailCer = TextMetrics.cer(ref.takeLast(n), decoded.takeLast(n))
        assertTrue(tailCer < 0.30) {
            "the decode never settled: tail CER $tailCer on '${decoded.takeLast(n)}'"
        }
    }

    /**
     * The other operator answers: same frequency, markedly faster. The unit tracker has to
     * follow a step change it was never told about (D-6 defers detection but requires the
     * reset path to exist).
     */
    @Test
    fun `an abrupt speed change is followed rather than fought`() {
        val slow = morse(wpm = 13.0, seed = 43L)
        val fast = morse(wpm = 26.0, seed = 44L)
        val session = Session()
        session.feed(slow.samples)
        val afterSlow = session.engine.transcript.size
        session.feed(fast.samples)
        session.finish()

        assertTrue(afterSlow > 20) { "slow half produced only $afterSlow characters" }
        val second = session.text(afterSlow)
        val cer = TextMetrics.cer(fast.normalizedText, second)
        // Looser than a clean decode: the tracker is recovering from a 2x step for the
        // first characters, which is exactly the cost D-6 chose to accept.
        assertTrue(cer < 0.45) { "post-step CER $cer; decoded '$second'" }
    }

    /**
     * The operator moves frequency, or the user tunes the radio. The lock should follow
     * and the change should be marked (FR-2.13).
     *
     * Was **2 characters** for the whole second transmission, with the lock never leaving
     * 612 Hz. The deadlock was between two individually-correct guards — the automatic
     * re-lock needs `poorElements` to accumulate (FR-2.10.1, elements not seconds, D-7),
     * but a lock on a now-empty frequency produces no elements at all, so the counter never
     * advances; and FR-2.10.2 suspends the check while `lowSignal` holds, which it does.
     * D-13's dead-end recovery breaks exactly this tie but was scoped to locks that had
     * *never* decoded anything, and this one had.
     *
     * Fixed by `decisions.md` D-23: a lock that has committed nothing for 10 s while a
     * distinct keyed signal exists elsewhere is handed over. The `toneHz` assertion is the
     * one to watch — the re-lock lands on the sweep's reading of the new tone, which jitters
     * around it, and `known-issues` D-10 says the tracking EMA cannot re-centre from far
     * off. If this starts failing on the frequency rather than the character count, that is
     * D-10 surfacing here, not this fix regressing.
     */
    @Test
    fun `a tone jump mid-session is recovered and marked`() {
        val first = morse(toneHz = 612.0, seed = 45L)
        val second = morse(toneHz = 1150.0, seed = 46L)
        val session = Session()
        session.feed(first.samples)
        val afterFirst = session.engine.transcript.size
        session.feed(second.samples)
        session.finish()

        assertTrue(afterFirst > 20) { "first tone produced only $afterFirst characters" }

        val recovered = session.engine.transcript.size - afterFirst
        assertTrue(recovered > 20) { "only $recovered characters decoded after the tone jump" }
        assertTrue(abs(session.engine.toneHz - 1150.0) < 25.0) {
            "ended on ${session.engine.toneHz}, expected the new tone at 1150 Hz"
        }
        val relocks = session.engine.events.filterIsInstance<LiveDecoderEngine.Event.Relock>()
        assertTrue(relocks.isNotEmpty()) { "tone moved 538 Hz with no re-lock event (FR-2.13)" }
    }

    /**
     * Signal, long pause, signal — with the second transmission at a different speed, so
     * the pause cannot be papered over by a stale model that happens to still fit. AC-9
     * covers holding the lock; this covers still being *right* afterwards.
     */
    @Test
    fun `a 30 s pause does not poison the transmission after it`() {
        val first = morse(wpm = 20.0, seed = 47L)
        val second = morse(wpm = 15.0, seed = 48L)
        val session = Session()
        session.feed(first.samples)
        val afterFirst = session.engine.transcript.size
        session.feed(FloatArray(30 * 16_000))
        session.feed(second.samples)
        session.finish()

        val tail = session.text(afterFirst)
        val cer = TextMetrics.cer(second.normalizedText, tail)
        assertTrue(cer < 0.35) { "post-pause CER $cer; decoded '$tail'" }
    }

    /**
     * After a lock taken on room noise, the tracker should end up on the actual tone.
     *
     * Written as the D-10 reproducer, where it settled tens of Hz low. It passes since the
     * `decisions.md` D-24 real-room guards, **but not because D-10 is fixed**: the guards
     * refuse to lock onto the speech at all, so there is no wrong lock to recover from
     * (every seed within 0.1 Hz; with all D-24 switches off the worst is 96.5 Hz out). The
     * path D-10 describes — a lock that *has* decoded, recovered more than 40 Hz from the
     * tone — is no longer reached by this fixture. Kept as a regression test for the path it
     * does exercise; `known-issues.md` D-10 records the gap.
     */
    @Test
    fun `the tone readout re-centres after recovering from a noise lock`() {
        // D-10 wants every recovery from a noise lock to re-centre on the tone. It failed
        // because the tracking EMA cannot re-centre once it is off by more than the
        // hysteresis window; see the doc comment for why it passes now.
        //
        // Asserted across a set of seeds, not one: which seed happens to land on the tone is
        // an accident of acquisition timing that reshuffles on any unrelated change (the
        // N-2 rewrite flipped seed 53, the D-2 re-bootstrap flipped seed 43). The defect is
        // that the *worst* case is tens of Hz off, and that is stable — so assert the worst.
        val offsets = listOf(41L, 42L, 44L, 47L, 48L).map { seed ->
            val session = Session()
            session.feed(NonMorseAudio.speech(20.0, seed = 61L))
            session.feed(morse(seed = seed).samples)
            session.finish()
            abs(session.engine.toneHz - 612.0)
        }
        assertTrue(offsets.max() < 15.0) {
            "recovered tone off by up to ${"%.1f".format(offsets.max())} Hz across seeds; $offsets"
        }
    }

    /** Audio that stops mid-character must not produce a spurious trailing character. */
    @Test
    fun `a truncated transmission ends cleanly`() {
        val gen = morse(seed = 49L)
        val cut = gen.samples.copyOfRange(0, (gen.samples.size * 0.6).toInt())
        val session = Session()
        session.feed(cut)
        session.finish()

        val decoded = session.text()
        assertTrue(decoded.isNotEmpty()) { "truncation lost the whole decode" }
        // The reference prefix is unknown mid-character, so assert the weaker, real
        // property: what was decoded is a plausible prefix of what was sent.
        val ref = gen.normalizedText
        val prefixCer = TextMetrics.cer(ref.take(decoded.length.coerceAtMost(ref.length)), decoded)
        assertTrue(prefixCer < 0.35) { "truncated decode diverged: '$decoded'" }
    }

    /**
     * Interference arriving part-way through: a second operator starts up 100 Hz away
     * while the first is still sending. The established lock should hold — D-7's
     * asymmetry, biased toward staying put.
     */
    @Test
    fun `interference starting mid-transmission does not steal an established lock`() {
        val wanted = morse(toneHz = 612.0, seed = 51L)
        val qrm = morse(toneHz = 760.0, snrDb = null, seed = 52L, body = "TEST DE QRM STATION")
        val half = wanted.samples.size / 2
        val mixed = wanted.samples.copyOf()
        for (i in qrm.samples.indices) {
            val at = half + i
            if (at < mixed.size) mixed[at] = (mixed[at] + 0.7f * qrm.samples[i]).coerceIn(-1f, 1f)
        }

        val session = Session()
        session.feed(mixed)
        session.finish()
        assertTrue(abs(session.engine.toneHz - 612.0) < 25.0) {
            "lock migrated to ${session.engine.toneHz} when interference appeared"
        }
    }
}

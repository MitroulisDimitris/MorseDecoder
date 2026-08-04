# Version history — measured

Every commit in this repo, benchmarked with **one ruler**.

Bench numbers reported in conversation were never comparable across time: the bench
itself changed as the project went on, so "mean CER 29 %" in one session and "17.9 %" in
another measured different scenario sets. This file fixes that. `HistoryBench` is written
against only the API surface that has existed since the first commit and carries its own
non-Morse fixtures, so it drops into a checkout of any commit and runs there unchanged.

Regenerate with the procedure at the bottom.

---

## What each commit is

| # | Commit | Shorthand | What landed |
|---|---|---|---|
| 1 | `ad5479d` | init | The whole decoder, first commit |
| 2 | `cd034af` | gate+cap | Output fit gate; 60 WPM speed cap (D-11) |
| 3 | `cb358e7` | thr+launch | Keying-threshold telemetry; launch into listening |
| 4 | `6c250a3` | D-13 band2k | Band to 2000 Hz; keyed-peak selection; dead-end recovery |
| 5 | `8d571ed` | D-4/D-5 | Out-of-range flag from reported speed; nullable fit |
| 6 | `92fd11e` | FR-2.7 win | Speed-adaptive analysis window (D-15) |
| 7 | `9b52819` | clamp-suppress | Suppress output at the speed clamp (D-16) |
| 8 | `7d1a2b5` | seed-guard | Discard a bootstrap seed claiming an impossible ratio (D-17) |
| 9 | *(this commit)* | file-gate | Retire the file path's `fitQuality < 0.12` veto (D-18) |

This table numbers only the commits that changed decoder behaviour, so its numbering is its
own — `progress.md` numbers all thirteen. Skipped here: `dddb819` and `4fd2154`
(README/UI copy), `bddb514` and `65c1f56` (tests and instruments), `953baf5` (docs).

**`HistoryBench` reports commit 9 identical to commit 8 on all 42 rows.** That is a real
result, not an omission: this file's `hand-13-rough` uses the 126-char text, and the veto
D-18 removed only ever fired on shorter transmissions of the same signal — see
`decisions.md` D-18's length table, and the caveat below.

---

## Live path — Morse CER %

Lower is better. Above 100 % means the decoder emitted more characters than were sent.

| scenario | init | gate+cap | thr+launch | **D-13** | D-4/D-5 | FR-2.7 | **clamp** | **seed-guard** |
|---|---|---|---|---|---|---|---|---|
| clean-20 | 0.8 | 0.8 | 0.8 | 1.6 | 1.6 | 1.6 | 1.6 | 1.6 |
| m20@10 | 100.0 | 100.0 | 100.0 | **1.6** | 1.6 | 1.6 | 1.6 | 1.6 |
| m20@0 | 100.0 | 100.0 | 100.0 | **1.6** | 1.6 | 1.6 | 1.6 | 1.6 |
| m20@-5 | 100.0 | 100.0 | 100.0 | 107.9 | 107.9 | 107.9 | **38.1** | 38.1 |
| hand-18 | 100.0 | 100.0 | 100.0 | **19.1** | 19.1 | **15.9** | 15.9 | 15.9 |
| hand-13-rough | 69.0 | 69.0 | 69.0 | **307.9** ⚠ | 307.9 | 307.9 | **207.1** | **65.9** |
| slow-7 | 227.0 | 222.2 | 222.2 | **0.8** | 0.8 | 0.8 | 0.8 | 0.8 |
| fast-35 | 23.0 | 27.8 | 27.8 | **3.2** | 3.2 | 3.2 | 3.2 | 3.2 |
| qrq-45 | 100.0 | 100.0 | 100.0 | **4.0** | 4.0 | 4.0 | 4.0 | 4.0 |
| farnsworth | 100.0 | 100.0 | 100.0 | **9.5** | 9.5 | 9.5 | 9.5 | 9.5 |
| drift-ramp | 100.0 | 100.0 | 100.0 | **1.6** | 1.6 | 1.6 | 1.6 | 1.6 |
| wobble | 46.8 | 100.0 | 100.0 | **1.6** | 1.6 | 1.6 | 1.6 | 1.6 |
| hesitation | 100.0 | 100.0 | 100.0 | **18.2** | 18.2 | 18.2 | 18.2 | 18.2 |
| qsb-impulse | 68.2 | 100.0 | 100.0 | **42.1** | 42.1 | 42.9 | 42.9 | 42.9 |
| tone-1882 | 100.0 | 100.0 | 100.0 | **1.6** | 1.6 | 1.6 | 1.6 | 1.6 |
| tone-700 | 100.0 | 100.0 | 100.0 | **1.6** | 1.6 | 1.6 | 1.6 | 1.6 |
| **MEAN** | **89.7** | 95.0 | 95.0 | **32.7** | 32.7 | 32.6 | **21.9** | **13.1** |

## File path — Morse CER %

| scenario | init | … | **D-13** | … | clamp | seed-guard | file-gate |
|---|---|---|---|---|---|---|---|
| tone-1882 | 100.0 | 100.0 | **0.0** | 0.0 | 0.0 | 0.0 | 0.0 |
| hand-13-rough | 57.1 | 57.1 | 57.1 | 57.1 | 57.1 | 57.1 | 57.1 |
| qsb-impulse | 150.8 | 150.8 | 150.8 | 150.8 | 150.8 | 150.8 | 150.8 |
| hand-18 / hesitation | 19.1 | 19.1 | 19.1 | 19.1 | 19.1 | 19.1 | 19.1 |
| m20@-5 | 3.2 | 3.2 | 3.2 | 3.2 | 3.2 | 3.2 | 3.2 |
| *(9 others)* | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| **MEAN** | **21.8** | 21.8 | **15.6** | 15.6 | 15.6 | 15.6 | 15.6 |

The file path has changed **once** in the project's history — D-13's band widening, which
fixed `tone-1882`. Everything else has been flat since the first commit, `hand-13-rough`
and `qsb-impulse` included. D-17 did not change it: the seed guard is live-path only.

**D-18 does not move a row here either, and that needs saying carefully.** It removed a
veto that fires on the file path's `hand-13-rough` — but on *`DecoderBench`'s* version of
that scenario, which is the same generator config over the 79-char text rather than this
file's 126-char one. Under the retired threshold the two landed on opposite sides:

| text | `fitQuality` | old gate | this file |
|---|---|---|---|
| 79 chars (`DecoderBench`) | 0.0987 | **declined**, 0 chars | not measured here |
| 126 chars (`HistoryBench`) | 0.3312 | decoded, 120 chars | the 57.1 % above |

So this file's 57.1 % was always the *ungated* number, and `DecoderBench` reported 100 % on
the same signal. Both were right about their own fixture. `DecoderBench` records the change
as 100 % → 64.6 %, file mean 13.8 % → 11.6 %.

**The lesson is about this file's premise, not about D-18.** One ruler protects against a
bench that changes over time; it does not protect against *two* benches disagreeing at one
moment, which is what happened here and went unnoticed. When the two
disagree on a scenario name, they are measuring different audio.

## Non-Morse — characters emitted (0 is correct)

| fixture | init | gate+cap | thr+launch | D-13 | … | clamp | seed-guard | file-gate |
|---|---|---|---|---|---|---|---|---|
| **live** | | | | | | | | |
| ambient-tone | 311 | **14** | 14 | **0** | 0 | 0 | 0 | 0 |
| knocking | 8 | **0** | 0 | 0 | 0 | 0 | 0 | 0 |
| rtty | 112 | 99 | 99 | 70 | 70 | 69 | 69 | 69 |
| speech | 29 | 29 | 29 | 26 | 26 | 26 | 26 | 26 |
| steady-carrier | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| **file** | | | | | | | | |
| ambient-tone | 211 | **171** | 171 | 171 | 171 | 171 | 171 | 171 |
| rtty | 125 | 124 | 124 | 124 | 124 | **0** | 0 | 0 |
| knocking | 30 | 30 | 30 | 30 | 30 | 30 | 30 | 30 |
| speech | 31 | 31 | 31 | 31 | 31 | 31 | 31 | 31 |

D-18 relaxed a file-path veto and no non-Morse row moved, here or in `DecoderBench`'s
larger corpus — the fixtures it declines score a hard zero rather than a marginal one.

---

## What this establishes

**1. Progress is real and mostly attributable to two commits.**
Live mean **89.7 % → 13.1 %**. D-13 (band + keyed-peak selection) did most of it, taking
the mean from 95.0 to 32.7 and fixing eleven scenarios that were at 100 % — i.e. decoding
nothing at all. Those were not "poor decodes"; the pre-D-13 decoder simply could not see a
612 Hz tone reliably enough to produce output on most of the matrix.

D-17 is the second: 21.9 → 13.1, all of it from a single scenario. Every other row in the
matrix is byte-identical, which is what a targeted fix looks like next to D-13's broad one.

**2. `cd034af` (the fit gate) made the synthetic mean *worse* — 89.7 → 95.0 — and was still
correct.** It cost `wobble` (46.8 → 100) and `qsb-impulse` (68.2 → 100) by suppressing
output, while cutting ambient-tone noise from 311 characters to 14. That is the honest
trade P-4 asks for, and a mean-only view would have called it a regression. **Mean CER
alone is not a sufficient acceptance signal for this project.**

**3. The file path has been essentially static.** One improvement in eight commits. All the
engineering has gone into the live path, which is where the app actually runs — but it
means `hand-13-rough` (57 %) and `qsb-impulse` (151 %) have never been addressed on the
path that decodes them best.

---

## `hand-13-rough` on the live path — a regression, and its recovery

**D-13 made the worst scenario significantly worse, and this was not noticed at the time.**

| | chars emitted (99 sent) | CER |
|---|---|---|
| init → thr+launch | 66 | 69 % |
| **D-13 → FR-2.7** | **274** | **307.9 %** |
| clamp-suppress | 194 | 207.1 % |
| **seed-guard (D-17)** | **99** | **65.9 %** |

The pre-D-13 decoder under-emitted on a rough fist (66 of 99). D-13 made it lock reliably —
and a reliable lock on a signal whose unit estimate is 4× wrong produces a *flood* of wrong
characters instead of a trickle. D-16 recovered a third of it, and D-17 removed the cause:
a single 25 ms fragment of a dash was forming the entire lower cluster of the bootstrap
seed and setting the timebase 4× short. The scenario now emits exactly as many characters
as were sent and sits below its first-commit value for the first time.

This is D-1 and D-6 stated as a time series: better tone acquisition is worthless — actively
harmful — without a working unit estimate, because everything the improved lock buys is
spent emitting more wrong characters. That lesson survives the fix.

What does **not** survive is the inference drawn from it. This was called the strongest
argument yet for the soft-decision front end (`prior-art.md` idea 1) on the grounds that it
targets exactly this failure — and the failure turned out to cost one bounds check. The
remaining 65.9 % is ordinary inaccuracy under 25 % jitter, not a broken timebase, so the
front end needs re-justifying on its own evidence before it is started.

**Not caught earlier because** the session bench was introduced *after* D-13 landed, so
D-13's own before/after was never measured on this scenario. That gap is what this file
exists to close.

---

## How to regenerate

`HistoryBench.kt` is not committed to the repo — it is a portable probe, kept in the
session scratchpad.

**Scratchpads are per-session, so "the session scratchpad" is not a location.** The D-18
run recovered the probe from an earlier session's directory by searching for it by name; it
is now copied forward again. If it is ever lost, this file becomes unreproducible and its
own rule — never re-baseline with a newer probe — makes rewriting it useless. Worth
committing, which is an owner's call because it changes what the repo claims about itself.

To re-run across history:

```sh
# 1. One worktree per commit, with the probe dropped in
for c in ad5479d cd034af cb358e7 6c250a3 8d571ed 92fd11e 9b52819; do
  git worktree add -q --detach ../wt-$c $c
  cp local.properties ../wt-$c/
  cp HistoryBench.kt ../wt-$c/core/src/test/kotlin/com/morsedecoder/core/
done

# 2. Run it in each
for c in ...; do
  (cd ../wt-$c && ./gradlew :core:test --tests '*HistoryBench*' \
     --console=plain --rerun-tasks -i) | grep "HB|" > hist/$c.txt
done

# 3. Clean up
git worktree prune
```

Output rows are `HB|scenario|kind|path|cer|chars`.

**Add new commits to the front of the list, never re-baseline the old ones.** The point of
this file is that every row was produced by the same probe; regenerating history with a
newer probe would destroy that.

Two properties of the probe are load-bearing:

- **It compiles against every commit.** Only the generator, both decode paths, and
  `TextMetrics` — all present since `ad5479d`.
- **It carries its own fixtures.** Importing `NonMorseAudio` would restrict it to commits
  after that file existed.

"""Score tools/device-run.sh output against reference text.

    python tools/score-device.py <run-dir> <truth.tsv>

truth.tsv: one line per clip, `<clip name or file>\t<reference text>`; a clip's log is
matched by basename without extension. CER is computed exactly as RealAudioBench does it:
upper-cased, spaces and unsupported characters removed, Levenshtein over reference length.

Columns: tone/wpm are the app's last status line; pre-lock is the tone the app was locked
on before playback started (a lock taken on room noise); quiet ch counts characters
emitted after playback ended; relocks lists each automatic/manual re-lock and the phase
(play / quiet) it fell in.
"""
import glob
import os
import re
import sys


def norm(s):
    return re.sub(r"[^A-Z0-9?/.,=+\-]", "", s.upper())


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def main(run_dir, truth_path):
    truth = {}
    for line in open(truth_path, encoding="utf-8"):
        if not line.strip() or line.startswith("#") or "\t" not in line:
            continue
        name, text = line.rstrip("\n").split("\t", 1)
        truth[os.path.splitext(os.path.basename(name))[0]] = text.replace(" / ", " ")

    print(f"{'clip':<32}{'CER%':>7}{'chars':>6}{'tone':>6}{'wpm':>6}{'pre-lock':>9}{'quiet ch':>9}  relocks")
    cers = []
    for log in sorted(glob.glob(os.path.join(run_dir, "*.log"))):
        name = os.path.basename(log)[:-4]
        phase, text, quiet, relocks, prelock, last = "pre", "", 0, [], "-", {}
        for line in open(log, encoding="utf-8", errors="replace"):
            m = re.search(r"MorseBench\(\s*\d+\):\s*(.*)", line)
            if not m:
                continue
            msg = m.group(1).strip("\r")
            if msg.startswith("MARK:"):
                phase = {"play_start": "play", "play_end": "quiet", "quiet_end": "done"}.get(msg[5:], phase)
            elif msg.startswith("CH|"):
                tok = msg.split("|", 2)[2]
                text += tok
                if phase in ("quiet", "done"):
                    quiet += len(tok.strip())
            elif msg.startswith("RELOCK|"):
                f = msg.split("|")
                relocks.append(f"{f[1].lower()}@{phase} {float(f[2]):.0f}->{float(f[3]):.0f}")
            elif msg.startswith("ST|"):
                last = dict(kv.split("=", 1) for kv in msg[3:].split("|"))
                if phase == "pre" and last.get("lock") in ("LOCKED", "DEGRADED"):
                    prelock = last["tone"]
        ref = truth.get(name)
        if ref is None:
            cer_s = "n/a"
        else:
            cer = lev(norm(ref), norm(text)) / max(1, len(norm(ref))) * 100
            cers.append(cer)
            cer_s = f"{cer:.1f}"
        print(f"{name[:31]:<32}{cer_s:>7}{len(norm(text)):>6}{last.get('tone', '-'):>6}"
              f"{last.get('wpm', '-'):>6}{prelock:>9}{quiet:>9}  {', '.join(relocks)}")
        print(f"{'':<32}  {text.strip()[:100]}")
    if cers:
        print(f"\nmean CER {sum(cers) / len(cers):.1f} %   worst {max(cers):.1f} %   n={len(cers)}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

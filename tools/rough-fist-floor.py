"""Error floor for DecoderBench's `hand-13-rough` fixture.

    python tools/rough-fist-floor.py

Simulates the fixture's timing model exactly as SyntheticMorseGenerator draws it (dash:dot
2.1, log-normal jitter 0.25 per element and gap, per-character bias 0.12) and decodes it
with an oracle that knows the true unit and ratio and decides every mark and gap at the
optimal threshold, with no language model. Whatever this oracle gets wrong is ambiguous in
the timing itself, so no timing decoder can get it right in raw mode.

Result 2026-11-09: ~30 % of characters wrong. The decoder reaches 32.9 % with true runs
and models (progress.md step 6a) and 36.7 % on the file path. See progress.md, review
2026-11-09, and known-issues.md D-6.
"""
import math, random
M={'A':'.-','B':'-...','C':'-.-.','D':'-..','E':'.','F':'..-.','G':'--.','H':'....','I':'..','J':'.---','K':'-.-','L':'.-..','M':'--','N':'-.','O':'---','P':'.--.','Q':'--.-','R':'.-.','S':'...','T':'-','U':'..-','V':'...-','W':'.--','X':'-..-','Y':'-.--','Z':'--..'}
for k,v in zip('0123456789',['-----','.----','..---','...--','....-','.....','-....','--...','---..','----.']): M[k]=v
txt="THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG PACK MY BOX WITH FIVE DOZEN LIQUOR JUGS 0123456789".replace(' ','')
r,j,b=2.1,0.25,0.12; th=math.log(r)/2; gth=math.log(3)/2
random.seed(1); N=0; bad=0
for _ in range(400):
  for ch in txt:
    bias=math.exp(b*random.gauss(0,1)); ok=True
    for i,e in enumerate(M[ch]):
      d=math.log(bias*(r if e=='-' else 1))+j*random.gauss(0,1)
      if (d>th)!=(e=='-'): ok=False
      if i>0:
        g=math.log(bias)+j*random.gauss(0,1)          # intra gap, 1 unit
        if g>gth: ok=False                            # would split the char
    gi=math.log(3)+j*random.gauss(0,1)                # inter-char gap, 3 units (no bias)
    if gi<gth: ok=False                               # would merge with next
    N+=1; bad+=not ok
print(f"element-wise oracle character error ~{100*bad/N:.1f}%")

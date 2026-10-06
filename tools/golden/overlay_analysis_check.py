#!/usr/bin/env python3
"""AE-051: local contrast (Texture, Clarity) must be measured on the picture with its heal patches, so the inside of a patch is not boosted.
Flat grey picture, the golden `overlay=` patch over its left half, Texture and Clarity 80: the patch interior must equal the same render without Texture and Clarity.
usage: overlay_analysis_check.py [golden binary]. Standard library only."""
import os, subprocess, sys, tempfile
G = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")
W, H = 600, 400
tmp = tempfile.mkdtemp(); src = os.path.join(tmp, "flat.ppm")
open(src, "wb").write(b"P6\n%d %d\n255\n" % (W, H) + bytes([100]) * (W * H * 3))
def row(*args):
    out = os.path.join(tmp, "o.ppm")
    subprocess.run([G, src, out, str(W), "half", *args], capture_output=True, check=True)
    d = open(out, "rb").read().split(b"\n", 3)[3]
    return lambda x: d[(200 * W + x) * 3]
plain, boosted = row("overlay=0.3"), row("overlay=0.3", "texture=80", "clarity=80")
inside = abs(boosted(100) - plain(100)); far = abs(boosted(500) - plain(500))
print("patch interior: %d without, %d with Texture and Clarity 80 (difference %d); untouched side difference %d" % (plain(100), boosted(100), inside, far))
ok = inside <= 2 and far <= 2
print("PASS" if ok else "FAIL (the patch is boosted: the analysis does not see it)")
sys.exit(0 if ok else 1)

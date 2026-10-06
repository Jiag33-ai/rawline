#!/usr/bin/env python3
"""Renders an asymmetric picture under each of the 8 EXIF orientations and compares with the definition of the orientation. Standard library only.
usage: orient_check.py [golden binary]"""
import os, subprocess, sys, tempfile
G = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")
W, H = 60, 40
def S(x, y):            # stored picture: x ramps red, y ramps green, a blue marker in the top left corner block
    return (int(x * 255 / (W - 1)), int(y * 255 / (H - 1)), 255 if (x < 8 and y < 8) else 40)
# D(x, y) in display coordinates, as (source x, source y), for the output size (ow, oh)
EXPECT = {1: (W, H, lambda x, y: (x, y)), 2: (W, H, lambda x, y: (W-1-x, y)), 3: (W, H, lambda x, y: (W-1-x, H-1-y)), 4: (W, H, lambda x, y: (x, H-1-y)),
          5: (H, W, lambda x, y: (y, x)), 6: (H, W, lambda x, y: (y, H-1-x)), 7: (H, W, lambda x, y: (W-1-y, H-1-x)), 8: (H, W, lambda x, y: (W-1-y, x))}
tmp = tempfile.mkdtemp(); src = os.path.join(tmp, "s.ppm")
open(src, "wb").write(b"P6\n%d %d\n255\n" % (W, H) + b"".join(bytes(S(x, y)) for y in range(H) for x in range(W)))
def render(o, ow):
    out = os.path.join(tmp, "o%d.ppm" % o)
    subprocess.run([G, src, out, str(ow), "full", "orient=%d" % o], capture_output=True, text=True)
    d = open(out, "rb").read().split(b"\n", 3); w, h = map(int, d[1].split()); return w, h, d[3]
rw, rh, ref = render(1, W)                      # the identity render is the oracle: same pixels, same curve, no orientation
assert (rw, rh) == (W, H)
bad = 0
for o, (ow, oh, f) in EXPECT.items():
    w, h, px = render(o, ow)
    ok = (w, h) == (ow, oh); worst = 0; wrong = 0
    if ok:
        for y in range(h):
            for x in range(w):
                sx, sy = f(x, y)
                a_ = px[(y * w + x) * 3:(y * w + x) * 3 + 3]; b_ = ref[(sy * W + sx) * 3:(sy * W + sx) * 3 + 3]
                d_ = max(abs(a_[i] - b_[i]) for i in range(3)); worst = max(worst, d_); wrong += d_ > 12
    print("orientation %d: output %dx%d %s, worst difference from the definition %d levels, %d pixels off by more than 12  %s" % (o, w, h, "size ok" if ok else "SIZE WRONG (expected %dx%d)" % (ow, oh), worst, wrong, "PASS" if ok and wrong == 0 else "FAIL"))
    bad += 0 if ok and wrong == 0 else 1
sys.exit(1 if bad else 0)

#!/usr/bin/env python3
"""Reference model + comparer for the synthetic colour fixtures. Standard library only."""
import re, sys, json, subprocess, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_dng as m

REPO = os.environ.get("RAWLINE_REPO", os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")))   # tools/colour/ -> the repo root
GOLDEN = os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")

def base_table():
    src = open(REPO + "/core/render/src/main/kotlin/app/rawline/core/render/BaseCurve.kt").read()
    body = src.split("val TABLE = floatArrayOf(")[1].split(")")[0]
    t = [float(x) for x in re.findall(r"[0-9.]+(?=f)", body)]
    assert len(t) == 256
    return t
TABLE = base_table()
def samp(x):
    p = min(max(x, 0.0), 1.0) * 255; i = min(int(p), 254); return TABLE[i] + (TABLE[i+1] - TABLE[i]) * (p - i)
def oetf(l): return 12.92*l if l <= 0.0031308 else 1.055*l**(1/2.4) - 0.055
def eotf(s): return s/12.92 if s <= 0.04045 else ((s+0.055)/1.055)**2.4

# XYZ(D50-adapted) is not needed: the engine chain for neutral-balanced input is  display = oetf(m_lib^-1 ... ) = identity colour path,
# so for sRGB-defined patches the expected linear sRGB out is  K * E * patch  (see section 6 of the document).
def expected_srgb8(patch_lin, exposure, K=1.0, base=True):
    out = []
    for c in patch_lin:
        v = oetf(min(max(K*exposure*c, 0.0), 1.0))
        out.append(round((samp(v) if base else v) * 255, 2))
    return out

def read_ppm(path):
    d = open(path, "rb").read()
    a = d.split(b"\n", 3)
    w, h = map(int, a[1].split()); return w, h, a[3]
def patch_mean(img, idx, scale):
    """mean RGB of the central 21x21 pixels of patch idx (6 columns x 4 rows, patch size 150 px in the source)."""
    w, h, px = img
    ps = int(m.PATCH * scale)
    cx = (idx % 6) * ps + ps // 2; cy = (idx // 6) * ps + ps // 2
    s = [0, 0, 0]; n = 0
    for y in range(cy-10, cy+11):
        for x in range(cx-10, cx+11):
            o = (y*w + x)*3
            for c in range(3): s[c] += px[o+c]
            n += 1
    return [v/n for v in s]

def render(dng, ppm, width=600, extra=()):
    r = subprocess.run([GOLDEN, dng, ppm, str(width), "full", *extra], capture_output=True, text=True)
    if r.returncode: raise SystemExit("golden failed: " + r.stderr[-400:])
    return read_ppm(ppm)

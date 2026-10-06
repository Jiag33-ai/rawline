#!/usr/bin/env python3
"""Look 2 shader checks (W23). Standard library only. Needs tools/colour/make_dng.py (W21) next to it or on PYTHONPATH.
usage: look2_checks.py GOLDEN_BIN WORKDIR [--look N] [--key shader|look]     (N = value given to the key, default 2; --look 1 is the negative control; `look` is the W22 golden key that also sets G_LOOK, `shader` sets only the shader maths)
Exit 0 when every check passes. Prints one line per check."""
import sys, os, math, colorsys, subprocess
KEY = "shader"
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here); sys.path.insert(0, os.path.join(here, "..", "colour"))
import make_dng as m

def read_ppm(p):
    d = open(p, "rb").read(); a = d.split(b"\n", 3); w, h = map(int, a[1].split()); return w, h, a[3]
def render(golden, dng, ppm, args, width):
    r = subprocess.run([golden, dng, ppm, str(width), "full", *args], capture_output=True, text=True)
    if r.returncode: raise SystemExit("golden failed: " + r.stderr[-300:])
    return read_ppm(ppm)
def patch_rgb(img, idx, scale):
    w, h, px = img; ps = int(m.PATCH * scale); cx = (idx % 6) * ps + ps // 2; cy = (idx // 6) * ps + ps // 2
    s = [0, 0, 0]; n = 0
    for y in range(cy - 10, cy + 11):
        for x in range(cx - 10, cx + 11):
            for c in range(3): s[c] += px[(y * w + x) * 3 + c]
            n += 1
    return [v / n for v in s]

def make_hue_sweep(path):
    patches = [tuple(m.srgb_eotf(v) for v in colorsys.hsv_to_rgb(i / 24.0, 0.5, 0.6)) for i in range(24)]
    M = m.balanced_matrix(m.M_S5M2)
    m.tiff_dng(path, m.make_scene(patches, 0.8, M), M, (1.0, 1.0, 1.0))

def make_texture(path):
    """3000 x 2000: 6 px stripes (x < 1000), a soft ramp with an 80 px wave (1000 to 2000), a hard step at x = 2500."""
    w0, h0 = m.W, m.H; m.W, m.H = 3000, 2000
    def level(x):
        if x < 1000: return 0.15 + (0.03 if (x // 3) % 2 == 0 else -0.03)
        if x < 2000: return 0.10 + 0.30 * ((x - 1000) / 1000.0) + 0.04 * math.sin(2 * math.pi * (x - 1000) / 80.0)
        return 0.10 if x < 2500 else 0.50
    line = [min(m.WHITE, max(0, int(round(512 + level(x) * 0.8 * (m.WHITE - 512))))) for x in range(m.W)]
    M = m.balanced_matrix(m.M_S5M2)
    m.tiff_dng(path, [line] * m.H, M, (1.0, 1.0, 1.0))
    m.W, m.H = w0, h0

def hsl_equal_strength(g, work, look):
    d = os.path.join(work, "hue_sweep.dng")
    if not os.path.exists(d): make_hue_sweep(d)
    a = render(g, d, os.path.join(work, "h_hsl.ppm"), ["hslsat=20", KEY + "=%d" % look], 600)
    b = render(g, d, os.path.join(work, "h_glob.ppm"), ["saturation=20", KEY + "=%d" % look], 600)
    worst = max(max(abs(x - y) for x, y in zip(patch_rgb(a, i, 0.5), patch_rgb(b, i, 0.5))) for i in range(24))
    return worst, "HSL saturation sliders all at +20 equal the global Saturation +20 on 24 hues (worst patch, levels, limit 1)", worst <= 1.0

def hsl_band_confined(g, work, look):
    d = os.path.join(work, "hue_sweep.dng")
    base = render(g, d, os.path.join(work, "h_b0.ppm"), [KEY + "=%d" % look], 600)
    one = render(g, d, os.path.join(work, "h_b1.ppm"), ["hslband=3550", KEY + "=%d" % look], 600)   # green band +50
    far = max(max(abs(x - y) for x, y in zip(patch_rgb(one, i, 0.5), patch_rgb(base, i, 0.5))) for i in list(range(0, 4)) + list(range(12, 24)))
    return far, "the green band alone leaves hues outside red..aqua untouched (worst, levels, limit 1)", far <= 1.0

def texture(g, work, look):
    d = os.path.join(work, "texture3k.dng")
    if not os.path.exists(d): make_texture(d)
    def row(img, x0, x1):
        w, h, px = img; ys = range(900, 1100, 20)
        rs = [[px[(y * w + x) * 3 + 1] for x in range(x0, x1)] for y in ys]
        return [sum(r[i] for r in rs) / len(rs) for i in range(len(rs[0]))]
    def metrics(tex):
        img = render(g, d, os.path.join(work, "t.ppm"), ["texture=%d" % tex, KEY + "=%d" % look], 3000)
        st = row(img, 100, 900); stripe = max(st) - min(st)
        wv = row(img, 1100, 1900); n = 80
        ma = [sum(wv[max(0, i - n // 2):i + n // 2 + 1]) / len(wv[max(0, i - n // 2):i + n // 2 + 1]) for i in range(len(wv))]
        wave = max(abs(a - b) for a, b in zip(wv[n:-n], ma[n:-n])) * 2
        ed = row(img, 2300, 2700); lo = sum(ed[20:80]) / 60
        under = lo - min(ed[160:200])
        return stripe, wave, under
    s0, w0, u0 = metrics(0); s1, w1, u1 = metrics(50)
    out = []
    out.append((s1 - s0, "Texture +50 raises 6 px stripe contrast by at least 6 levels", s1 - s0 >= 6))
    out.append((w1 - w0, "Texture +50 leaves an 80 px wave alone (change at most 1 level)", abs(w1 - w0) <= 1.0))
    out.append((u1 - u0, "Texture +50 undershoot at a 5:1 step is at most 10 levels", (u1 - u0) <= 10.0))
    return out

def main():
    global KEY
    g, work = sys.argv[1], sys.argv[2]; look = 2
    if "--key" in sys.argv: KEY = sys.argv[sys.argv.index("--key") + 1]
    if "--look" in sys.argv: look = int(sys.argv[sys.argv.index("--look") + 1])
    os.makedirs(work, exist_ok=True)
    results = [hsl_equal_strength(g, work, look), hsl_band_confined(g, work, look)] + texture(g, work, look)
    ok = True
    for v, text, passed in results:
        print("%s  %s: %.2f" % ("ok  " if passed else "FAIL", text, v)); ok = ok and passed
    sys.exit(0 if ok else 1)
main()

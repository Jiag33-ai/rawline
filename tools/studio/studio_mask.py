#!/usr/bin/env python3
"""Golden scenes of Studio S2 against the independent reference (studio_ref.py, studio_brush.py): layer masks and selection clip. Standard library only.
usage: studio_mask.py make <dir>      writes layer/mask/selection files, scene_<name>.txt and expected_<name>.rgba
       studio_mask.py compare <dir>   compares out_<name>.rgba (written by studio_golden) with the expectation; exit 1 on failure
Scenes: studio_mask_half, studio_mask_gradient, studio_mask_all255_identity, studio_mask_inverted, studio_mask_disabled, studio_mask_paint, studio_sel_clip."""
import math, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import studio_ref as R
import studio_brush as B
import studio_scene as S

CW, CH = 256, 192            # canvas
PW, PH = 160, 120            # the masked layer's own size; placed at (40, 30), scale 1.25 so the mask is resampled with the layer

def photo():
    p = bytearray()
    for y in range(PH):
        for x in range(PW):
            p += bytes((255 - x, 40 + y, (x * 5 + y * 3) % 256, 255 if (x + y) % 11 else 200))
    return bytes(p)

def mask_half():     return bytes(255 if x < PW // 2 else 0 for y in range(PH) for x in range(PW))
def mask_gradient(): return bytes(x * 255 // (PW - 1) for y in range(PH) for x in range(PW))
def mask_white():    return bytes([255]) * (PW * PH)

# name: (mask builder or None, mask_mode, blend mode, opacity)
SCENES = {
    "mask_half": (mask_half, 1, 0, 0.9),
    "mask_gradient": (mask_gradient, 1, 1, 0.8),
    "mask_all255_identity": (mask_white, 1, 0, 0.9),
    "mask_inverted": (mask_half, 2, 0, 0.9),
    "mask_disabled": (mask_half, 0, 0, 0.9),
    "mask_nomask": (None, 0, 0, 0.9),             # not a golden of its own: the control the identity and disabled scenes must equal byte for byte
}
LX, LY, LS = 40.0, 30.0, 1.25

def write_scene(d, name, layers, extra_lines=()):
    with open(os.path.join(d, "scene_%s.txt" % name), "w") as f:
        f.write("view 0 0 1 %d %d\n" % (CW, CH))
        for ln in layers: f.write(ln + "\n")
        for ln in extra_lines: f.write(ln + "\n")

def make(d):
    os.makedirs(d, exist_ok=True)
    base = S.base(); ph = photo()
    open(os.path.join(d, "m_base.rgba"), "wb").write(base); open(os.path.join(d, "m_photo.rgba"), "wb").write(ph)
    for name, (mb, mm, blend, op) in SCENES.items():
        m = mb() if mb else None
        lines = ["layer %s %d %d 0 0 1 1 0" % (os.path.join(d, "m_base.rgba"), CW, CH),
                 "layer %s %d %d %g %g %g %g %d" % (os.path.join(d, "m_photo.rgba"), PW, PH, LX, LY, LS, op, blend)]
        if m is not None:
            mp = os.path.join(d, "mask_%s.r8" % name); open(mp, "wb").write(m)
            lines.append("mask %s %d %d %d" % (mp, PW, PH, mm))
        write_scene(d, name, lines)
        exp = R.render([R.Layer(base, CW, CH), R.Layer(ph, PW, PH, LX, LY, LS, op, blend, m, mm)], (0.0, 0.0, 1.0), CW, CH)
        open(os.path.join(d, "expected_%s.rgba" % name), "wb").write(exp)
    make_paint(d, base)
    make_sel(d, base)
    print("mask and selection scenes written to", d)

# ---- mask painting: a live stroke on the mask (shader) against the baked mask (reference) ----------------------------------------
def bake_mask(m, cov, value, opacity, sel=None, w=PW):
    out = bytearray(m)
    for i in range(len(m)):
        a = min(cov[i], 1.0) * opacity * (sel[i] / 255.0 if sel else 1.0)
        if a <= 0.0: continue
        out[i] = int(min(max((m[i] / 255.0 + (value - m[i] / 255.0) * a), 0.0), 1.0) * 255.0 + 0.5)
    return bytes(out)

def make_paint(d, base):
    ph = photo(); m0 = mask_white()
    pts = [(10, 20, 1), (80, 60, 1), (150, 100, 1)]
    stamps = B.walk(pts, 28.0, 0.1, False)
    cov = B.coverage(PW, PH, stamps, 0.5, 1.0)
    value, opacity = 0.0, 0.85
    baked = bake_mask(m0, cov, value, opacity)
    mp = os.path.join(d, "mask_paint0.r8"); open(mp, "wb").write(m0)
    lines = ["layer %s %d %d 0 0 1 1 0" % (os.path.join(d, "m_base.rgba"), CW, CH),
             "layer %s %d %d %g %g %g %g %d" % (os.path.join(d, "m_photo.rgba"), PW, PH, LX, LY, LS, 0.9, 0),
             "mask %s %d %d 1" % (mp, PW, PH), "mstroke 1 %g %g %g %g" % (value, opacity, 0.5, 1.0)]
    write_scene(d, "mask_paint", lines, ["stamp %.9g %.9g %.9g" % s for s in stamps])
    exp = R.render([R.Layer(base, CW, CH), R.Layer(ph, PW, PH, LX, LY, LS, 0.9, 0, baked, 1)], (0.0, 0.0, 1.0), CW, CH)
    open(os.path.join(d, "expected_mask_paint.rgba"), "wb").write(exp)

# ---- selection clip -------------------------------------------------------------------------------------------------------------
def ellipse_plane(w, h, x0, y0, x1, y1):
    """Independent 4x4 supersampled ellipse coverage, (n * 255 + 8) // 16, as the Kotlin Selection.ellipse defines it."""
    cx, cy, rx, ry = (x0 + x1) / 2, (y0 + y1) / 2, abs(x1 - x0) / 2, abs(y1 - y0) / 2
    out = bytearray(w * h)
    for y in range(max(0, math.floor(cy - ry)), min(h, math.ceil(cy + ry))):
        for x in range(max(0, math.floor(cx - rx)), min(w, math.ceil(cx + rx))):
            n = 0
            for j in range(4):
                for i in range(4):
                    px, py = x + (i + 0.5) / 4, y + (j + 0.5) / 4
                    if ((px - cx) / rx) ** 2 + ((py - cy) / ry) ** 2 <= 1.0: n += 1
            out[y * w + x] = (n * 255 + 8) // 16
    return bytes(out)

SW, SH = 200, 150
def make_sel(d, _):
    bg = bytes(p for y in range(SH) for x in range(SW) for p in (40 + x, 200 - y, 90 + (x + y) % 60, 255))
    open(os.path.join(d, "s_bg.rgba"), "wb").write(bg)
    paint0 = bytes(SW * SH * 4)
    open(os.path.join(d, "s_paint.rgba"), "wb").write(paint0)
    sel = ellipse_plane(SW, SH, 40.0, 25.0, 160.0, 125.0)
    open(os.path.join(d, "sel_ellipse.r8"), "wb").write(sel)
    pts = [(10, 30, 1), (100, 80, 1), (190, 120, 1)]
    stamps = B.walk(pts, 36.0, 0.1, False)
    cov = B.coverage(SW, SH, stamps, 0.6, 1.0)
    col, op = (0.9, 0.2, 0.1), 1.0
    clipped = [c * (sel[i] / 255.0) for i, c in enumerate(cov)]
    baked = B.commit(paint0, SW, SH, clipped, col, op, False)
    layers = ["canvas %d %d" % (SW, SH), "layer %s %d %d 0 0 1 1 0" % (os.path.join(d, "s_bg.rgba"), SW, SH),
              "layer %s %d %d 0 0 1 1 0" % (os.path.join(d, "s_paint.rgba"), SW, SH), "sel %s %d %d" % (os.path.join(d, "sel_ellipse.r8"), SW, SH),
              "stroke 1 %g %g %g %g 0 0.6 1" % (col[0], col[1], col[2], op)]
    with open(os.path.join(d, "scene_sel_clip.txt"), "w") as f:
        for ln in layers: f.write(ln + "\n")
        for s in stamps: f.write("stamp %.9g %.9g %.9g\n" % s)
    # the control: the same scene with the stroke but no selection (the stroke is visible outside the ellipse), and with no stroke at all (what outside must equal)
    with open(os.path.join(d, "scene_sel_none.txt"), "w") as f:
        for ln in layers[:3]: f.write(ln + "\n")
    with open(os.path.join(d, "scene_sel_free.txt"), "w") as f:
        for ln in layers[:3] + layers[4:]: f.write(ln + "\n")
        for s in stamps: f.write("stamp %.9g %.9g %.9g\n" % s)
    exp = R.render([R.Layer(bg, SW, SH), R.Layer(baked, SW, SH)], (0.0, 0.0, 1.0), SW, SH)
    open(os.path.join(d, "expected_sel_clip.rgba"), "wb").write(exp)

def read(d, n): return open(os.path.join(d, n), "rb").read()

def compare(d, tol=1):
    fails = 0
    def report(ok, text):
        nonlocal fails
        print("%s  %s" % ("PASS" if ok else "FAIL", text)); fails += 0 if ok else 1
    def vs(name, golden, t):
        exp, got = read(d, "expected_%s.rgba" % name), read(d, "out_%s.rgba" % name)
        worst = max(abs(a - b) for a, b in zip(exp, got)); off = sum(1 for a, b in zip(exp, got) if abs(a - b) > 1)
        report(len(exp) == len(got) and worst <= t, "%s worst %d level(s), %d values off by more than 1  (tolerance %d)" % (golden, worst, off, t))
    vs("mask_half", "studio_mask_half", tol)
    vs("mask_gradient", "studio_mask_gradient", tol)
    vs("mask_inverted", "studio_mask_inverted", tol)
    vs("mask_all255_identity", "studio_mask_all255_identity (against the reference)", tol)
    vs("mask_disabled", "studio_mask_disabled (against the reference)", tol)
    vs("mask_paint", "studio_mask_paint (live mask stroke against the baked mask)", 2)
    nomask = read(d, "out_mask_nomask.rgba")
    report(read(d, "out_mask_all255_identity.rgba") == nomask, "studio_mask_all255_identity: a layer with an all 255 mask renders byte for byte like the same layer without one")
    report(read(d, "out_mask_disabled.rgba") == nomask, "studio_mask_disabled: a disabled mask renders byte for byte like no mask")
    report(read(d, "out_mask_half.rgba") != nomask, "studio_mask_half differs from the unmasked render (the mask does something)")
    vs("sel_clip", "studio_sel_clip (against the reference)", 2)
    sel = read(d, "sel_ellipse.r8"); got = read(d, "out_sel_clip.rgba"); none = read(d, "out_sel_none.rgba"); free = read(d, "out_sel_free.rgba")
    outside = [i for i in range(SW * SH) if sel[i] == 0]
    untouched = all(got[i * 4:i * 4 + 4] == none[i * 4:i * 4 + 4] for i in outside)
    report(untouched, "studio_sel_clip: %d pixels outside the selection are byte for byte what they were before the stroke" % len(outside))
    outside_painted = sum(1 for i in outside if free[i * 4:i * 4 + 4] != none[i * 4:i * 4 + 4])
    report(outside_painted > 500, "studio_sel_clip control: the same stroke without a selection changes %d of those pixels (the clip is what keeps them clean)" % outside_painted)
    return fails

def small(path):
    """A tiny masked scene with its expected pixels, as text, for the Kotlin reference test (core/studio-model/src/test/resources/scene_mask_small.txt)."""
    import random
    rnd = random.Random(9)
    def rl(w, h, opaque):
        return bytes(b for _ in range(w * h) for b in (rnd.randrange(256), rnd.randrange(256), rnd.randrange(256), 255 if opaque else rnd.choice([0, 90, 180, 255])))
    base = (rl(24, 16, True), 24, 16, 0.0, 0.0, 1.0, 1.0, 0, None, 0)
    mw, mh = 12, 10
    specs = [(rl(mw, mh, False), mw, mh, 4.0, 3.0, 1.5, 0.8, 1, bytes(rnd.randrange(256) for _ in range(mw * mh)), 1),
             (rl(8, 8, False), 8, 8, 2.0, 6.0, 1.0, 1.0, 0, bytes(rnd.choice([0, 255, 128]) for _ in range(64)), 2),
             (rl(6, 6, False), 6, 6, 10.0, 2.0, 1.0, 0.7, 2, bytes(rnd.randrange(256) for _ in range(36)), 0)]
    layers = [base] + specs
    view = (1.0, 1.0, 1.5, 30, 20)
    exp = R.render([R.Layer(*l) for l in layers], view[:3], view[3], view[4])
    with open(path, "w") as f:
        f.write("view %g %g %g %d %d\n" % view)
        for pix, w, h, x, y, sc, op, mode, m, mm in layers:
            f.write("layer %d %d %g %g %g %g %d %d %s %s\n" % (w, h, x, y, sc, op, mode, mm, pix.hex(), m.hex() if m else "-"))
        f.write("expected %s\n" % exp.hex())
        # selection shapes for the Kotlin Selection tests: an ellipse and a lasso over a 40 x 30 canvas
        f.write("ellipse 40 30 6.5 4.25 33.75 25.5 %s\n" % ellipse_plane(40, 30, 6.5, 4.25, 33.75, 25.5).hex())
        xs = [5.0, 30.5, 36.0, 20.25, 8.0]; ys = [3.0, 6.5, 22.0, 27.75, 15.0]
        f.write("lasso 40 30 %s | %s %s\n" % (" ".join("%g" % v for v in xs), " ".join("%g" % v for v in ys), lasso_plane(40, 30, xs, ys).hex()))

def lasso_plane(w, h, xs, ys):
    """Independent even-odd polygon coverage with the same 4x4 supersampling."""
    def inside(px, py):
        ins = False; j = len(xs) - 1
        for i in range(len(xs)):
            if (ys[i] > py) != (ys[j] > py) and px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]: ins = not ins
            j = i
        return ins
    out = bytearray(w * h)
    for y in range(max(0, math.floor(min(ys))), min(h, math.ceil(max(ys)))):
        for x in range(max(0, math.floor(min(xs))), min(w, math.ceil(max(xs)))):
            n = sum(1 for j in range(4) for i in range(4) if inside(x + (i + 0.5) / 4, y + (j + 0.5) / 4))
            out[y * w + x] = (n * 255 + 8) // 16
    return bytes(out)

if __name__ == "__main__":
    if sys.argv[1] == "small": small(sys.argv[2])
    elif sys.argv[1] == "make": make(sys.argv[2])
    elif sys.argv[1] == "compare": sys.exit(1 if compare(sys.argv[2]) else 0)

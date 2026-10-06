#!/usr/bin/env python3
"""Builds the golden scene `studio_blend3` (files for the GPU harness plus the expected image from the independent reference) and compares.
usage: studio_scene.py make <dir>      writes layer files, scene_{a,b,c}.txt and expected_{a,b,c}.rgba
       studio_scene.py compare <dir>   compares out_{a,b,c}.rgba (written by studio_golden) with the expectation; exit 1 on failure"""
import math, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import studio_ref as R

W, H = 256, 192

def base():
    p = bytearray()
    for y in range(H):
        for x in range(W):
            p += bytes((x, y * 255 // (H - 1), (x * 3 + y * 2) % 256, 255))
    return bytes(p)

def multiply_layer():   # 160 x 120, colour varies with x, alpha ramps left to right
    w, h = 160, 120; p = bytearray()
    for y in range(h):
        for x in range(w):
            p += bytes((255, 160 + (x * 95 // (w - 1)), 64 + (y * 150 // (h - 1)), x * 255 // (w - 1)))
    return bytes(p), w, h

def screen_layer():     # 100 x 100 soft disc, partial alpha at the rim
    w, h = 100, 100; p = bytearray()
    for y in range(h):
        for x in range(w):
            d = math.hypot(x + 0.5 - 50, y + 0.5 - 50)
            a = max(0.0, min(1.0, (50 - d) / 20))
            p += bytes((64, 128, 255, int(round(a * 255))))
    return bytes(p), w, h

def normal_layer():     # 64 x 64, constant half alpha yellow
    return bytes((255, 255, 0, 128)) * (64 * 64), 64, 64

LAYERS = [  # name, builder, x, y, scale, opacity, mode
    ("base", lambda: (base(), W, H), 0, 0, 1.0, 1.0, 0),
    ("multiply", multiply_layer, 40, 30, 1.0, 0.8, 1),
    ("screen", screen_layer, 90, 50, 1.5, 0.6, 2),
    ("normal", normal_layer, 10, 120, 1.0, 1.0, 0),
]
VIEWS = {"a": (0.0, 0.0, 1.0, 256, 192), "b": (64.0, 48.0, 2.0, 256, 192), "c": (-20.0, -10.0, 1.0, 200, 150),
         "band1": (0.0, 0.0, 1.0, 256, 96), "band2": (0.0, 96.0, 1.0, 256, 96)}   # band1 and band2 are the strips of a flatten export: stitched they must equal view a EXACTLY

def make(d):
    os.makedirs(d, exist_ok=True)
    built = []
    for name, build, x, y, s, op, mode in LAYERS:
        pix, w, h = build()
        open(os.path.join(d, name + ".rgba"), "wb").write(pix)
        built.append((name, R.Layer(pix, w, h, x, y, s, op, mode), w, h, x, y, s, op, mode))
    for key, (vx, vy, z, ow, oh) in VIEWS.items():
        with open(os.path.join(d, "scene_%s.txt" % key), "w") as f:
            f.write("view %g %g %g %d %d\n" % (vx, vy, z, ow, oh))
            for name, _, w, h, x, y, s, op, mode in built:
                f.write("layer %s %d %d %g %g %g %g %d\n" % (os.path.join(d, name + ".rgba"), w, h, x, y, s, op, mode))
        open(os.path.join(d, "expected_%s.rgba" % key), "wb").write(R.render([b[1] for b in built], (vx, vy, z), ow, oh))
    print("scene written to", d)

def compare(d, tol=1):
    fails = 0
    for key, (vx, vy, z, ow, oh) in VIEWS.items():
        exp = open(os.path.join(d, "expected_%s.rgba" % key), "rb").read()
        got = open(os.path.join(d, "out_%s.rgba" % key), "rb").read()
        if len(exp) != len(got): print("FAIL view %s: size %d vs %d" % (key, len(got), len(exp))); fails += 1; continue
        worst = max(abs(a - b) for a, b in zip(exp, got)); off = sum(1 for a, b in zip(exp, got) if a != b)
        ok = worst <= tol
        print("%s  studio_blend3 view %s (zoom %g): worst %d level(s), %d of %d values differ  (tolerance %d)" % ("PASS" if ok else "FAIL", key, z, worst, off, len(exp), tol))
        fails += 0 if ok else 1
    a = open(os.path.join(d, "out_a.rgba"), "rb").read()
    stitched = open(os.path.join(d, "out_band1.rgba"), "rb").read() + open(os.path.join(d, "out_band2.rgba"), "rb").read()
    same = stitched == a
    print("%s  studio_blend3 flatten strips stitched equal the whole render byte for byte" % ("PASS" if same else "FAIL"))
    fails += 0 if same else 1
    return fails

def small(path):
    """A tiny scene with its expected pixels, as text, for the Kotlin tests (core/studio-model/src/test/resources/scene_small.txt)."""
    import random
    rnd = random.Random(5)
    def rnd_layer(w, h, alpha_mode):
        p = bytearray()
        for _ in range(w * h):
            a = 255 if alpha_mode == "opaque" else rnd.choice([0, 64, 128, 200, 255])
            p += bytes((rnd.randrange(256), rnd.randrange(256), rnd.randrange(256), a))
        return bytes(p)
    specs = [(24, 16, 0.0, 0.0, 1.0, 1.0, 0, "opaque"), (12, 10, 4.0, 3.0, 1.0, 0.8, 1, "mixed"), (8, 8, 6.0, 2.0, 1.5, 0.6, 2, "mixed"), (6, 6, 1.0, 9.0, 1.0, 1.0, 0, "mixed")]
    layers = [(rnd_layer(w, h, am), w, h, x, y, sc, op, mode) for w, h, x, y, sc, op, mode, am in specs]
    view = (2.0, 1.0, 1.5, 30, 20)
    exp = R.render([R.Layer(*l) for l in layers], view[:3], view[3], view[4])
    with open(path, "w") as f:
        f.write("view %g %g %g %d %d\n" % view)
        for pix, w, h, x, y, sc, op, mode in layers:
            f.write("layer %d %d %g %g %g %g %d %s\n" % (w, h, x, y, sc, op, mode, pix.hex()))
        f.write("expected %s\n" % exp.hex())

if __name__ == "__main__":
    if sys.argv[1] == "make": make(sys.argv[2])
    elif sys.argv[1] == "small": small(sys.argv[2])
    elif sys.argv[1] == "compare": sys.exit(1 if compare(sys.argv[2]) else 0)

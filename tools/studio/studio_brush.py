#!/usr/bin/env python3
"""Independent reference for the Studio brush (spec 4.3): stamp walker, stamp coverage, stroke accumulation and commit. Standard library only.
usage: studio_brush.py vectors <file>     walker cases for the Kotlin test
       studio_brush.py make <dir>         golden scenes `studio_brush` (files for the GPU harness and expected images)
       studio_brush.py compare <dir>      compare out_*.rgba with expected_*.rgba"""
import math, os, random, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import studio_ref as R

def falloff(r, radius, hardness):
    """Coverage of a round stamp at distance r from its centre: 1 inside hardness * radius, 0 outside radius, smoothstep between."""
    if r >= radius: return 0.0
    inner = hardness * radius
    if r <= inner: return 1.0
    u = 1.0 - (r - inner) / (radius - inner)
    return u * u * (3.0 - 2.0 * u)

def stamp_diameter(base, pressure, pressure_size):
    return base * (0.2 + 0.8 * pressure) if pressure_size else base

def walk(points, diameter, spacing, pressure_size):
    """points: [(x, y, pressure)] in layer pixels. Returns stamps [(x, y, radius)]: one at the first point, then every
    max(0.5, d * spacing) pixels along the path (d is the diameter of the previous stamp), the remainder carried across segments."""
    if not points: return []
    out = []
    x, y, p = points[0]
    d = stamp_diameter(diameter, p, pressure_size); out.append((x, y, d / 2.0))
    step = max(0.5, d * spacing)   # fixed when a stamp is placed
    carry = 0.0                    # distance travelled since the last stamp
    for (x0, y0, p0), (x1, y1, p1) in zip(points, points[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        if seg == 0: continue
        t = 0.0
        while True:
            need = step - carry
            if t + need > seg + 1e-9:
                carry += seg - t; break
            t += need; carry = 0.0
            f = t / seg
            pp = p0 + (p1 - p0) * f
            d = stamp_diameter(diameter, pp, pressure_size)
            out.append((x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, d / 2.0))
            step = max(0.5, d * spacing)
    return out

def coverage(w, h, stamps, hardness, flow):
    """Accumulated stroke coverage per pixel (row 0 top), pixel centres, 'over' accumulation a = a + s (1 - a) with s = flow * stamp coverage."""
    acc = [0.0] * (w * h)
    for cx, cy, r in stamps:
        x0, x1 = max(0, int(math.floor(cx - r - 1))), min(w - 1, int(math.ceil(cx + r + 1)))
        y0, y1 = max(0, int(math.floor(cy - r - 1))), min(h - 1, int(math.ceil(cy + r + 1)))
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                c = falloff(math.hypot(x + 0.5 - cx, y + 0.5 - cy), r, hardness)
                if c > 0.0:
                    s = flow * c; i = y * w + x
                    acc[i] = acc[i] + s * (1.0 - acc[i])
    return acc

def commit(pix, w, h, cov, colour, opacity, erase):
    """Bakes a stroke into straight RGBA8 layer pixels: paint = over with colour at cov * opacity, erase = alpha * (1 - cov * opacity)."""
    out = bytearray(pix)
    for i in range(w * h):
        a = min(cov[i], 1.0) * opacity
        if a <= 0.0: continue
        o = i * 4
        if erase:
            out[o + 3] = int(math.floor(pix[o + 3] * (1.0 - a) + 0.5))
        else:
            r, g, b, ar = R.composite(tuple(pix[o + k] / 255.0 for k in range(3)), pix[o + 3] / 255.0, colour, a, 0)
            out[o:o + 4] = bytes(int(min(max(v, 0.0), 1.0) * 255.0 + 0.5) for v in (r, g, b, ar))
        if out[o + 3] == 0: out[o:o + 3] = b"\0\0\0"   # canonical transparent: no hidden colour under alpha 0
    return bytes(out)

def write_vectors(path):
    rnd = random.Random(11)
    lines = ["# diameter spacing pressureSize | x y p ... | -> x y radius ..."]
    cases = [(20.0, 0.1, 0, [(10, 10, 1), (60, 10, 1)]),
             (20.0, 0.25, 0, [(10, 10, 1), (30, 10, 1), (30, 40, 1)]),
             (10.0, 0.05, 1, [(5, 5, 0.0), (45, 5, 1.0)]),
             (3.0, 0.1, 0, [(0, 0, 1), (10, 0, 1)])] + \
            [(rnd.choice([4.0, 12.0, 40.0]), rnd.choice([0.05, 0.1, 0.3]), rnd.randrange(2),
              [(rnd.uniform(0, 100), rnd.uniform(0, 100), rnd.random()) for _ in range(rnd.randrange(1, 6))]) for _ in range(40)]
    for d, sp, ps, pts in cases:
        st = walk(pts, d, sp, bool(ps))
        lines.append("%.9g %.9g %d | %s | -> %s" % (d, sp, ps, " ".join("%.9g %.9g %.9g" % p for p in pts), " ".join("%.9g %.9g %.9g" % s for s in st)))
    open(path, "w").write("\n".join(lines) + "\n")
    return len(cases)

def small(path):
    """A small stroke case with its baked result, as text, for the Kotlin tests (core/studio-model/src/test/resources/stroke_small.txt)."""
    rnd = random.Random(21)
    w, h = 30, 22
    pix = bytearray()
    for _ in range(w * h):
        a = rnd.choice([0, 0, 60, 128, 255])
        pix += bytes((rnd.randrange(256), rnd.randrange(256), rnd.randrange(256), a)) if a else bytes(4)
    cases = [("paint", 9.0, 0.5, 0.8, 0.6, 0.1, 1, [(4, 4, 0.3), (15, 12, 0.9), (26, 6, 0.5)], (0.9, 0.4, 0.1)),
             ("erase", 7.0, 0.8, 1.0, 0.7, 0.1, 0, [(3, 18, 1), (27, 3, 1)], (0, 0, 0))]
    with open(path, "w") as f:
        f.write("layer %d %d %s\n" % (w, h, bytes(pix).hex()))
        for kind, dia, hard, flow, op, sp, ps, pts, col in cases:
            st = walk(pts, dia, sp, bool(ps)); cov = coverage(w, h, st, hard, flow)
            baked = commit(bytes(pix), w, h, cov, col, op, kind == "erase")
            f.write("case %s %g %g %g %g %g %d %g %g %g | %s | %s\n" % (kind, dia, hard, flow, op, sp, ps, col[0], col[1], col[2], " ".join("%g %g %g" % p for p in pts), baked.hex()))

# ---- golden scene studio_brush -------------------------------------------------------------------------------------------------
W, H = 200, 150
def backdrop():
    p = bytearray()
    for y in range(H):
        for x in range(W):
            p += bytes((40 + x, 200 - y, 90 + (x + y) % 60, 255))
    return bytes(p)

def opaque_blue():
    return bytes((30, 60, 200, 255)) * (W * H)

CASES = {   # name: (stroke points, diameter, hardness, flow, spacing, pressureSize, colour, opacity, erase, base pixels builder)
    "hard": ([(20, 30, 1), (100, 30, 1), (160, 110, 1)], 30.0, 1.0, 1.0, 0.1, False, (0.9, 0.2, 0.1), 1.0, False, None),
    "soft": ([(30, 100, 1), (170, 40, 1)], 40.0, 0.2, 1.0, 0.1, False, (0.1, 0.8, 0.3), 0.7, False, None),
    "flow": ([(20, 75, 1), (180, 75, 1)], 24.0, 0.5, 0.15, 0.08, False, (1.0, 1.0, 1.0), 1.0, False, None),
    "pressure": ([(20, 20, 0.1), (100, 70, 0.6), (180, 120, 1.0)], 36.0, 0.8, 1.0, 0.1, True, (0.0, 0.0, 0.0), 0.9, False, None),
    "erase": ([(30, 20, 1), (170, 130, 1)], 44.0, 0.6, 1.0, 0.1, False, (0, 0, 0), 0.8, True, opaque_blue),
}

def make(d):
    os.makedirs(d, exist_ok=True)
    base = backdrop()
    open(os.path.join(d, "bg.rgba"), "wb").write(base)
    for name, (pts, dia, hard, flow, sp, ps, col, op, er, builder) in CASES.items():
        paint0 = builder() if builder else bytes(W * H * 4)
        open(os.path.join(d, "paint_%s.rgba" % name), "wb").write(paint0)
        stamps = walk(pts, dia, sp, ps)
        cov = coverage(W, H, stamps, hard, flow)
        with open(os.path.join(d, "scene_%s.txt" % name), "w") as f:
            f.write("canvas %d %d\nlayer %s %d %d 0 0 1 1 0\nlayer %s %d %d 0 0 1 1 0\n" % (W, H, os.path.join(d, "bg.rgba"), W, H, os.path.join(d, "paint_%s.rgba" % name), W, H))
            f.write("stroke 1 %g %g %g %g %d %g %g\n" % (col[0], col[1], col[2], op, 1 if er else 0, hard, flow))   # paint layer slot 1
            for x, y, r in stamps: f.write("stamp %.9g %.9g %.9g\n" % (x, y, r))
        baked = commit(paint0, W, H, cov, col, op, er)
        exp = R.render([R.Layer(base, W, H), R.Layer(baked, W, H)], (0.0, 0.0, 1.0), W, H)
        open(os.path.join(d, "expected_%s.rgba" % name), "wb").write(exp)
        open(os.path.join(d, "baked_%s.rgba" % name), "wb").write(baked)
        print("%s: %d stamps" % (name, len(stamps)))

def compare(d, tol=2):
    fails = 0
    for name in CASES:
        exp = open(os.path.join(d, "expected_%s.rgba" % name), "rb").read()
        got = open(os.path.join(d, "out_%s.rgba" % name), "rb").read()
        worst = max(abs(a - b) for a, b in zip(exp, got)); off = sum(1 for a, b in zip(exp, got) if abs(a - b) > 1)
        ok = worst <= tol
        print("%s  studio_brush %-8s worst %d level(s), %d values off by more than 1  (tolerance %d)" % ("PASS" if ok else "FAIL", name, worst, off, tol))
        fails += 0 if ok else 1
    return fails

if __name__ == "__main__":
    c = sys.argv[1]
    if c == "vectors": print(write_vectors(sys.argv[2]), "walker cases")
    elif c == "small": small(sys.argv[2])
    elif c == "make": make(sys.argv[2])
    elif c == "compare": sys.exit(1 if compare(sys.argv[2]) else 0)

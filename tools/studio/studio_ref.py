#!/usr/bin/env python3
"""Independent reference for the Studio compositor (spec 2.2, W3C Compositing and Blending Level 1, straight alpha, GAMMA blend space).
Standard library only. Used to (1) generate blend_vectors.tsv that the Kotlin tests read, (2) render the golden scene expected image."""
import math, random, sys, os, struct

MODES = {0: "normal", 1: "multiply", 2: "screen"}

def blend(mode, cb, cs):
    if mode == 0: return cs
    if mode == 1: return cb * cs
    if mode == 2: return cb + cs - cb * cs
    raise ValueError(mode)

def composite(cb, ab, cs, a_s, mode, opacity=1.0, mask=1.0):
    """backdrop colour cb (3 floats) and alpha ab, source colour cs and alpha a_s, layer opacity, mask. Returns (r, g, b, a) straight."""
    a_s = a_s * opacity * mask
    ar = a_s + ab * (1.0 - a_s)
    if ar <= 0.0: return (0.0, 0.0, 0.0, 0.0)
    out = []
    for i in range(3):
        cr = ((1.0 - a_s) * ab * cb[i] + a_s * ((1.0 - ab) * cs[i] + ab * blend(mode, cb[i], cs[i]))) / ar
        out.append(cr)
    return (out[0], out[1], out[2], ar)

class Layer:
    def __init__(self, pix, w, h, x=0.0, y=0.0, scale=1.0, opacity=1.0, mode=0):
        self.pix, self.w, self.h, self.x, self.y, self.scale, self.opacity, self.mode = pix, w, h, x, y, scale, opacity, mode   # pix: bytes RGBA8 straight

    def sample(self, dx, dy):
        """Straight RGBA (floats 0..1) of the layer at document position (dx, dy) in pixel centres, bilinear and alpha weighted; alpha 0 outside."""
        rw, rh = self.w * self.scale, self.h * self.scale
        lx = (dx - self.x) / rw * self.w
        ly = (dy - self.y) / rh * self.h
        if not (0.0 <= lx < self.w and 0.0 <= ly < self.h): return (0.0, 0.0, 0.0, 0.0)
        u, v = lx - 0.5, ly - 0.5
        i0, j0 = math.floor(u), math.floor(v)
        fx, fy = u - i0, v - j0
        acc = [0.0, 0.0, 0.0]; asum = 0.0
        for jj, wy in ((j0, 1 - fy), (j0 + 1, fy)):
            for ii, wx in ((i0, 1 - fx), (i0 + 1, fx)):
                cx = min(max(ii, 0), self.w - 1); cy = min(max(jj, 0), self.h - 1)
                o = (cy * self.w + cx) * 4
                a = self.pix[o + 3] / 255.0
                wgt = wx * wy * a
                for c in range(3): acc[c] += wgt * (self.pix[o + c] / 255.0)
                asum += wgt
        if asum <= 0.0: return (0.0, 0.0, 0.0, 0.0)
        return (acc[0] / asum, acc[1] / asum, acc[2] / asum, asum)

def render(layers, view, out_w, out_h):
    """layers bottom to top (visible only). view = (vx, vy, zoom). Returns bytes RGBA8 straight, rounded to nearest."""
    vx, vy, zoom = view
    out = bytearray(out_w * out_h * 4)
    for y in range(out_h):
        for x in range(out_w):
            dx, dy = vx + (x + 0.5) / zoom, vy + (y + 0.5) / zoom
            cb, ab = (0.0, 0.0, 0.0), 0.0
            for L in layers:
                r, g, b, a = L.sample(dx, dy)
                if a > 0.0:
                    r, g, b, a = composite(cb, ab, (r, g, b), a, L.mode, L.opacity)
                    cb, ab = (r, g, b), a
            o = (y * out_w + x) * 4
            out[o:o + 4] = bytes(int(min(max(v, 0.0), 1.0) * 255.0 + 0.5) for v in (cb[0], cb[1], cb[2], ab))
    return bytes(out)

def write_vectors(path, n=300, seed=7):
    """Random cases with a hand-checkable structure: cb, ab, cs, as, mode, opacity, expected r g b a (9 significant digits)."""
    rnd = random.Random(seed)
    lines = ["# cbr cbg cbb ab csr csg csb as mode opacity -> r g b a (W3C general formula, straight alpha)"]
    special = [(0.2, 0.4, 0.6, 1.0, 0.8, 0.5, 0.1, 1.0, m, 1.0) for m in (0, 1, 2)] + \
              [(0.2, 0.4, 0.6, 1.0, 0.8, 0.5, 0.1, 0.5, m, 1.0) for m in (0, 1, 2)] + \
              [(0.2, 0.4, 0.6, 0.0, 0.8, 0.5, 0.1, 1.0, m, 1.0) for m in (0, 1, 2)] + \
              [(0.2, 0.4, 0.6, 0.5, 0.8, 0.5, 0.1, 0.5, m, 0.8) for m in (0, 1, 2)] + \
              [(0.2, 0.4, 0.6, 1.0, 0.8, 0.5, 0.1, 0.0, m, 1.0) for m in (0, 1, 2)]
    cases = special + [tuple(rnd.random() for _ in range(3)) + (rnd.choice([0.0, 1.0, rnd.random()]),) + tuple(rnd.random() for _ in range(3)) +
                       (rnd.choice([0.0, 1.0, rnd.random()]), rnd.randrange(3), rnd.choice([1.0, rnd.random()])) for _ in range(n)]
    for c in cases:
        cb, ab, cs, a_s, mode, op = c[0:3], c[3], c[4:7], c[7], int(c[8]), c[9]
        r = composite(cb, ab, cs, a_s, mode, op)
        lines.append(" ".join("%.9g" % v for v in list(cb) + [ab] + list(cs) + [a_s]) + " %d %.9g -> " % (mode, op) + " ".join("%.9g" % v for v in r))
    open(path, "w").write("\n".join(lines) + "\n")
    return len(cases)

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "vectors":
        print(write_vectors(sys.argv[2] if len(sys.argv) > 2 else "blend_vectors.tsv"), "vectors")

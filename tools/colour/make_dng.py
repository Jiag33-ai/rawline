#!/usr/bin/env python3
"""Synthetic Bayer DNG writer for colour pipeline tests. Standard library only."""
import struct, math, random, json, sys

# ---- colour constants ----
XYZ_D65 = (0.95047, 1.0, 1.08883)
SRGB_TO_XYZ = (0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750, 0.0193339, 0.1191920, 0.9503041)
# LibRaw prophoto_rgb: linear sRGB -> working space (ProPhoto, Bradford adapted)
SRGB_TO_WORK = (0.529317, 0.330092, 0.140588, 0.098368, 0.873465, 0.028169, 0.016879, 0.117663, 0.865457)
M_S5M2 = tuple(v / 10000.0 for v in (10308, -4206, -783, -4088, 12102, 2229, -125, 1051, 5912))  # LibRaw DC-S5M2 XYZ->camera

def mv(A, v): return [sum(A[3*r+k]*v[k] for k in range(3)) for r in range(3)]
def mul(A, B): return [sum(A[3*r+k]*B[3*k+c] for k in range(3)) for r in range(3) for c in range(3)]
def srgb_eotf(v):
    return v/12.92 if v <= 0.04045 else ((v+0.055)/1.055)**2.4

def balanced_matrix(M0):
    """Rows scaled so that M * XYZ_D65 = (1,1,1): camera neutral is (1,1,1)."""
    n = mv(M0, XYZ_D65)
    return [M0[3*r+c]/n[r] for r in range(3) for c in range(3)]

W, H, PATCH = 1200, 800, 150           # 8 x 5 patches of 150 px; first 24 used (6 x 4 grid in the top left 900 x 600), rest grey
BLACK = (512, 512, 512, 512)           # R, G1, G2, B   (RGGB)
WHITE = 16383

def make_scene(patches_lin_srgb, exposure, M, neutral_scale=(1.0, 1.0, 1.0), noise=None, seed=1, BLACK=BLACK):
    """Returns the mosaic (list of rows of ints). patches: list of (r,g,b) linear sRGB, scene-referred, white = 1.0."""
    rnd = random.Random(seed)
    cams = []
    for s in patches_lin_srgb:
        xyz = mv(SRGB_TO_XYZ, s)
        cam = mv(M, xyz)                                   # camera native, neutral (1,1,1) when M is balanced
        cams.append([cam[c] * neutral_scale[c] * exposure for c in range(3)])
    cols = 6
    rows = []
    for y in range(H):
        row = []
        for x in range(W):
            px, py = x // PATCH, y // PATCH
            idx = py * cols + px if (px < cols and py < 4) else None
            cam = cams[idx] if idx is not None and idx < len(cams) else [0.18 * exposure * neutral_scale[c] for c in range(3)]
            c = 0 if (y % 2 == 0 and x % 2 == 0) else 2 if (y % 2 == 1 and x % 2 == 1) else 1
            ch = 0 if c == 0 else 2 if c == 2 else 1
            black = BLACK[0] if c == 0 else BLACK[3] if c == 2 else BLACK[1 if y % 2 == 0 else 2]
            v = black + cam[ch] * (WHITE - black)
            if noise:
                sig = math.sqrt(noise[0] + noise[1] * max(v - black, 0))
                v += rnd.gauss(0, sig)
            row.append(min(WHITE, max(0, int(round(v)))))
        rows.append(row)
    return rows

def tiff_dng(path, mosaic, M, neutral, white=WHITE, black=BLACK):
    w, h = W, H
    data = b"".join(struct.pack("<%dH" % w, *r) for r in mosaic)
    ents = []   # (tag, type, count, payload bytes or int)
    def short(t, *v): ents.append((t, 3, len(v), struct.pack("<%dH" % len(v), *v)))
    def long_(t, *v): ents.append((t, 4, len(v), struct.pack("<%dI" % len(v), *v)))
    def byte(t, *v): ents.append((t, 1, len(v), bytes(v)))
    def ascii_(t, s): b = s.encode() + b"\0"; ents.append((t, 2, len(b), b))
    def rat(t, vals): ents.append((t, 5, len(vals), b"".join(struct.pack("<II", int(round(v*10000)), 10000) for v in vals)))
    def srat(t, vals): ents.append((t, 10, len(vals), b"".join(struct.pack("<ii", int(round(v*10000)), 10000) for v in vals)))
    long_(254, 0); long_(256, w); long_(257, h); short(258, 16); short(259, 1); short(262, 32803)
    ascii_(271, "Rawline"); ascii_(272, "Synthetic Chart")
    long_(273, 0)  # strip offset placeholder, patched below
    short(274, 1); short(277, 1); long_(278, h); long_(279, len(data)); short(284, 1)
    short(33421, 2, 2); byte(33422, 0, 1, 1, 2)
    byte(50706, 1, 4, 0, 0); byte(50707, 1, 1, 0, 0); ascii_(50708, "Rawline Synthetic")
    short(50713, 2, 2); short(50714, *black); long_(50717, white)
    srat(50721, M); rat(50728, neutral); srat(50730, [0.0]); short(50778, 21)
    ents.sort(key=lambda e: e[0])
    n = len(ents)
    ifd_off = 8
    ifd_size = 2 + n*12 + 4
    extra_off = ifd_off + ifd_size
    extra = b""; table = []
    for tag, typ, cnt, payload in ents:
        if len(payload) <= 4:
            table.append((tag, typ, cnt, payload.ljust(4, b"\0")))
        else:
            off = extra_off + len(extra)
            extra += payload + (b"\0" if len(payload) % 2 else b"")
            table.append((tag, typ, cnt, struct.pack("<I", off)))
    strip_off = extra_off + len(extra)
    out = struct.pack("<2sHI", b"II", 42, ifd_off) + struct.pack("<H", n)
    for tag, typ, cnt, val in table:
        if tag == 273: val = struct.pack("<I", strip_off)
        out += struct.pack("<HHI", tag, typ, cnt) + val
    out += struct.pack("<I", 0) + extra + data
    open(path, "wb").write(out)

if __name__ == "__main__":
    ramp = []
    for i in range(24):
        v = 0.002 * (1.0/0.002) ** (i/23.0)
        ramp.append((v, v, v))
    M = balanced_matrix(M_S5M2)
    mos = make_scene(ramp, 0.8, M)
    tiff_dng(sys.argv[1] if len(sys.argv) > 1 else "ramp.dng", mos, M, (1.0, 1.0, 1.0))
    json.dump({"patches": ramp}, open("ramp.json", "w"))

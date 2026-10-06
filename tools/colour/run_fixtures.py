#!/usr/bin/env python3
"""Colour verification: builds fixtures F1..F8, renders each through the golden binary, compares with the reference model.
Standard library only. Exit code 0 when every fixture behaves as its status says (PASS must pass, XFAIL must still fail)."""
import sys, os, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_dng as m, reference as R

CHART8 = [(115,82,68),(194,150,130),(98,122,157),(87,108,67),(133,128,177),(103,189,170),
          (214,126,44),(80,91,166),(193,90,99),(94,60,108),(157,188,64),(224,163,46),
          (56,61,150),(70,148,73),(175,54,60),(231,199,31),(187,86,149),(8,133,161),
          (243,243,242),(200,200,200),(160,160,160),(122,122,121),(85,85,85),(52,52,52)]
CHART = [tuple(m.srgb_eotf(v/255) for v in p) for p in CHART8]
RAMP = [(0.002*(500**(i/23)),)*3 for i in range(24)]
PRIM = [(1,0,0),(0,1,0),(0,0,1),(0,1,1),(1,0,1),(1,1,0),(.5,0,0),(0,.5,0),(0,0,.5),(0,.5,.5),(.5,0,.5),(.5,.5,0),
        (.18,.18,.18),(.5,.5,.5),(1,1,1),(0,0,0),(.9,.6,.5),(.5,.6,.9),(.6,.9,.5),(.05,.05,.05),(.02,.02,.02),(.01,.01,.01),(.005,.005,.005),(.002,.002,.002)]

def build(name, patches, E, neutral=(1,1,1), black=m.BLACK, noise=None):
    M = m.balanced_matrix(m.M_S5M2)
    path = name + ".dng"
    m.tiff_dng(path, m.make_scene(patches, E, M, neutral, noise, BLACK=black), M, neutral, black=black)
    return path

def check_dng(path):
    """Re-parses the file we wrote: header, tag table sorted, required DNG tags present."""
    d = open(path, "rb").read()
    assert d[:4] == b"II*\x00", "bad TIFF header"
    off = struct.unpack("<I", d[4:8])[0]; n = struct.unpack("<H", d[off:off+2])[0]
    tags = [struct.unpack("<H", d[off+2+12*i:off+4+12*i])[0] for i in range(n)]
    assert tags == sorted(tags), "IFD tags must be ascending"
    for t in (256, 257, 258, 259, 262, 273, 279, 33421, 33422, 50706, 50714, 50717, 50721, 50728):
        assert t in tags, "missing tag %d" % t

def neutral_k(neutral):
    """Expected overall scale K for a balanced-matrix DNG with AsShotNeutral=neutral (LibRaw highlight=2): min(neutral)."""
    return min(neutral)

# name, patches, exposure, kwargs, expected K (None = apply the design target K=1), tolerance, status
FIXTURES = [
  ("F1_ramp",    RAMP,  0.7, {},                                 1.0,  2.0, "PASS"),
  ("F2_chart",   CHART, 0.7, {},                                 1.0,  3.0, "PASS"),
  ("F3_black",   CHART, 0.7, dict(black=(1024,)*4),              1.0,  3.0, "PASS"),
  ("F4_prim",    PRIM,  0.7, dict(),                             1.0,  6.0, "PASS"),
  ("F5_noise",   CHART, 0.7, dict(noise=(4.0, 0.05)),            1.0,  3.0, "PASS"),
  ("F6_wb_warm", CHART, 0.7, dict(neutral=(0.55, 1.0, 0.7)),     1.0,  3.0, "XFAIL"),   # design target K=1 (AE-023); today K=min(n)=0.55
  ("F7_wb_cool", CHART, 0.7, dict(neutral=(0.8, 1.0, 0.6)),      1.0,  3.0, "XFAIL"),
  ("F8_maxthr",  RAMP,  0.8, {},                                 1.0,  3.0, "XFAIL"),   # LibRaw adjust_maximum_thr: brightest pixel in (0.75, 1.0) of white rescales to 1.0
]

def run(fx):
    name, patches, E, kw, K, tol, status = fx
    img = R.render(build(name, patches, E, **kw), name + ".ppm", 600)
    worst, rows = 0.0, []
    for i, pt in enumerate(patches):
        obs = R.patch_mean(img, i, 0.5); exp = R.expected_srgb8(pt, E, K)
        d = max(abs(a-b) for a, b in zip(obs, exp)); worst = max(worst, d); rows.append((i, [round(v, 1) for v in obs], exp, round(d, 2)))
    return worst, rows

def clipped_neutral_test():
    """F9: a clipped neutral must land at >= 253 with R=G=B within 1 level, for unity and warm WB."""
    res = {}
    for label, nb in (("unity", (1, 1, 1)), ("warm", (0.55, 1.0, 0.7))):
        img = R.render(build("F9_clip_" + label, RAMP, 4.0, neutral=nb), "F9_clip_%s.ppm" % label, 600)
        o = R.patch_mean(img, 23, 0.5); res[label] = (round(min(o)), round(max(o) - min(o), 1))
    return res

def matrices_test():
    """M1..M3 without rendering: engine ProPhoto->P3 times LibRaw prophoto_rgb equals the nominal sRGB->P3 matrix; rows sum to 1."""
    mp3 = (1.63277,-0.37961,-0.252809,-0.153699,1.166619,-0.013002,0.010388,-0.062789,1.052053)
    A = m.mul(mp3, m.SRGB_TO_WORK)
    nominal = (0.8224621,0.1775380,0.0,0.0331941,0.9668058,0.0,0.0170827,0.0723974,0.9105199)
    worst = max(abs(a-b) for a, b in zip(A, nominal)); rows = [sum(A[3*i:3*i+3]) for i in range(3)]
    return worst, rows

if __name__ == "__main__":
    verbose = "-v" in sys.argv; bad = 0
    for fx in FIXTURES:
        worst, rows = run(fx); status = fx[6]; passed = worst <= fx[5]
        ok = passed if status == "PASS" else not passed
        verdict = ("PASS" if passed else "FAIL") + ("" if status == "PASS" else "  (expected failure: %s)" % ("still failing" if not passed else "XPASS, remove the marker"))
        print("%-11s E=%.2f worst %.2f levels tol %.1f  %s" % (fx[0], fx[2], worst, fx[5], verdict))
        if verbose:
            for r in rows: print("    ", r)
        bad += 0 if ok else 1
    for fx in FIXTURES: check_dng(fx[0] + ".dng")
    c = clipped_neutral_test()
    for k, (lo, spread) in c.items():
        ok = lo >= 253 and spread <= 1
        print("F9_clip_%-6s min channel %d spread %.1f  %s" % (k, lo, spread, "PASS" if ok else "FAIL (expected for warm until AE-001 is fixed)" if k == "warm" else "FAIL"))
        bad += 0 if (ok or k == "warm") else 1
    w, rows = matrices_test()
    print("M_P3 max |engine*libraw - nominal| = %.5f, row sums %s  %s" % (w, [round(r, 5) for r in rows], "PASS" if w < 5e-4 and all(abs(r-1) < 5e-4 for r in rows) else "FAIL"))
    bad += 0 if (w < 5e-4 and all(abs(r-1) < 5e-4 for r in rows)) else 1
    sys.exit(1 if bad else 0)

#!/usr/bin/env python3
"""Colour verification: builds fixtures F1..F9, renders each through the golden binary under BOTH look versions and compares with the reference model.
Standard library only. Exit code 0 when every fixture behaves as its status for that look says."""
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
    d = open(path, "rb").read()
    assert d[:4] == b"II*\x00", "bad TIFF header"
    off = struct.unpack("<I", d[4:8])[0]; n = struct.unpack("<H", d[off:off+2])[0]
    tags = [struct.unpack("<H", d[off+2+12*i:off+4+12*i])[0] for i in range(n)]
    assert tags == sorted(tags), "IFD tags must be ascending"
    for t in (256, 257, 258, 259, 262, 273, 279, 33421, 33422, 50706, 50714, 50717, 50721, 50728):
        assert t in tags, "missing tag %d" % t

# name, patches, exposure, kwargs, tolerance, status under look 1, status under look 2.
# Look 1 is the rendering of every edit saved before the look field existed: its known faults are asserted so nobody "fixes" it by accident.
# Look 2 is the colour contract: K = 1 whatever the white balance or the frame's brightest pixel, and a clipped neutral is white.
FIXTURES = [
  ("F1_ramp",    RAMP,  0.7, {},                              2.0, "PASS",  "PASS"),
  ("F2_chart",   CHART, 0.7, {},                              3.0, "PASS",  "PASS"),
  ("F3_black",   CHART, 0.7, dict(black=(1024,)*4),           3.0, "PASS",  "PASS"),
  ("F4_prim",    PRIM,  0.7, {},                              6.0, "PASS",  "PASS"),
  ("F5_noise",   CHART, 0.7, dict(noise=(4.0, 0.05)),         3.0, "PASS",  "PASS"),
  ("F6_wb_warm", CHART, 0.7, dict(neutral=(0.55, 1.0, 0.7)),  3.0, "XFAIL", "PASS"),
  ("F7_wb_cool", CHART, 0.7, dict(neutral=(0.8, 1.0, 0.6)),   3.0, "XFAIL", "PASS"),
  ("F8_maxthr",  RAMP,  0.8, {},                              3.0, "XFAIL", "PASS"),
]

def run(fx, look):
    name, patches, E, kw, tol, s1, s2 = fx
    img = R.render(build("L%d_%s" % (look, name), patches, E, **kw), "L%d_%s.ppm" % (look, name), 600, look=look)
    worst = 0.0
    for i, pt in enumerate(patches):
        obs = R.patch_mean(img, i, 0.5); exp = R.expected_srgb8(pt, E, 1.0, look=look)
        worst = max(worst, max(abs(a-b) for a, b in zip(obs, exp)))
    return worst

def clipped_neutral(look, neutral):
    """F9: a heavily overexposed ramp (E = 4): the brightest patch must be white and neutral."""
    img = R.render(build("L%d_F9" % look, RAMP, 4.0, neutral=neutral), "L%d_F9.ppm" % look, 600, look=look)
    o = R.patch_mean(img, 23, 0.5)
    return round(min(o)), round(max(o) - min(o), 1)

def matrices_test():
    mp3 = (1.63277,-0.37961,-0.252809,-0.153699,1.166619,-0.013002,0.010388,-0.062789,1.052053)
    A = m.mul(mp3, m.SRGB_TO_WORK)
    nominal = (0.8224621,0.1775380,0.0,0.0331941,0.9668058,0.0,0.0170827,0.0723974,0.9105199)
    return max(abs(a-b) for a, b in zip(A, nominal)), [sum(A[3*i:3*i+3]) for i in range(3)]

if __name__ == "__main__":
    bad = 0
    for look in (1, 2):
        print("--- look version %d" % look)
        for fx in FIXTURES:
            status = fx[5] if look == 1 else fx[6]
            worst = run(fx, look); passed = worst <= fx[4]
            ok = passed if status == "PASS" else not passed
            print("%-11s E=%.2f worst %6.2f levels tol %.1f  %s%s" % (fx[0], fx[2], worst, fx[4], "PASS" if passed else "FAIL", "" if status == "PASS" else "  (look 1 keeps its known fault)" if not passed else "  UNEXPECTED PASS"))
            bad += 0 if ok else 1
        for label, nb in (("unity", (1, 1, 1)), ("warm", (0.55, 1.0, 0.7))):
            lo, spread = clipped_neutral(look, nb); white = lo >= 253 and spread <= 1
            expect_white = look == 2 or label == "unity"
            print("F9_clip_%-6s min channel %d spread %.1f  %s" % (label, lo, spread, "white" if white else "not white" + ("" if expect_white else "  (look 1 keeps its known fault)")))
            bad += 0 if white == expect_white else 1
    for fx in FIXTURES: check_dng("L1_" + fx[0] + ".dng")
    w, rows = matrices_test()
    ok = w < 5e-4 and all(abs(r-1) < 5e-4 for r in rows)
    print("M_P3 max |engine*libraw - nominal| = %.5f  %s" % (w, "PASS" if ok else "FAIL")); bad += 0 if ok else 1
    sys.exit(1 if bad else 0)

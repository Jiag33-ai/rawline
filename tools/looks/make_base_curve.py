#!/usr/bin/env python3
"""Look version 2 base curve: the version 1 curve re-expressed for a neutral that clips at 1.0 (K0 = pre_mul of green in the fit samples), with a shoulder that reaches white at 1.0.
Standard library only. Prints the comparison and, with `table`, the 256 entry table for engine/base_curve.h and BaseCurve.kt."""
import os, re, sys, math
REPO = os.environ.get('RAWLINE_REPO', os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')))
G, C, K0 = 4.30, 1.10, 0.4971
def oetf(l): return 12.92*l if l <= 0.0031308 else 1.055*l**(1/2.4)-0.055
def eotf(s): return s/12.92 if s <= 0.04045 else ((s+0.055)/1.055)**2.4
def y_old(u): return G*u/(1+(G-1)*u**C)                 # version 1 curve in linear light, input u = camera neutral after the old scaling
def d_old(u, h=1e-6): return (y_old(u+h)-y_old(u-h))/(2*h)
XK = 0.55   # knee: below this neutral level the version 2 curve is the version 1 curve exactly; above it a cubic shoulder reaches white at 1.0
def y2(x, xk=None):
    xk = XK if xk is None else xk
    if x <= xk: return y_old(K0*x)
    yk, sk = y_old(K0*xk), K0*d_old(K0*xk)
    t = (x-xk)/(1-xk)                                    # cubic Hermite from (xk, yk, sk) to (1, 1, 0)
    h00, h10, h01 = 2*t**3-3*t**2+1, t**3-2*t**2+t, -2*t**3+3*t**2
    return h00*yk + h10*(1-xk)*sk + h01*1.0
def table2(xk=None):
    return [oetf(min(max(y2(eotf(i/255), xk), 0), 1)) for i in range(256)]
def table1():
    src = open(REPO + '/core/render/src/main/kotlin/app/rawline/core/render/BaseCurve.kt').read()
    return [float(x) for x in re.findall(r"[0-9.]+(?=f)", src.split("val TABLE = floatArrayOf(")[1].split(")")[0])][:256]
def check():
    """Property tests of the version 2 table (BK-440). Returns a list of failures."""
    t1, t2 = table1(), table2(); bad = []
    def lookup(t, s): p = s*255; i = min(int(p), 254); return t[i]+(t[i+1]-t[i])*(p-i)
    if abs(t2[0]) > 1e-9: bad.append("t[0] is not 0")
    if abs(t2[255]-1.0) > 1e-9: bad.append("t[255] is not 1")
    if any(b < a for a, b in zip(t2, t2[1:])): bad.append("not monotonic")
    if t2[255]-t2[254] > 0.5*(t2[200]-t2[199]) : bad.append("no shoulder: the last step is not smaller than half a mid step")
    worst = max(abs(lookup(t1, oetf(K0*eotf(i/255))) - lookup(t2, i/255))*255 for i in range(256) if eotf(i/255) <= XK)
    if worst > 0.15: bad.append("below the knee version 2 differs from version 1 by %.2f levels" % worst)
    if any(not (0.0 <= v <= 1.0) for v in t2): bad.append("values outside 0..1")
    # the two shipped copies of the table (engine header and Kotlin) are this table, to the 5 digits they are written with
    hdr = open(REPO + '/core/native/src/main/cpp/engine/base_curve.h').read().split("kBaseCurve2[256] = {")[1].split("};")[0]
    kt = open(REPO + '/core/render/src/main/kotlin/app/rawline/core/render/BaseCurve.kt').read().split("val TABLE2 = floatArrayOf(")[1].split(")")[0]
    for name, text in (("base_curve.h kBaseCurve2", hdr), ("BaseCurve.kt TABLE2", kt)):
        v = [float(x) for x in re.findall(r"[0-9]\.[0-9]+", text)]
        if len(v) != 256 or max(abs(a - b) for a, b in zip(v, t2)) > 6e-6: bad.append(name + " differs from the generated table")
    return bad

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "check":
        f = check(); print("FAIL: " + "; ".join(f) if f else "base curve version 2: all properties hold"); sys.exit(1 if f else 0)
    if len(sys.argv) > 2: XK = float(sys.argv[2])   # try other knees: make_base_curve.py compare 0.45
    t1, t2 = table1(), table2()
    # model check: version 1 table vs the formula (so the formula is the right description of it)
    err = max(abs(t1[i]-oetf(y_old(eotf(i/255)))) for i in range(256)); print("formula against the stored version 1 table: max abs diff %.5f" % err)
    # equivalence below the knee: old render of x = K0 x' against new render of x'
    def lookup(t, s): p = s*255; i = min(int(p), 254); return t[i]+(t[i+1]-t[i])*(p-i)
    rows = []
    for xp in [0.002, 0.01, 0.03, 0.1, 0.2, 0.3, 0.4, 0.5, 0.55, 0.6, 0.7, 0.8, 0.9, 1.0]:
        o = lookup(t1, oetf(min(K0*xp, 1))); n = lookup(t2, oetf(min(xp, 1)))
        rows.append((xp, o*255, n*255)); 
    for xp, o, n in rows: print("x'=%.3f  v1 %.1f  v2 %.1f  diff %+.1f" % (xp, o, n, n-o))
    below = [abs(lookup(t1, oetf(K0*eotf(i/255))) - lookup(t2, i/255))*255 for i in range(256) if eotf(i/255) <= XK]
    print("knee xk=%.2f: max |v2 - v1| below the knee %.2f levels; value at 1.0: %.3f; monotonic: %s" % (XK, max(below), lookup(t2, 1.0), all(b >= a for a, b in zip(t2, t2[1:]))))
    if len(sys.argv) > 1 and sys.argv[1] == 'table':
        print(", ".join("%.5f" % v for v in t2))

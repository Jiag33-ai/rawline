# W22 Colour contract: white point, white balance neutrality and the base curve, behind a look version

Written 6 Oct 2026 against main 6164874. Replaces the earlier W22 and the white balance item of W23 (W23 keeps the shader items that are still open: HSL partition and the remaining design calls). Every number below was measured here: the engine hunks were built and run on Mesa llvmpipe through the real golden binary (`git apply --check` passes on the current tree), the fixtures F1 to F9 run under both looks, the curve properties are checked by a script, and the recipe change was compiled with the Kotlin 2.4.10 compiler and its 6 tests run under JUnit. Not run here: Gradle, the Android build, the JNI and Kotlin plumbing in section 5, and anything on a phone.

Backlog links: BK-470, BK-438, BK-440, BK-437, BK-023, AE-001, AE-023; fixtures F6, F7, F8, F9 of `W21-colour-verification.md` are the acceptance.

## 1. What is wrong today (measured)

1. White balance changes brightness (AE-023). LibRaw normalises its multipliers by the largest, so a neutral lands at K = min(cam_mul) / max(cam_mul) of the as-shot WB. On the S5M2X sample (`cam_mul` 515, 256, 445; `pre_mul` 1.000, 0.4971, 0.8641) K = 0.4971. A warm scene with neutral (0.55, 1, 0.7) renders at K = 0.55 (fixture F6 off by 33.5 levels), a cool one at 0.6 (F7, 28.7 levels).
2. LibRaw's `adjust_maximum_thr` (default 0.75) lowers the white point to the frame's brightest pixel when that pixel is between 75 and 100 percent of white: up to +0.4 EV that depends on one specular highlight (fixture F8, 12.4 levels; BK-470). `raw_decode.cpp` never sets it.
3. A clipped neutral lands at 250 (warm) instead of white (F9), because the curve reaches white at input 1.0 but a clipped neutral sits at K, not at 1 (AE-001).
The stored base curve is exactly the formula `y = g x / (1 + (g - 1) x^c)` in linear light with g = 4.30, c = 1.10, input and output sRGB encoded (measured: the formula reproduces the table in `BaseCurve.kt` to 0.00000). Its gain of 4.3 is the 1 / K0 = 2.01 of the fit scene (K0 = 0.4971) folded into the curve: that is why the curve looks too bright for a neutral that clips at 1.0.

## 2. The design: look version 2

Three changes, all behind a per-edit look version, so nothing a user has already edited moves.

| | Look 1 (every edit saved so far) | Look 2 (new edits) |
|---|---|---|
| Decode white point | LibRaw rescales when the frame peak is in (0.75, 1.0) of white | never (decode always uses `adjust_maximum_thr = 0`) |
| Source gain `uSrcGain` (a shader multiplier on every source texel) | `v1Scale` = the factor LibRaw's rule would have applied (1.0 for most frames) | `wbGain` = max(cam_mul) / min(cam_mul) (2.0117 on the sample): a neutral is at the level it has at unity white balance |
| Base curve | the stored table (`kBaseCurve`, `BaseCurve.TABLE`) | `kBaseCurve2`: the same curve re-expressed for neutral at 1.0, with a cubic shoulder from a knee at 0.55 that reaches white at 1.0 |

Why this is exact for look 1, and why one decode serves both looks (so the prefetch and the half size preview never need to know the look): the decoder runs once with `adjust_maximum_thr = 0` and reports two numbers; the engine multiplies by the one the edit's look asks for. `v1Scale` is computed by LibRaw's own rule from `maximum` and `data_maximum` after processing: `(data_max > 0 && data_max < maximum && data_max > 0.75 * maximum) ? maximum / data_max : 1`. Measured on synthetic frames (peak 0.5, 0.7, 0.8, 0.95, 1.0 of white): the rule reproduces LibRaw's scaling (peak 0.8 gives 1.25, 0.95 gives 1.05, others 1). The real sample has `data_max` above `maximum`, so its factor is 1.

Measured equivalence (the acceptance numbers):
- Look 1 reproduces today: fixture numbers under `look=1` are identical to the numbers on main (F1 0.86, F2 0.65, F3 0.65, F4 3.00, F5 0.77, F6 33.46, F7 28.74, F8 12.38, F9 warm 250). Existing golden references under `look=1`: base, tone, colour, local, detail all mean difference 0.00.
- Look 2 passes everything: F1 0.84, F2 0.60, F3 0.64, F4 1.00, F5 0.63, F6 0.60, F7 0.60, F8 0.52 levels, F9 white (255, R=G=B) for unity and warm.
- On the real S5M2X sample (the fit scene) look 2 renders the same picture as look 1: 240,000 pixels, mean difference 0.00, largest difference 1 level in every luma band. So no photo that stays below the shoulder changes.
- Existing golden scenes under `look=2`: base mean 0.03, colour 0.03, detail 0.25, local 0.77, tone 7.17 (the tone scene pushes highlights into the new shoulder, which is the point). So: keep every existing scene on `look=1` (it then proves migration safety) and add new scenes on `look=2`, references made with `--update` and reviewed by eye.
- Edge case worth knowing: a saturated, out of gamut pixel in a frame that LibRaw used to rescale can differ by a few levels at its edge pixels between the old build and look 1 (single pixels; every chart patch mean is within 1.5 levels). The reason is LibRaw clipping to 16 bits after its rescale, which look 1 now applies after the fact.

The curve (`tools/looks/make_base_curve.py`, section 7): below the knee 0.55 (neutral level at 1.0 = clip) look 2 equals look 1 within 0.09 level; above it a cubic Hermite from the knee to (1, 1) with zero slope at 1. Lift against look 1 at neutral 0.7: +6.6 levels, at 0.8: +13.5, at 0.9: +18.3, at 1.0 (clipped white): +18.2. Properties checked by `make_base_curve.py check`: t[0] = 0, t[255] = 1, monotonic, a shoulder (last step under half a mid step), equal to look 1 below the knee. The knee 0.55 and the cubic are a judgement; `make_base_curve.py compare 0.45` and `compare 0.70` show the alternatives. This is a re-expression, not a new fit: a true refit of the shoulder needs highlight rich frames and the camera JPEGs, which this repository does not have; log it as a phone task (BK-021) for Jai.

## 3. The migration plan for saved edits (exact)

State: today a stored recipe has `schemaVersion = 1` and no look field. After this task:
1. `EditRecipe.lookVersion` (default `Look.CURRENT` = 2). `RecipeJson.write` always writes `lookVersion`; `read` takes `optInt("lookVersion", 1)` (a recipe without the key was made under the first look) and clamps with `Look.supported` (a look from a newer build renders with the newest this build knows, never refused). Compiled and tested: `LookTest` (6 tests), `editrecipe.patch`.
2. Nothing is rewritten on upgrade. Rows in the catalogue (Room) keep their JSON; the editor and the exporter read the recipe and use its `lookVersion`. No database migration, no new column, no backup format change (`BackupFormat` carries the JSON as is; a build from before this task reads `lookVersion` as unknown and renders as look 1, a small difference only for edits made after the upgrade).
3. A photo with no stored recipe renders with look 2 (a new `EditRecipe()`); this is the one visible change for existing users: an unedited RAW that has a clipped neutral or a non-daylight white balance looks different from before (that is the fix). `isDefault` stays `this == EditRecipe()`, so a stored look 1 recipe is never treated as default and never deleted (`saveAction`), tested by `anOldEditThatIsOtherwiseDefaultIsNotADefaultRecipeSoItIsNeverDeleted`.
4. "Reset" builds `EditRecipe()`, so a reset photo moves to look 2 (it is a new edit).
5. Presets, copy and paste settings, auto: they copy adjust blocks only, never `lookVersion` (add tests `applyPresetKeepsTheTargetsLook` and `pasteSettingsKeepsTheTargetsLook` next to the existing preset tests).
6. Update look: editor overflow menu row "Look" shows "Look: earlier (made before the look update)" with a button "Update look" only for look 1 recipes. It sets `recipe.withCurrentLook()` as one history entry, so Undo returns to the earlier look; a toast says "Look updated. Undo to go back." Heal patches need nothing: they are stored as display values and converted with the current curve (`ColorSpaces.displayToWorking(..., useBase, look)`), so they look the same after the update.
7. Export uses the recipe's look (the exporter takes the same two calls as the editor), so exporting an old edit gives the same pixels as before the upgrade (within 1 level, section 2).
8. Library thumbnails come from the camera preview and are not rendered, so nothing changes; any cache of rendered previews must key on the recipe JSON (which now contains the look). Add a test that two recipes differing only in `lookVersion` have different cache keys.
9. Copy report: add a line "Edits by look: look 1 N, look 2 M" (count from the catalogue) so Jai can see the transition.
10. Future looks: the `Look` object and the two engine inputs are the extension point; a look 3 adds a table and a gain rule, never edits an existing one (a test pins the SHA-256 of `kBaseCurve` so look 1 can never change by accident).
Downgrade: an older build opening a look 2 recipe ignores the key and renders look 1; acceptable and documented in docs/DECISIONS.md.

## 4. Order of work and ownership

Owns: `core/native/src/main/cpp/raw_decode.*`, `engine/engine.*` (gain only), `engine/base_curve.h`, `shaders/main.frag` and `lowres.frag` (the gain multiply only), `jni_engine.cpp` (three small functions), `Native.kt`, `core/model/EditRecipe.kt`, `core/render/BaseCurve.kt`, `ColorSpaces.kt`, `RenderParams.kt` (the curve calls), `EditorSession.kt` and `Exporter.kt` (look plumbing only), `HealOverlay`, `Healer`, `Denoiser` (pass the look to the colour conversions), `feature/editor` (the Look row), `tools/looks`, `tools/colour`, `tools/golden` (the `look=` key, scene lines), docs/COLOUR.md and docs/DECISIONS.md. W23 must not touch `lowres.frag` or the source fetch in `main.frag` until this merges.
Order: (1) tools first: `tools/looks/make_base_curve.py`, `tools/colour` (the version in section 7 replaces the W21 one), run on main: look 2 columns must FAIL where the engine is not yet changed (that is the red state); (2) engine hunks and golden key (`look2_engine.patch`), `kBaseCurve2` generated by `make_base_curve.py table`; (3) `EditRecipe.lookVersion` (`editrecipe.patch` and `LookTest`); (4) Kotlin and JNI plumbing (section 5); (5) the editor row and strings; (6) goldens: add `look=1` to every existing scene line in `run-golden.sh` and add `base2`, `tone2`, `clip2` on `look=2`; (7) docs.

## 5. Exact changes

### Native (tested: `look2_engine.patch`, section 7)
- `raw_decode.cpp`: `P.adjust_maximum_thr = 0.f`; after `dcraw_process` fill `out.v1Scale` and `out.wbGain` (hunk). `raw_decode.h`: the two floats.
- `engine.h/.cpp`: `setSrcGain(float)` (invalidates the analysis cache when it changes), uniform `uSrcGain` set for the analysis pass and the main pass. Shaders: `lowres.frag` and `main.frag` multiply the source fetch (and the two chromatic aberration fetches) by `uSrcGain`, before the overlay and the vignetting gain, so heal patches and the local analysis see the same scale.
- `base_curve.h`: add `kBaseCurve2` (generate with `python3 tools/looks/make_base_curve.py table | tail -1`, whose last line is the 256 comma separated values, laid out eight to a row like `kBaseCurve`; keep `kBaseCurve` byte for byte).
- `golden.cpp`: key `look=1|2` (default 2) sets `setBaseCurve(look == 1 ? kBaseCurve : kBaseCurve2)` and `setSrcGain(look == 1 ? img.v1Scale : img.wbGain)`.

### JNI and Kotlin (not compiled here; follow the existing patterns)
- `jni_engine.cpp`/`Native.kt`: `external fun rawGains(handle: Long): FloatArray` returning `[v1Scale, wbGain]` (reads the `RawImage`; for finished pictures both are 1); `external fun engineSetSrcGain(h: Long, gain: Float)`; extend `engineSetBaseCurve(h: Long, enabled: Boolean)` to `engineSetBaseCurve(h: Long, enabled: Boolean, look: Int)` choosing `kBaseCurve` or `kBaseCurve2` when enabled (identity when not); `external fun baseCurve(look: Int): FloatArray`.
- `BaseCurve.kt`: keep `TABLE` (look 1, unchanged), add `TABLE2` (paste of `make_base_curve.py table`), `fun table(look: Int) = if (look == Look.V1) TABLE else TABLE2`, and `toWorking(user, withBase, look)` using `table(look)`. `RenderTest`'s existing "Kotlin table equals the header" test gets a second assertion for `TABLE2` against `kBaseCurve2`.
- `ColorSpaces.kt`: `curve` becomes `BaseCurve.table(look)` per call (add a `look: Int = Look.CURRENT` parameter to `displayToWorking`, `workingToDisplay`, and the lazy inverse table per look: `inverse(look)` cached in a two entry array). Every caller passes the recipe's look: `HealOverlay`, `Healer`, `Denoiser`, `RenderParams.build` curve lines (`BaseCurve.toWorking(..., useBaseline, recipe.lookVersion)`).
- `EditorSession.kt`: wherever `Native.engineSetBaseCurve(engine, !finishedPicture)` is called (after each `engineSetSource`, and in `reloadSource` and `ensureFull`), call a new private `applyLook()`: `Native.engineSetBaseCurve(engine, !finishedPicture, recipe.lookVersion); Native.engineSetSrcGain(engine, gain)` with `gain = if (finishedPicture) 1f else gains[if (recipe.lookVersion == Look.V1) 0 else 1]` and `gains = Native.rawGains(handle)` read before the handle is consumed by `engineSetSource` (store the pair in a field). `setRecipe` calls `applyLook()` when `r.lookVersion != recipe.lookVersion` (Update look and its undo), then rebuilds and renders.
- `Exporter.kt`: same two calls after `engineSetSource`, from `recipe.lookVersion` and the pair read from the handle before it is consumed.
- Editor UI: a "Look" row in the overflow menu as in section 3 item 6; strings in Australian English, no em dashes: "Look: current" / "Look: earlier (made before the look update)", button "Update look", toast "Look updated. Undo to go back."

### Tests to add (host)
- `LookTest` (given), preset and paste tests (section 3 item 5), cache key test (item 8), `BaseCurve.TABLE2` equals `kBaseCurve2`, SHA-256 of `kBaseCurve` pinned, `rawGains` of a finished picture is `[1, 1]`.
- `EditorSession`/`Exporter` look plumbing: a fake engine (the existing host fakes for `RawPrefetch`) records `setBaseCurve(look)` and `setSrcGain` calls for a look 1 and a look 2 recipe.

## 6. Acceptance (all must hold)

1. `python3 tools/looks/make_base_curve.py check` passes and `tools/colour/run-colour.sh` exits 0 printing exactly the output in section 8 (look 1 block unchanged from main, look 2 block all PASS and both F9 white).
2. `tools/golden/run-golden.sh` green: every existing scene on `look=1` byte-identical to its stored reference; `base2`, `tone2`, `clip2` references reviewed by eye (the warm clipped sky reaches white; mid-tones unchanged).
3. A look 1 recipe renders and exports as before (host golden above), a look 2 recipe passes the fixtures, an unedited photo renders look 2, Update look is one undoable step.
4. `./gradlew testDebugUnitTest assembleDebug` green; docs/COLOUR.md and docs/DECISIONS.md describe the look versions, K0 = 0.4971 and its derivation, the knee choice and the downgrade behaviour.
Jai on the phone (after the release): open three photos edited before the update and one unedited with a bright sky; nothing in the edited ones should look different; on the bright sky photo tap Update look and see the sky reach white with the mid-tones unchanged, then Undo; open a warm indoor RAW and see its brightness match the daylight ones; paste the Copy report (it shows "Edits by look").

## 7. Tested source

### tools/looks/make_base_curve.py
```python
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
```
### tools/colour/reference.py (replaces the W21 file)
```python
#!/usr/bin/env python3
"""Reference model + comparer for the synthetic colour fixtures. Standard library only."""
import re, sys, json, subprocess, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_dng as m

REPO = os.environ.get("RAWLINE_REPO", os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")))
GOLDEN = os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")

def base_table():
    src = open(REPO + "/core/render/src/main/kotlin/app/rawline/core/render/BaseCurve.kt").read()
    body = src.split("val TABLE = floatArrayOf(")[1].split(")")[0]
    t = [float(x) for x in re.findall(r"[0-9.]+(?=f)", body)]
    assert len(t) == 256
    return t
sys.path.insert(0, os.path.join(REPO, "tools", "looks"))
import make_base_curve as _curve2
TABLES = {1: base_table(), 2: _curve2.table2()}   # look version 1: the table in BaseCurve.kt; look version 2: tools/looks/make_base_curve.py
def samp(x, look=2):
    T = TABLES[look]
    p = min(max(x, 0.0), 1.0) * 255; i = min(int(p), 254); return T[i] + (T[i+1] - T[i]) * (p - i)
def oetf(l): return 12.92*l if l <= 0.0031308 else 1.055*l**(1/2.4) - 0.055
def eotf(s): return s/12.92 if s <= 0.04045 else ((s+0.055)/1.055)**2.4

# XYZ(D50-adapted) is not needed: the engine chain for neutral-balanced input is  display = oetf(m_lib^-1 ... ) = identity colour path,
# so for sRGB-defined patches the expected linear sRGB out is  K * E * patch  (see section 6 of the document).
def expected_srgb8(patch_lin, exposure, K=1.0, base=True, look=2):
    out = []
    for c in patch_lin:
        v = oetf(min(max(K*exposure*c, 0.0), 1.0))
        out.append(round((samp(v, look) if base else v) * 255, 2))
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

def render(dng, ppm, width=600, extra=(), look=2):
    r = subprocess.run([GOLDEN, dng, ppm, str(width), "full", "look=%d" % look, *extra], capture_output=True, text=True)
    if r.returncode: raise SystemExit("golden failed: " + r.stderr[-400:])
    return read_ppm(ppm)
```
### tools/colour/run_fixtures.py (replaces the W21 file; `make_dng.py` is unchanged)
```python
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
```
### look2_engine.patch (raw_decode, engine, shaders, golden key; apply with `git apply`)
```diff
--- a/core/native/src/main/cpp/raw_decode.cpp
+++ b/core/native/src/main/cpp/raw_decode.cpp
@@ -42,4 +42,5 @@
     P.med_passes = 0;
     P.fbdd_noiserd = 0;
+    P.adjust_maximum_thr = 0.f;   // look version 2: the frame's brightest pixel must not move the white point (the v1 factor is reported in out.v1Scale)
 
     int r = lr.open_file(path.c_str());
@@ -58,4 +59,11 @@
     out.camera = std::string(lr.imgdata.idata.make) + " " + lr.imgdata.idata.model;
     for (int i = 0; i < 4; i++) out.wbMul[i] = lr.imgdata.color.cam_mul[i];
+    {   // LibRaw's own rule for the white point it would have lowered (adjust_maximum, default threshold 0.75), evaluated on the unscaled decode
+        const auto &C = lr.imgdata.color;
+        float mx = float(C.maximum), dm = float(C.data_maximum);
+        out.v1Scale = (dm > 0.f && dm < mx && dm > mx * 0.75f) ? mx / dm : 1.f;
+        float lo = std::min(C.cam_mul[0], std::min(C.cam_mul[1], C.cam_mul[2])), hi = std::max(C.cam_mul[0], std::max(C.cam_mul[1], C.cam_mul[2]));
+        out.wbGain = (lo > 0.f) ? hi / lo : 1.f;   // pre_mul is normalised by its largest member, so a neutral lands at min/max of cam_mul: this brings it back to 1
+    }
     out.width = w;
     out.height = h;
--- a/core/native/src/main/cpp/raw_decode.h
+++ b/core/native/src/main/cpp/raw_decode.h
@@ -48,4 +48,6 @@
     std::string camera;
     float wbMul[4] = {1, 1, 1, 1};
+    float v1Scale = 1.f;   // factor LibRaw's default white point rule would have applied (look version 1)
+    float wbGain = 1.f;    // brings a neutral back to the level it has at unity white balance (look version 2)
 };
 
--- a/core/native/src/main/cpp/engine/engine.h
+++ b/core/native/src/main/cpp/engine/engine.h
@@ -29,4 +29,6 @@
     void updateOverlayRegion(int x, int y, int w, int h, const uint8_t *rgbaHalf);   // premultiplied RGBA half float
     void setBaseCurve(const float *lut256);
+    /** Multiplier applied to every source texel (look version: 1 = LibRaw's old white point rule, 2 = white balance neutral gain). */
+    void setSrcGain(float g) { if (g != srcGain_) { srcGain_ = g; invalidateAnalysis(); } }
 
     /** Size in pixels of the final image for these params at full source resolution. */
@@ -91,4 +93,5 @@
     Target l0_, bs_, bl_, bd_, tmp_, e_, outT_, eS_, outS_;   // eS_ and outS_: small renders (see drawOutput)
     int srcW_ = 0, srcH_ = 0, srcLevels_ = 1;
+    float srcGain_ = 1.f;
     int overlayW_ = 0;
     uint64_t analysisKey_ = ~0ull;
--- a/core/native/src/main/cpp/engine/engine.cpp
+++ b/core/native/src/main/cpp/engine/engine.cpp
@@ -375,4 +375,5 @@
     setGeometryUniforms(lowres_, p);
     float ratio = std::max(float(ow) / lw, float(oh) / lh);
+    glUniform1f(lowres_.u("uSrcGain"), srcGain_);
     glUniform1f(lowres_.u("uLod"), std::clamp(std::log2(std::max(1.f, ratio)), 0.f, float(srcLevels_ - 1)));
     glBindFramebuffer(GL_FRAMEBUFFER, l0_.fbo);
@@ -431,4 +432,5 @@
     // How many source pixels land on one output pixel (oriented crop size is in source pixels already).
     float lod = std::log2(std::max(1.f, std::max(hx, hy)));
+    glUniform1f(pr.u("uSrcGain"), srcGain_);
     glUniform1f(pr.u("uLod"), std::clamp(lod, 0.f, float(srcLevels_ - 1)));
     glUniform1i(pr.u("uNumMasks"), int(p[G_NUM_MASKS] + 0.5f));
--- a/core/native/src/main/cpp/shaders/main.frag
+++ b/core/native/src/main/cpp/shaders/main.frag
@@ -20,4 +20,5 @@
 uniform vec2 uOutPx;         // pixels in the render target
 uniform float uLod;
+uniform float uSrcGain;      // look version: 1 reproduces LibRaw's old white point rule, 2 is the white balance neutral gain
 uniform int uNumMasks;
 uniform int uShowMask;
@@ -253,5 +254,5 @@
     vec2 guv = clamp(g.xy, 0.0, 1.0);
     vec4 s = textureLod(uSrc, guv, uLod);
-    vec3 c = s.rgb;
+    vec3 c = s.rgb * uSrcGain;
     // Lens profile: lateral chromatic aberration (red and blue sampled at their own radius) and vignetting
     vec2 sd = (guv - 0.5) * uSrcSize;
@@ -260,6 +261,6 @@
         float sr = uTcaR.x + rn * (uTcaR.y + rn * uTcaR.z);
         float sb = uTcaB.x + rn * (uTcaB.y + rn * uTcaB.z);
-        c.r = textureLod(uSrc, clamp(0.5 + (guv - 0.5) * sr, 0.0, 1.0), uLod).r;
-        c.b = textureLod(uSrc, clamp(0.5 + (guv - 0.5) * sb, 0.0, 1.0), uLod).b;
+        c.r = textureLod(uSrc, clamp(0.5 + (guv - 0.5) * sr, 0.0, 1.0), uLod).r * uSrcGain;
+        c.b = textureLod(uSrc, clamp(0.5 + (guv - 0.5) * sb, 0.0, 1.0), uLod).b * uSrcGain;
     }
     // Heal and remove patches are rendered from the source as it is (no lens gain, no CA correction), so they are laid over it
--- a/core/native/src/main/cpp/shaders/lowres.frag
+++ b/core/native/src/main/cpp/shaders/lowres.frag
@@ -6,9 +6,10 @@
 uniform sampler2D uSrc;
 uniform float uLod;
+uniform float uSrcGain;
 //@include geometry.glsl
 const vec3 Y = vec3(0.28807, 0.71184, 0.0000857);
 void main() {
     vec3 g = srcUv(vUv);
-    vec3 c = textureLod(uSrc, clamp(g.xy, 0.0, 1.0), uLod).rgb;
+    vec3 c = textureLod(uSrc, clamp(g.xy, 0.0, 1.0), uLod).rgb * uSrcGain;
     c = max(c, 0.0);
     oColor = vec4(dot(c, Y), min(c.r, min(c.g, c.b)), 0.0, 1.0);
--- a/tools/golden/golden.cpp
+++ b/tools/golden/golden.cpp
@@ -14,4 +14,5 @@
 #include <string>
 
+#include "engine/base_curve.h"
 #include "engine/engine.h"
 #include "engine/halfs.h"
@@ -138,4 +139,5 @@
         {"saturation", S_SATURATION}, {"texture", S_TEXTURE}, {"clarity", S_CLARITY}, {"dehaze", S_DEHAZE}};
     bool autofit = false, mark = false, useMaskExposure = false;
+    int look = 2;
     float maskExposure = 0.f;
     for (int i = 5; i < argc; i++) {
@@ -146,4 +148,5 @@
         float v = float(atof(a.c_str() + eq + 1));
         if (blockSlots.count(k)) p[kOffBlocks + blockSlots[k]] = v;
+        else if (k == "look") look = int(v);
         else if (k == "sharpen") p[G_DETAIL] = v;
         else if (k == "nrl") p[G_NR] = v;
@@ -238,5 +241,7 @@
     }
     if (useMaskExposure) p[kOffBlocks + kBlockFloats + S_EXPOSURE] = maskExposure;
-    fprintf(stderr, "SRC %d %d %d\n", img.width, img.height, ori);
+    eng.setBaseCurve(look == 1 ? kBaseCurve : kBaseCurve2);
+    eng.setSrcGain(look == 1 ? img.v1Scale : img.wbGain);
+    fprintf(stderr, "SRC %d %d %d look %d gain %.4f\n", img.width, img.height, ori, look, look == 1 ? img.v1Scale : img.wbGain);
     if (autofit) {
         fitCrop(p.data(), float(img.width), float(img.height));
```
### editrecipe.patch (EditRecipe.kt) and LookTest.kt
```diff
--- a/core/model/src/main/kotlin/app/rawline/core/model/EditRecipe.kt
+++ b/core/model/src/main/kotlin/app/rawline/core/model/EditRecipe.kt
@@ -80,8 +80,24 @@
 /** [region] is x, y, w, h of the patch in the source image, normalised. Strokes are in the masks' frame. */
 data class HealOp(val kind: String, val stroke: BrushStroke, val sourceX: Float = 0f, val sourceY: Float = 0f, val patchKey: String? = null, val region: List<Float> = emptyList())
 
+/**
+ * Look versions. V1 is the rendering every edit made before this field existed was judged under: LibRaw lowers its white point to the frame's
+ * brightest pixel when that pixel is between 75 and 100 percent of white (up to +0.4 EV), white balance dims the picture by min(AsShotNeutral), and the
+ * base curve has no shoulder at white. V2 removes those three effects (see docs/COLOUR.md). A saved edit keeps its version; only an explicit
+ * "Update look" (or a reset, which is a new edit) moves it.
+ */
+object Look {
+    const val V1 = 1
+    const val V2 = 2
+    const val CURRENT = V2
+    /** A version this build cannot render (written by a newer build) is shown with the newest look it knows rather than refused. */
+    fun supported(v: Int) = v.coerceIn(V1, CURRENT)
+}
+
 data class EditRecipe(
     val schemaVersion: Int = 1,
+    /** Which tone and white point behaviour renders this edit (see Look). Edits saved before the field existed read as [Look.V1] and keep their exact old rendering until the user updates them. */
+    val lookVersion: Int = Look.CURRENT,
     val adjust: Adjust = Adjust(),
     val detail: Detail = Detail(),
     val effects: Effects = Effects(),
@@ -92,6 +108,9 @@
 ) {
     val isDefault get() = this == EditRecipe()
 
+    /** The same edit under the current look (what "Update look" does). Not a default recipe's concern: only recipes that were saved under an older look differ. */
+    fun withCurrentLook() = if (lookVersion == Look.CURRENT) this else copy(lookVersion = Look.CURRENT)
+
     fun toJson(): String = RecipeJson.write(this)
 
     companion object {
@@ -156,6 +175,7 @@
 
     fun write(r: EditRecipe): String = JSONObject().apply {
         put("schemaVersion", r.schemaVersion)
+        put("lookVersion", r.lookVersion)
         put("adjust", adjustToJson(r.adjust))
         put("detail", JSONObject().apply {
             put("sharpen", r.detail.sharpen.toDouble()); put("radius", r.detail.radius.toDouble()); put("detail", r.detail.detail.toDouble())
@@ -212,6 +232,7 @@
         val heals = o.optJSONArray("heals")
         return EditRecipe(
             o.optInt("schemaVersion", 1),
+            Look.supported(o.optInt("lookVersion", Look.V1)),   // a recipe without the key was made under the first look
             o.optJSONObject("adjust")?.let(::adjustFromJson) ?: Adjust(),
             if (d == null) Detail() else Detail(
                 d.optDouble("sharpen", 0.0).toFloat(), d.optDouble("radius", 1.0).toFloat(), d.optDouble("detail", 25.0).toFloat(),
```
```kotlin
package app.rawline.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test fun aRecipeSavedBeforeTheFieldExistedReadsAsLookOne() {
        val old = """{"schemaVersion":1,"adjust":{"exposure":0.4}}"""
        val r = EditRecipe.fromJson(old)
        assertEquals(Look.V1, r.lookVersion); assertEquals(0.4f, r.adjust.exposure, 1e-6f)
    }

    @Test fun aNewRecipeIsTheCurrentLookAndRoundTrips() {
        assertEquals(Look.CURRENT, EditRecipe().lookVersion)
        val r = EditRecipe(lookVersion = Look.V1, adjust = Adjust(exposure = 0.5f))
        assertEquals(r, EditRecipe.fromJson(r.toJson()))
        assertEquals(Look.V1, EditRecipe.fromJson(r.toJson()).lookVersion)
        assertEquals(Look.V2, EditRecipe.fromJson(EditRecipe(adjust = Adjust(exposure = 0.5f)).toJson()).lookVersion)
    }

    @Test fun anOldEditThatIsOtherwiseDefaultIsNotADefaultRecipeSoItIsNeverDeleted() {
        assertTrue(EditRecipe().isDefault)
        assertFalse(EditRecipe(lookVersion = Look.V1).isDefault)
    }

    @Test fun aLookFromTheFutureIsRenderedWithTheNewestLookWeKnow() {
        val r = EditRecipe.fromJson("""{"schemaVersion":1,"lookVersion":9}""")
        assertEquals(Look.CURRENT, r.lookVersion)
        assertEquals(Look.V1, EditRecipe.fromJson("""{"lookVersion":0}""").lookVersion)
    }

    @Test fun updateLookChangesOnlyTheVersion() {
        val old = EditRecipe(lookVersion = Look.V1, adjust = Adjust(exposure = 0.3f, contrast = 12f))
        val up = old.withCurrentLook()
        assertEquals(Look.CURRENT, up.lookVersion); assertEquals(old.copy(lookVersion = Look.CURRENT), up)
        assertTrue(up.withCurrentLook() === up)
    }

    @Test fun resetBuildsANewEditUnderTheCurrentLook() { assertEquals(Look.CURRENT, EditRecipe().lookVersion) }
}
```

## 8. Expected output of `tools/colour/run-colour.sh` after this task

```
--- look version 1
F1_ramp     E=0.70 worst   0.86 levels tol 2.0  PASS
F2_chart    E=0.70 worst   0.65 levels tol 3.0  PASS
F3_black    E=0.70 worst   0.65 levels tol 3.0  PASS
F4_prim     E=0.70 worst   3.00 levels tol 6.0  PASS
F5_noise    E=0.70 worst   0.77 levels tol 3.0  PASS
F6_wb_warm  E=0.70 worst  33.46 levels tol 3.0  FAIL  (look 1 keeps its known fault)
F7_wb_cool  E=0.70 worst  28.74 levels tol 3.0  FAIL  (look 1 keeps its known fault)
F8_maxthr   E=0.80 worst  12.38 levels tol 3.0  FAIL  (look 1 keeps its known fault)
F9_clip_unity  min channel 255 spread 0.0  white
F9_clip_warm   min channel 250 spread 0.0  not white  (look 1 keeps its known fault)
--- look version 2
F1_ramp     E=0.70 worst   0.84 levels tol 2.0  PASS
F2_chart    E=0.70 worst   0.60 levels tol 3.0  PASS
F3_black    E=0.70 worst   0.64 levels tol 3.0  PASS
F4_prim     E=0.70 worst   1.00 levels tol 6.0  PASS
F5_noise    E=0.70 worst   0.63 levels tol 3.0  PASS
F6_wb_warm  E=0.70 worst   0.60 levels tol 3.0  PASS
F7_wb_cool  E=0.70 worst   0.60 levels tol 3.0  PASS
F8_maxthr   E=0.80 worst   0.52 levels tol 3.0  PASS
F9_clip_unity  min channel 255 spread 0.0  white
F9_clip_warm   min channel 255 spread 0.0  white
M_P3 max |engine*libraw - nominal| = 0.00032  PASS
```
Exit code 0. The look 1 block is the regression guard: if any of those numbers moves, an existing edit would look different.

## 9. Risks and open items

- The knee and shoulder shape are my choice, not a fit; a highlight rich phone comparison against the camera JPEG (BK-021) may move them. Because look 2 is a new table, changing it before release costs nothing; after release it needs look 3.
- Cameras other than the S5M2X: `wbGain` is computed from `cam_mul`, so it is camera independent, but K0 = 0.4971 belongs to the Panasonic fit scene; a camera with a very different base sensitivity will sit brighter or darker by its own factor until it has its own curve. The S24 Ultra Expert RAW path has not been checked.
- DNGs with dual illuminant matrices (S24U) interpolate by white balance inside LibRaw; `cam_mul` then reflects that; verify with a real Expert RAW (no sample available).
- LibRaw upgrades: `v1Scale` depends on `maximum` and `data_maximum` semantics of 0.22.2 (pinned by hash); a LibRaw bump must rerun fixture F8 under look 1.

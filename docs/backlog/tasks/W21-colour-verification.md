# W21 Colour verification harness (synthetic DNG charts, no phone needed)

Owner of this doc: backlog thinker. Written 6 Oct 2026 against main 71918bf. Every number below was measured by running the code in section 9 through the real engine (`tools/golden` binary, Mesa llvmpipe). Nothing here is guessed.

Backlog links: BK-435..446 (colour verification plan), BK-470 (LibRaw adjust_maximum), AE-001 (clipped highlights), AE-023 (WB changes brightness), W22 and W23 in the NEXT 25 list.

## 1. What this proves, and what it does not

Proves (pure software, deterministic, runs in CI in about 20 seconds):
- The whole chain DNG decode, black level, camera matrix, white balance, ProPhoto working space, output matrix to sRGB, sRGB encoding and the 256 entry base curve reproduces a reference model to within 1 level on neutral-balanced input.
- The engine to Display P3 matrix, composed with LibRaw's ProPhoto matrix, equals the nominal sRGB to P3 matrix within 3.2e-4 (row sums 1.00035, 0.99992, 0.99965, so white is off by under 0.1 level).
- Regressions in any of these show up as a number, not a feeling.

Does not prove: real S5IIX colour. The synthetic camera matrix is the LibRaw `DC-S5M2` matrix scaled so neutral is (1,1,1). Real sensor spectral response, real lens colour and the JPEG look the base curve was fitted to need a photographed chart (Jai's phone, a separate task, BK-441).

## 2. Measured findings (these drive the XFAIL fixtures)

1. PASS: neutral-balanced input matches the model within 0.9 level (ramp) and 0.7 level (24 patch chart). Black level 512 and 1024 give identical output. Noise (sigma^2 = 4 + 0.05 x signal) moves patch means by under 0.9 level.
2. XFAIL, AE-023 confirmed numerically: white balance changes brightness. With AsShotNeutral (0.55, 1, 0.7) every output is scaled by K = min(neutral) = 0.55 (measured 0.54 to 0.55). With (0.8, 1, 0.6), K = 0.6 (measured 0.59 to 0.60). Neutrals stay neutral (no colour cast), only the level drops. Cause: LibRaw `highlight=2` divides the multipliers by the largest, so green stays 1 and red and blue shrink. The base curve was fitted at one WB, so other WBs land darker or lighter by up to about 0.9 EV. Target: K independent of WB (change under 0.1 EV).
3. XFAIL, new (BK-470): LibRaw `adjust_maximum_thr` defaults to 0.75 (`utils_libraw.cpp` `adjust_maximum`). If the brightest pixel in the frame, after black subtraction, is between 75 percent and 100 percent of the white level, LibRaw lowers its white point to that pixel. Measured with a full ramp: peak 0.74 gives K = 0.99, peak 0.76 gives K = 1.31, peak 0.80 gives 1.25, peak 0.95 gives 1.05, peak 1.0 gives 1.00. So two frames of the same scene with the same exposure differ by up to +0.4 EV depending on whether one specular sits just under clipping. `raw_decode.cpp` never sets it. Fix: set `P.adjust_maximum_thr = 0.0f` (disables; values under 0.00001 return early), then refit the base curve offset because it was fitted with the behaviour on.
4. XFAIL, AE-001: a clipped neutral under warm WB lands at 250 (R=G=B, so no cast, but not white), against 255 for unity WB. Target: at least 253 and R=G=B within 1.
5. PASS: out of gamut saturated primaries clip per channel and the in gamut channel is right within 3 levels (a channel that should be 0 reads 1 to 3 on the 50 percent secondaries; tolerance 6 for that fixture only).

## 3. Synthetic DNG: exact tag list

Little endian TIFF ("II", 42, first IFD at byte 8), one IFD, one uncompressed 16 bit strip. 1200 x 800 pixels, RGGB, 14 bit data stored in 16 bit words. All out of line values are placed after the IFD in tag order, padded to even length, and the strip comes last.

| Tag | Name | Type | Count | Value |
|---|---|---|---|---|
| 254 | NewSubfileType | LONG | 1 | 0 |
| 256 | ImageWidth | LONG | 1 | 1200 |
| 257 | ImageLength | LONG | 1 | 800 |
| 258 | BitsPerSample | SHORT | 1 | 16 |
| 259 | Compression | SHORT | 1 | 1 (none) |
| 262 | PhotometricInterpretation | SHORT | 1 | 32803 (CFA) |
| 271 | Make | ASCII | 8 | "Rawline" |
| 272 | Model | ASCII | 16 | "Synthetic Chart" |
| 273 | StripOffsets | LONG | 1 | patched after layout (offset of pixel data) |
| 274 | Orientation | SHORT | 1 | 1 |
| 277 | SamplesPerPixel | SHORT | 1 | 1 |
| 278 | RowsPerStrip | LONG | 1 | 800 |
| 279 | StripByteCounts | LONG | 1 | 1920000 |
| 284 | PlanarConfiguration | SHORT | 1 | 1 |
| 33421 | CFARepeatPatternDim | SHORT | 2 | 2, 2 |
| 33422 | CFAPattern | BYTE | 4 | 0, 1, 1, 2 (R G / G B) |
| 50706 | DNGVersion | BYTE | 4 | 1, 4, 0, 0 |
| 50707 | DNGBackwardVersion | BYTE | 4 | 1, 1, 0, 0 |
| 50708 | UniqueCameraModel | ASCII | 18 | "Rawline Synthetic" |
| 50713 | BlackLevelRepeatDim | SHORT | 2 | 2, 2 |
| 50714 | BlackLevel | SHORT | 4 | 512 x 4 (1024 x 4 in F3) |
| 50717 | WhiteLevel | LONG | 1 | 16383 |
| 50721 | ColorMatrix1 | SRATIONAL | 9 | balanced matrix x 10000 / 10000 (section 4) |
| 50728 | AsShotNeutral | RATIONAL | 3 | per fixture, x 10000 / 10000 |
| 50730 | BaselineExposure | SRATIONAL | 1 | 0 |
| 50778 | CalibrationIlluminant1 | SHORT | 1 | 21 (D65) |

Byte layout: bytes 0..7 header; byte 8 IFD entry count (2 bytes); 26 entries of 12 bytes (tag, type, count, value or offset); 4 bytes next-IFD (0); then out of line payloads; then 1,920,000 bytes of pixels, row major, little endian uint16. Total file size 1,920,480 bytes for the 26 entry layout (the writer asserts nothing about size; `check_dng` re-parses the header and the ascending tag order and checks the required tags are present).

Pixel value for patch colour p (linear sRGB, white = 1), exposure E and channel c:
`v = black_c + (white - black_c) x E x neutral_c x (M x XYZ(p))_c`, rounded and clamped to 0..16383, with M the balanced matrix. Patches: 150 px squares, 6 columns by 4 rows from the top left (900 x 600 px), the rest of the frame is mid grey 0.18. Optional Gaussian noise: sigma^2 = a + b x (v - black).

## 4. Constants

- XYZ of D65 white: (0.95047, 1, 1.08883). sRGB to XYZ: Lindbloom D65 matrix.
- M0 = LibRaw `DC-S5M2` XYZ to camera: {10308, -4206, -783, -4088, 12102, 2229, -125, 1051, 5912} / 10000.
- Balanced matrix M: each row of M0 divided by (M0 x XYZ_D65) for that row, so D65 white maps to camera (1,1,1) and the DNG with AsShotNeutral (1,1,1) is consistent.
- sRGB to working (LibRaw `prophoto_rgb`, Bradford adapted): {0.529317, 0.330092, 0.140588; 0.098368, 0.873465, 0.028169; 0.016879, 0.117663, 0.865457}. The app's `ColorSpaces.m` and the engine `proPhotoToSrgb` agree with it to about 4 digits (computed 6 Oct 2026).
- Engine ProPhoto to Display P3 (engine.cpp): {1.63277, -0.37961, -0.252809; -0.153699, 1.166619, -0.013002; 0.010388, -0.062789, 1.052053}.

## 5. Expected value model

For a patch with linear sRGB p, exposure E and overall scale K:
`display_c = TABLE( oetf( clamp(K x E x p_c, 0, 1) ) ) x 255`
where oetf is the sRGB encode, TABLE is the 256 entry base curve in `BaseCurve.kt` (linear interpolation, the shader samples with `(v x 255 + 0.5) / 256`), K = 1 for a balanced matrix with neutral (1,1,1) and a frame peak under 0.75 of white. The design target for every fixture is K = 1 (that is what the XFAIL fixtures assert).

Why K = 1 is exact: with neutral (1,1,1) the DNG path gives camera RGB = M x XYZ, LibRaw inverts M and applies its ProPhoto matrix, so working RGB = m_lib x p x E; the engine multiplies by the inverse of the same matrix, so linear sRGB out = E x p (up to the 4 digit matrix agreement).

### 5a. Ramp F1 (neutral, E = 0.7): observed against model

| Patch | Linear input | Observed R (levels) | Absolute difference from model |
|---|---|---|---|
| 0 | 0.00200 | 17.0 | 17.86 |
| 1 | 0.00262 | 21.0 | 21.69 |
| 2 | 0.00343 | 26.0 | 25.93 |
| 3 | 0.00450 | 30.0 | 30.67 |
| 4 | 0.00589 | 36.0 | 35.95 |
| 5 | 0.00772 | 42.0 | 41.85 |
| 6 | 0.01012 | 48.0 | 48.41 |
| 7 | 0.01326 | 56.0 | 55.71 |
| 8 | 0.01737 | 64.0 | 63.81 |
| 9 | 0.02276 | 73.0 | 72.77 |
| 10 | 0.02982 | 83.0 | 82.64 |
| 11 | 0.03907 | 93.0 | 93.47 |
| 12 | 0.05119 | 105.0 | 105.27 |
| 13 | 0.06707 | 118.0 | 118.01 |
| 14 | 0.08788 | 131.0 | 131.62 |
| 15 | 0.11514 | 146.0 | 145.94 |
| 16 | 0.15086 | 161.0 | 160.76 |
| 17 | 0.19766 | 176.0 | 175.75 |
| 18 | 0.25898 | 191.0 | 190.54 |
| 19 | 0.33932 | 205.0 | 204.68 |
| 20 | 0.44459 | 218.0 | 217.74 |
| 21 | 0.58251 | 229.0 | 229.34 |
| 22 | 0.76323 | 239.0 | 239.22 |
| 23 | 1.00000 | 247.0 | 247.24 |

The model value is `TABLE(oetf(0.7 x input)) x 255` (run `reference.py` functions to print it). Observed values are the central 21 x 21 mean of the rendered patch.

### 5b. Chart F2 (E = 0.7): sRGB 8 bit input, linear input, model output, observed output

Chart values are the widely used ColorChecker 24 sRGB (D65) 8 bit values. Patch order is row major, 6 columns.

| Patch | sRGB 8 bit | Linear sRGB | Model R, G, B | Observed R, G, B |
|---|---|---|---|---|
| 0 | 115,82,68 | 0.17144 0.08438 0.05781 | 167.8, 129.5, 110.9 | 168, 129, 111 |
| 1 | 194,150,130 | 0.53948 0.30499 0.22323 | 226.2, 199.2, 182.5 | 226, 199, 182 |
| 2 | 98,122,157 | 0.12214 0.19462 0.33716 | 149.1, 174.9, 204.4 | 149, 175, 204 |
| 3 | 87,108,67 | 0.09531 0.14996 0.05613 | 135.8, 160.4, 109.5 | 136, 160, 109 |
| 4 | 133,128,177 | 0.23455 0.21586 0.43966 | 185.2, 180.6, 217.2 | 185, 181, 217 |
| 5 | 103,189,170 | 0.13563 0.50888 0.40198 | 154.9, 223.7, 213.0 | 155, 224, 213 |
| 6 | 214,126,44 | 0.67244 0.20864 0.02519 | 234.8, 178.7, 76.4 | 235, 179, 76 |
| 7 | 80,91,166 | 0.08022 0.10462 0.38133 | 126.9, 140.8, 210.5 | 127, 141, 210 |
| 8 | 193,90,99 | 0.53328 0.10224 0.12477 | 225.7, 139.6, 150.3 | 226, 139, 150 |
| 9 | 94,60,108 | 0.11193 0.04519 0.14996 | 144.4, 99.7, 160.4 | 144, 100, 160 |
| 10 | 157,188,64 | 0.33716 0.50289 0.05127 | 204.4, 223.2, 105.3 | 204, 223, 105 |
| 11 | 224,163,46 | 0.74540 0.36625 0.02732 | 238.4, 208.5, 79.3 | 238, 208, 79 |
| 12 | 56,61,150 | 0.03955 0.04667 0.30499 | 94.0, 101.1, 199.2 | 94, 101, 199 |
| 13 | 70,148,73 | 0.06125 0.29614 0.06663 | 113.6, 197.7, 117.7 | 114, 198, 118 |
| 14 | 175,54,60 | 0.42869 0.03689 0.04519 | 216.1, 91.1, 99.7 | 216, 91, 100 |
| 15 | 231,199,31 | 0.79910 0.57112 0.01370 | 240.7, 228.6, 56.6 | 241, 228, 56 |
| 16 | 187,86,149 | 0.49693 0.09306 0.30054 | 222.7, 134.6, 198.4 | 223, 134, 198 |
| 17 | 8,133,161 | 0.00243 0.23455 0.35640 | 20.6, 185.2, 207.2 | 21, 185, 207 |
| 18 | 243,243,242 | 0.89627 0.89627 0.88792 | 244.2, 244.2, 243.9 | 244, 244, 244 |
| 19 | 200,200,200 | 0.57758 0.57758 0.57758 | 229.0, 229.0, 229.0 | 229, 229, 229 |
| 20 | 160,160,160 | 0.35153 0.35153 0.35153 | 206.5, 206.5, 206.5 | 206, 206, 206 |
| 21 | 122,122,121 | 0.19462 0.19462 0.19120 | 174.9, 174.9, 173.9 | 175, 175, 174 |
| 22 | 85,85,85 | 0.09084 0.09084 0.09084 | 133.3, 133.3, 133.3 | 133, 133, 133 |
| 23 | 52,52,52 | 0.03434 0.03434 0.03434 | 88.2, 88.2, 88.2 | 88, 88, 88 |

## 6. Fixtures and tolerances

| ID | Content | E | WB neutral | Black | Noise | Tolerance (max abs levels) | Status today |
|---|---|---|---|---|---|---|---|
| F1 | 24 step neutral ramp 0.002 to 1.0 (log) | 0.7 | 1,1,1 | 512 | none | 2.0 | PASS (0.86) |
| F2 | ColorChecker 24 | 0.7 | 1,1,1 | 512 | none | 3.0 | PASS (0.65) |
| F3 | ColorChecker 24, higher black level | 0.7 | 1,1,1 | 1024 | none | 3.0 | PASS (0.65) |
| F4 | primaries, secondaries, skin, deep shadows | 0.7 | 1,1,1 | 512 | none | 6.0 | PASS (3.0) |
| F5 | ColorChecker 24 with shot noise | 0.7 | 1,1,1 | 512 | 4 + 0.05 x signal | 3.0 | PASS (0.77) |
| F6 | ColorChecker 24, warm light | 0.7 | 0.55,1,0.7 | 512 | none | 3.0 | XFAIL (33.5, AE-023) |
| F7 | ColorChecker 24, cool light | 0.7 | 0.8,1,0.6 | 512 | none | 3.0 | XFAIL (28.7, AE-023) |
| F8 | ramp with peak at 0.8 of white | 0.8 | 1,1,1 | 512 | none | 3.0 | XFAIL (12.4, BK-470) |
| F9 | ramp, heavy overexposure (E = 4), patch 23 must be white | 4.0 | unity and 0.55,1,0.7 | 512 | none | min channel at least 253, spread at most 1 | unity PASS, warm FAIL (250, AE-001) |
| M1 | engine ProPhoto to P3 times `prophoto_rgb` against nominal sRGB to P3 | n/a | n/a | n/a | n/a | 5e-4 per element, rows sum to 1 within 5e-4 | PASS (3.2e-4) |

Tolerance reasoning: output is 8 bit (rounding gives up to 0.5), the shader texture lookup is interpolated, matrices agree to about 4 digits (0.03 percent, about 0.1 level at white), and half float intermediate storage adds under 0.5 level. 1 level of observed error is therefore the floor; the numbers above show it is reached. Patch means use the central 21 x 21 pixels, so edge filtering is excluded.

XFAIL protocol: `run_fixtures.py` exits 0 when PASS fixtures pass and XFAIL fixtures still fail. When a fix lands, the XFAIL starts passing, the script prints "XPASS, remove the marker" and exits 1 until the status in `FIXTURES` is changed to PASS. That makes a fix visible and forces the marker to be removed in the same commit.

## 7. How a worker does it (no questions needed)

Files and wiring, exactly (nothing in Gradle or CMake changes for W21; it is Python, one shell script, one CI step and one small Kotlin test):

1. Create `tools/colour/` with `make_dng.py`, `reference.py`, `run_fixtures.py` (section 9, copy verbatim; `reference.py` finds the repo root from its own location, so it works in CI and locally) and `tools/colour/run-colour.sh` (mode 755):
   ```
   #!/usr/bin/env bash
   # Synthetic DNG colour checks through the real shader pipeline (needs the golden binary: tools/golden/run-golden.sh or build.sh builds /tmp/golden/golden).
   set -euo pipefail
   ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
   WORK="$(mktemp -d)"
   trap 'rm -rf "$WORK"' EXIT
   cd "$WORK"
   GOLDEN_BIN="${GOLDEN_BIN:-/tmp/golden/golden}" python3 "$ROOT/tools/colour/run_fixtures.py" "$@"
   ```
   `tools/golden/build.sh` is not changed. The fixtures write `F*.dng` and `F*.ppm` into the temporary directory only.
2. CI: in `.github/workflows/build.yml`, job `golden`, add after the step "Golden render tests" (the golden binary is built there, python3 is on the runner, no Pillow needed):
   ```
         - name: Colour verification (synthetic DNG charts)
           run: ./tools/colour/run-colour.sh
   ```
   Runtime on a runner: about 10 to 20 seconds per fixture (pure Python mosaic generation); if the job gets too slow, build the mosaic with `array('H')` and `bytes` joins (same output).
3. Run it on main first. Expect exactly the output in section 8. If any PASS fixture differs by more than 0.3 level from those numbers, stop and report (the toolchain or the golden binary changed).
4. Host test for the matrix copies (guards `rawFromSrgb8`'s third row, the only known disagreement of 3 to 5e-4): make `ColorSpaces.m` `internal val SRGB_TO_WORKING` (rename, same numbers) and add `core/render/src/test/kotlin/app/rawline/core/render/ColorSpacesMatrixTest.kt`:
   ```kotlin
   package app.rawline.core.render
   import org.junit.Assert.assertEquals
   import org.junit.Test
   class ColorSpacesMatrixTest {
       // LibRaw 0.22.2 prophoto_rgb (src/tables/colordata.cpp), linear sRGB to ProPhoto, Bradford adapted
       private val libraw = doubleArrayOf(0.529317, 0.330092, 0.140588, 0.098368, 0.873465, 0.028169, 0.016879, 0.117663, 0.865457)
       @Test fun appMatrixEqualsLibrawsProphotoTable() {
           for (i in 0 until 9) assertEquals("element $i", libraw[i], ColorSpaces.SRGB_TO_WORKING[i].toDouble(), 5e-4)   // measured largest difference 1.4e-4
       }
       @Test fun everyRowSumsToOneSoNeutralStaysNeutral() {
           for (r in 0 until 3) assertEquals(1.0, (0 until 3).sumOf { ColorSpaces.SRGB_TO_WORKING[r * 3 + it].toDouble() }, 5e-4)
       }
   }
   ```
   (Not compiled here; the 1.4e-4 figure was computed 6 Oct 2026.) If `rawFromSrgb8` in `raw_decode.cpp` still has its own copy, add the same check there by reading the constants from a shared header in the same commit, or leave it for W22 (it is the single-source cleanup of BK-437).
5. Optional golden.cpp additions (owner: whoever touches golden.cpp next; keep them small): `space=p3` calls `eng.setOutputSpace(1)`, then add fixture F10 (ColorChecker 24 with expected `TABLE(oetf(P3_lin)) x 255`, P3_lin = (0.8224621, 0.1775380, 0; 0.0331941, 0.9668058, 0; 0.0170827, 0.0723974, 0.9105199) times the linear sRGB patch, tolerance 3 levels).
6. Do not fix the XFAILs in this task. They belong to W22 (`W22-colour-contract.md`: white point, white balance neutrality and the curve, behind a look version), which replaces `run_fixtures.py` by a version that runs every fixture under both looks.

## 8. Expected output on main 71918bf

```
F1_ramp     E=0.70 worst 0.86 levels tol 2.0  PASS
F2_chart    E=0.70 worst 0.65 levels tol 3.0  PASS
F3_black    E=0.70 worst 0.65 levels tol 3.0  PASS
F4_prim     E=0.70 worst 3.00 levels tol 6.0  PASS
F5_noise    E=0.70 worst 0.77 levels tol 3.0  PASS
F6_wb_warm  E=0.70 worst 33.46 levels tol 3.0  FAIL  (expected failure: still failing)
F7_wb_cool  E=0.70 worst 28.74 levels tol 3.0  FAIL  (expected failure: still failing)
F8_maxthr   E=0.80 worst 12.38 levels tol 3.0  FAIL  (expected failure: still failing)
F9_clip_unity  min channel 255 spread 0.0  PASS
F9_clip_warm   min channel 250 spread 0.0  FAIL (expected for warm until AE-001 is fixed)
M_P3 max |engine*libraw - nominal| = 0.00032, row sums [1.00035, 0.99992, 0.99965]  PASS
```
Exit code 0. Runtime is dominated by the pure Python mosaic generation (about 1 million pixels per fixture); budget 10 to 20 seconds per fixture on a CI runner. If that is too slow in CI, build the mosaic with `array('H')` and `bytes` joins (same output).

## 9. Source (tested; copy verbatim)

### make_dng.py
```python
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
```

### reference.py
```python
#!/usr/bin/env python3
"""Reference model + comparer for the synthetic colour fixtures. Standard library only."""
import re, sys, json, subprocess, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_dng as m

REPO = os.environ.get("RAWLINE_REPO", os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")))   # tools/colour/ -> the repo root
GOLDEN = os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")

def base_table():
    src = open(REPO + "/core/render/src/main/kotlin/app/rawline/core/render/BaseCurve.kt").read()
    body = src.split("val TABLE = floatArrayOf(")[1].split(")")[0]
    t = [float(x) for x in re.findall(r"[0-9.]+(?=f)", body)]
    assert len(t) == 256
    return t
TABLE = base_table()
def samp(x):
    p = min(max(x, 0.0), 1.0) * 255; i = min(int(p), 254); return TABLE[i] + (TABLE[i+1] - TABLE[i]) * (p - i)
def oetf(l): return 12.92*l if l <= 0.0031308 else 1.055*l**(1/2.4) - 0.055
def eotf(s): return s/12.92 if s <= 0.04045 else ((s+0.055)/1.055)**2.4

# XYZ(D50-adapted) is not needed: the engine chain for neutral-balanced input is  display = oetf(m_lib^-1 ... ) = identity colour path,
# so for sRGB-defined patches the expected linear sRGB out is  K * E * patch  (see section 6 of the document).
def expected_srgb8(patch_lin, exposure, K=1.0, base=True):
    out = []
    for c in patch_lin:
        v = oetf(min(max(K*exposure*c, 0.0), 1.0))
        out.append(round((samp(v) if base else v) * 255, 2))
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

def render(dng, ppm, width=600, extra=()):
    r = subprocess.run([GOLDEN, dng, ppm, str(width), "full", *extra], capture_output=True, text=True)
    if r.returncode: raise SystemExit("golden failed: " + r.stderr[-400:])
    return read_ppm(ppm)
```

### run_fixtures.py
```python
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
```

## 10. Follow-ups this unlocks

- W22: set `adjust_maximum_thr = 0`, refit the base curve offset on the S5M2X samples, flip F8 to PASS.
- W23: make WB brightness-neutral (normalise the multipliers by their luminance weighted mean instead of the maximum, or compensate in the engine), flip F6, F7 and F9 warm to PASS. The base curve must be refitted at the same time because it embeds today's K for daylight.
- BK-441: shoot a physical ColorChecker with the S5IIX, add it as a real fixture for the phone's Copy report (colour error as mean delta E against the published values).
- Add fixtures with a dual illuminant DNG (ColorMatrix2, CalibrationIlluminant2 = 17) when the S24 Ultra Expert RAW path is verified, since LibRaw interpolates by AsShotNeutral for those.

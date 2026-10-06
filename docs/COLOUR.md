# Colour: the checks and the look versions

How the picture gets its colour and brightness, what proves it, and what a "look version" is. Written with the work of W21 and W22 (BK-435, 436, 437, 438, 440, 470). Australian English, no model names.

## 1. The chain
DNG or RAW decode (LibRaw: black level, camera matrix, camera white balance, ProPhoto working space, gamma 1) then the engine: source gain, edit, output matrix to sRGB or Display P3, sRGB encoding, base tone curve table. Every stage except the real camera's colour is checked by `tools/colour` (section 2).

## 2. The verification harness (`tools/colour`, runs in the golden CI job)
- `make_dng.py` writes synthetic Bayer DNG files (1200 x 800, RGGB, 14 bit, a 24 patch chart, a ramp, noise, a warm and a cool white balance, a bright frame peak, heavy overexposure). Standard library only.
- `reference.py` is an independent model in plain Python: expected display value = base table of the look at `oetf(clamp(K x E x patch))`.
- `run_fixtures.py` renders every fixture through the real engine (the golden binary, Mesa llvmpipe) under both look versions and compares. `./tools/colour/run-colour.sh` runs it (about 15 s). Exit 0 means every fixture behaves as its status for that look says.
- `tools/looks/make_base_curve.py check` proves the properties of the look 2 curve and that the engine header and the Kotlin table are that table.
- Host tests: `ColorSpacesMatrixTest` (the app's sRGB to working matrix equals LibRaw's ProPhoto table), `RenderTest` (both tables equal the engine header; look 1's table is pinned by its SHA-256), `LookGainsTest`, `LookTest`, `tools/golden/look_test.cpp` (the gains the decoder reports).

Result on 6 Oct 2026 (levels of 255, worst patch; tolerance in brackets):

| Fixture | Look 1 (every edit saved before looks) | Look 2 |
|---|---|---|
| F1 ramp (2.0) | 0.86 PASS | 0.84 PASS |
| F2 chart (3.0) | 0.65 PASS | 0.60 PASS |
| F3 higher black level (3.0) | 0.65 PASS | 0.64 PASS |
| F4 primaries (6.0) | 3.00 PASS | 1.00 PASS |
| F5 noise (3.0) | 0.77 PASS | 0.63 PASS |
| F6 warm white balance (3.0) | 33.46 expected fault (AE-023) | 0.60 PASS |
| F7 cool white balance (3.0) | 28.74 expected fault (AE-023) | 0.60 PASS |
| F8 frame peak at 0.8 of white (3.0) | 12.38 expected fault (BK-470) | 0.52 PASS |
| F9 clipped neutral, unity | 255 white | 255 white |
| F9 clipped neutral, warm | 250 expected fault (AE-001) | 255 white |
| M_P3 matrices | 0.00032 PASS | same |

Look 1 keeps its faults on purpose: those numbers are the guard that an existing edit renders as it always did. If one moves, a saved edit would look different.

What this does not prove: the colour of a real S5IIX. The synthetic camera matrix is LibRaw's `DC-S5M2` matrix scaled so neutral is (1,1,1). A photographed ColorChecker is a phone task (BK-441).

## 3. Look versions
An edit carries `lookVersion` in its recipe. A recipe without the key was made under look 1 and reads as look 1; nothing is rewritten on upgrade.

| | Look 1 | Look 2 |
|---|---|---|
| Decode white point | LibRaw rescales when the frame's brightest pixel is between 75 and 100 percent of white (up to +0.4 EV) | never (`adjust_maximum_thr = 0` for every decode) |
| Source gain (`uSrcGain`, a multiplier on every source texel) | `v1Scale`, the factor LibRaw's rule would have applied (1.0 for most frames, 1.0 for the S5M2X sample) | `wbGain` = max(cam_mul) / min(cam_mul) (2.0117 on the sample): a neutral lands at the level it has at unity white balance |
| Base curve | `kBaseCurve` / `BaseCurve.TABLE`, never changes | `kBaseCurve2` / `BaseCurve.TABLE2` |
| HSL bands | overlapping bands: the weights at a hue do not sum to 1 (a pure orange got 1.64 times the slider) | the two bands that bracket the hue cross-fade with a smoothstep, the others are 0, so equal sliders equal global Saturation |
| Texture | the base for fine detail is the 512 px analysis layer, limited to 2 stops each way | the base is the mean of a ring of eight source taps (radius max(1.5, 0.07 percent of the long edge) source pixels, with the source gain and the heal overlay), limited to 0.6 stop each way |
| Grading zones | Rec 709 weights on gamma values | the shader's ProPhoto Y weights on linear light, then gamma |

One decode serves both looks (the prefetch and the half size preview never need to know the look): the decoder reports `[v1Scale, wbGain]` and the engine multiplies by the one the edit's look asks for.

### The curves
The stored table is exactly `y = g x / (1 + (g - 1) x^c)` in linear light, g = 4.30, c = 1.10, input and output sRGB encoded. Its gain of 4.3 contains 1 / K0 = 2.01 of the fit scene (K0 = 0.4971, the green pre-multiplier of the S5M2X fit samples: LibRaw divides its multipliers by the largest, so a neutral lands at min / max of `cam_mul`). Look 2 re-expresses the same curve for a neutral at 1.0 and adds a cubic shoulder from a knee at 0.55 that reaches white at 1.0 with zero slope. Below the knee look 2 equals look 1 within 0.09 level; on the real sample the two render the same picture (mean difference 0.00, largest 1 level).

**The knee (0.55) and the shoulder shape are provisional.** They are a judgement, not a fit (approved as provisional by the PM, 6 Oct 2026). A true refit needs highlight rich frames and the camera's own JPEGs, which this repository does not have: Jai's phone task BK-021. Changing the table before a release costs nothing; after a release it needs look 3. `make_base_curve.py compare 0.45` and `compare 0.70` show the alternatives.

### What changes for people
- Edits saved before the update: unchanged, they render and export as before. The editor's More menu says "Look: earlier (made before the look update)" with "Update look". It is one undoable step (Undo returns to the earlier look); a short note says "Look updated. Undo to go back."
- A photo with no edit, and Reset all edits: look 2 (a new edit). An unedited RAW with a clipped neutral or a non daylight white balance therefore looks different from before: that is the fix.
- Presets, Paste edits and Paste from last never carry a look: the target keeps its own.
- Heal and remove patches are stored as display values and converted with the edit's base curve, so the overlay is built again when the look changes.
- Export uses the recipe's look, so an old edit exports the same pixels as before (within 1 level; single saturated out of gamut pixels in a frame LibRaw used to rescale can differ by a few levels at their edge, because LibRaw clipped to 16 bits after its rescale and look 1 now applies the factor after the fact).
- Downgrade: an older build opening a look 2 recipe ignores `lookVersion` and renders look 1 (a small difference, only for edits made after the update). A look from a newer build renders with the newest look this build knows.
- Future looks: a look 3 adds a table and a gain rule and never edits an existing one.

### Known limits
- K0 = 0.4971 belongs to the Panasonic fit scene. A camera with a very different base sensitivity sits brighter or darker by its own factor until it has its own curve.
- A DNG with two illuminant matrices (the S24 Ultra Expert RAW) interpolates by white balance inside LibRaw, so `cam_mul` reflects that. Not checked: no sample file.
- `v1Scale` depends on LibRaw 0.22.2's `maximum` and `data_maximum` (pinned by hash). A LibRaw upgrade must re-run fixture F8 under look 1.
- `rawFromSrgb8` (finished pictures) still carries its own copy of the matrix, which differs from LibRaw's in the third row by 3 to 5e-4 (BK-437, left as it is: it only touches JPEG, HEIC and PNG).

### Shader maths of look 2 (W23)
The three rows above ride in the free params slot `G_LOOK` (31), which `RenderParams.build` fills from `recipe.lookVersion`; `initDefaultParams` sets it to 1, so a caller that does not know about looks, and every look 1 golden scene, renders as before (byte for byte, checked). Checks: `tools/golden/look2_checks.py` (HSL sliders at +20 equal global Saturation +20 on 24 hues, the green band stays inside its reach, Texture +50 lifts 6 px stripes, leaves an 80 px wave alone and undershoots at most 10 levels at a 5:1 step) must pass at look 2 and fail at look 1. Golden scenes `hsl2`, `texture2`, `grade2` are look 2 only.
Not done, logged as BK-485: the HSL hue is taken from ProPhoto gamma values, so the band names do not match what the eye calls those hues (sRGB pure green gets 78 of 129 levels of a full green band). Putting the band centres on sRGB hue would change every HSL edit again, so it is a candidate for look 3.

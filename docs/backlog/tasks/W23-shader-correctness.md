# W23 Shader correctness bundle, look 2 (HSL partition, fine Texture radius, grading luma), gated by the look version

Status at writing: main e75d824 (W22 not merged). Covers the remainder of BK-459 (AE-013, AE-020, AE-029 check, AE-030), with BK-443 (white balance luminance) staying in W22. Everything in sections 3 to 6 was built and run on Mesa llvmpipe through the real golden harness in a scratch copy of the tree; the Kotlin hunk in section 5 and the CI wiring in section 7 were NOT compiled or run.

## 1. What is already merged and must not be redone
Checked in the HEAD shader and `run-golden.sh` on 6 Oct 2026: negative dehaze (AE-002), lens gain in the local analysis (AE-003), mask baseline with `gainTone` (AE-012, numeric check `AE-012 mask clarity on a flat picture under +1 EV` exists), grading tint luminance neutral (AE-014, `tintColour`), range masks in the display domain (AE-015, `toDisplay`), heal overlay before the vignetting gain (AE-005), manual vignette centred on the frame (AE-016), Clarity weight clamped (AE-029, `max(..., 0.0)`). So "grading" and "mask baseline" from the PM list are done; this task adds only the three look-changing items below, and one extra regression check for the mask baseline is NOT needed.

## 2. Decisions (no questions left)
- D1 Everything look-changing here is gated by one global slot `G_LOOK = 31` (a free slot between G_SHOWMASK and G_LDIST; `kParamFloats` and `G_COUNT` do not change, so the Kotlin mirror test only gains one name). `initDefaultParams` sets it to 1, so any caller that does not know about looks, and every existing golden scene, renders exactly as before. `RenderParams.build` writes `recipe.lookVersion` (a W22 field) into it. Look 2 and later use the new maths.
- D2 No new JNI call: the slot rides in the params array that already reaches the engine every render. The engine reads it into the uniform `uLook` and computes `uTexOn` (any block with a Texture slider away from zero) so the eight extra taps cost nothing when Texture is untouched.
- D3 HSL bands (AE-013), look 2: the two bands that bracket the pixel's hue cross-fade with a smoothstep and all others are 0, so the weights always sum to exactly 1. Result: HSL saturation sliders all at +20 give the same picture as global Saturation +20 (look 1 differed by up to 14 levels on 24 hues).
- D4 Texture (AE-020), look 2: the base for fine detail is the mean of a ring of eight source taps (4 axis at radius r, 4 diagonal at r x 0.7071, centre excluded). r = max(1.5, 0.0007 x long edge) source pixels, taken in the mip level being drawn with a one texel minimum, so a preview and an export see the same detail. The fine ratio is limited to 0.6 stop each way (look 1: 2 stops) so a hard edge is not boosted into a halo. Clarity, Highlights, Shadows keep the 512 px analysis layers.
- D5 Grading zones (AE-030), look 2: the zone is chosen by `pow(dot(linear, Y), 1/2.2)` with the shader's own ProPhoto Y weights; look 1 used Rec 709 weights on gamma values.
- D6 Existing saved edits keep look 1 (W22 rule: a recipe without `lookVersion` reads as 1) and do not change. A new edit starts at the current look (2), and the editor's "Update look" row (W22) switches an old edit, with undo.
- D7 Not in this task: AE-022 (mask layers resampled to 1024 x 1024, stretched anisotropically; a separate engine job), Texture taps at output resolution for the analysis layers, and the band name problem in finding F3 below.

## 3. Measured results (llvmpipe, sample `P1055415.RW2` and synthetic DNGs, scratch tree built from the section 4 patch)
Regression: with `G_LOOK` at its default of 1, all 19 scenes of `run-golden.sh` render byte-identical (`cmp`) to HEAD. With `shader=1` on the HSL, texture and grading fixtures the numbers equal HEAD's.

| Check | Look 1 | Look 2 |
|---|---|---|
| 24 hues, HSL saturation all +20 versus global Saturation +20, worst patch (levels) | 14.0 (mean 7.0) | 0.0 |
| Green band +50: patches at hues 0 to 0.125 and 0.5 to 0.96 change (levels) | 0 | 0 (a guard that passes at both looks) |
| Texture +50 on 3000 px frame: 6 px stripes peak to peak (22 before) | 32 | 31 |
| Texture +50: 80 px wave peak to peak (29.3 before) | 35.4 (+21 percent) | 29.4 (unchanged) |
| Texture +50: undershoot at a 5:1 step (levels) | 22 | 8 |
| Real photo, Texture +60, mean abs change, width 320 / 1280 / 3000 | 2.4 / 4.8 / 6.0 | 1.9 / 2.1 / 3.5 |
| Grading shadows tint, full strength, largest look 1 to look 2 gap over hues | | 4 to 6 levels (small; a correctness change, not a visible one) |

Reading of the last two rows: look 1's Texture strength grows 2.5 times from the fit-to-screen preview to the export (the analysis blur is a fixed fraction of the frame), look 2 grows 1.8 times, and mostly because a 3000 px frame genuinely holds more fine detail. The band numbers confirm AE-013 and AE-020 as written in audit-engine.md. Grading is as small as a P3 suggests.

Finding F3 (not fixed here): the HSL hue is taken in ProPhoto gamma space, so the band names do not match what the eye calls those hues. The sRGB pure green patch (hue 0.333) gets 78 of 129 levels of a full green band, and the strongest green response sits on sRGB hues 0.375 to 0.42; cyan-ish 0.458 still moves 25. Fixing it means computing the HSL in a perceptual or sRGB-like space, which changes every HSL edit; it is a candidate for look 3 and is logged as BK-485.

## 4. The engine patch (applies to e75d824 with `git apply`; `git apply --check` passes on the live tree)
It adds `G_LOOK`, the uniform `uLook` and `uTexOn`, the three shader changes, and golden keys `shader=` (sets G_LOOK), `hslsat=`, `hsllum=`, `hslband=` (band x 1000 + 500 + amount), `gshadows=hue,sat`. The golden harness keeps its default of look 1, so existing scenes are untouched. Once W22 lands, its `look=` golden key should also set `p[G_LOOK]` (one line) and `shader=` can go.
If W22 merges first, rebase the engine hunk: it sits next to the `uOverlayOn` uniform and W22 adds `uSrcGain` in the same function. In `main.frag` the Texture ring taps should multiply by `uSrcGain` like every other source fetch (W22 section 5).
```diff
--- a/core/native/src/main/cpp/shaders/main.frag
+++ b/core/native/src/main/cpp/shaders/main.frag
@@ -28,6 +28,8 @@
 uniform vec3 uLensFlags;     // tca on, vignetting on
 uniform mat3 uToSrgb;        // working space (ProPhoto, D50) to linear sRGB (colour and luminance range masks compare in display terms)
 uniform sampler2D uBase;     // base tone curve, 256 wide (the same table the output pass uses)
+uniform float uLook;         // look version of the recipe: 1 keeps the original maths, 2 (or more) uses the corrected HSL bands, grading luma and fine texture radius
+uniform float uTexOn;        // 1 when any block has a Texture slider away from zero (the fine detail taps are only made then)
 
 //@include geometry.glsl
 
@@ -90,7 +92,9 @@
 vec3 grade(vec3 g, int block) {
     // g is gamma encoded. Three-way colour grading.
     vec4 sh = B(block, 10), mi = B(block, 11), hi = B(block, 12), gl = B(block, 13), bl = B(block, 14);
-    float l = dot(g, vec3(0.2126, 0.7152, 0.0722));
+    // Look 2: the zone is chosen by the luminance of the working space (Y weights on linear light, then gamma), so a saturated blue is as dark as it is.
+    // Look 1 weighted the gamma values with Rec 709, which treats a deep ProPhoto blue as 0.07 bright.
+    float l = uLook > 1.5 ? pow(max(dot(toLinear(g), Y), 0.0), 1.0 / 2.2) : dot(g, vec3(0.2126, 0.7152, 0.0722));
     float blend = bl.x;          // 0..1 (default 0.5)
     float bal = bl.y;            // -1..1
     float lo = 0.33 + bal * 0.2, hiE = 0.66 + bal * 0.2;
@@ -109,7 +113,7 @@
 }
 
 // Applies one adjustment block. 'bs','bl','bd' are the local analysis samples at this pixel.
-vec3 adjust(vec3 c, int block, float bs, float bl, float bd, out float gainTone) {
+vec3 adjust(vec3 c, int block, float bs, float bf, float bl, float bd, out float gainTone) {
     vec4 b0 = B(block, 0), b1 = B(block, 1), b2 = B(block, 2), b3 = B(block, 3);
     float exposure = b0.x, contrast = b0.y, highlights = b0.z, shadows = b0.w;
     float whites = b1.x, blacks = b1.y, temp = b1.z, tint = b1.w;
@@ -134,10 +138,12 @@
 
     // Texture (fine) and clarity (broad) local contrast from the blurred layers.
     float Ym = max(luma(c), 1.0e-5);
-    float fine = log2(Ym / max(bs * gain * tone, 1.0e-5));
+    float fine = log2(Ym / max((uLook > 1.5 ? bf : bs) * gain * tone, 1.0e-5));   // look 2: fine detail against a small radius ring, look 1: against the 512 px analysis layer
     float broad = log2(Ym / max(bl * gain * tone, 1.0e-5));
     float mid = max(1.0 - abs(pow(Ym, 1.0 / 2.4) * 2.0 - 1.0), 0.0);   // 0 above white: clarity never reverses sign on super-white pixels
-    c *= exp2(clamp(fine, -2.0, 2.0) * texture * 0.01 * 0.8 + clamp(broad, -2.0, 2.0) * clarity * 0.01 * 0.8 * mid);
+    // Look 2: fine detail is limited to 0.6 stop either side so a hard edge (which the small ring sees as a huge ratio) is not boosted into a halo.
+    float fl = uLook > 1.5 ? 0.6 : 2.0;
+    c *= exp2(clamp(fine, -fl, fl) * texture * 0.01 * 0.8 + clamp(broad, -2.0, 2.0) * clarity * 0.01 * 0.8 * mid);
 
     // Dehaze via dark channel prior.
     if (abs(dehaze) > 0.001) {
@@ -169,8 +175,19 @@
     // Saturation, vibrance and the HSL colour mixer.
     vec3 hsv = rgb2hsv(clamp(g, 0.0, 1.0));
     float hueShift = 0.0, satMul = 0.0, lumMul = 0.0;
+    // Look 2: the two bands that bracket the hue cross-fade and every other band is 0, so the weights always sum to 1 and an equal
+    // slider value has an equal effect at every hue. Look 1 keeps the old overlapping bands (a pure orange got 1.64 times the slider).
+    int bk = 7, bn = 0; float bt = 0.0;
+    if (uLook > 1.5) {
+        for (int i = 0; i < 8; i++) if (hsv.x >= BAND[i]) bk = i;
+        bn = (bk + 1) & 7;
+        float c1 = BAND[bn] + (bn == 0 ? 1.0 : 0.0);
+        bt = clamp((hsv.x - BAND[bk]) / (c1 - BAND[bk]), 0.0, 1.0);
+        bt = bt * bt * (3.0 - 2.0 * bt);
+    }
     for (int i = 0; i < 8; i++) {
-        float w = bandWeight(hsv.x, i) * smoothstep(0.02, 0.2, hsv.y);
+        float bw = uLook > 1.5 ? (i == bk ? 1.0 - bt : (i == bn ? bt : 0.0)) : bandWeight(hsv.x, i);
+        float w = bw * smoothstep(0.02, 0.2, hsv.y);
         vec4 hv = B(block, 4 + (i >> 2)), sv = B(block, 6 + (i >> 2)), lv = B(block, 8 + (i >> 2));
         int j = i & 3;
         hueShift += w * hv[j];
@@ -291,7 +308,25 @@
     // makes them the blur of the corrected picture. Without it a corner of a lens corrected photo reads as detail against a dark base.
     float bs = texture(uBs, p).x * vgain, bl = texture(uBl, p).x * vgain, bd = texture(uBd, p).y * vgain;
     float gt0;
-    c = adjust(c, 0, bs, bl, bd, gt0);
+    // Look 2 Texture: the base for fine detail is the mean luminance of a ring of eight source taps whose radius is about 0.07 percent of the long edge
+    // (4 px on a 6000 px frame, never under 1.5 px), measured in source pixels so a preview and an export see the same detail. Only made when a Texture slider is set.
+    float bf = bs;
+    if (uLook > 1.5 && uTexOn > 0.5) {
+        float rl = max(1.0, max(1.5, 0.0007 * max(uSrcSize.x, uSrcSize.y)) / exp2(uLod));
+        vec2 o = vec2(rl * exp2(uLod)) / uSrcSize;
+        vec2 o2 = o * 0.70710678;
+        float acc = 0.0;
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(o.x, 0.0), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv - vec2(o.x, 0.0), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(0.0, o.y), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv - vec2(0.0, o.y), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + o2, 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv - o2, 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(o2.x, -o2.y), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(-o2.x, o2.y), 0.0, 1.0), uLod).rgb);
+        bf = max(acc * 0.125, 1.0e-5) * vgain;
+    }
+    c = adjust(c, 0, bs, bf, bl, bd, gt0);
     float asp = bdim.x / bdim.y;
     for (int m = 0; m < 8; m++) {
         if (m >= uNumMasks) break;
@@ -299,7 +334,7 @@
         if (a > 0.001) {
             // c already carries the global (and earlier mask) exposure and tone: measure local contrast against the same baseline
             float gtm;
-            vec3 adj = adjust(c, 1 + m, bs * gt0, bl * gt0, bd * gt0, gtm);
+            vec3 adj = adjust(c, 1 + m, bs * gt0, bf * gt0, bl * gt0, bd * gt0, gtm);
             c = mix(c, adj, a);
             gt0 = mix(gt0, gt0 * gtm, a);
         }
--- a/core/native/src/main/cpp/engine/engine.cpp
+++ b/core/native/src/main/cpp/engine/engine.cpp
@@ -439,6 +439,12 @@
     glUniform3fv(pr.u("uLensVig"), 1, p + G_LVIG);
     glUniform3f(pr.u("uLensFlags"), p[G_LTCA_ON], p[G_LVIG_ON], 0.f);
     glUniform1f(pr.u("uOverlayOn"), overlayW_ > 0 ? p[G_OVERLAY] : 0.f);
+    glUniform1f(pr.u("uLook"), p[G_LOOK]);
+    {
+        float texOn = 0.f;
+        for (int b = 0; b < kMaxBlocks; b++) if (std::fabs(p[kOffBlocks + b * kBlockFloats + S_TEXTURE]) > 0.001f) texOn = 1.f;
+        glUniform1f(pr.u("uTexOn"), texOn);
+    }
     glUniformMatrix3fv(pr.u("uToSrgb"), 1, GL_FALSE, colourMats().srgb);   // masks compare in sRGB display terms whatever the export space
     (void)bw;
     setGeometryUniforms(pr, p);
--- a/core/native/src/main/cpp/engine/params.h
+++ b/core/native/src/main/cpp/engine/params.h
@@ -28,6 +28,7 @@
     G_NUM_MASKS = 28,  // 1
     G_OVERLAY = 29,    // 1
     G_SHOWMASK = 30,   // 1: mask index to tint red for editing, -1 off
+    G_LOOK = 31,       // 1: look version for shader behaviour (1 = original maths, 2 = HSL partition, fine texture radius, ProPhoto grading luma)
     G_LDIST = 32,      // 5: lens distortion polynomial p0..p4 (r_src = r * (p0 + p1 r + p2 r^2 + p3 r^3 + p4 r^4))
     G_LDIST_ON = 37,   // 1
     G_LTCA = 38,       // 6: red (v, c, b), blue (v, c, b) scale polynomials about the source centre
@@ -63,6 +64,7 @@
         for (int i = 0; i < kCurveSize; i++) p[kOffCurves + r * kCurveSize + i] = i / 255.f;
     p[G_DETAIL + 1] = 1.f;
     p[G_SHOWMASK] = -1.f;
+    p[G_LOOK] = 1.f;   // a caller that does not know about looks gets the original maths
 }
 
 }  // namespace rl
--- a/tools/golden/golden.cpp
+++ b/tools/golden/golden.cpp
@@ -145,6 +145,11 @@
         std::string k = a.substr(0, eq);
         float v = float(atof(a.c_str() + eq + 1));
         if (blockSlots.count(k)) p[kOffBlocks + blockSlots[k]] = v;
+        else if (k == "hslsat") { for (int b = 0; b < 8; b++) p[kOffBlocks + S_MIX_SAT + b] = v; }   // all eight HSL saturation sliders
+        else if (k == "hsllum") { for (int b = 0; b < 8; b++) p[kOffBlocks + S_MIX_LUM + b] = v; }
+        else if (k == "hslband") { int b = int(v) / 1000; float amt = float(int(v) % 1000) - 500.f; p[kOffBlocks + S_MIX_SAT + b] = amt; }
+        else if (k == "shader") p[G_LOOK] = v;   // look version for shader behaviour (W23)
+        else if (k == "gshadows") { float h0 = 0, s0 = 0; sscanf(a.c_str() + eq + 1, "%f,%f", &h0, &s0); p[kOffBlocks + S_GRADE_SHADOWS] = h0; p[kOffBlocks + S_GRADE_SHADOWS + 1] = s0; }
         else if (k == "sharpen") p[G_DETAIL] = v;
         else if (k == "nrl") p[G_NR] = v;
         else if (k == "nrc") p[G_NR + 1] = v;
```

### 4b. The same patch rebased for a tree that already has W22 (the order in DISPATCH.md)
W22 and W23 both touch `main.frag`, `engine.cpp` and `golden.cpp`. Checked on 6 Oct 2026: W23's first patch applies on main plus W22 with line offsets only for the shader, engine and params files, but its `golden.cpp` hunk fails because W22 also adds keys next to `sharpen`. The patch below is the three files unchanged plus a rebased `golden.cpp` hunk in which W22's `look=` key also sets `G_LOOK` (so one key switches the curve, the source gain and the shader maths). It applies cleanly to main plus W22 (`patch --dry-run`). I built that combination (W22 engine patch, the generated `kBaseCurve2`, this patch) and ran the check script with `--key look`: look 2 passes all five checks, look 1 fails the same three. Use this one when W22 has merged, and the section 4 patch otherwise.
```diff
--- a/core/native/src/main/cpp/shaders/main.frag
+++ b/core/native/src/main/cpp/shaders/main.frag
@@ -28,6 +28,8 @@
 uniform vec3 uLensFlags;     // tca on, vignetting on
 uniform mat3 uToSrgb;        // working space (ProPhoto, D50) to linear sRGB (colour and luminance range masks compare in display terms)
 uniform sampler2D uBase;     // base tone curve, 256 wide (the same table the output pass uses)
+uniform float uLook;         // look version of the recipe: 1 keeps the original maths, 2 (or more) uses the corrected HSL bands, grading luma and fine texture radius
+uniform float uTexOn;        // 1 when any block has a Texture slider away from zero (the fine detail taps are only made then)
 
 //@include geometry.glsl
 
@@ -90,7 +92,9 @@
 vec3 grade(vec3 g, int block) {
     // g is gamma encoded. Three-way colour grading.
     vec4 sh = B(block, 10), mi = B(block, 11), hi = B(block, 12), gl = B(block, 13), bl = B(block, 14);
-    float l = dot(g, vec3(0.2126, 0.7152, 0.0722));
+    // Look 2: the zone is chosen by the luminance of the working space (Y weights on linear light, then gamma), so a saturated blue is as dark as it is.
+    // Look 1 weighted the gamma values with Rec 709, which treats a deep ProPhoto blue as 0.07 bright.
+    float l = uLook > 1.5 ? pow(max(dot(toLinear(g), Y), 0.0), 1.0 / 2.2) : dot(g, vec3(0.2126, 0.7152, 0.0722));
     float blend = bl.x;          // 0..1 (default 0.5)
     float bal = bl.y;            // -1..1
     float lo = 0.33 + bal * 0.2, hiE = 0.66 + bal * 0.2;
@@ -109,7 +113,7 @@
 }
 
 // Applies one adjustment block. 'bs','bl','bd' are the local analysis samples at this pixel.
-vec3 adjust(vec3 c, int block, float bs, float bl, float bd, out float gainTone) {
+vec3 adjust(vec3 c, int block, float bs, float bf, float bl, float bd, out float gainTone) {
     vec4 b0 = B(block, 0), b1 = B(block, 1), b2 = B(block, 2), b3 = B(block, 3);
     float exposure = b0.x, contrast = b0.y, highlights = b0.z, shadows = b0.w;
     float whites = b1.x, blacks = b1.y, temp = b1.z, tint = b1.w;
@@ -134,10 +138,12 @@
 
     // Texture (fine) and clarity (broad) local contrast from the blurred layers.
     float Ym = max(luma(c), 1.0e-5);
-    float fine = log2(Ym / max(bs * gain * tone, 1.0e-5));
+    float fine = log2(Ym / max((uLook > 1.5 ? bf : bs) * gain * tone, 1.0e-5));   // look 2: fine detail against a small radius ring, look 1: against the 512 px analysis layer
     float broad = log2(Ym / max(bl * gain * tone, 1.0e-5));
     float mid = max(1.0 - abs(pow(Ym, 1.0 / 2.4) * 2.0 - 1.0), 0.0);   // 0 above white: clarity never reverses sign on super-white pixels
-    c *= exp2(clamp(fine, -2.0, 2.0) * texture * 0.01 * 0.8 + clamp(broad, -2.0, 2.0) * clarity * 0.01 * 0.8 * mid);
+    // Look 2: fine detail is limited to 0.6 stop either side so a hard edge (which the small ring sees as a huge ratio) is not boosted into a halo.
+    float fl = uLook > 1.5 ? 0.6 : 2.0;
+    c *= exp2(clamp(fine, -fl, fl) * texture * 0.01 * 0.8 + clamp(broad, -2.0, 2.0) * clarity * 0.01 * 0.8 * mid);
 
     // Dehaze via dark channel prior.
     if (abs(dehaze) > 0.001) {
@@ -169,8 +175,19 @@
     // Saturation, vibrance and the HSL colour mixer.
     vec3 hsv = rgb2hsv(clamp(g, 0.0, 1.0));
     float hueShift = 0.0, satMul = 0.0, lumMul = 0.0;
+    // Look 2: the two bands that bracket the hue cross-fade and every other band is 0, so the weights always sum to 1 and an equal
+    // slider value has an equal effect at every hue. Look 1 keeps the old overlapping bands (a pure orange got 1.64 times the slider).
+    int bk = 7, bn = 0; float bt = 0.0;
+    if (uLook > 1.5) {
+        for (int i = 0; i < 8; i++) if (hsv.x >= BAND[i]) bk = i;
+        bn = (bk + 1) & 7;
+        float c1 = BAND[bn] + (bn == 0 ? 1.0 : 0.0);
+        bt = clamp((hsv.x - BAND[bk]) / (c1 - BAND[bk]), 0.0, 1.0);
+        bt = bt * bt * (3.0 - 2.0 * bt);
+    }
     for (int i = 0; i < 8; i++) {
-        float w = bandWeight(hsv.x, i) * smoothstep(0.02, 0.2, hsv.y);
+        float bw = uLook > 1.5 ? (i == bk ? 1.0 - bt : (i == bn ? bt : 0.0)) : bandWeight(hsv.x, i);
+        float w = bw * smoothstep(0.02, 0.2, hsv.y);
         vec4 hv = B(block, 4 + (i >> 2)), sv = B(block, 6 + (i >> 2)), lv = B(block, 8 + (i >> 2));
         int j = i & 3;
         hueShift += w * hv[j];
@@ -291,7 +308,25 @@
     // makes them the blur of the corrected picture. Without it a corner of a lens corrected photo reads as detail against a dark base.
     float bs = texture(uBs, p).x * vgain, bl = texture(uBl, p).x * vgain, bd = texture(uBd, p).y * vgain;
     float gt0;
-    c = adjust(c, 0, bs, bl, bd, gt0);
+    // Look 2 Texture: the base for fine detail is the mean luminance of a ring of eight source taps whose radius is about 0.07 percent of the long edge
+    // (4 px on a 6000 px frame, never under 1.5 px), measured in source pixels so a preview and an export see the same detail. Only made when a Texture slider is set.
+    float bf = bs;
+    if (uLook > 1.5 && uTexOn > 0.5) {
+        float rl = max(1.0, max(1.5, 0.0007 * max(uSrcSize.x, uSrcSize.y)) / exp2(uLod));
+        vec2 o = vec2(rl * exp2(uLod)) / uSrcSize;
+        vec2 o2 = o * 0.70710678;
+        float acc = 0.0;
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(o.x, 0.0), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv - vec2(o.x, 0.0), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(0.0, o.y), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv - vec2(0.0, o.y), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + o2, 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv - o2, 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(o2.x, -o2.y), 0.0, 1.0), uLod).rgb);
+        acc += luma(textureLod(uSrc, clamp(guv + vec2(-o2.x, o2.y), 0.0, 1.0), uLod).rgb);
+        bf = max(acc * 0.125, 1.0e-5) * vgain;
+    }
+    c = adjust(c, 0, bs, bf, bl, bd, gt0);
     float asp = bdim.x / bdim.y;
     for (int m = 0; m < 8; m++) {
         if (m >= uNumMasks) break;
@@ -299,7 +334,7 @@
         if (a > 0.001) {
             // c already carries the global (and earlier mask) exposure and tone: measure local contrast against the same baseline
             float gtm;
-            vec3 adj = adjust(c, 1 + m, bs * gt0, bl * gt0, bd * gt0, gtm);
+            vec3 adj = adjust(c, 1 + m, bs * gt0, bf * gt0, bl * gt0, bd * gt0, gtm);
             c = mix(c, adj, a);
             gt0 = mix(gt0, gt0 * gtm, a);
         }
--- a/core/native/src/main/cpp/engine/engine.cpp
+++ b/core/native/src/main/cpp/engine/engine.cpp
@@ -439,6 +439,12 @@
     glUniform3fv(pr.u("uLensVig"), 1, p + G_LVIG);
     glUniform3f(pr.u("uLensFlags"), p[G_LTCA_ON], p[G_LVIG_ON], 0.f);
     glUniform1f(pr.u("uOverlayOn"), overlayW_ > 0 ? p[G_OVERLAY] : 0.f);
+    glUniform1f(pr.u("uLook"), p[G_LOOK]);
+    {
+        float texOn = 0.f;
+        for (int b = 0; b < kMaxBlocks; b++) if (std::fabs(p[kOffBlocks + b * kBlockFloats + S_TEXTURE]) > 0.001f) texOn = 1.f;
+        glUniform1f(pr.u("uTexOn"), texOn);
+    }
     glUniformMatrix3fv(pr.u("uToSrgb"), 1, GL_FALSE, colourMats().srgb);   // masks compare in sRGB display terms whatever the export space
     (void)bw;
     setGeometryUniforms(pr, p);
--- a/core/native/src/main/cpp/engine/params.h
+++ b/core/native/src/main/cpp/engine/params.h
@@ -28,6 +28,7 @@
     G_NUM_MASKS = 28,  // 1
     G_OVERLAY = 29,    // 1
     G_SHOWMASK = 30,   // 1: mask index to tint red for editing, -1 off
+    G_LOOK = 31,       // 1: look version for shader behaviour (1 = original maths, 2 = HSL partition, fine texture radius, ProPhoto grading luma)
     G_LDIST = 32,      // 5: lens distortion polynomial p0..p4 (r_src = r * (p0 + p1 r + p2 r^2 + p3 r^3 + p4 r^4))
     G_LDIST_ON = 37,   // 1
     G_LTCA = 38,       // 6: red (v, c, b), blue (v, c, b) scale polynomials about the source centre
@@ -63,6 +64,7 @@
         for (int i = 0; i < kCurveSize; i++) p[kOffCurves + r * kCurveSize + i] = i / 255.f;
     p[G_DETAIL + 1] = 1.f;
     p[G_SHOWMASK] = -1.f;
+    p[G_LOOK] = 1.f;   // a caller that does not know about looks gets the original maths
 }
 
 }  // namespace rl
--- a/tools/golden/golden.cpp
+++ b/tools/golden/golden.cpp
@@ -147,7 +147,11 @@
         std::string k = a.substr(0, eq);
         float v = float(atof(a.c_str() + eq + 1));
         if (blockSlots.count(k)) p[kOffBlocks + blockSlots[k]] = v;
-        else if (k == "look") look = int(v);
+        else if (k == "look") { look = int(v); p[G_LOOK] = float(v); }   // W22 look: curve and source gain (here) plus the shader maths (W23)
+        else if (k == "hslsat") { for (int b = 0; b < 8; b++) p[kOffBlocks + S_MIX_SAT + b] = v; }   // all eight HSL saturation sliders
+        else if (k == "hsllum") { for (int b = 0; b < 8; b++) p[kOffBlocks + S_MIX_LUM + b] = v; }
+        else if (k == "hslband") { int b = int(v) / 1000; float amt = float(int(v) % 1000) - 500.f; p[kOffBlocks + S_MIX_SAT + b] = amt; }   // band * 1000 + 500 + amount
+        else if (k == "gshadows") { float h0 = 0, s0 = 0; sscanf(a.c_str() + eq + 1, "%f,%f", &h0, &s0); p[kOffBlocks + S_GRADE_SHADOWS] = h0; p[kOffBlocks + S_GRADE_SHADOWS + 1] = s0; }
         else if (k == "sharpen") p[G_DETAIL] = v;
         else if (k == "nrl") p[G_NR] = v;
         else if (k == "nrc") p[G_NR + 1] = v;
```

## 5. Kotlin hunk (not compiled)
`core/render/src/main/kotlin/app/rawline/core/render/RenderParams.kt`: in `object P` add `const val G_LOOK = 31` after `G_SHOWMASK`; in `build(...)`, after the `G_OVERLAY` line, add `out[P.G_LOOK] = recipe.lookVersion.toFloat()`. The heal source render (`forHealSource` and the Healer and Denoiser callers that clone a recipe) must copy `lookVersion` so a patch is rendered with the same maths as the picture. In `RenderTest.kt` add `"G_LOOK" to P.G_LOOK` to the constant map that is compared with `params.h`, and a test `buildWritesTheLookIntoTheParams`: `assertEquals(1f, build(EditRecipe(lookVersion = 1), 1)[P.G_LOOK])` and `assertEquals(2f, build(EditRecipe(lookVersion = 2), 1)[P.G_LOOK])`. Depends on W22 (`EditRecipe.lookVersion`).

## 6. The check script (run and verified: passes for look 2, fails 3 of 5 for look 1, which is the negative control)
Save as `tools/golden/look2_checks.py` (`--key look` after W22, `--key shader` before); it needs W21's `tools/colour/make_dng.py` (the script looks in `../colour`). It writes two synthetic DNGs into the work folder (a 24 hue sweep and a 3000 x 2000 texture chart, 12 MB, built in 6 s and not committed).
```python
#!/usr/bin/env python3
"""Look 2 shader checks (W23). Standard library only. Needs tools/colour/make_dng.py (W21) next to it or on PYTHONPATH.
usage: look2_checks.py GOLDEN_BIN WORKDIR [--look N] [--key shader|look]     (N = value given to the key, default 2; --look 1 is the negative control; `look` is the W22 golden key that also sets G_LOOK, `shader` sets only the shader maths)
Exit 0 when every check passes. Prints one line per check."""
import sys, os, math, colorsys, subprocess
KEY = "shader"
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here); sys.path.insert(0, os.path.join(here, "..", "colour"))
import make_dng as m

def read_ppm(p):
    d = open(p, "rb").read(); a = d.split(b"\n", 3); w, h = map(int, a[1].split()); return w, h, a[3]
def render(golden, dng, ppm, args, width):
    r = subprocess.run([golden, dng, ppm, str(width), "full", *args], capture_output=True, text=True)
    if r.returncode: raise SystemExit("golden failed: " + r.stderr[-300:])
    return read_ppm(ppm)
def patch_rgb(img, idx, scale):
    w, h, px = img; ps = int(m.PATCH * scale); cx = (idx % 6) * ps + ps // 2; cy = (idx // 6) * ps + ps // 2
    s = [0, 0, 0]; n = 0
    for y in range(cy - 10, cy + 11):
        for x in range(cx - 10, cx + 11):
            for c in range(3): s[c] += px[(y * w + x) * 3 + c]
            n += 1
    return [v / n for v in s]

def make_hue_sweep(path):
    patches = [tuple(m.srgb_eotf(v) for v in colorsys.hsv_to_rgb(i / 24.0, 0.5, 0.6)) for i in range(24)]
    M = m.balanced_matrix(m.M_S5M2)
    m.tiff_dng(path, m.make_scene(patches, 0.8, M), M, (1.0, 1.0, 1.0))

def make_texture(path):
    """3000 x 2000: 6 px stripes (x < 1000), a soft ramp with an 80 px wave (1000 to 2000), a hard step at x = 2500."""
    w0, h0 = m.W, m.H; m.W, m.H = 3000, 2000
    def level(x):
        if x < 1000: return 0.15 + (0.03 if (x // 3) % 2 == 0 else -0.03)
        if x < 2000: return 0.10 + 0.30 * ((x - 1000) / 1000.0) + 0.04 * math.sin(2 * math.pi * (x - 1000) / 80.0)
        return 0.10 if x < 2500 else 0.50
    line = [min(m.WHITE, max(0, int(round(512 + level(x) * 0.8 * (m.WHITE - 512))))) for x in range(m.W)]
    M = m.balanced_matrix(m.M_S5M2)
    m.tiff_dng(path, [line] * m.H, M, (1.0, 1.0, 1.0))
    m.W, m.H = w0, h0

def hsl_equal_strength(g, work, look):
    d = os.path.join(work, "hue_sweep.dng")
    if not os.path.exists(d): make_hue_sweep(d)
    a = render(g, d, os.path.join(work, "h_hsl.ppm"), ["hslsat=20", KEY + "=%d" % look], 600)
    b = render(g, d, os.path.join(work, "h_glob.ppm"), ["saturation=20", KEY + "=%d" % look], 600)
    worst = max(max(abs(x - y) for x, y in zip(patch_rgb(a, i, 0.5), patch_rgb(b, i, 0.5))) for i in range(24))
    return worst, "HSL saturation sliders all at +20 equal the global Saturation +20 on 24 hues (worst patch, levels, limit 1)", worst <= 1.0

def hsl_band_confined(g, work, look):
    d = os.path.join(work, "hue_sweep.dng")
    base = render(g, d, os.path.join(work, "h_b0.ppm"), [KEY + "=%d" % look], 600)
    one = render(g, d, os.path.join(work, "h_b1.ppm"), ["hslband=3550", KEY + "=%d" % look], 600)   # green band +50
    far = max(max(abs(x - y) for x, y in zip(patch_rgb(one, i, 0.5), patch_rgb(base, i, 0.5))) for i in list(range(0, 4)) + list(range(12, 24)))
    return far, "the green band alone leaves hues outside red..aqua untouched (worst, levels, limit 1)", far <= 1.0

def texture(g, work, look):
    d = os.path.join(work, "texture3k.dng")
    if not os.path.exists(d): make_texture(d)
    def row(img, x0, x1):
        w, h, px = img; ys = range(900, 1100, 20)
        rs = [[px[(y * w + x) * 3 + 1] for x in range(x0, x1)] for y in ys]
        return [sum(r[i] for r in rs) / len(rs) for i in range(len(rs[0]))]
    def metrics(tex):
        img = render(g, d, os.path.join(work, "t.ppm"), ["texture=%d" % tex, KEY + "=%d" % look], 3000)
        st = row(img, 100, 900); stripe = max(st) - min(st)
        wv = row(img, 1100, 1900); n = 80
        ma = [sum(wv[max(0, i - n // 2):i + n // 2 + 1]) / len(wv[max(0, i - n // 2):i + n // 2 + 1]) for i in range(len(wv))]
        wave = max(abs(a - b) for a, b in zip(wv[n:-n], ma[n:-n])) * 2
        ed = row(img, 2300, 2700); lo = sum(ed[20:80]) / 60
        under = lo - min(ed[160:200])
        return stripe, wave, under
    s0, w0, u0 = metrics(0); s1, w1, u1 = metrics(50)
    out = []
    out.append((s1 - s0, "Texture +50 raises 6 px stripe contrast by at least 6 levels", s1 - s0 >= 6))
    out.append((w1 - w0, "Texture +50 leaves an 80 px wave alone (change at most 1 level)", abs(w1 - w0) <= 1.0))
    out.append((u1 - u0, "Texture +50 undershoot at a 5:1 step is at most 10 levels", (u1 - u0) <= 10.0))
    return out

def main():
    global KEY
    g, work = sys.argv[1], sys.argv[2]; look = 2
    if "--key" in sys.argv: KEY = sys.argv[sys.argv.index("--key") + 1]
    if "--look" in sys.argv: look = int(sys.argv[sys.argv.index("--look") + 1])
    os.makedirs(work, exist_ok=True)
    results = [hsl_equal_strength(g, work, look), hsl_band_confined(g, work, look)] + texture(g, work, look)
    ok = True
    for v, text, passed in results:
        print("%s  %s: %.2f" % ("ok  " if passed else "FAIL", text, v)); ok = ok and passed
    sys.exit(0 if ok else 1)
main()
```

## 7. Wiring
`run-golden.sh`: after the property checks add
```
python3 "$ROOT/tools/golden/look2_checks.py" /tmp/golden/golden "$W/look2" --look 2 --key look || fail=1
```
and a negative control that must fail (so the check cannot go blind):
```
if python3 "$ROOT/tools/golden/look2_checks.py" /tmp/golden/golden "$W/look2" --look 1 --key look >/dev/null; then echo "FAIL look 2 checks pass on look 1: the checks are blind"; fail=1; fi
```
Add to the SCENES list (references via `--update`, reviewed by eye on the phone image of the sample): `"hsl2|shader=2 hslsat=40 hsllum=-20"`, `"texture2|shader=2 texture=60"`, `"grade2|shader=2 gshadows=0,1 exposure=0.4"`. The existing look 1 scenes are not changed.
Note the "green band confined" check also passes at look 1 (the old reach is only 0.13 either side), so it guards look 2 but does not discriminate.

## 8. Order of work
1. Apply the section 4 patch, run `tools/golden/run-golden.sh`: every existing scene must pass unchanged (the proof of D1).
2. Add `look2_checks.py` and the wiring (section 7); check look 2 passes and the control fails.
3. Kotlin hunk (needs W22 for the field), RenderTest, a full `./gradlew testDebugUnitTest`.
4. `--update` only the three new scenes; look at them on the sample.
5. Phone: Jai compares one HSL edit before and after "Update look" on three photos (a green landscape, a skin tone shot, a blue sky). The report gives `studio`-style numbers only from the Copy report; no speed or look claim without it. Texture cost: record `render_ms` with Texture +60 at look 1 and 2 (8 extra taps).

## 9. Risks
- Visible change to every edit that uses HSL, Texture or grading once Jai taps Update look; the gating and the explicit row are the mitigation, and undo restores it.
- The Texture ring costs 8 fetches per pixel only while a Texture slider is set; on the S24 Ultra this is expected to be small, unmeasured.
- Ring radius is a design number (0.07 percent of the long edge); it is the one value Jai may want to change after looking, and it is a constant in one line.

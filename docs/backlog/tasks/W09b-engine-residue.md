# W09b Engine residue after the merged audit fixes

Written 6 Oct 2026 against main 6164874 (engine commits ee38b8b, 3ad30b3, 3ad453d, cb58ce4 and 516e4a3 are in). Small task: two items that are worth a worker now, both with tested code, and a short list of what to leave alone and why. Starts after W22 only if W22 is already running (both touch `lowres.frag`: the AE-051 hunk adds the overlay to the analysis pass, W22 adds the source gain multiply; the two hunks do not overlap, apply the later one by hand).

## 0. Corrections to what I wrote earlier

- `W09-engine-fixes.md` section 3.8 (mip chain at 1:1 export, AE-025) is WRONG and was rightly not done: the local analysis pass samples the source at a high mip level (about 3.5 for a 24 MP frame) even for a 1:1 export, so dropping the mips would change the local tone of every export. Do not implement it. The saving it promised (about a third of the source texture) is not available without building the analysis image another way, which is not worth the risk.
- BACKLOG W09b (the earlier version) listed AE-017, AE-024, AE-026: those are merged (cb58ce4).

## 1. Item A: local contrast ignores heal and remove patches (AE-051, size S)

Problem, measured: the Texture and Clarity weights compare the pixel (which includes the heal overlay) with the local analysis layers (built from the source without the overlay). A flat grey picture with a heal patch over its left half and Texture and Clarity at 80: the patch interior goes from 216 to 251 (+35 levels) while the untouched half stays at 171. In practice a patch that is brighter or darker than its surround gets a spurious local contrast boost, and so does everything the user paints over with Remove.
Fix (tested): `lowres.frag` lays the overlay over the sampled source exactly as `main.frag` does (after the source gain, before any vignetting gain: in the analysis the vignetting gain is applied through `vgain` in `main.frag`, so no change is needed there); the engine binds the overlay texture and `uOverlayOn` for the analysis pass and invalidates the analysis cache whenever the overlay changes (`setOverlay`, `updateOverlayRegion`). Result with the fix: interior 216 without and with Texture and Clarity 80 (difference 0); the edge of the patch shows a normal local contrast halo (220, 196, 164 across the boundary), which is a real edge. Cost: the analysis is rebuilt after every heal stroke (a 512 px pass and four blurs: small).
Test: `overlay_analysis_check.py` (section 5) must fail on main (interior difference 35) and pass after the patch; add it to `tools/golden/run-golden.sh` next to the existing property checks.
Patch: `ae051.patch` (`git apply --check` passes on the current tree).

## 2. Item B: orientation regression test and the LibRaw flip map (AE-041, size S)

Problem as audited: "mirrored EXIF orientations and the mirror then rotate combination are wrong in the shader". Measured: they are not. The golden key `orient=N` (the exact (rot90, flipH) pairs of `RenderParams.orientationToRotFlip`) rendered through the real shader gives, for all eight EXIF orientations of an asymmetric 60 x 40 picture, exactly the pixels the definition says (output sizes 60 x 40 and 40 x 60; the only differences are 8 level steps on the edge pixels of the marker block, single pixels). A deliberately wrong flip for orientation 5 fails with 2,280 pixels off by up to 255 levels, so the test can fail. So the shader and the Kotlin map are right; what remains is a regression test and one real gap: `decodeRaw` maps only LibRaw flips 3, 5 and 6 to orientations (rotations); mirrored flips 1, 2, 4 and 7 fall back to "no rotation". Dormant for Panasonic and Samsung (cameras never mirror) but cheap to close.
Change: `exifOrientationFromLibrawFlip(int)` (header snippet in section 5, with its host test) replaces the inline `flip == 3 ? 3 : flip == 5 ? 8 : flip == 6 ? 6 : 1` in `raw_decode.cpp`; the table is the inverse of dcraw's `"50132467"[exif & 7]`, round trip tested for all eight.
Test: `orient_check.py` against the golden binary with the `orient=` key (patch `orient_key.patch`); add to `run-golden.sh`. Expected output: eight PASS lines, `size ok`, 0 pixels off by more than 12.

## 3. Leave alone, with reasons (no worker needed)

| Item | Why |
|---|---|
| AE-025 mip chain at export | Wrong premise, see section 0. |
| AE-050 remove patch is 512 px | The LaMa model input is 512; a bigger patch needs tiling the model, a feature not a fix. Phone comparison first. |
| AE-044 and AE-054 denoise preview versus export, highlight headroom | Need a phone side by side and, for headroom, a decision about the working range after look 2 (W22 changes the headroom: re-check afterwards). |
| AE-052 GPU delegate precision | Needs the phone. |
| AE-049, AE-045, AE-047 buffer copies | Memory polish without a measured problem; the memory table of W11 decides. |
| AE-040 read by descriptor | Compressed DNG via zlib is in (2c85bf2); the path reopen works on every device seen so far; keep BK-468 for when a provider fails. |
| AE-013 HSL partition, AE-030, AE-033, AE-020, AE-032, AE-048 | Look changing design calls; only behind a look version (see W22) and Jai's eyes. |

## 4. Order and exit

Item A, then Item B, one branch, one commit each. Exit: CI green, `run-golden.sh` identical for every existing scene, the two new checks in `run-golden.sh`, docs/DECISIONS.md notes the AE-025 verdict (the analysis needs the mips) and the AE-041 verdict (verified correct).

## 5. Tested source

### overlay_analysis_check.py (tools/golden/)
```python
#!/usr/bin/env python3
"""AE-051: local contrast (Texture, Clarity) must be measured on the picture with its heal patches, so the inside of a patch is not boosted.
Flat grey picture, the golden `overlay=` patch over its left half, Texture and Clarity 80: the patch interior must equal the same render without Texture and Clarity.
usage: overlay_analysis_check.py [golden binary]. Standard library only."""
import os, subprocess, sys, tempfile
G = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")
W, H = 600, 400
tmp = tempfile.mkdtemp(); src = os.path.join(tmp, "flat.ppm")
open(src, "wb").write(b"P6\n%d %d\n255\n" % (W, H) + bytes([100]) * (W * H * 3))
def row(*args):
    out = os.path.join(tmp, "o.ppm")
    subprocess.run([G, src, out, str(W), "half", *args], capture_output=True, check=True)
    d = open(out, "rb").read().split(b"\n", 3)[3]
    return lambda x: d[(200 * W + x) * 3]
plain, boosted = row("overlay=0.3"), row("overlay=0.3", "texture=80", "clarity=80")
inside = abs(boosted(100) - plain(100)); far = abs(boosted(500) - plain(500))
print("patch interior: %d without, %d with Texture and Clarity 80 (difference %d); untouched side difference %d" % (plain(100), boosted(100), inside, far))
ok = inside <= 2 and far <= 2
print("PASS" if ok else "FAIL (the patch is boosted: the analysis does not see it)")
sys.exit(0 if ok else 1)
```
### orient_check.py (tools/golden/)
```python
#!/usr/bin/env python3
"""Renders an asymmetric picture under each of the 8 EXIF orientations and compares with the definition of the orientation. Standard library only.
usage: orient_check.py [golden binary]"""
import os, subprocess, sys, tempfile
G = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")
W, H = 60, 40
def S(x, y):            # stored picture: x ramps red, y ramps green, a blue marker in the top left corner block
    return (int(x * 255 / (W - 1)), int(y * 255 / (H - 1)), 255 if (x < 8 and y < 8) else 40)
# D(x, y) in display coordinates, as (source x, source y), for the output size (ow, oh)
EXPECT = {1: (W, H, lambda x, y: (x, y)), 2: (W, H, lambda x, y: (W-1-x, y)), 3: (W, H, lambda x, y: (W-1-x, H-1-y)), 4: (W, H, lambda x, y: (x, H-1-y)),
          5: (H, W, lambda x, y: (y, x)), 6: (H, W, lambda x, y: (y, H-1-x)), 7: (H, W, lambda x, y: (W-1-y, H-1-x)), 8: (H, W, lambda x, y: (W-1-y, x))}
tmp = tempfile.mkdtemp(); src = os.path.join(tmp, "s.ppm")
open(src, "wb").write(b"P6\n%d %d\n255\n" % (W, H) + b"".join(bytes(S(x, y)) for y in range(H) for x in range(W)))
def render(o, ow):
    out = os.path.join(tmp, "o%d.ppm" % o)
    subprocess.run([G, src, out, str(ow), "full", "orient=%d" % o], capture_output=True, text=True)
    d = open(out, "rb").read().split(b"\n", 3); w, h = map(int, d[1].split()); return w, h, d[3]
rw, rh, ref = render(1, W)                      # the identity render is the oracle: same pixels, same curve, no orientation
assert (rw, rh) == (W, H)
bad = 0
for o, (ow, oh, f) in EXPECT.items():
    w, h, px = render(o, ow)
    ok = (w, h) == (ow, oh); worst = 0; wrong = 0
    if ok:
        for y in range(h):
            for x in range(w):
                sx, sy = f(x, y)
                a_ = px[(y * w + x) * 3:(y * w + x) * 3 + 3]; b_ = ref[(sy * W + sx) * 3:(sy * W + sx) * 3 + 3]
                d_ = max(abs(a_[i] - b_[i]) for i in range(3)); worst = max(worst, d_); wrong += d_ > 12
    print("orientation %d: output %dx%d %s, worst difference from the definition %d levels, %d pixels off by more than 12  %s" % (o, w, h, "size ok" if ok else "SIZE WRONG (expected %dx%d)" % (ow, oh), worst, wrong, "PASS" if ok and wrong == 0 else "FAIL"))
    bad += 0 if ok and wrong == 0 else 1
sys.exit(1 if bad else 0)
```
### exifOrientationFromLibrawFlip (core/native/src/main/cpp/orient_map.h) and its host test (tools/golden/orient_map_test.cpp, build with `g++ -std=c++17 -I core/native/src/main/cpp`)
```cpp
#pragma once
// LibRaw's sizes.flip (dcraw's bit field: 1 mirror left to right, 2 mirror top to bottom, 4 transpose) to the EXIF orientation 1..8 the engine
// understands. dcraw builds flip from EXIF with flip = "50132467"[exif & 7] - '0'; this is its inverse.
inline int exifOrientationFromLibrawFlip(int flip) {
    switch (flip) {
        case 0: return 1;
        case 1: return 2;   // mirror horizontal
        case 3: return 3;   // rotate 180
        case 2: return 4;   // mirror vertical
        case 4: return 5;   // transpose
        case 6: return 6;   // rotate 90 clockwise
        case 7: return 7;   // transverse
        case 5: return 8;   // rotate 90 counter clockwise
        default: return 1;
    }
}
```
```cpp
#include <cstdio>
#include <cstring>
#include "orient_map.h"
int main() {
    const char *dcraw = "50132467";   // flip for EXIF orientation (exif & 7): 0 -> 8, 1 -> 1, ..., 7 -> 7
    int fails = 0;
    for (int exif = 1; exif <= 8; exif++) {
        int flip = dcraw[exif & 7] - '0';
        int back = exifOrientationFromLibrawFlip(flip);
        if (back != exif) { std::printf("FAIL exif %d -> flip %d -> %d\n", exif, flip, back); fails++; }
    }
    if (exifOrientationFromLibrawFlip(99) != 1) { std::printf("FAIL unknown flip\n"); fails++; }
    std::printf(fails ? "FAILED\n" : "orientation map: all 8 round trip\n");
    return fails ? 1 : 0;
}
```
### ae051.patch
```diff
--- a/core/native/src/main/cpp/shaders/lowres.frag
+++ b/core/native/src/main/cpp/shaders/lowres.frag
@@ -6,4 +6,6 @@
 uniform sampler2D uSrc;
 uniform float uLod;
+uniform sampler2D uOverlay;   // heal and remove patches, premultiplied, source space (the same layer main.frag lays over the pixel)
+uniform float uOverlayOn;
 //@include geometry.glsl
 const vec3 Y = vec3(0.28807, 0.71184, 0.0000857);
@@ -11,4 +13,5 @@
     vec3 g = srcUv(vUv);
     vec3 c = textureLod(uSrc, clamp(g.xy, 0.0, 1.0), uLod).rgb;
+    if (uOverlayOn > 0.5) { vec4 o = texture(uOverlay, clamp(g.xy, 0.0, 1.0)); c = c * (1.0 - o.a) + o.rgb; }   // local tone is measured on the picture with its repairs
     c = max(c, 0.0);
     oColor = vec4(dot(c, Y), min(c.r, min(c.g, c.b)), 0.0, 1.0);
--- a/core/native/src/main/cpp/engine/engine.cpp
+++ b/core/native/src/main/cpp/engine/engine.cpp
@@ -282,8 +282,9 @@
     glDeleteTextures(1, &overlayTex_);
     overlayTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
-    if (!rgbaHalfBytes) { glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1); overlayW_ = 0; return; }
+    if (!rgbaHalfBytes) { glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1); overlayW_ = 0; invalidateAnalysis(); return; }
     glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, w, h);
     glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_HALF_FLOAT, rgbaHalfBytes);
     overlayW_ = w;
+    invalidateAnalysis();   // the analysis layers include the overlay
 }
 
@@ -293,4 +294,5 @@
     glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
     glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_HALF_FLOAT, data);
+    invalidateAnalysis();
 }
 
@@ -372,4 +374,9 @@
     glBindTexture(GL_TEXTURE_2D, srcTex_);
     glUniform1i(lowres_.u("uSrc"), 0);
+    glActiveTexture(GL_TEXTURE1);
+    glBindTexture(GL_TEXTURE_2D, overlayTex_);
+    glUniform1i(lowres_.u("uOverlay"), 1);
+    glUniform1f(lowres_.u("uOverlayOn"), overlayW_ > 0 ? p[G_OVERLAY] : 0.f);
+    glActiveTexture(GL_TEXTURE0);
     glUniform1f(lowres_.u("uFlipY"), 0.f);
     setGeometryUniforms(lowres_, p);
```
### orient_key.patch (golden.cpp)
```diff
--- a/tools/golden/golden.cpp
+++ b/tools/golden/golden.cpp
@@ -146,4 +146,8 @@
         float v = float(atof(a.c_str() + eq + 1));
         if (blockSlots.count(k)) p[kOffBlocks + blockSlots[k]] = v;
+        else if (k == "orient") {   // EXIF orientation 1..8 as RenderParams.orientationToRotFlip maps it: (rot90 clockwise, flipH)
+            static const int rotOf[9] = {0, 0, 0, 2, 2, 1, 1, 3, 3}; static const int flipOf[9] = {0, 0, 1, 0, 1, 1, 0, 1, 0};
+            int o = int(v); p[G_GEO + 3] = float(rotOf[o]); p[G_GEO + 1] = float(flipOf[o]);
+        }
         else if (k == "sharpen") p[G_DETAIL] = v;
         else if (k == "nrl") p[G_NR] = v;
```

## 9. Both patches rebased for a tree that already has W22 (the order in DISPATCH.md; checked 6 Oct 2026 at b5b06ac plus the two W22 patches)

The two patches above (`ae051.patch`, `orient_key.patch`) apply to b5b06ac but not after W22: W22 adds `uSrcGain` to `lowres.frag` and moves lines in `golden.cpp`. The combined patch below (engine.cpp, lowres.frag and golden.cpp) applies with `git apply --check` after W22, and `W23` section 4b applies on top of it. Difference from `orient_key.patch`: the `orient` key branch sits after the `mark` key instead of before `sharpen`, so it does not touch the lines W23 edits. If the golden key table was changed again (DISPATCH step 6) put the same four lines after any `else if (k == "...")` branch that W23 does not edit. Copy of the file: `docs/backlog/patches/w09b-after-w22.patch`.

```diff
--- a/core/native/src/main/cpp/engine/engine.cpp
+++ b/core/native/src/main/cpp/engine/engine.cpp
@@ -281,10 +281,11 @@ void Engine::setLayer(int index, const uint8_t *alpha, int w, int h) {
 void Engine::setOverlay(const uint8_t *rgbaHalfBytes, int w, int h) {
     glDeleteTextures(1, &overlayTex_);
     overlayTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR);
-    if (!rgbaHalfBytes) { glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1); overlayW_ = 0; return; }
+    if (!rgbaHalfBytes) { glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, 1, 1); overlayW_ = 0; invalidateAnalysis(); return; }
     glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, w, h);
     glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_HALF_FLOAT, rgbaHalfBytes);
     overlayW_ = w;
+    invalidateAnalysis();   // the analysis layers include the overlay
 }
 
 void Engine::updateOverlayRegion(int x, int y, int w, int h, const uint8_t *data) {
@@ -292,6 +293,7 @@ void Engine::updateOverlayRegion(int x, int y, int w, int h, const uint8_t *data
     glBindTexture(GL_TEXTURE_2D, overlayTex_);
     glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
     glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_HALF_FLOAT, data);
+    invalidateAnalysis();
 }
 
 void Engine::setBaseCurve(const float *lut) {
@@ -371,6 +373,11 @@ void Engine::runAnalysis(const float *p) {
     glActiveTexture(GL_TEXTURE0);
     glBindTexture(GL_TEXTURE_2D, srcTex_);
     glUniform1i(lowres_.u("uSrc"), 0);
+    glActiveTexture(GL_TEXTURE1);
+    glBindTexture(GL_TEXTURE_2D, overlayTex_);
+    glUniform1i(lowres_.u("uOverlay"), 1);
+    glUniform1f(lowres_.u("uOverlayOn"), overlayW_ > 0 ? p[G_OVERLAY] : 0.f);
+    glActiveTexture(GL_TEXTURE0);
     glUniform1f(lowres_.u("uFlipY"), 0.f);
     setGeometryUniforms(lowres_, p);
     float ratio = std::max(float(ow) / lw, float(oh) / lh);
--- a/core/native/src/main/cpp/shaders/lowres.frag
+++ b/core/native/src/main/cpp/shaders/lowres.frag
@@ -5,11 +5,14 @@ in vec2 vUv;
 out vec4 oColor;
 uniform sampler2D uSrc;
 uniform float uLod;
+uniform sampler2D uOverlay;   // heal and remove patches, premultiplied, source space (the same layer main.frag lays over the pixel)
+uniform float uOverlayOn;
 uniform float uSrcGain;
 //@include geometry.glsl
 const vec3 Y = vec3(0.28807, 0.71184, 0.0000857);
 void main() {
     vec3 g = srcUv(vUv);
+    if (uOverlayOn > 0.5) { vec4 o = texture(uOverlay, clamp(g.xy, 0.0, 1.0)); c = c * (1.0 - o.a) + o.rgb; }   // local tone is measured on the picture with its repairs
     vec3 c = textureLod(uSrc, clamp(g.xy, 0.0, 1.0), uLod).rgb * uSrcGain;
     c = max(c, 0.0);
     oColor = vec4(dot(c, Y), min(c.r, min(c.g, c.b)), 0.0, 1.0);
--- a/tools/golden/golden.cpp
+++ b/tools/golden/golden.cpp
@@ -161,6 +161,10 @@ int main(int argc, char **argv) {
         else if (k == "ksh") p[G_GEO2 + 1] = v;
         else if (k == "autofit") autofit = v > 0.5f;
         else if (k == "mark") mark = v > 0.5f;
+        else if (k == "orient") {   // EXIF orientation 1..8 as RenderParams.orientationToRotFlip maps it: (rot90 clockwise, flipH)
+            static const int rotOf[9] = {0, 0, 0, 2, 2, 1, 1, 3, 3}; static const int flipOf[9] = {0, 0, 1, 0, 1, 1, 0, 1, 0};
+            int o = int(v); p[G_GEO + 3] = float(rotOf[o]); p[G_GEO + 1] = float(flipOf[o]);
+        }
         else if (k == "lensfill") {   // strong synthetic profile (poly3, k1 = 0.06) that samples beyond the frame edge without a crop fit
             p[G_LDIST] = 1.f - 0.06f; p[G_LDIST + 2] = 0.06f; p[G_LDIST_ON] = v;
         }
```

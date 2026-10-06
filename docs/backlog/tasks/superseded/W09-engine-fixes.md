# W09 Engine fixes that are not the colour contract (resource handling, lens geometry, memory)

> CORRECTION: section 3.8 (mips flag, AE-025) is wrong and must not be implemented: the local analysis samples the source at a high mip level even for a 1:1 export (see W09b-engine-residue.md section 0).
>
> STATUS 6 Oct 2026, main 3ad453d: SUPERSEDED, DO NOT DISPATCH. A worker merged this scope while the file was being written (ee38b8b, 3ad30b3, 3ad453d: items 1 to 7, 7b, 9 to 13, with a better in-place decode than the lazy one drafted in 3.6, and `engine_test.cpp` with the same checks). Still open from this file, now task W09b in BACKLOG.md: 3.3b (AI mask input frame with the lens), 3.8 (mips flag), the heal overlay base curve for finished pictures (AE-026) and the prefetch leak (AE-024). Keep the file for its measured acceptance numbers (section 3.1) and the `engine_checks.py` merge gate.

Written 6 Oct 2026 against main 0620e2a plus another worker's uncommitted tree (see section 0). Every claim below was measured or compiled here; the patches in section 8 were checked with `git apply --check` against the tree as it stood, the C++ pieces were built and run on Mesa llvmpipe, and the pure Kotlin pieces were compiled with the Kotlin 2.4 compiler from the Gradle distribution and run under JUnit (14 tests). The Android-dependent Kotlin edits (EditorSession, Exporter, Denoiser, JNI) are written carefully but not compiled here: CI is their check.

Backlog links: BK-459, BK-460, BK-464, BK-465, BK-468, BK-469 and the audit items AE-002 to AE-011, AE-017, AE-025, AE-026, AE-028, AE-047. This file replaces the NEXT 25 entry "W09 Slider, pan and zoom" only in part: the full GPU histogram reduction and the pan and zoom transform stay in that task; this one fixes what each slider tick costs today and everything else on the list below.

## 0. Read first: what is already being done in the working tree

At the time of writing another worker has uncommitted changes in `engine.cpp`, `engine.h`, `halfs.h`, `jni_engine.cpp`, `Native.kt`, `main.frag`, `out.frag`, `Healer.kt`, `Exporter.kt`, `golden.cpp`, `run-golden.sh`, plus new `halfs_test.cpp`, `numcheck.py`, `HealGeometryTest.kt` and golden references `dehazeneg`, `lenslocal`, `grade`. That is the shader and 16 bit export bundle. It already contains the fixes for items 1, 2, 3 (the Healer half), 4 and 13 below. Do not redo them. Start this task only after that worker has merged to main, then rebase: the patches here apply to that tree as it stands now (`git apply --check` passes) and touch different hunks (setSource, release, ensureTarget, the output pass target selection), but `renderRegion` and `drawOutput` are being reshaped by them, so if a hunk fails, re-express it from the intent in section 3.

Acceptance numbers for the four overlapping items were measured on both trees with `engine_checks.py` (section 7): the in-progress tree passes all four checks, unmodified main fails all four. Use that script as the merge gate for their work and as the regression test afterwards.

## 1. Item list, in the order to do them

| # | Item | Audit | Status at writing | Size |
|---|---|---|---|---|
| 1 | Negative Dehaze adds haze instead of contrast | AE-002 | in the other worker's tree, verified | done upstream |
| 2 | Lens vignetting gain reaches the local analysis (Texture, Clarity) | AE-003 | in the other worker's tree, verified | done upstream |
| 3a | Heal, clone and remove strokes use the lens warp | AE-004 | in the other worker's tree (Healer.kt, HealGeometryTest.kt) | done upstream |
| 3b | AI mask input frame uses the lens and manual distortion | AE-017 | this task | S |
| 4 | Heal overlay composited before the vignetting gain | AE-005 | in the other worker's tree, verified | done upstream |
| 5 | A failed upload destroys the source | AE-006 | this task, patch tested | S |
| 6 | 24 MP and 45 MP decode builds the pixels twice | AE-007 | this task, patch tested | M |
| 7 | AI denoise holds every tile result on the Java heap | AE-009 | this task, class tested | M |
| 7b | Export of a large JPEG or PNG decodes at full size | AE-008 | this task, helper tested | S |
| 8 | Export builds a mip chain it never samples | AE-025 | this task, patch tested | S |
| 9 | Each slider tick reallocates the render targets | AE-010 | this task, patch tested | S |
| 10 | Context loss: the dead engine is destroyed inside the new context | AE-011 | this task, patch tested (finding downgraded, see 5.10) | S |
| 11 | `OutputStream.nullOutputStream()` needs API 33, minSdk is 31 | docs/AUDIT.md lint | this task, class tested | S |
| 12 | Lint `HalfFloat` errors and per-pixel allocation in the heal overlay | AE-047 | this task, helper tested | S |
| 13 | `floatToHalf` rounds 65520 and up to Infinity | AE-028 | in the other worker's tree (halfs.h, halfs_test.cpp); Kotlin twin here | done upstream (C++), S (Kotlin) |

Order for this task: 11, 12 (lint and the Kotlin half helper), 5, 10, 9, 8 (one engine change set), 6, 7, 7b, 3b. Items 5, 8, 9, 10 share `engine.h`/`engine.cpp` and one JNI signature change, so they go in one commit with `engine_test.cpp`.

## 2. Rules for this task

- Branch per task, merge when CI is green (CLAUDE.md). `./gradlew testDebugUnitTest`, `./gradlew :core:render:lintDebug :core:ml:lintDebug`, and for any shader or engine change `tools/golden/run-golden.sh` with every existing reference identical.
- No new golden references are needed. The existing ones must stay byte-identical (the engine hunks change resource handling only).
- Every engine call stays on the GL thread. `Native.engineAbandon` is called on the GL thread too (in `onSurfaceCreated`).
- Memory numbers below are from llvmpipe on this machine (CPU side decode) and are not phone results. Do not write a speed or memory claim into docs/PERF.md without the phone's Copy report (CLAUDE.md).

## 3. Per item: where, what, test

### 3.1 to 3.4 and 3.13 (done in the other worker's tree): acceptance only

Merge gate for their work, and the permanent regression test: `engine_checks.py` (section 7). It renders flat and edge pictures through the real GLSL and asserts:
- Dehaze -100 on a flat grey 40 picture goes from 83 to 234 (model: `TABLE(oetf(0.5 x lin + 0.45))`; measured 234.0, model 234.0). On unmodified main it stays 83: the old formula is the identity on flat areas and only boosts contrast at edges.
- Dehaze -100 on a 40 | 200 edge: no black halo. Unmodified main reads 0 at x=290 (crushed) and 245 on the bright side; the fix reads 234 and 249 (uniform lift).
- Lens vignetting with Texture and Clarity 60 on a flat grey picture equals the picture without them: corner 234.8 against 234.8. Unmodified main reads 251.5 (a +16.7 level boost from the analysis not seeing the 6.8x gain).
- A heal patch at the corner (built from the uncorrected source, so value 0.02122 for grey 40) matches the surround: patch 125.4, surround 125.7. Unmodified main: patch 83.0 against surround 125.7 (a visible dark blob).

For 13 (C++ `floatToHalf`): compared with Python's IEEE `struct.pack('e')` on 219,205 values (random bit patterns, plus the 65000 to 67000 range): unmodified code has 170 mismatches, all Infinity at 65520 and above; the fix `if ((h & 0x7FFF) >= 0x7C00) h = sign | 0x7BFF` leaves one mismatch, an exact tie (3.4111328 rounds up here, half to even in Python), which is harmless. The other worker's version (`if (h >= 0x7C00u) h = 0x7BFFu` before applying the sign) is equivalent.

### 3.5 Failed upload destroys the source (AE-006, size S)

Where: `engine.cpp` `Engine::setSource` (it deletes `srcTex_`, allocates, then sets `srcW_/srcH_` before checking errors); `EditorSession.kt` `ensureFull` (result ignored) and `reloadSource` (result ignored); JNI `engineSetSource` frees the CPU image whether or not the upload worked (Exporter already knows this).

Change: `Engine::setSourceRows(w, h, mips, rows)` allocates a new texture, uploads in 256 row strips, swaps only on success, restores the old binding and drains errors on failure, and clears stale errors on entry. `setSource` becomes a thin wrapper. See `engine.cpp.patch`. `EditorSession.ensureFull` shows "Full resolution is not available on this phone for this photo" once and does not retry; `reloadSource` shows "Update failed" and keeps the previous source. Peak GPU memory during a swap is old plus new texture (about 100 MB more at the half size source) which is the price of not losing the image.

Test (`engine_test.cpp`, runs on Mesa): upload an 8x8 source, render; upload a source larger than `GL_MAX_TEXTURE_SIZE` (returns false); render again. Result: unmodified code renders a different picture (max difference 216 levels), the fix renders an identical one (0). Also strips: a 600 row source arrives intact.

### 3.6 Decode builds the pixels twice (AE-007, size M)

Where: `raw_decode.cpp` `decodeRaw` (fills `out.half`, 8 bytes a pixel, while LibRaw still holds `imgdata.image`, also 8 bytes a pixel); JNI `engineSetSource`; JNI `rawRead` and `rawWrite` (they use `img->half`).

Change (`raw_decode.h/.cpp` patches, JNI hunks in `kotlin_and_jni.patch`): `RawImage` gains `std::shared_ptr<LibRaw> lr`, `convertRows(y0, rows, dst)` and `materialise()`. `decodeRaw(..., lazy)` keeps LibRaw's 16 bit result and does not build `half`; the JNI decode passes `lazy = true`; `engineSetSource` uploads straight from LibRaw's buffer in 256 row strips (`setSourceRows`) and then deletes the image; `rawRead` and `rawWrite` call `materialise()` first (denoise still works, with the same peak it has today). Non-raw pictures (`rawFromSrgb8`) keep `half` and `lr` empty.

Measured on the real S5M2X sample (6008 x 4008, full decode, llvmpipe, through `golden full`): output PPM identical byte for byte to the unmodified build. Process peak after decode, before upload: 520 MB eager, 342 MB lazy (-178 MB, which is the 192 MB half array, 34 percent). Scale to 45 MP (8192 x 5464): about 360 MB saved on the CPU side. The later peak in the golden binary (606 MB lazy) is llvmpipe holding the texture in system memory; on the phone that part is GPU memory, so the honest claim is the CPU side only, and it must be confirmed with the Copy report.

Test: `GOLDEN_STRIPS=1 golden sample.RW2 out.ppm 600 full` equals the same command without it (`cmp`); add this as a step in `run-golden.sh` (it needs the sample RAW the script already downloads). The `golden.cpp` hunks are in section 6.

### 3.7 AI denoise on the Java heap (AE-009, size M)

Where: `Denoiser.kt` `run` (`results` list holds every tile's `outLin` until the end; 288 MB at 24 MP, 540 MB at 45 MP; the export path catches `Exception`, not `OutOfMemoryError`).

Change: `DeferredRows` (class in `core/ml`, `DeferredRows.kt`): a tile row's results are written when the next row has been read, so at most two rows are alive (43 tiles of 192 x 192 x 3 floats is 6.4 MB a row). Hunk in `kotlin_and_jni.patch`. `Exporter.kt` turns `OutOfMemoryError` from denoise into a readable `IllegalStateException`.

Test (`DeferredRowsTest`): same tiling as the denoiser (tile 256, overlap 32, stride 192) over a 700 x 500 image with a result that depends on the neighbours: writing two rows late gives exactly the image that writing everything at the end gives; a negative control shows that writing each tile immediately gives a different image (so the test is sensitive to order); at most two rows are held. A Python simulation of the same scheme found zero tile reads that touched an already written pixel.

### 3.7b Export of a large non-raw picture (AE-008, size S)

Where: `Exporter.kt` `decode`: `ImageDecoder` without `setTargetSize` (the editor caps the same file at 8192 in `EditorSession.decodeFor`), then `ByteBuffer.allocate(argb.byteCount)` on the Java heap.

Change: `DecodeCap.target(w, h, DecodeCap.FULL)` (new object in `core/render`) used in the `ImageDecoder` callback. Test `DecodeCapTest`: a 101.8 MP JPEG (11648 x 8736) is capped to 8192 x 6144; small pictures return null; a 40000 x 20 panorama keeps one pixel on the short side. The zero-copy hand-over through `AndroidBitmap_lockPixels` is a later step (M); with the cap the heap copy is at most 8192 x 5461 x 4 = 179 MB, down from 400 MB for 100 MP.

### 3.8 Mip chain at 1:1 export (AE-025, size S)

Where: `engine.cpp` (`glGenerateMipmap` always; 33 percent extra memory and time), `Exporter.kt` (the engine is created per export).

Change: `setSource(..., mips)` and the JNI/Kotlin parameter `mips`. `Exporter` passes `needMips = s.longEdge in 1 until max(info[0], info[1])` (a downscaled export needs the mips for its lod; a 1:1 export never samples them; the output can only be smaller than the source). The editor passes `true`. Test: `engine_test.cpp` renders identically with and without mips at 1:1. Serialising the export against the editor's resident full texture (about 1 GB of GPU memory together at 45 MP) is not part of this task; log it as a follow-up.

### 3.9 Slider tick costs two target rebuilds (AE-010, size S)

Where: `EditorSession.kt` `computeHistogram` (line 516), `sample`, `baseStats`, `renderSource`, `renderFrame` all call `Native.engineRenderRegion`, which resizes the shared screen targets `e_` and `outT_`; `computeHistogram` then calls `requestRender()` to redraw the screen.

Change: `renderRegion(..., probe)` uses `eProbe_` and `outProbe_` when `probe` is true; all five call sites pass `true`; the trailing `requestRender()` in `computeHistogram` is removed (a probe render no longer makes the screen stale). Facts found while measuring: the analysis cache key is the geometry only, so histogram and sample renders (same params as the screen) do not re-run the analysis. `renderSource`, `baseStats` and `renderFrame` use different geometry and still re-run it twice per call; a second analysis slot is a follow-up (M).

Test (`engine_test.cpp`): 20 ticks of "screen frame, then 256 x 170 probe render" reallocate 0 render targets once warm (unmodified code: 40 reallocations, two per tick, 1143 ms against 862 ms for the same 20 ticks on llvmpipe, which is a relative figure only). Phone check: the Copy report's `frame_render_ms` p95 before and after.

### 3.10 Context loss (AE-011, size S; finding corrected)

Where: `EditorSession.kt` `onSurfaceCreated` calls `engineDestroy` on the old engine inside the new context.

What I found: destroying the dead engine does leave `GL_INVALID_VALUE` (0x501) in the new context, as the audit says, but `engineInit` runs before the next `setSource` and clears it, so the claimed "GPU upload failed after a restore" does not reproduce on llvmpipe (reproduced the exact order: lose context, make a new one current, destroy, create, init, upload: the upload returns true). The real risk is different and worse on a real driver: deleting GL names that belong to another context is undefined, and name numbers are reused, so the delete can remove objects of the new context. So the fix stays, at P3 rather than P2.

Change: `Engine::abandon()` (marks not ready so `~Engine` skips `release()`), JNI `engineAbandon`, `Native.engineAbandon`, and `onSurfaceCreated` calls it instead of `engineDestroy`. `setSourceRows` and `renderRegion` drain stale errors on entry. Test (`engine_test.cpp`): after a lost context, `abandon()` then `delete` leaves `glGetError() == GL_NO_ERROR` in the new context (unmodified: 0x501), and a fresh engine takes a source.

### 3.3b AI mask input frame (AE-017, size S)

Where: `EditorSession.kt` `renderFrame` (line 285 onward): `RenderParams.build(EditRecipe(geometry = g), ...)` has default optics and no lens, so the frame the AI sees is not lens corrected while the picture on screen is; mask edges land up to about 4 percent of the frame height (about 150 px at 24 MP) off at the corners of a 20 mm shot.

Change: `EditRecipe(geometry = g, optics = recipe.optics)` and `lens = lens` (hunk in `kotlin_and_jni.patch`). The frame coordinate is still the pre-warp one, so no mask maths changes. Test: the mapping itself is covered by the other worker's `HealGeometryTest`. Add one host test in `RenderTest` that `RenderParams.build` with a lens sets `G_LDIST_ON` to 1 and without it to 0, and by hand on the phone: remove a person at the corner of a lens-corrected photo and check the mask edge hugs the subject.

### 3.11 `nullOutputStream` (size S)

Where: `ModelStore.kt:123` `copy(raw, java.io.OutputStream.nullOutputStream()) {}`. API 33 call in a minSdk 31 app: a model download crashes on Android 12 and 12L (not on the S24, but lint fails the build once the lint job is blocking).

Change: `NullSink` object (`NullSink.kt`, section 8) and `copy(raw, NullSink) {}`. Test `NullSinkTest`; lint: `./gradlew :core:ml:lintDebug` must report no `NewApi`.

### 3.12 `HalfFloat` lint and per-pixel allocation (AE-047, size S)

Where: `HealOverlay.kt:57-60` (five `HalfFloat` errors; also one `floatArrayOf` of four floats per pixel, about a million allocations for a 1024 px patch); `Exporter.kt:130` has the sixth, in the 16 bit path the other worker is replacing (`renderRegion16`), so it disappears with their merge.

Change: `Halves` (saturating `toHalf`/`toFloat` on a Short, same rules as the C++ helper) and `HealMath.over(buf, di, out, o, r, g, b, na)` replace `android.util.Half` and the array. Tests in `HalvesTest`: all 63,488 finite half patterns round trip exactly; 65519 to 1e9 and Infinity saturate to 0x7BFF, negatives to 0xFBFF, NaN to 0; blend vectors for full, half and no coverage; huge values do not become Infinity. Cross-check against the JDK's `Float.floatToFloat16` on 1.1 million random in-range floats: 13 differences, all exact ties (this code rounds half up, the JDK half to even), harmless. Success: `:core:render:lintDebug` reports no `HalfFloat`.

## 4. Exit check for the task

1. `./gradlew testDebugUnitTest` green, including the 14 new tests (Halves 4, HealMath 3, DecodeCap 4, NullSink 1, DeferredRows 2; the sources are in section 7) and `:core:render:lintDebug :core:ml:lintDebug` with no `HalfFloat` or `NewApi`.
2. `tools/golden/run-golden.sh` green with every existing reference identical; `engine_checks.py` and `engine_test` pass; `GOLDEN_STRIPS=1` output equals the normal output.
3. Release APK on a phone: open a 24 MP RW2, zoom in (full decode, upload), rotate the phone, export JPEG and TIFF; Jai pastes the Copy report. Nothing is claimed about speed or memory from this machine.

## 5. Things deliberately not in this task

- Colour contract: white level, WB brightness, base curve (W22, W23, BK-470).
- The full GPU histogram reduction, pan and zoom transform, and slider drag at half resolution (the rest of the NEXT 25 W09).
- A second analysis cache slot for `renderSource`, `baseStats`, `renderFrame`; export versus editor residency of the full texture; zero-copy bitmap hand-over to JNI.

## 6. golden.cpp additions

Add to `tools/golden/golden.cpp` (the other worker is also editing that file, so insert these by hand after their merge):

1. A key `overlay=<v>` in the argument loop (needs `#include "engine/halfs.h"` if not already included):
```cpp
        else if (k == "overlay") {   // heal overlay: a patch holding the raw (uncorrected) value 0.02122 at the top left quarter, as a perfect heal on a flat source would
            const int ow2 = 60, oh2 = 40;
            std::vector<uint16_t> ov(size_t(ow2) * oh2 * 4, 0);
            for (int y = 0; y < 10; y++) for (int x = 0; x < 15; x++) { size_t i = (size_t(y) * ow2 + x) * 4; ov[i] = rl::floatToHalf(v * 1.0003f); ov[i + 1] = rl::floatToHalf(v * 0.9999f); ov[i + 2] = rl::floatToHalf(v * 0.9996f); ov[i + 3] = rl::floatToHalf(1.f); }
            eng.setOverlay(reinterpret_cast<const uint8_t *>(ov.data()), ow2, oh2);
            p[G_OVERLAY] = 1.f;
        }
```
2. Strip upload and a memory line, to compare the lazy and the eager paths (`GOLDEN_STRIPS=1`): in the decode call pass `getenv("GOLDEN_STRIPS") != nullptr` as the new `lazy` argument, and replace the `eng.setSource(...)` call by:
```cpp
    bool upOk = img.lr ? eng.setSourceRows(img.width, img.height, true, [&](int y0, int rows) { static std::vector<uint16_t> strip; strip.resize(size_t(rows) * img.width * 4); img.convertRows(y0, rows, strip.data()); return strip.data(); }) : eng.setSource(img.width, img.height, img.half.data());
    if (img.lr) img.lr.reset();
```
3. Optional, used for the peak figures above:
```cpp
static void mem(const char *tag) { FILE *f = fopen("/proc/self/status", "r"); char l[256]; long hwm = 0, rss = 0; while (f && fgets(l, sizeof l, f)) { sscanf(l, "VmHWM: %ld", &hwm); sscanf(l, "VmRSS: %ld", &rss); } if (f) fclose(f); fprintf(stderr, "MEM %s rss %ld MB peak %ld MB\n", tag, rss / 1024, hwm / 1024); }
```
call `mem("after decode")` after the decode and `mem("after upload")` after the upload.

## 7. Tests and scripts (tested)

### tools/golden/engine_checks.py
```python
#!/usr/bin/env python3
"""Shader acceptance checks that need no RAW file: flat and edge pictures through the real GLSL (golden binary). Standard library only.
usage: engine_checks.py [path to golden binary]     (default /tmp/golden/golden). Needs the golden build to have the `overlay=` key."""
import os, subprocess, sys, tempfile

G = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GOLDEN_BIN", "/tmp/golden/golden")
W, H = 600, 400
tmp = tempfile.mkdtemp()

def ppm(name, rows):
    p = os.path.join(tmp, name); open(p, "wb").write(b"P6\n%d %d\n255\n" % (W, H) + rows); return p
flat = lambda v: ppm("flat%d.ppm" % v, bytes([v]) * (W * H * 3))
edge = ppm("edge.ppm", (bytes([40]) * 3 * 300 + bytes([200]) * 3 * 300) * H)

def render(src, *args):
    out = os.path.join(tmp, "o%d.ppm" % len(os.listdir(tmp)))
    r = subprocess.run([G, src, out, str(W), "half", *args], capture_output=True, text=True)
    if r.returncode: raise SystemExit("golden failed: " + r.stderr[-300:])
    d = open(out, "rb").read().split(b"\n", 3)[3]
    return lambda x, y, r=6: sum(d[((y + j) * W + x + i) * 3] for j in range(-r, r + 1) for i in range(-r, r + 1)) / (2 * r + 1) ** 2

fails = 0
def check(ok, what, detail=""):
    global fails
    print("%s  %s %s" % ("PASS" if ok else "FAIL", what, detail)); fails += 0 if ok else 1

# AE-002: negative dehaze adds haze: a flat dark grey is lifted towards the airlight, and an edge gets no black halo
base, hz = render(flat(40))(300, 200), render(flat(40), "dehaze=-100")(300, 200)
check(abs(base - 83) <= 1.5 and abs(hz - 234) <= 1.5, "dehaze -100 lifts flat grey 40 from 83 to 234 (model: base curve of 0.5 x lin + 0.45)", "got %.1f -> %.1f" % (base, hz))
e = render(edge, "dehaze=-100")
check(abs(e(290, 200) - e(100, 200)) <= 1.5 and e(290, 200) > 100, "no black halo beside an edge with dehaze -100", "x=100 %.1f, x=290 %.1f" % (e(100, 200), e(290, 200)))

# AE-003: lens vignetting correction must not feed Texture and Clarity (corner equals the same corner without them)
a, b = render(flat(128), "lens=1"), render(flat(128), "lens=1", "clarity=60", "texture=60")
check(abs(a(40, 40) - b(40, 40)) <= 1.5 and abs(a(560, 360) - b(560, 360)) <= 1.5, "flat grey with lens correction is unchanged by Texture and Clarity",
      "corner %.1f vs %.1f" % (a(40, 40), b(40, 40)))

# AE-005: a heal patch built from the uncorrected source takes the same vignetting gain as its surroundings
o = render(flat(40), "lens=1", "overlay=0.02122")
check(abs(o(30, 20) - o(30, 380)) <= 1.5, "heal patch at the corner matches the pixels around it", "patch %.1f, surround %.1f" % (o(30, 20), o(30, 380)))
print("all passed" if not fails else "FAILED")
sys.exit(1 if fails else 0)
```

### tools/golden/engine_test.cpp
Build line: `g++ -O1 -std=c++17 -I$OUT -I$C -I$C/engine tools/golden/engine_test.cpp $C/engine/engine.cpp -lEGL -lGLESv2 -o $OUT/engine_test` (same includes as `build.sh`; `$OUT` holds `shader_sources.h`). Add the build and run to `run-golden.sh` after the golden comparison.
```cpp
// Host tests for the engine's resource handling (audit AE-006, AE-010, AE-011) on Mesa llvmpipe. Exit code 0 when all pass.
// build: g++ -O1 -std=c++17 -I$OUT -I$C -I$C/engine tools/golden/engine_test.cpp $C/engine/engine.cpp -lEGL -lGLESv2 -o $OUT/engine_test
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "engine/engine.h"
#include "engine/halfs.h"
#include "engine/params.h"

using namespace rl;

static int fails = 0;
static void check(bool ok, const char *what) { std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what); if (!ok) fails++; }

struct Gl {
    EGLDisplay d; EGLConfig cfg; EGLSurface s; EGLContext c = EGL_NO_CONTEXT;
    Gl(int w, int h) {
        auto getPD = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
        d = getPD(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
        EGLint a, b; eglInitialize(d, &a, &b); eglBindAPI(EGL_OPENGL_ES_API);
        EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
        EGLint n; eglChooseConfig(d, ca, &cfg, 1, &n);
        EGLint pa[] = {EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE};
        s = eglCreatePbufferSurface(d, cfg, pa);
        fresh();
    }
    void fresh() {   // a new context, as after a context loss
        if (c != EGL_NO_CONTEXT) { eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); eglDestroyContext(d, c); }
        EGLint cx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
        eglMakeCurrent(d, s, s, c);
    }
};

static std::vector<uint16_t> flat(int w, int h, float v) {
    std::vector<uint16_t> px(size_t(w) * h * 4, floatToHalf(v));
    for (size_t i = 3; i < px.size(); i += 4) px[i] = floatToHalf(1.f);
    return px;
}
static void frame(Engine &e, uint8_t *out) {
    std::vector<float> p(kParamFloats); initDefaultParams(p.data());
    e.renderRegion(p.data(), 8, 8, {0, 0, 1, 1}, out);
}

int main() {
    Gl gl(540, 1150);
    std::string err;

    {   // AE-006: a failed upload leaves the engine rendering the previous source
        Engine e; e.init(err);
        auto px = flat(8, 8, 0.3f);
        check(e.setSource(8, 8, px.data()), "first upload succeeds");
        uint8_t before[256], after[256]; frame(e, before);
        GLint maxTex = 0; glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
        check(!e.setSource(maxTex + 1, maxTex + 1, px.data()), "an upload that cannot be allocated returns false");
        frame(e, after);
        int diff = 0; for (int i = 0; i < 256; i++) diff = std::max(diff, std::abs(int(before[i]) - int(after[i])));
        check(diff == 0, "the render after the failed upload equals the render before it");
        check(e.setSource(8, 8, px.data(), false), "an upload without mips succeeds");
        frame(e, after);
        diff = 0; for (int i = 0; i < 256; i++) diff = std::max(diff, std::abs(int(before[i]) - int(after[i])));
        check(diff == 0, "no mip chain renders the same at 1:1");
        // strips: a source taller than one strip (256 rows) must arrive intact
        auto tall = flat(64, 600, 0.3f);
        check(e.setSourceRows(64, 600, true, [&](int y0, int) { return tall.data() + size_t(y0) * 64 * 4; }), "strip upload of a 600 row source");
    }

    {   // AE-010: a slider tick (screen frame, then the histogram render) must not reallocate render targets
        Engine e; e.init(err);
        auto px = flat(1600, 1066, 0.25f);
        e.setSource(1600, 1066, px.data());
        std::vector<float> p(kParamFloats); initDefaultParams(p.data());
        std::vector<uint8_t> small(256 * 170 * 4);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        e.renderToScreen(p.data(), 0, 0, 540, 1150, {0, 0, 1, 1});
        e.renderRegion(p.data(), 256, 170, {0, 0, 1, 1}, small.data(), true);
        int base = e.targetRebuilds();
        for (int i = 0; i < 20; i++) {
            e.renderToScreen(p.data(), 0, 0, 540, 1150, {0, 0, 1, 1});
            e.renderRegion(p.data(), 256, 170, {0, 0, 1, 1}, small.data(), true);
        }
        std::printf("      target reallocations over 20 ticks: %d\n", e.targetRebuilds() - base);
        check(e.targetRebuilds() == base, "20 slider ticks reallocate no render target");
    }

    {   // AE-011: destroying an engine whose context is gone must not touch the new context
        auto px = flat(8, 8, 0.5f);
        Engine *e1 = new Engine(); e1->init(err); e1->setSource(8, 8, px.data());
        gl.fresh();                      // context 1 is destroyed, context 2 is current
        e1->abandon(); delete e1;
        check(glGetError() == GL_NO_ERROR, "abandon() leaves no GL error in the new context");
        Engine e2; check(e2.init(err), "the new engine initialises"); check(e2.setSource(8, 8, px.data()), "and takes a source");
    }
    std::printf("%s\n", fails ? "FAILED" : "all passed");
    return fails ? 1 : 0;
}
```

Measured output of `engine_test` on the patched tree: 11 PASS lines (listed at the top of the file's checks), `all passed`, exit 0. Output of `engine_checks.py` on the other worker's tree: 4 PASS; on unmodified main: 4 FAIL.

### Kotlin (compiled and run: 14 tests OK)

Sources go to `core/render/src/main/kotlin/app/rawline/core/render/` (`Halves.kt` with `HealMath`, `DecodeCap.kt`) and `core/ml/src/main/kotlin/app/rawline/core/ml/` (`DeferredRows.kt`, `NullSink.kt`). Tests go to `core/render/src/test/kotlin/app/rawline/core/render/` (`HalvesTest.kt`, `DecodeCapTest.kt`) and `core/ml/src/test/kotlin/app/rawline/core/ml/MlTest.kt` (split it into `NullSinkTest.kt` and `DeferredRowsTest.kt` if you prefer one class per file).

#### Halves.kt (with HealMath)
```kotlin
package app.rawline.core.render

/**
 * IEEE 754 half precision on the raw 16 bits (a Short), saturating: values that would round to Infinity become the largest finite
 * half (65504), NaN becomes 0. Same rules as `rl::floatToHalf` in engine/halfs.h, so the heal overlay and the engine agree.
 * Replaces android.util.Half where the bits are handled as a Short: lint's HalfFloat check flags those calls, and the Android
 * class does not run in plain JVM unit tests.
 */
object Halves {
    private const val LARGEST = 0x7BFF

    fun toHalf(f: Float): Short {
        val x = java.lang.Float.floatToRawIntBits(f)
        val sign = (x ushr 16) and 0x8000
        val expBits = (x ushr 23) and 0xFF
        val exp = expBits - 127 + 15
        var mant = x and 0x7FFFFF
        if (expBits == 0xFF) return (if (mant != 0) 0 else sign or LARGEST).toShort()   // NaN to 0, Infinity to the largest finite half
        if (exp >= 31) return (sign or LARGEST).toShort()
        if (exp <= 0) {
            if (exp < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - exp
            var h = mant ushr shift
            if (((mant ushr (shift - 1)) and 1) != 0) h++
            return (sign or h).toShort()
        }
        var h = (exp shl 10) or (mant ushr 13)
        if ((mant and 0x1000) != 0) h++   // round to nearest
        if (h >= 0x7C00) h = LARGEST      // the carry out of the largest binade (65520 and up) would be Infinity
        return (sign or h).toShort()
    }

    fun toFloat(h: Short): Float {
        val v = h.toInt() and 0xFFFF
        val sign = (v and 0x8000) shl 16
        var exp = (v ushr 10) and 0x1F
        var mant = v and 0x3FF
        val bits: Int
        if (exp == 0) {
            if (mant == 0) bits = sign
            else {
                exp = 1
                while ((mant and 0x400) == 0) { mant = mant shl 1; exp-- }
                mant = mant and 0x3FF
                bits = sign or ((exp + 127 - 15) shl 23) or (mant shl 13)
            }
        } else if (exp == 31) bits = sign or 0x7F800000 or (mant shl 13)
        else bits = sign or ((exp + 127 - 15) shl 23) or (mant shl 13)
        return java.lang.Float.intBitsToFloat(bits)
    }
}

/** The heal overlay blend for one pixel, without a temporary array: new premultiplied colour (r, g, b already linear working space) at coverage na over what the overlay held. */
object HealMath {
    fun over(buf: ShortArray, di: Int, out: ShortArray, o: Int, r: Float, g: Float, b: Float, na: Float) {
        val keep = 1f - na
        val h0 = Halves.toHalf(r * na + Halves.toFloat(buf[di]) * keep)
        val h1 = Halves.toHalf(g * na + Halves.toFloat(buf[di + 1]) * keep)
        val h2 = Halves.toHalf(b * na + Halves.toFloat(buf[di + 2]) * keep)
        val h3 = Halves.toHalf(na + Halves.toFloat(buf[di + 3]) * keep)
        buf[di] = h0; buf[di + 1] = h1; buf[di + 2] = h2; buf[di + 3] = h3
        out[o] = h0; out[o + 1] = h1; out[o + 2] = h2; out[o + 3] = h3
    }
}
```
#### DecodeCap.kt
```kotlin
package app.rawline.core.render

/** Longest edge a decoded picture may have before it goes to the GPU: 8192 is inside GL_MAX_TEXTURE_SIZE on every device we support. */
object DecodeCap {
    const val FULL = 8192
    const val HALF = 3072

    /** The size to decode at, or null when the picture already fits. Keeps the aspect ratio, never returns a zero side. */
    fun target(w: Int, h: Int, cap: Int): Pair<Int, Int>? {
        val long = maxOf(w, h)
        if (long <= cap || long <= 0) return null
        val s = cap.toFloat() / long
        return maxOf(1, (w * s).toInt()) to maxOf(1, (h * s).toInt())
    }
}
```
#### DeferredRows.kt
```kotlin
package app.rawline.core.ml

/**
 * Write-back of tile results one tile row late. A tile reads 32 px beyond its core, so its neighbours' cores must still hold
 * the original pixels when it reads. Raster order means that is true for the left neighbour only if nothing of the current row
 * is written yet, and for the row above only if that row is written after the current row was fully read. So: hold a row's results,
 * and write the previous row when the current row has finished reading. At most two rows of results are alive at once, instead of
 * every tile of the image.
 */
class DeferredRows<T>(private val write: (T) -> Unit) {
    private var previous: List<T>? = null
    private val current = ArrayList<T>()

    /** A tile of the current row has been read and processed. */
    fun add(item: T) { current.add(item) }

    /** The whole current row has been read: write the row before it, keep this one. */
    fun rowDone() {
        previous?.forEach(write)
        previous = ArrayList(current)
        current.clear()
    }

    /** After the last row. */
    fun finish() {
        previous?.forEach(write)
        current.forEach(write)
        previous = null
        current.clear()
    }

    /** Results currently held (for a memory guard and for tests). */
    val held: Int get() = (previous?.size ?: 0) + current.size
}
```
#### NullSink.kt
```kotlin
package app.rawline.core.ml

import java.io.OutputStream

/** Discards everything. java.io.OutputStream.nullOutputStream() needs API 33 and minSdk is 31. */
object NullSink : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}
```
#### HalvesTest.kt
```kotlin
package app.rawline.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalvesTest {
    private fun bits(f: Float) = Halves.toHalf(f).toInt() and 0xFFFF

    @Test fun knownValues() {
        assertEquals(0x0000, bits(0f))
        assertEquals(0x3C00, bits(1f))
        assertEquals(0x3800, bits(0.5f))
        assertEquals(0xC000, bits(-2f))
        assertEquals(0x7BFF, bits(65504f))
    }

    @Test fun overflowSaturatesInsteadOfInfinity() {
        for (f in floatArrayOf(65519f, 65520f, 65535f, 65536f, 1e9f, Float.POSITIVE_INFINITY)) assertEquals("$f", 0x7BFF, bits(f))
        for (f in floatArrayOf(-65520f, -65535f, -1e9f, Float.NEGATIVE_INFINITY)) assertEquals("$f", 0xFBFF, bits(f))
        assertEquals(0, bits(Float.NaN))
    }

    @Test fun everyFiniteHalfRoundTrips() {
        for (h in 0..0xFFFF) {
            val exp = (h ushr 10) and 0x1F
            if (exp == 31) continue   // Infinity and NaN patterns are not produced by toHalf
            val f = Halves.toFloat(h.toShort())
            assertEquals("0x${h.toString(16)}", h, bits(f))
        }
    }

    @Test fun subnormalsAndTinyValues() {
        assertEquals(0x0001, bits(5.9604645e-8f))   // smallest subnormal
        assertEquals(0x0000, bits(1e-9f))
        assertTrue(Halves.toFloat(0x0400.toShort()) == 6.1035156e-5f)   // smallest normal
    }
}

class HealMathTest {
    private fun px(r: Float, g: Float, b: Float, a: Float) = shortArrayOf(Halves.toHalf(r), Halves.toHalf(g), Halves.toHalf(b), Halves.toHalf(a))
    private fun f(s: ShortArray, i: Int) = Halves.toFloat(s[i])

    @Test fun fullCoverageReplacesWhatWasThere() {
        val buf = px(0.2f, 0.3f, 0.4f, 1f); val out = ShortArray(4)
        HealMath.over(buf, 0, out, 0, 0.5f, 0.6f, 0.7f, 1f)
        assertEquals(0.5f, f(out, 0), 1e-3f); assertEquals(0.6f, f(out, 1), 1e-3f); assertEquals(0.7f, f(out, 2), 1e-3f); assertEquals(1f, f(out, 3), 1e-3f)
        assertTrue(buf.contentEquals(out))
    }

    @Test fun halfCoverageMixesWithTheOldPixel() {
        val buf = px(0.2f, 0.2f, 0.2f, 0.5f); val out = ShortArray(4)
        HealMath.over(buf, 0, out, 0, 0.6f, 0.6f, 0.6f, 0.5f)
        assertEquals(0.5f * 0.6f + 0.5f * 0.2f, f(out, 0), 1e-3f)   // 0.4
        assertEquals(0.5f + 0.5f * 0.5f, f(out, 3), 1e-3f)           // 0.75
    }

    @Test fun hugeValuesSaturateInsteadOfBecomingInfinity() {
        val buf = ShortArray(4); val out = ShortArray(4)
        HealMath.over(buf, 0, out, 0, 70000f, 70000f, 70000f, 1f)
        assertEquals(65504f, f(out, 0), 0f)
    }
}
```
#### DecodeCapTest.kt
```kotlin
package app.rawline.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeCapTest {
    @Test fun smallPictureIsNotScaled() { assertNull(DecodeCap.target(6000, 4000, DecodeCap.FULL)) }
    @Test fun hundredMegapixelJpegIsCappedKeepingAspect() {
        val (w, h) = DecodeCap.target(11648, 8736, DecodeCap.FULL)!!   // 101.8 MP
        assertEquals(8192, w); assertEquals(6144, h)
    }
    @Test fun extremePanoramaKeepsOnePixel() {
        val (w, h) = DecodeCap.target(40000, 20, DecodeCap.FULL)!!
        assertEquals(8192, w); assertTrue(h >= 1)
    }
    @Test fun halfCapIsSmaller() { assertEquals(3072, DecodeCap.target(6000, 4000, DecodeCap.HALF)!!.first) }
}
```
#### MlTest.kt
```kotlin
package app.rawline.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NullSinkTest {
    @Test fun acceptsWritesWithoutError() { NullSink.write(1); NullSink.write(ByteArray(10), 0, 10); NullSink.write(ByteArray(3)); NullSink.flush(); NullSink.close() }
}

class DeferredRowsTest {
    /** Same image, same tiling as the denoiser (tile 256, overlap 32, stride 192), with a result that depends on the neighbours. */
    private val W = 700; private val H = 500; private val T = 256; private val O = 32; private val S = T - 2 * O
    private class Tile(val cx: Int, val cy: Int, val cw: Int, val ch: Int, val v: IntArray)

    private fun px(img: IntArray, x: Int, y: Int) = img[y.coerceIn(0, H - 1) * W + x.coerceIn(0, W - 1)]
    private fun process(img: IntArray, cx: Int, cy: Int, cw: Int, ch: Int) = IntArray(cw * ch) { i ->
        val x = cx + i % cw; val y = cy + i / cw
        // reads the tile as it is at read time, including the overlap ring
        px(img, x, y) * 2 + px(img, x - 1, y) + px(img, x + 1, y) + px(img, x, y - 1) + px(img, x, y + 1)
    }
    private fun write(img: IntArray, t: Tile) { for (i in t.v.indices) img[(t.cy + i / t.cw) * W + t.cx + i % t.cw] = t.v[i] }

    private fun run(deferred: Boolean, immediate: Boolean = false): Pair<IntArray, Int> {
        val rnd = java.util.Random(3)
        val src = IntArray(W * H) { rnd.nextInt(1000) }
        val img = src.copyOf(); val nx = (W + S - 1) / S; val ny = (H + S - 1) / S
        var maxHeld = 0
        val all = ArrayList<Tile>()
        val d = DeferredRows<Tile> { write(img, it) }
        for (ty in 0 until ny) {
            for (tx in 0 until nx) {
                val cx = tx * S; val cy = ty * S; val cw = minOf(S, W - cx); val ch = minOf(S, H - cy)
                // the tile read: process() looks one pixel beyond the core, always inside the tile's overlap ring
                val t = Tile(cx, cy, cw, ch, process(img, cx, cy, cw, ch))
                if (immediate) write(img, t) else if (deferred) { d.add(t); maxHeld = maxOf(maxHeld, d.held) } else all.add(t)
            }
            if (deferred) d.rowDone()
        }
        if (immediate) {} else if (deferred) d.finish() else all.forEach { write(img, it) }
        return img to maxHeld
    }

    @Test fun writingEachTileImmediatelyWouldCorruptNeighbours() {   // negative control: the test image really is sensitive to the order
        assertTrue(!run(false).first.contentEquals(run(false, immediate = true).first))
    }

    @Test fun twoRowsLateGivesTheSameImageAsWritingAtTheEnd() {
        val (a, _) = run(false); val (b, held) = run(true)
        assertTrue(a.contentEquals(b))
        assertTrue("held $held", held <= 2 * ((W + S - 1) / S))
    }
}
```

## 8. Patches (apply with `git apply`, in this order, to the tree after the other worker's merge)

`git apply --check` passed for all five against the working tree on 6 Oct 2026.

### engine.h.patch
```diff
--- a/core/native/src/main/cpp/engine/engine.h
+++ b/core/native/src/main/cpp/engine/engine.h
@@ -1,6 +1,7 @@
 #pragma once
 #include <GLES3/gl3.h>
 #include <cstdint>
+#include <functional>
 #include <string>
 #include <vector>
 
@@ -16,7 +17,15 @@
     void release();
 
     /** RGBA half float pixels, row 0 = image top. Linear ProPhoto. Generates mipmaps. */
-    bool setSource(int w, int h, const uint16_t *rgbaHalf);
+    /** Pointer to `rows` RGBA half rows starting at row y0 (valid until the next call). */
+    using RowSource = std::function<const uint16_t *(int y0, int rows)>;
+    /** Uploads in strips of 256 rows. The previous source is kept, and nothing else changes, if allocation or upload fails. mips false skips the mip chain (1:1 export). */
+    bool setSourceRows(int w, int h, bool mips, const RowSource &rows);
+    bool setSource(int w, int h, const uint16_t *rgbaHalf, bool mips = true);
+    /** The GL context this engine lived in is gone: forget every GL name without calling GL, so the (new) current context is untouched. */
+    void abandon() { ready_ = false; }
+    /** Tests: how many render targets were (re)allocated so far. */
+    int targetRebuilds() const { return rebuilds_; }
     bool hasSource() const { return srcW_ > 0; }
     int sourceW() const { return srcW_; }
     int sourceH() const { return srcH_; }
@@ -33,7 +42,8 @@
     void renderToScreen(const float *params, int vx, int vy, int vw, int vh, Rect vis);
 
     /** Renders a region into an RGBA8 buffer (top row first). Used for exports and tests. Not available in high precision mode. */
-    bool renderRegion(const float *params, int pw, int ph, Rect vis, uint8_t *rgba);
+    /** probe: histogram, sample and AI-frame renders use their own small targets, so the screen targets are never resized. */
+    bool renderRegion(const float *params, int pw, int ph, Rect vis, uint8_t *rgba, bool probe = false);
 
     /** High precision mode only: renders a region into 16 bit RGB (pw * ph * 3 values, top row first), display encoded, quantised once from float32. */
     bool renderRegion16(const float *params, int pw, int ph, Rect vis, uint16_t *rgb);
@@ -59,7 +69,7 @@
     void uploadTables(const float *params);
     void runAnalysis(const float *params);
     void runMain(const float *params, Rect vis, int pw, int ph, Target &e, int margin, GLenum fmt);
-    bool drawOutput(const float *params, int pw, int ph, Rect vis);
+    bool drawOutput(const float *params, int pw, int ph, Rect vis, bool probe = false);
     static void drainErrors() { int guard = 0; while (glGetError() != GL_NO_ERROR && ++guard < 16) {} }
     void setGeometryUniforms(GLuint prog, const float *params);
     void draw();
@@ -67,7 +77,8 @@
     Prog lowres_, blur_, main_, out_;
     GLuint vao_ = 0;
     GLuint srcTex_ = 0, blocksTex_ = 0, masksTex_ = 0, curvesTex_ = 0, layersTex_ = 0, overlayTex_ = 0, baseTex_ = 0, baseTex32_ = 0;
-    Target l0_, bs_, bl_, bd_, tmp_, e_, outT_;
+    Target l0_, bs_, bl_, bd_, tmp_, e_, outT_, eProbe_, outProbe_;
+    int rebuilds_ = 0;
     int srcW_ = 0, srcH_ = 0, srcLevels_ = 1;
     int overlayW_ = 0;
     uint64_t analysisKey_ = ~0ull;
```
### engine.cpp.patch
```diff
--- a/core/native/src/main/cpp/engine/engine.cpp
+++ b/core/native/src/main/cpp/engine/engine.cpp
@@ -168,6 +168,7 @@
 void Engine::ensureTarget(Target &t, int w, int h, GLenum fmt) {
     if (t.tex && t.w == w && t.h == h && t.fmt == fmt) return;
     freeTarget(t);
+    rebuilds_++;
     t.w = w; t.h = h; t.fmt = fmt;
     // float32 textures are only filterable with an extension; targets are read with texelFetch or exact texel coordinates there
     t.tex = makeTex2D(GL_TEXTURE_2D, fmt == GL_RGBA32F ? GL_NEAREST : GL_LINEAR);
@@ -180,7 +181,7 @@
 
 void Engine::release() {
     if (!ready_) return;
-    for (Target *t : {&l0_, &bs_, &bl_, &bd_, &tmp_, &e_, &outT_}) freeTarget(*t);
+    for (Target *t : {&l0_, &bs_, &bl_, &bd_, &tmp_, &e_, &outT_, &eProbe_, &outProbe_}) freeTarget(*t);
     GLuint texs[] = {srcTex_, blocksTex_, masksTex_, curvesTex_, layersTex_, overlayTex_, baseTex_, baseTex32_};
     glDeleteTextures(8, texs);
     glDeleteProgram(lowres_.id); glDeleteProgram(blur_.id); glDeleteProgram(main_.id); glDeleteProgram(out_.id);
@@ -189,19 +190,38 @@
     ready_ = false;
 }
 
-bool Engine::setSource(int w, int h, const uint16_t *rgbaHalf) {
-    glBindTexture(GL_TEXTURE_2D, srcTex_);
+bool Engine::setSourceRows(int w, int h, bool mips, const RowSource &rows) {
+    while (glGetError() != GL_NO_ERROR) {}   // a stale error from an earlier call must not fail this upload
     int levels = 1;
-    for (int m = std::max(w, h); m > 1; m >>= 1) levels++;
-    glDeleteTextures(1, &srcTex_);
-    srcTex_ = makeTex2D(GL_TEXTURE_2D, GL_LINEAR_MIPMAP_LINEAR);
+    if (mips) for (int m = std::max(w, h); m > 1; m >>= 1) levels++;
+    GLuint fresh = makeTex2D(GL_TEXTURE_2D, mips ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
     glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA16F, w, h);
-    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
-    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_HALF_FLOAT, rgbaHalf);
-    glGenerateMipmap(GL_TEXTURE_2D);
+    bool ok = glGetError() == GL_NO_ERROR;
+    if (ok) {
+        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
+        constexpr int kStrip = 256;
+        for (int y0 = 0; y0 < h && ok; y0 += kStrip) {
+            int n = std::min(kStrip, h - y0);
+            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y0, w, n, GL_RGBA, GL_HALF_FLOAT, rows(y0, n));
+            ok = glGetError() == GL_NO_ERROR;
+        }
+    }
+    if (ok && mips) { glGenerateMipmap(GL_TEXTURE_2D); ok = glGetError() == GL_NO_ERROR; }
+    if (!ok) {
+        glDeleteTextures(1, &fresh);
+        glBindTexture(GL_TEXTURE_2D, srcTex_);
+        while (glGetError() != GL_NO_ERROR) {}
+        return false;   // srcTex_, srcW_, srcH_ and srcLevels_ still describe the previous source
+    }
+    if (srcTex_) glDeleteTextures(1, &srcTex_);
+    srcTex_ = fresh;
     srcW_ = w; srcH_ = h; srcLevels_ = levels;
     invalidateAnalysis();
-    return glGetError() == GL_NO_ERROR;
+    return true;
+}
+
+bool Engine::setSource(int w, int h, const uint16_t *rgbaHalf, bool mips) {
+    return setSourceRows(w, h, mips, [&](int y0, int) { return rgbaHalf + size_t(y0) * w * 4; });
 }
 
 // Bilinear resample of an 8 bit mask (pixel centre mapping). Masks cover the whole frame, so stretching is exact in meaning.
@@ -440,17 +460,19 @@
 }
 
 // Draws the output pass into outT_ (RGBA8, or RGBA32F in high precision mode) and leaves outT_ bound for reading.
-bool Engine::drawOutput(const float *p, int pw, int ph, Rect vis) {
+bool Engine::drawOutput(const float *p, int pw, int ph, Rect vis, bool probe) {
     targetsOk_ = true;
+    Target &eT = probe ? eProbe_ : e_;
+    Target &oT = probe ? outProbe_ : outT_;
     uploadTables(p);
     runAnalysis(p);
-    runMain(p, vis, pw, ph, e_, kMargin, hiPrec_ ? GL_RGBA32F : GL_RGBA16F);
-    ensureTarget(outT_, pw, ph, hiPrec_ ? GL_RGBA32F : GL_RGBA8);
-    glBindFramebuffer(GL_FRAMEBUFFER, outT_.fbo);
+    runMain(p, vis, pw, ph, eT, kMargin, hiPrec_ ? GL_RGBA32F : GL_RGBA16F);
+    ensureTarget(oT, pw, ph, hiPrec_ ? GL_RGBA32F : GL_RGBA8);
+    glBindFramebuffer(GL_FRAMEBUFFER, oT.fbo);
     glViewport(0, 0, pw, ph);
     glUseProgram(out_.id);
     glActiveTexture(GL_TEXTURE0);
-    glBindTexture(GL_TEXTURE_2D, e_.tex);
+    glBindTexture(GL_TEXTURE_2D, eT.tex);
     glActiveTexture(GL_TEXTURE1);
     glBindTexture(GL_TEXTURE_2D, baseTex_);
     glActiveTexture(GL_TEXTURE2);
@@ -464,13 +486,13 @@
     return targetsOk_;
 }
 
-bool Engine::renderRegion(const float *p, int pw, int ph, Rect vis, uint8_t *rgba) {
+bool Engine::renderRegion(const float *p, int pw, int ph, Rect vis, uint8_t *rgba, bool probe) {
     if (!hasSource() || hiPrec_) return false;
     drainErrors();
     GLint prevFbo = 0, prevVp[4];
     glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
     glGetIntegerv(GL_VIEWPORT, prevVp);
-    bool ok = drawOutput(p, pw, ph, vis);
+    bool ok = drawOutput(p, pw, ph, vis, probe);
     if (ok) glReadPixels(0, 0, pw, ph, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
     glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
     glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
```
### raw_decode.h.patch
```diff
--- a/core/native/src/main/cpp/raw_decode.h
+++ b/core/native/src/main/cpp/raw_decode.h
@@ -1,18 +1,26 @@
 #pragma once
 #include <cstdint>
+#include <memory>
 #include <string>
 #include <vector>
 
+class LibRaw;
+
 struct RawImage {
     int width = 0, height = 0;
     int orientation = 1;          // TIFF orientation of the stored data
-    std::vector<uint16_t> half;   // RGBA half float, linear ProPhoto, row 0 = top
+    std::vector<uint16_t> half;   // RGBA half float, linear ProPhoto, row 0 = top; empty while the pixels still live in `lr` (see materialise)
+    std::shared_ptr<LibRaw> lr;   // a lazy decode keeps LibRaw's 16 bit result (8 bytes a pixel) and converts strips on demand, so no second full copy exists
+    /** Converts `rows` rows starting at y0 into dst (width x rows x 4 halves). Works for both layouts. */
+    void convertRows(int y0, int rows, uint16_t *dst) const;
+    /** Builds the whole `half` array and releases `lr`. Needed by anything that reads or writes pixels on the CPU (denoise). */
+    void materialise();
     std::string camera;
     float wbMul[4] = {1, 1, 1, 1};
 };
 
 /** Decodes with LibRaw: camera white balance, linear, ProPhoto primaries. halfSize skips demosaic (2x2 binning). */
-bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err);
+bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err, bool lazy = false);
 
 /** Converts an 8 bit sRGB RGBA bitmap (JPEG, HEIC, PNG) into the working space so it can be edited like a raw. */
 void rawFromSrgb8(const uint8_t *rgba, int w, int h, RawImage &out);
```
### raw_decode.cpp.patch
```diff
--- a/core/native/src/main/cpp/raw_decode.cpp
+++ b/core/native/src/main/cpp/raw_decode.cpp
@@ -4,12 +4,14 @@
 
 #include <algorithm>
 #include <cmath>
+#include <cstring>
 #include <thread>
 
 #include "engine/halfs.h"
 
-bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err) {
-    LibRaw lr;
+bool decodeRaw(const std::string &path, bool halfSize, RawImage &out, std::string &err, bool lazy) {
+    auto lrp = std::make_shared<LibRaw>();
+    LibRaw &lr = *lrp;
     auto &P = lr.imgdata.params;
     P.use_camera_wb = 1;
     P.use_auto_wb = 0;
@@ -37,33 +39,45 @@
 
     out.width = w;
     out.height = h;
-    out.half.assign(size_t(w) * h * 4, 0);
+    out.lr = lrp;
+    if (!lazy) out.materialise();
+    int flip = lr.imgdata.sizes.flip;
+    out.orientation = flip == 3 ? 3 : flip == 5 ? 8 : flip == 6 ? 6 : 1;
+    out.camera = std::string(lr.imgdata.idata.make) + " " + lr.imgdata.idata.model;
+    for (int i = 0; i < 4; i++) out.wbMul[i] = lr.imgdata.color.cam_mul[i];
+    return true;
+}
+
+void RawImage::convertRows(int y0, int rows, uint16_t *dst) const {
+    if (!lr) { std::memcpy(dst, half.data() + size_t(y0) * width * 4, size_t(rows) * width * 8); return; }
+    const ushort(*img)[4] = lr->imgdata.image;
     const float inv = 1.0f / 65535.0f;
-    uint16_t one = rl::floatToHalf(1.0f);
+    const uint16_t one = rl::floatToHalf(1.0f);
     unsigned nt = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
     std::vector<std::thread> pool;
     for (unsigned t = 0; t < nt; t++) {
         pool.emplace_back([&, t] {
-            size_t begin = size_t(h) * t / nt, end = size_t(h) * (t + 1) / nt;
-            for (size_t y = begin; y < end; y++) {
-                const ushort(*src)[4] = img + y * w;
-                uint16_t *dst = out.half.data() + y * w * 4;
-                for (int x = 0; x < w; x++) {
-                    dst[x * 4 + 0] = rl::floatToHalf(src[x][0] * inv);
-                    dst[x * 4 + 1] = rl::floatToHalf(src[x][1] * inv);
-                    dst[x * 4 + 2] = rl::floatToHalf(src[x][2] * inv);
-                    dst[x * 4 + 3] = one;
+            int begin = int(int64_t(rows) * t / nt), end = int(int64_t(rows) * (t + 1) / nt);
+            for (int y = begin; y < end; y++) {
+                const ushort(*src)[4] = img + size_t(y0 + y) * width;
+                uint16_t *d = dst + size_t(y) * width * 4;
+                for (int x = 0; x < width; x++) {
+                    d[x * 4 + 0] = rl::floatToHalf(src[x][0] * inv);
+                    d[x * 4 + 1] = rl::floatToHalf(src[x][1] * inv);
+                    d[x * 4 + 2] = rl::floatToHalf(src[x][2] * inv);
+                    d[x * 4 + 3] = one;
                 }
             }
         });
     }
     for (auto &th : pool) th.join();
+}
 
-    int flip = lr.imgdata.sizes.flip;
-    out.orientation = flip == 3 ? 3 : flip == 5 ? 8 : flip == 6 ? 6 : 1;
-    out.camera = std::string(lr.imgdata.idata.make) + " " + lr.imgdata.idata.model;
-    for (int i = 0; i < 4; i++) out.wbMul[i] = lr.imgdata.color.cam_mul[i];
-    return true;
+void RawImage::materialise() {
+    if (!lr) return;
+    half.resize(size_t(width) * height * 4);
+    convertRows(0, height, half.data());
+    lr.reset();
 }
 
 void rawFromSrgb8(const uint8_t *rgba, int w, int h, RawImage &out) {
```
### kotlin_and_jni.patch (Native.kt, jni_engine.cpp, EditorSession.kt, Exporter.kt, Denoiser.kt, HealOverlay.kt, ModelStore.kt)
```diff
--- a/core/render/src/main/kotlin/app/rawline/core/render/HealOverlay.kt
+++ b/core/render/src/main/kotlin/app/rawline/core/render/HealOverlay.kt
@@ -1,6 +1,5 @@
 package app.rawline.core.render
 
-import android.util.Half
 import app.rawline.core.nativelib.Native
 import kotlin.math.max
 import kotlin.math.min
@@ -54,10 +53,7 @@
                 }
                 ColorSpaces.displayToWorking(r / a, g / a, b / a, tmp)
                 val na = a.coerceIn(0f, 1f)
-                val oldA = Half.toFloat(buf[di + 3])
-                val keep = 1f - na
-                val vals = floatArrayOf(tmp[0] * na + Half.toFloat(buf[di]) * keep, tmp[1] * na + Half.toFloat(buf[di + 1]) * keep, tmp[2] * na + Half.toFloat(buf[di + 2]) * keep, na + oldA * keep)
-                for (k in 0 until 4) { val hv = Half.toHalf(vals[k]); buf[di + k] = hv; out[o + k] = hv }
+                HealMath.over(buf, di, out, o, tmp[0], tmp[1], tmp[2], na)   // no per-pixel array, saturating half conversion
             }
         }
         // upload row by row region at once
--- a/core/render/src/main/kotlin/app/rawline/core/render/Exporter.kt
+++ b/core/render/src/main/kotlin/app/rawline/core/render/Exporter.kt
@@ -79,10 +79,13 @@
             if (handle == 0L) throw IllegalStateException("Could not decode ${photo.name}")
             val info = Native.rawInfo(handle)
             if (recipe.detail.aiDenoise && denoise != null) {
-                kotlinx.coroutines.runBlocking { denoise.invoke(handle, recipe.detail.aiDenoiseAmount) { onProgress(it * 0.4f) } }
+                try { kotlinx.coroutines.runBlocking { denoise.invoke(handle, recipe.detail.aiDenoiseAmount) { onProgress(it * 0.4f) } } }
+                catch (e: OutOfMemoryError) { throw IllegalStateException("Not enough memory for AI denoise at this size. Export without it, or smaller.") }
             }
             // engineSetSource frees the CPU image whether or not the upload worked, so drop our handle before checking.
-            val uploaded = Native.engineSetSource(engine, handle)
+            // a 1:1 export never samples the mips: skip them (a third less GPU memory and time); a downscaled export needs them
+            val needMips = s.longEdge in 1 until max(info[0], info[1])
+            val uploaded = Native.engineSetSource(engine, handle, needMips)
             handle = 0L
             if (!uploaded) throw IllegalStateException("GPU upload failed")
             Native.engineSetBaseCurve(engine, photo.kind == Kind.RAW)
@@ -173,7 +176,12 @@
     private fun decode(photo: Photo): Long {
         val uri = Uri.parse(photo.uri)
         if (photo.kind == Kind.RAW) return context.contentResolver.openFileDescriptor(uri, "r")?.use { Native.decodeRaw(it.fd, false) } ?: 0L
-        val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ -> d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE; d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)) }
+        val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
+            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
+            d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
+            // the editor caps the same file at 8192 (EditorSession.decodeFor); without this a 100 MP JPEG edits fine and then runs out of memory here
+            DecodeCap.target(info.size.width, info.size.height, DecodeCap.FULL)?.let { (w, h) -> d.setTargetSize(w, h) }
+        }
         val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
         val buf = ByteBuffer.allocate(argb.byteCount); argb.copyPixelsToBuffer(buf)
         return Native.rawFromRgba(buf.array(), argb.width, argb.height)
--- a/core/render/src/main/kotlin/app/rawline/core/render/EditorSession.kt
+++ b/core/render/src/main/kotlin/app/rawline/core/render/EditorSession.kt
@@ -125,7 +125,7 @@
                 post(onDrop = { Native.freeRaw(handle) }) {
                     if (gen != generation || engine == 0L) { Native.freeRaw(handle); return@post }
                     val u0 = System.nanoTime()
-                    val ok = Native.engineSetSource(engine, handle).also { Native.engineSetBaseCurve(engine, !finishedPicture) }
+                    val ok = Native.engineSetSource(engine, handle, true).also { Native.engineSetBaseCurve(engine, !finishedPicture) }
                     val upMs = (System.nanoTime() - u0) / 1_000_000
                     onTiming("edit_upload_ms", upMs)
                     if (!ok) { _state.value = SessionState(Stage.ERROR, "GPU upload failed"); return@post }
@@ -191,7 +191,7 @@
         post(onDrop = { d.complete(null) }) {
             val arr = RenderParams.patchSource()
             val buf = ByteArray(pw * ph * 4)
-            if (!Native.engineRenderRegion(engine, arr, pw, ph, x, y, w, h, buf)) { d.complete(null); return@post }
+            if (!Native.engineRenderRegion(engine, arr, pw, ph, x, y, w, h, buf, true)) { d.complete(null); return@post }
             val px = IntArray(pw * ph) { i -> (0xFF shl 24) or ((buf[i * 4].toInt() and 0xFF) shl 16) or ((buf[i * 4 + 1].toInt() and 0xFF) shl 8) or (buf[i * 4 + 2].toInt() and 0xFF) }
             d.complete(android.graphics.Bitmap.createBitmap(px, pw, ph, android.graphics.Bitmap.Config.ARGB_8888))
             requestRender()
@@ -219,7 +219,10 @@
             try { maybeDenoise(handle) } catch (e: Throwable) { Native.freeRaw(handle); throw e }
             post(onDrop = { Native.freeRaw(handle) }) {
                 if (gen != generation || engine == 0L) { Native.freeRaw(handle); return@post }
-                Native.engineSetSource(engine, handle).also { Native.engineSetBaseCurve(engine, !finishedPicture) }
+                // On failure the engine keeps the half size source it had (AE-006), so editing carries on; say so once and do not retry in a loop.
+                val ok = Native.engineSetSource(engine, handle, true)
+                Native.engineSetBaseCurve(engine, !finishedPicture)
+                if (!ok) { _status.value = "Full resolution is not available on this phone for this photo"; requestRender(); return@post }
                 rebuild()
                 _state.value = _state.value.copy(usingFull = true, outW = geometryOutSize[0], outH = geometryOutSize[1])
                 requestRender()
@@ -238,7 +241,7 @@
             val ow = Native.engineOutputSize(engine, arr)
             val h = (w * ow[1].toFloat() / ow[0]).toInt().coerceIn(16, 400)
             val buf = ByteArray(w * h * 4)
-            if (!Native.engineRenderRegion(engine, arr, w, h, 0f, 0f, 1f, 1f, buf)) { d.complete(null); return@post }
+            if (!Native.engineRenderRegion(engine, arr, w, h, 0f, 0f, 1f, 1f, buf, true)) { d.complete(null); return@post }
             val luma = IntArray(256)
             var r = 0.0; var g = 0.0; var b = 0.0
             var i = 0
@@ -263,7 +266,7 @@
             val buf = ByteArray(8 * 8 * 4)
             val hw = 0.006f
             val x = (nx - hw).coerceIn(0f, 1f - 2 * hw); val y = (ny - hw).coerceIn(0f, 1f - 2 * hw)
-            if (!Native.engineRenderRegion(engine, params, 8, 8, x, y, 2 * hw, 2 * hw, buf)) { d.complete(null); return@post }
+            if (!Native.engineRenderRegion(engine, params, 8, 8, x, y, 2 * hw, 2 * hw, buf, true)) { d.complete(null); return@post }
             var r = 0f; var g = 0f; var b = 0f
             for (i in 0 until 64) { r += buf[i * 4].toInt() and 0xFF; g += buf[i * 4 + 1].toInt() and 0xFF; b += buf[i * 4 + 2].toInt() and 0xFF }
             d.complete(floatArrayOf(r / 64f / 255f, g / 64f / 255f, b / 64f / 255f))
@@ -280,12 +283,13 @@
         val d = CompletableDeferred<android.graphics.Bitmap?>()
         post(onDrop = { d.complete(null) }) {
             val g = recipe.geometry.copy(cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f, keystoneV = recipe.geometry.keystoneV, keystoneH = recipe.geometry.keystoneH)
-            val arr = RenderParams.build(EditRecipe(geometry = g), orientation, emptyMap(), useBaseline = !finishedPicture)
+            // what the AI sees must be what the user sees: same lens and manual distortion (AE-017); the frame coordinate stays the pre-warp one
+            val arr = RenderParams.build(EditRecipe(geometry = g, optics = recipe.optics), orientation, emptyMap(), useBaseline = !finishedPicture, lens = lens)
             val ow = Native.engineOutputSize(engine, arr)
             val s = maxEdge.toFloat() / maxOf(ow[0], ow[1])
             val w = (ow[0] * s).toInt().coerceAtLeast(8); val h = (ow[1] * s).toInt().coerceAtLeast(8)
             val buf = ByteArray(w * h * 4)
-            if (!Native.engineRenderRegion(engine, arr, w, h, 0f, 0f, 1f, 1f, buf)) { d.complete(null); return@post }
+            if (!Native.engineRenderRegion(engine, arr, w, h, 0f, 0f, 1f, 1f, buf, true)) { d.complete(null); return@post }
             val px = IntArray(w * h) { i -> (0xFF shl 24) or ((buf[i * 4].toInt() and 0xFF) shl 16) or ((buf[i * 4 + 1].toInt() and 0xFF) shl 8) or (buf[i * 4 + 2].toInt() and 0xFF) }
             d.complete(android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888))
             requestRender()
@@ -369,7 +373,9 @@
                 if (!d.aiDenoise) { _status.value = null }
                 post(onDrop = { Native.freeRaw(handle) }) {
                     if (gen != generation || engine == 0L) { Native.freeRaw(handle); return@post }
-                    Native.engineSetSource(engine, handle).also { Native.engineSetBaseCurve(engine, !finishedPicture) }
+                    val ok = Native.engineSetSource(engine, handle, true)
+                    Native.engineSetBaseCurve(engine, !finishedPicture)
+                    if (!ok) { _status.value = "Update failed"; return@post }   // the previous source is still loaded
                     srcW = info[0]; srcH = info[1]
                     rebuild(); requestRender()
                 }
@@ -461,7 +467,8 @@
     private fun onSurfaceCreated() {
         if (released) return
         synchronized(pending) { glReady = false }
-        if (engine != 0L) Native.engineDestroy(engine)
+        // the old context is gone: its GL names mean nothing here, so free the C++ object without calling GL (engineDestroy would delete names in the new context)
+        if (engine != 0L) Native.engineAbandon(engine)
         engine = Native.engineCreate()
         val err = Native.engineInit(engine)
         if (err != null) { _state.value = SessionState(Stage.ERROR, "GPU init failed: $err"); return }
@@ -519,7 +526,7 @@
         val oh = geometryOutSize[1].toFloat().coerceAtLeast(1f)
         val h = (w * oh / ow).toInt().coerceIn(16, 512)
         val buf = ByteArray(w * h * 4)
-        if (!Native.engineRenderRegion(engine, params, w, h, 0f, 0f, 1f, 1f, buf)) return
+        if (!Native.engineRenderRegion(engine, params, w, h, 0f, 0f, 1f, 1f, buf, true)) return
         val hist = IntArray(256 * 3)
         var i = 0
         while (i < buf.size) {
@@ -529,7 +536,6 @@
             i += 4
         }
         _histogram.value = hist
-        // renderRegion used the shared target; redraw the screen next frame
-        requestRender()
+        // the probe render has its own targets, so the screen is not stale and needs no second frame
     }
 }
--- a/core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt
+++ b/core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt
@@ -120,7 +120,7 @@
                             }
                             e = z.nextEntry
                         }
-                        copy(raw, java.io.OutputStream.nullOutputStream()) {}   // the checksum covers the whole zip, including its trailer
+                        copy(raw, NullSink) {}   // the checksum covers the whole zip, including its trailer
                     }
                 }
             }
--- a/core/ml/src/main/kotlin/app/rawline/core/ml/Denoiser.kt
+++ b/core/ml/src/main/kotlin/app/rawline/core/ml/Denoiser.kt
@@ -33,9 +33,10 @@
         val outBuf = TfModel.floats(tile * tile * 3)
         val disp = FloatArray(tile * tile * 3)
         val tmp = FloatArray(3)
-        // Results are written after all tiles are read, so overlaps never see already denoised pixels.
+        // A tile reads 32 px beyond its core, so results are written one tile row late (DeferredRows): overlaps never see denoised pixels,
+        // and at most two rows of results are alive instead of the whole image (540 MB of floats at 45 MP).
         class Out(val x: Int, val y: Int, val w: Int, val h: Int, val rgb: FloatArray)
-        val results = ArrayList<Out>()
+        val pending = DeferredRows<Out> { Native.rawWrite(handle, it.x, it.y, it.w, it.h, it.rgb) }
         for (ty in 0 until ny) for (tx in 0 until nx) {
             coroutineContext.ensureActive()
             val cx = tx * stride; val cy = ty * stride            // core origin
@@ -68,11 +69,12 @@
                 if (changed < 1e-4f) { outLin[o] = lin[oi * 3]; outLin[o + 1] = lin[oi * 3 + 1]; outLin[o + 2] = lin[oi * 3 + 2] }
                 else { outLin[o] = tmp[0]; outLin[o + 1] = tmp[1]; outLin[o + 2] = tmp[2] }
             }
-            results.add(Out(cx, cy, cw, ch, outLin))
+            pending.add(Out(cx, cy, cw, ch, outLin))
             done++
             onProgress(done / total.toFloat())
+            if (tx == nx - 1) pending.rowDone()   // the row has been read: the row before it can be written now
         }
-        results.forEach { Native.rawWrite(handle, it.x, it.y, it.w, it.h, it.rgb) }
+        pending.finish()
         PerfLog.record("denoise_total_ms (${w}x$h)", (System.nanoTime() - t0) / 1_000_000)
         return true
     }
--- a/core/native/src/main/kotlin/app/rawline/core/nativelib/Native.kt
+++ b/core/native/src/main/kotlin/app/rawline/core/nativelib/Native.kt
@@ -24,13 +24,17 @@
     external fun engineCreate(): Long
     external fun engineInit(h: Long): String?          // null on success, else the GL error text
     external fun engineDestroy(h: Long)
-    external fun engineSetSource(h: Long, raw: Long): Boolean
+    /** Uploads (and frees) a decoded raw. [mips] false skips the mip chain (a 1:1 export never samples them). On failure the previous source stays. */
+    external fun engineSetSource(h: Long, raw: Long, mips: Boolean): Boolean
+    /** The GL context behind this engine is gone: free the C++ object without any GL call (engineDestroy would delete names in the new context). */
+    external fun engineAbandon(h: Long)
     external fun engineSetBaseCurve(h: Long, enabled: Boolean)
     external fun engineSetLayer(h: Long, index: Int, alpha: ByteArray, w: Int, hgt: Int)
     external fun engineSetOverlay(h: Long, rgbaHalf: ShortArray?, w: Int, hgt: Int)
     external fun engineOutputSize(h: Long, params: FloatArray): IntArray
     external fun engineRender(h: Long, params: FloatArray, vx: Int, vy: Int, vw: Int, vh: Int, x: Float, y: Float, w: Float, hgt: Float)
-    external fun engineRenderRegion(h: Long, params: FloatArray, pw: Int, ph: Int, x: Float, y: Float, w: Float, hgt: Float, out: ByteArray): Boolean
+    /** [probe] true for histogram, sample and other small renders: they use their own targets so the screen targets are never resized. */
+    external fun engineRenderRegion(h: Long, params: FloatArray, pw: Int, ph: Int, x: Float, y: Float, w: Float, hgt: Float, out: ByteArray, probe: Boolean): Boolean
     /** 16 bit export: pw*ph*3 unsigned 16 bit values (held in shorts), display encoded. Needs [engineSetHighPrecision] first. */
     external fun engineRenderRegion16(h: Long, params: FloatArray, pw: Int, ph: Int, x: Float, y: Float, w: Float, hgt: Float, out: ShortArray): Boolean
     /** Float32 intermediate and output targets and curve for the 16 bit export; call before the first render. */
--- a/core/native/src/main/cpp/jni_engine.cpp
+++ b/core/native/src/main/cpp/jni_engine.cpp
@@ -59,7 +59,7 @@
         snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
         auto *img = new RawImage();
         std::string err;
-        if (!decodeRaw(path, half, *img, err)) {
+        if (!decodeRaw(path, half, *img, err, /*lazy*/ true)) {
             LOGE("decodeRaw failed: %s", err.c_str());
             delete img;
             return 0;
@@ -123,15 +123,28 @@
 }
 
 // Uploads the decoded raw to the GPU and frees the CPU copy.
-JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineSetSource(JNIEnv *, jobject, jlong h, jlong raw) {
+JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineSetSource(JNIEnv *, jobject, jlong h, jlong raw, jboolean mips) {
     return guarded<jboolean>("engineSetSource", JNI_FALSE, [&]() -> jboolean {
         auto *img = reinterpret_cast<RawImage *>(raw);
-        bool ok = reinterpret_cast<Engine *>(h)->setSource(img->width, img->height, img->half.data());
+        auto *e = reinterpret_cast<Engine *>(h);
+        std::vector<uint16_t> strip;   // a lazy decode converts 256 rows at a time straight from LibRaw's buffer: no second full copy
+        bool ok = img->lr
+            ? e->setSourceRows(img->width, img->height, mips, [&](int y0, int rows) { strip.resize(size_t(rows) * img->width * 4); img->convertRows(y0, rows, strip.data()); return strip.data(); })
+            : e->setSource(img->width, img->height, img->half.data(), mips);
         delete img;
         return ok;
     });
 }
 
+// The GL context is already gone: delete the C++ object without calling GL (Engine::abandon marks it not ready, so ~Engine skips release()).
+JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineAbandon(JNIEnv *, jobject, jlong h) {
+    guardedV("engineAbandon", [&]() {
+        auto *e = reinterpret_cast<Engine *>(h);
+        e->abandon();
+        delete e;
+    });
+}
+
 JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_engineSetLayer(JNIEnv *env, jobject, jlong h, jint idx, jbyteArray data, jint w, jint hgt) {
     guardedV("engineSetLayer", [&]() {
         if (!fits(env, data, w, hgt, 1)) return;
@@ -186,6 +199,7 @@
 JNIEXPORT jfloatArray JNICALL Java_app_rawline_core_nativelib_Native_rawRead(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt) {
     return guarded<jfloatArray>("rawRead", nullptr, [&]() -> jfloatArray {
         auto *img = reinterpret_cast<RawImage *>(h);
+        img->materialise();   // pixel access on the CPU needs the half array (a lazy decode has none)
         if (w <= 0 || hgt <= 0 || w > 16384 || hgt > 16384) return env->NewFloatArray(0);
         std::vector<float> out;
         try { out.resize(size_t(w) * hgt * 3); } catch (...) { return env->NewFloatArray(0); }
@@ -205,6 +219,7 @@
 JNIEXPORT void JNICALL Java_app_rawline_core_nativelib_Native_rawWrite(JNIEnv *env, jobject, jlong h, jint x, jint y, jint w, jint hgt, jfloatArray data) {
     guardedV("rawWrite", [&]() {
         auto *img = reinterpret_cast<RawImage *>(h);
+        img->materialise();
         if (!fits(env, data, w, hgt, 3)) return;
         jfloat *p = env->GetFloatArrayElements(data, nullptr);
         if (!p) return;
@@ -242,14 +257,14 @@
 
 // Renders a region into a Java byte array (RGBA8, top row first). Used for tiles, histogram and thumbnails.
 JNIEXPORT jboolean JNICALL Java_app_rawline_core_nativelib_Native_engineRenderRegion(
-    JNIEnv *env, jobject, jlong h, jfloatArray params, jint pw, jint ph, jfloat x, jfloat y, jfloat w, jfloat hgt, jbyteArray out) {
+    JNIEnv *env, jobject, jlong h, jfloatArray params, jint pw, jint ph, jfloat x, jfloat y, jfloat w, jfloat hgt, jbyteArray out, jboolean probe) {
     return guarded<jboolean>("engineRenderRegion", JNI_FALSE, [&]() -> jboolean {
         if (!fits(env, out, pw, ph, 4)) return false;
         ParamsRef pr(env, params);
         jbyte *o = env->GetByteArrayElements(out, nullptr);
         if (!o) return JNI_FALSE;
         auto unpin = defer([&] { env->ReleaseByteArrayElements(out, o, 0); });
-        return reinterpret_cast<Engine *>(h)->renderRegion(pr.p, pw, ph, {x, y, w, hgt}, reinterpret_cast<uint8_t *>(o));
+        return reinterpret_cast<Engine *>(h)->renderRegion(pr.p, pw, ph, {x, y, w, hgt}, reinterpret_cast<uint8_t *>(o), probe);
     });
 }
 
```

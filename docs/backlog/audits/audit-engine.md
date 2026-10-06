# Engine audit: core/native, core/render, core/ml

Read-only static audit. No repo files edited, no gradle run. Three throwaway probes were compiled outside the repo (scratchpad/probe) against the host LibRaw archive and the sample file /tmp/golden-work/P1055415.RW2 to confirm the decode scale (AE-001) and the half float rounding (AE-028). Line numbers are the current working tree. Sizes: S = under 50 lines, M = 50 to 300, L = over 300 or needs the phone.

Severity: P0 data loss or crash on normal use. P1 wrong or visibly broken output, or a crash/OOM on plausible input. P2 quality, correctness in a feature, or a performance trap. P3 hardening, rare path, polish.

## Lens profile verdict (DECISIONS.md "radius normalisation is unverified")

lensfun and PanoTools sources are not reachable from the sandbox, so the decision rests on the shipped data.
- Vignetting (pa model): half the diagonal is RIGHT, and this is provable from the data. Lumix S 20-60 at 20 mm f/3.5: k1=-0.8703, k2=0.1721, k3=-0.1558. With r=1 at the corner, C(1)=1-0.870+0.172-0.156=0.146 (about 2.8 stops, plausible for that lens). With r=1 at half the short side, the 3:2 corner is r^2=3.25 and C=1-2.83+1.82-5.35=-5.4 (negative, impossible). With half the long side, C=-0.37 (also impossible). So `rv = length(sd) / (0.5 * length(uSrcSize))` in main.frag:244 is correct.
- Distortion (ptlens, poly3, poly5) and TCA: half the SHORT side (inscribed circle) is the PanoTools/Hugin convention and is what `rn = length(q) / (0.5 * min(dims))` does (geometry.glsl:411, main.frag:237). The ptlens identity d = 1-a-b-c (LensProfiles.kt:112) only makes sense with that convention, because it pins r=1 (the short-side edge) as the fixed point. I rate this high but not proven (no lensfun source to read). Direction is right: shader samples source at Rd = Ru * f(Ru) with f = a r^3 + b r^2 + c r + d, which is the lensfun ptlens definition; poly3 (1-k1, 0, k1) and poly5 (1, 0, k1, 0, k2) map correctly; TCA (v, c, b) order matches the parser.
- Recommendation: keep both normalisations, rewrite the DECISIONS line as "vignetting verified against shipped data, distortion/TCA per PanoTools". To close the remaining doubt cheaply, shoot a brick wall at 20 mm on the S 20-60 and compare against the in-camera corrected JPEG (see AE-027 for what is still not handled: calibration aspect ratio and crop factor are ignored).

---

## Findings

### AE-001 P1 Raw white level is halved: clipped neutral highlights land at 0.5 linear
- core/native/src/main/cpp/raw_decode.cpp:20 (`P.highlight = 2`), consequences in shaders/main.frag:106-111, 73-80, shaders/out.frag (base curve), engine/base_curve.h.
- Scenario: LibRaw `scale_colors` divides every multiplier by the LARGEST one when highlight > 0 (`pre_mul[c] /= dmax`). Probe on the sample RW2: pre_mul after processing is (1.000, 0.497, 0.864), maximum 15807. Green, the channel that clips first on neutral highlights, therefore tops out at 0.497 of the 16 bit range. A fully clipped white sky or lamp enters the pipeline at about 0.50 linear. Through the base curve (sRGB 0.7355 -> table index 187 -> 0.941) it displays at about 240/255, never white. The three fitted sample photos never reached clipping (probe: max 30705/28370/40714 of 65535), so the curve fit never saw this; "shoulder forced to white" at input 1.0 is a level that neutral data never reaches.
- Knock-on: every threshold written for white = 1.0 is off by about one stop. The highlights weight `smoothstep(0.45, 0.95, baseG)` (main.frag:110) reaches only about 65 percent at the brightest neutral pixel; Whites +100 (white point 0.65) cannot clip anything; the grading highlight zone (main.frag:77) tops out at luma 0.73; luminance-range masks and the histogram right edge are all compressed. Highlight 0 (clip) would scale G by 1.0 instead (probe: max 60158 / 56850 / 65535).
- Fix: keep blend (mode 2) but rescale so neutral clip = 1.0: multiply by `dmax / pre_mul[G]` (about 2.01 here; `RawImage.wbMul` already carries cam_mul, so it can be applied at upload or as a uniform in main.frag), then refit the base curve with a real clipped-highlight sample so 1.0 maps to white. Re-run goldens (every reference changes by about one stop).
- Size: M (shader uniform plus curve refit); needs phone/golden review.

### AE-002 P1 Negative Dehaze increases contrast instead of adding haze
- shaders/main.frag:129-132
- Scenario: for d < 0, `t = 1.0 - d * 0.5` is greater than 1 (1.5 at -100). `c = c*t + A*(1-t)*dm*(-d)` then equals 1.5c - 0.5*A*dm. Computed with A=0.9, dm=0.5: c=0.0 -> -0.225 (clips to black), 0.1 -> -0.075, 0.45 -> 0.45, 0.8 -> 0.975. Blacks get crushed and highlights brighten: a contrast boost around A*dm, the opposite of adding atmosphere. A real haze lift raises blacks toward A.
- Fix: `float t = 1.0 + d * 0.5; c = c * t + A * (1.0 - t);` (t in 0.5..1, lifts toward A; optionally weight by dm). Add a golden scene for negative dehaze (none exists).
- Size: S.

### AE-003 P1 Local analysis ignores lens vignetting, WB and manual vignette: corners get a spurious Texture/Clarity boost
- shaders/lowres.frag:12-15 builds bs/bl/bd from raw source samples; shaders/main.frag:247 (`c /= corr`), 255, 100 apply gains before `adjust`; shaders/main.frag:116-119 compare them.
- Scenario: `fine = log2(Ym / (bs*gain*tone))` where Ym includes the lens vignetting correction (up to 1/0.146 = 6.8x at the corner of the S 20-60 at 20 mm f/3.5) but bs does not. fine = +2.77, clamped to +2. Default baseline Texture is +25 and Clarity +10 (RenderParams.kt:345-349), so every lens-corrected photo gets `exp2(2*0.25*0.8)` = +32 percent extra gain in the corners from Texture and about +12 percent more from Clarity, on top of the correction. At f/5.6 (correction 2x) it is still about +15 percent. The shadow/highlight weights also read `bl` from the uncorrected image (main.frag:106), so corners are treated as deep shadows and Shadows lifts them harder. Temp changes shift Ym by +-10 percent the same way (smaller).
- Fix: apply the lens vignette and manual vignette gain to `bs`, `bl`, `bd` in main.frag before `adjust` (they are smooth, so multiply the sampled values by the same `1/corr`), or fold the correction into lowres.frag. Include WB luma change only if you want exact parity.
- Size: S.

### AE-004 P1 Heal, clone and remove are placed without the lens distortion the shader applies
- core/ml/.../Healer.kt:280 and 311 (`Geo.frameToSource(...)` with no `lensDist`); core/render/.../Geo.kt:19; shaders/geometry.glsl:409-414.
- Scenario: lens correction is on by default (`Optics.lensCorrection = true`). `srcUv` warps the frame position through the lens polynomial before sampling the source and the overlay, but Healer converts the stroke to source coordinates as if no profile existed. On the S 20-60 at 20 mm the polynomial is 0.951 at the corner, so a stroke at a corner of a 6000 px frame lands about 4 to 5 percent of the radius (roughly 100 to 150 px at 24 MP) away from the object the user touched. The patch and mask are built around the wrong source pixels. Near the centre the error is small, so it passes casual testing.
- Fix: pass `RenderParams.lensDistFor(recipe, session.lens)` to both `frameToSource` calls (the signature already accepts it) and to `Geo.frameHeightPx` users.
- Size: S.

### AE-005 P1 Heal overlay is composited after lens vignetting and TCA but built without them
- shaders/main.frag:243-252 (overlay after `c /= corr`); RenderParams.kt:280 (`patchSource()` has no lens).
- Scenario: `renderSource` produces a patch from the uncorrected source (no vignette gain, no TCA). The shader then replaces the already de-vignetted pixel with the raw-level patch colour, so a heal near a corner is up to 6.8x darker than its surroundings on the 20 mm lens (visible dark blob), and has uncorrected colour fringes.
- Fix: composite the overlay BEFORE the lens vignette divide (move the `uOverlayOn` block above line 243, and apply TCA sampling to the overlay or accept it), or render the patch through the same lens gain and divide the gain out when storing.
- Size: S.

### AE-006 P1 A failed full resolution upload destroys the source and nothing recovers
- core/native/.../engine/engine.cpp:171-184 (`setSource`), core/render/.../EditorSession.kt:222 and 372 (result ignored).
- Scenario: `setSource` deletes the old texture (line 175), allocates the new one, and assigns `srcW_/srcH_` (181) before checking errors. On a 45 MP file the new texture is 8192x5464 RGBA16F plus mips, about 480 MB of GPU memory. If glTexStorage2D returns GL_OUT_OF_MEMORY the function returns false but the engine already has no valid texture and the new dimensions. `ensureFull()` discards the boolean (`.also { setBaseCurve }`), keeps `fullRequested = true`, and the editor then renders black or garbage with no retry and no message. `reloadSource` has the same ignore.
- Fix: allocate and fill the new texture first, swap only on success (keep the old one on failure), set dims after success, return the result, and make EditorSession show a message or retry at reduced size. Also clear stale errors at entry (see AE-034).
- Size: S.

### AE-007 P1 Memory spike on 24 MP and 45 MP decode (CPU copy built twice, plus driver staging)
- core/native/.../raw_decode.cpp:40 (`out.half.assign`), engine.cpp:179 (upload), jni_engine.cpp:126-133.
- Scenario: at the moment `out.half` is filled, LibRaw still holds `imgdata.image` (ushort[4] per pixel = 8 bytes) plus `raw_image` (2 bytes). 24 MP: 192 MB + 48 MB + 192 MB half = about 430 MB peak. 45 MP: 360 + 90 + 360 = about 810 MB, then glTexSubImage2D can stage another copy in the driver (up to 1.2 GB transient), while the editor may also hold its own half size texture and the export service its own context. This is a plausible low-memory kill on the export service (foreground service still counts).
- Fix: convert and upload in strips (about 256 rows) straight from `imgdata.image` with `glTexSubImage2D`, no `out.half` at all; at minimum `lr.recycle()` before upload by moving the conversion into a 16 bit to half pass that frees the source rows as it goes (not possible in place, so strip upload is the clean answer). Skip `glGenerateMipmap` when exporting at 1:1 (AE-025).
- Size: M.

### AE-008 P1 Exporter decodes non-raw sources at full size with no cap, through the Java heap
- core/render/.../Exporter.kt:171-178 (compare EditorSession.kt:437-438 which caps at 3072/8192).
- Scenario: a 100 MP JPEG or a large stitched PNG decodes to a 400 MB software bitmap, `ByteBuffer.allocate(argb.byteCount)` adds another 400 MB on the Java heap (OOM on a 512 MB heap limit), `rawFromRgba` then builds an 800 MB half copy. The editor path caps the same file at 8192, so the file edits fine and then fails to export.
- Fix: cap with `setTargetSize` to the GPU limit (query GL_MAX_TEXTURE_SIZE, 8192 is safe), allocate direct buffers or hand the bitmap to JNI through `AndroidBitmap_lockPixels` to avoid the heap copy.
- Size: S for the cap, M for the zero-copy path.

### AE-009 P1 AI denoise keeps the entire image as floats on the Java heap
- core/ml/.../Denoiser.kt:157-158, 191, 195 (`results` list of `outLin`), per-tile allocations at 163, 172, 174-175.
- Scenario: all tile results are held until every tile is read. Half size preview (3000x2000): 72 MB, fine. Export at 24 MP: 288 MB of FloatArrays; at 45 MP: 540 MB, over the heap limit on most devices, so AI denoise export throws OutOfMemoryError (not caught as an Exception subclass in the export path). Per tile it also allocates about 6 MB of arrays (`lin`, `den`, three `chan`, three `low`, `outLin`); 672 tiles at 24 MP is about 4 GB of churn.
- Fix: write results back in a deferred batch of rows (only the overlap needs the unmodified data: keep a 32 px strip of originals), or write to a second native buffer via `rawWrite` per tile into a separate output handle and swap. Reuse the per-tile arrays.
- Size: M.

### AE-010 P2 Histogram, sample and stats renders thrash the shared targets and stall the GPU on every slider tick
- core/render/.../EditorSession.kt:144 (`wantHistogram = true` on every `setRecipe`), 509-512, 516-534; engine.cpp:149-158 (`ensureTarget`), 423-456.
- Scenario: each slider move: screen frame (e_ at viewport + 16), then `computeHistogram` -> `renderRegion` resizes e_ to 272 px and allocates outT_, so `ensureTarget` frees and recreates the textures and FBOs, then does a synchronous `glReadPixels` (a full pipeline stall mid-frame), then `requestRender()` (line 533) queues a SECOND screen frame, which resizes e_ back (another 20 MB alloc/free at 1080x2300 RGBA16F). So each tick costs two screen-size render targets rebuilt, one stall and one redundant frame.
- Fix: give small renders their own targets (`eHist_`, `outHist_`) so the screen target is never resized; read back with a PBO and one frame of latency; drop the `requestRender()` at line 533 (the histogram does not change the screen); debounce to 100 ms during drags. `sample()` (260-273), `baseStats` and `renderFrame` also resize e_ and invalidate the analysis key (two analysis passes each).
- Size: M.

### AE-011 P2 After a GL context loss the dead engine is destroyed inside the new context and leaves a GL error that fails the next upload
- core/render/.../EditorSession.kt:464 (`engineDestroy(engine)` before `engineCreate`), engine.cpp:160-169 (`release`), 183 (`glGetError()` at end of `setSource`).
- Scenario: `release()` runs `glDeleteProgram` with ids that belong to the lost context. In the fresh context those names do not exist, so ES sets GL_INVALID_VALUE. Nothing clears it, `load()` then calls `engineSetSource`, whose final `glGetError() == GL_NO_ERROR` sees the stale error and returns false, so the editor shows "GPU upload failed" after exactly the scenario the restore code is meant to handle. (Deleting stale texture names in a new context is also unsafe if anything else already created objects there.)
- Fix: add `Engine::abandon()` (no GL calls, just delete the C++ object) for the lost-context path; in `setSource` and every `glGetError`-returning method, drain errors at entry.
- Size: S.

### AE-012 P2 Mask blocks measure local contrast against a baseline that excludes the global edit
- shaders/main.frag:92-119 via 269 (`adjust(c, 1+m, bs, bl, bd)` receives the already globally adjusted `c`).
- Scenario: for a mask block, `fine`/`broad` compare Ym (after global exposure, tone, WB) with `bs * gain_local * tone_local` (source luminance and only the local gain). Global exposure +1 makes log2 ratio about +1 everywhere, so a mask with Clarity +50 brightens the whole masked area by up to exp2(1*0.5*0.8*mid) (+32 percent at mid-tones) regardless of detail. Highlights/Shadows weights (106-110) and Dehaze A/dm (123-124) use un-adjusted `bl`/`bd` as well, so zone selection is wrong whenever the global exposure is not 0.
- Fix: pass the global gain*tone into the mask call (scale `bs/bl/bd` by the global gain and tone product before the local `adjust`), or compute the local terms from the block-0 output luminance.
- Size: S.

### AE-013 P2 HSL colour bands are not a partition of unity, so slider strength depends on hue
- shaders/main.frag:55-62, 150-157.
- Scenario: band centres are uneven (red 0, orange 0.083, yellow 0.167, green 0.333, aqua 0.5, blue 0.667, purple 0.778, magenta 0.889) with a fixed 0.133 reach. A pure orange pixel gets orange 1.0 plus red 0.32 plus yellow 0.32 (1.64x the slider). A mid-green pixel gets exactly 1.0, and a pixel at hue 0.25 (between yellow and green) gets only 0.63 total. The same slider moves orange 1.6x harder than green, and colours between yellow and green are under-corrected.
- Fix: normalise (divide the three sums by the total weight, treating 0 as identity weight) or use triangular bands with width equal to neighbour spacing.
- Size: S.

### AE-014 P2 Colour grading tint is not luminance neutral
- shaders/main.frag:82-88.
- Scenario: `hsv2rgb(h,1,1) - 0.5` has channel sum +0.5 for yellow/cyan/green hues and -0.5 for red/blue/magenta. Shadows hue = yellow at sat 100 adds +0.175 to R and G and -0.175 to B in gamma space, a Rec 709 luma lift of about +0.15 (computed 0.428 * 0.35). The user wanted a colour change and gets a brightness shift whose sign depends on the hue picked.
- Fix: subtract the tint colour's own luma (`ts -= dot(ts, vec3(0.2126,0.7152,0.0722))`), or build the tint as `(rgb - mean(rgb))`.
- Size: S.

### AE-015 P2 Colour range and luminance range masks compare in a different domain from the picker
- shaders/main.frag:208-217 versus EditorSession.kt:260-273 (`sample()`) and MaskingFeature.kt:530-541.
- Scenario: the picker stores the DISPLAY colour (sRGB encoded, after ProPhoto to sRGB, after the base curve, 8 bit). The shader compares `toGamma(c)`, which is pow(1/2.2) of linear ProPhoto before the base curve. Mid-grey display 0.50 corresponds to working gamma about 0.28, so the exact picked colour sits about 0.22 per channel (distance 0.38) from itself and is only partly selected at the default range 0.5. Luminance range uses the same working gamma, while the UI bar is labelled in display terms.
- Fix: convert working to display in the shader (base curve plus sRGB OETF through `uBase`) for masks 4 and 5, or have `sample()` return working-gamma values (render with a flag to skip the base curve and matrix). After AE-001 is fixed re-check the defaults.
- Size: S to M.

### AE-016 P2 Manual lens vignetting correction is centred on the crop, not the lens axis
- shaders/main.frag:254-255.
- Scenario: `d = (p - 0.5) * vec2(uAspect, 1)` uses the post-crop coordinate `p` and the cropped aspect. After cropping into a corner the correction (and its sign) is applied relative to the crop centre. The profile based correction (244) uses source coordinates and is right; the manual slider is inconsistent with it.
- Fix: use the same source-centred `sd`/diagonal normalisation as the profile path (`length(sd) / (0.5*length(uSrcSize))`).
- Size: S.

### AE-017 P2 AI mask input frame ignores lens and manual distortion that the display applies
- core/render/.../EditorSession.kt:279-294 (`renderFrame`: `EditRecipe(geometry = g)`, no lens, default Optics).
- Scenario: masks are sampled at the pre-warp frame coordinate `c` while the picture on screen has had lens and manual distortion applied. The subject/sky/object masks are computed on an image that was not lens corrected, so with lens correction on (default) the mask edge is displaced relative to the picture by up to about 4 percent of frame height at the corners of a 20 mm shot (about 150 px at 24 MP), and `optics.distortion` produces the same effect. Brush masks are unaffected (drawn on the displayed frame).
- Fix: pass `lens` and `recipe.optics` into `renderFrame` so the AI sees what the user sees (the frame coordinate is still the pre-warp `c`, so no mask math changes).
- Size: S.

### AE-018 P2 "16 bit" TIFF is about 11 bits, and untagged colour
- engine.cpp:432 (`outT_` is RGBA16F), 446-451 (float read repacked to half), shaders/out.frag (uBase is R16F, uOutLinear unused), Exporter.kt:130 and 182-208.
- Scenario: the output is display-referred 0..1 stored in half float. Half has 11 significant bits, so between 0.5 and 1.0 the step is 1/2048 (32 distinct 16 bit levels collapse to one); smooth skies show banding in a "16 bit" file. The path also converts float to half in C++ then half to float to short in Kotlin. `uOutLinear` is set but never read, the JNI comment says "linear" but the data is sRGB/P3 encoded. The TIFF has no ICC profile or colour space tag, so a Display P3 export is read as sRGB by every viewer (visibly desaturated or shifted).
- Fix: render the TIFF output into RGBA32F (or RGBA16UI with manual packing), read floats directly and quantise once; use an R32F/RGBA32F base-curve LUT in that mode; embed an ICC (sRGB and Display P3 profiles are small) via tag 34675. Delete `uOutLinear`.
- Size: M.

### AE-019 P2 The detail pass runs on every frame because of the baseline sharpen, and wastes work when NR is off
- shaders/out.frag:44-85, RenderParams.kt:335 (`d.sharpen + Baseline.SHARPEN` = always at least 45).
- Scenario: `sharp > 0` is true for every raw, so each pixel does 25 texelFetch, 25 `pow(.,1/2.4)`, 50 `exp` and the Y weights, then (when sharpening) a pow again, on every preview frame. The bilateral weights and `acc/wsum` are computed even when luminance NR is 0 (default), and `den` is then mixed with weight 0. At 1080x2300 that is about 220 M transcendental ops per frame, spent on every pan and slider tick, on a thermally limited phone.
- Fix: guard the NR accumulation with `nrL > 0`; precompute luma^(1/2.4) once into the alpha or a second channel of `e_` (alpha currently carries `inside`, move it to a mask bit), drop `exp` to a small constant table, use a separable blur for the sharpen base `yb`.
- Size: M. Needs phone timings to confirm the gain (PERF.md has no frame numbers yet).

### AE-020 P2 Texture and Clarity cannot act on fine detail
- core/native/.../engine/engine.cpp:20 (`kAnalysisEdge = 512`), 314-331; shaders/main.frag:114-119.
- Scenario: `bs` is the sharpest layer, a Gaussian of about 2.5 px at 512 px, then bilinear sampled. At 6000 px that is a radius of about 30 px, at 3000 px preview about 15 px. "Texture" (fine detail, a few px in the reference editor) therefore boosts mid-frequency structure only, with halo risk on strong edges, and Texture +25 baseline is a mid-frequency lift instead of pixel-level micro contrast.
- Fix: compute a small-radius blur at the working (output) resolution (separable 9 to 13 tap in the detail pass or a second analysis target at 1/2 resolution) for Texture; keep 512 for Clarity, Highlights, Shadows.
- Size: M.

### AE-021 P2 LibRaw is built single threaded (no OpenMP), AHD for full decodes
- core/native/src/main/cpp/CMakeLists.txt:459-463 (no `-fopenmp`); LibRaw `libraw_types.h:44` only defines LIBRAW_USE_OPENMP when `_OPENMP` is defined; raw_decode.cpp:22.
- Scenario: the AHD demosaic loops in LibRaw are `#pragma omp` guarded, so on the phone full decodes run on one core. PERF.md already notes 0.9 s for a half-size decode on x86 (single thread); a full 24 MP AHD is several seconds on a phone core, which makes the "Export full-res 24 MP JPEG under 3 s" target and the zoom-in swap-over (EditorSession.kt:209-228) unreachable, and 45 MP much worse.
- Fix: compile LibRaw with `-fopenmp` and link libomp (NDK ships it), or switch the full decode to DHT/AAHD? (check quality) or demosaic on the GPU. Add `-O3 -DNDEBUG`.
- Size: S for OpenMP, L for a GPU demosaic.

### AE-022 P2 Mask layer resolution is capped at 1024 by 1024 and stretched anisotropically
- engine/params.h:14 (`kLayerTex = 1024`), engine.cpp:187-214; BrushLayer.kt:129 stores 2048 on the long edge.
- Scenario: a 2048x1365 brush or AI layer is bilinear-resampled to 1024x1024 (halving horizontal detail, 0.75x vertically with non-uniform scale). In a 6000x4000 export each mask texel covers about 6 by 4 px, so brush edges and AI edge refinement (the guided filter works at 1024 to 2048) lose their precision, and thin features (hair, wires) smear. The previous pass fixed correctness (layers of different sizes) but at a quarter of the stored resolution.
- Fix: use a 2048x2048 R8 array (16 layers = 64 MB, or allocate by actual layer count), or an array texture with layers at the common long-edge size and aspect uniform; skip the resample by matching the stored size.
- Size: S to M.

### AE-023 P2 White balance multiplies in ProPhoto with no luminance compensation
- shaders/main.frag:100.
- Scenario: `c *= (2^(0.012 t), 2^(-0.006 tint), 2^(-0.012 t))` in linear ProPhoto. Blue contributes 0.0000857 to luma, so lowering blue does not compensate raising red: Temp +50 raises Y by about 14 percent, Temp -50 lowers it by about 10 percent. Exposure appears to drift with the Temp slider. Multiplying ProPhoto primaries (non-physical, partly negative sRGB projections) also shifts hue relative to a camera-space shift; the "approximation" in DECISIONS.md is documented, the luminance drift is not.
- Fix: renormalise the multipliers so luma(wb) = 1 (`wb /= dot(wb, Y)` for a neutral reference, or divide by `dot(wb, Y)`), or apply WB with the camera matrix (the LibRaw cam_mul and rgb_cam are already available).
- Size: S.

### AE-024 P2 RawPrefetch leaks a native decode when cancelled mid-flight
- core/render/.../RawPrefetch.kt:230-245.
- Scenario: `cancel()` clears `ready` and `wanted` and frees the stored handles, but an in-flight job keeps running (the scope is never cancelled) and then does `ready[p.id] = h` after the clear. That handle (48 MB at 24 MP, 100 MB at 45 MP) is never freed unless a later `take` finds it; with `capacity = 3` this repeats on every library to editor transition.
- Fix: keep a generation counter; compare before storing and free the handle if cancelled; cancel the scope in `cancel()`.
- Size: S.

### AE-025 P2 Export builds a full mip chain and can hold two full resolution textures
- engine.cpp:171-184 (`glGenerateMipmap` always), Exporter.kt:83 (own context), EditorSession.kt:209-228.
- Scenario: for a full size export (no scale down) mips are never sampled (lod 0) but cost 33 percent more memory and time. If the editor is open and zoomed in (full source resident, 477 MB at 45 MP) while the export service creates a second context and uploads another full source, the process holds about 1 GB of GPU memory plus the CPU spike from AE-007.
- Fix: `levels = 1` when `ow/pw <= 1` for the export engine (add a flag to `setSource`), serialise export against editor full-res residency (drop the editor's full texture on export start, or share the context).
- Size: S to M.

### AE-026 P2 Heal overlay colour conversion assumes the camera base curve even for JPEG, HEIC and PNG
- core/render/.../ColorSpaces.kt:8 (`Native.baseCurve()` is always the camera curve), 10-22, HealOverlay.kt:144; EditorSession.kt:128 (`engineSetBaseCurve(!finishedPicture)`).
- Scenario: for a finished picture the engine draws with an identity base curve, so `renderSource` returns sRGB. `displayToWorking` then inverts the camera curve (shadows lowered about 4.3x), so every patch placed on a JPEG is stored far too dark (mid-tones about 0.5 display land near 0.28 working) and shows as a dark blot after the identity curve. Raw is fine because the pair is consistent. The Denoiser round trip uses the same pair, so it only changes the model's input domain for JPEG.
- Fix: make `ColorSpaces` take a `useBase` flag (identity inverse and forward when false) and pass `!finishedPicture` from Healer, HealOverlay and Denoiser.
- Size: S.

### AE-027 P2 Lens profile: vignetting uses the nearest calibration (no interpolation) and aspect ratio and crop factor are ignored
- core/render/.../LensProfiles.kt:62-66, 88-98 (`cropfactor` and calibration `aspect` never read), 119-132.
- Scenario: distortion and TCA are interpolated by focal length (59-60), vignetting jumps to whichever calibration is nearest, so on a zoom the correction visibly steps at the midpoint between calibrated focal lengths (for example 20 to 30 mm of the 20-60: k1 changes -0.87 to -0.73 between entries) and across apertures. lensfun interpolates vignetting over focal, aperture and distance. The calibration aspect ratio and cropfactor in the XML are dropped, so APS-C lenses in the file (Sigma DC DN, Leica TL, cropfactor 1.534) and 16:9 or 1:1 captures use a wrong radius unit.
- Fix: bilinear interpolate vignetting over focal and log aperture (coefficients linear), read `cropfactor` and `aspect`, scale `rn` by the calibration to actual short-side ratio.
- Size: M.

### AE-028 P3 floatToHalf rounds 65520 to 65535 up to Infinity
- core/native/.../engine/halfs.h:23 (`if (mant & 0x1000u) h++`), 13.
- Scenario: confirmed by running the function: 65519 -> 0x7BFF (65504), 65520 -> 0x7C00 (inf), 65535 -> inf. The overflow guard only covers `exp >= 31`, but the mantissa carry from exp 30 to 31 produces Inf. Reachable through `rawWrite` (AI output) or heal overlay data if a value exceeds 65519; it ends up as Inf in the texture, then NaN after blur or dehaze, a black or white pixel cluster that spreads through the analysis blur.
- Fix: after rounding, `if ((h & 0x7FFF) >= 0x7C00) h = (h & 0x8000) | 0x7BFF`.
- Size: S.

### AE-029 P3 Clarity weight `mid` goes negative above 1.0
- shaders/main.frag:118.
- Scenario: `mid = 1 - abs(pow(Ym,1/2.4)*2 - 1)` becomes negative for Ym above 1 (pow above 1 gives |.| above 1). After a positive exposure, clarity reverses sign on super-white pixels (smoothing instead of adding contrast), and a hard step appears at Ym = 1.
- Fix: `max(mid, 0.0)`.
- Size: S.

### AE-030 P3 Grading zones use Rec 709 luma on ProPhoto gamma values
- shaders/main.frag:73 (`dot(g, vec3(0.2126, 0.7152, 0.0722))`).
- Scenario: `g` is gamma 2.2 of linear ProPhoto, where the blue weight is about 0. Saturated blues are classified as brighter than they are (Rec 709 gives blue 0.07 against a ProPhoto 0.00009) and sit in the wrong zone, so Shadows/Highlights tints hit deep blues unevenly.
- Fix: use the shader's own `Y` weights on linear before gamma.
- Size: S.

### AE-031 P3 Luma-ratio sharpening and chroma NR degenerate when ProPhoto luma is near 0
- shaders/out.frag:81 (`c *= lin / max(dot(c, Y), 1e-5)`), 70 (`avg / ya`).
- Scenario: ProPhoto saturated blues (stage LEDs, blue flowers) have `dot(c, Y)` near zero (c = (0,0,0.1) gives 8.6e-6). The scale `lin / 1e-5` can multiply such a pixel by 10 (sparkle) or zero it when neighbours are slightly brighter (black hole). The same near-zero denominator makes `chroma = avg / ya` explode in the colour NR.
- Fix: add luma-ratio guards (clamp the ratio to 0.5..2, and fall back to additive sharpening when luma is below about 1e-3), or sharpen on max(r,g,b) for those pixels.
- Size: S.

### AE-032 P3 Colour noise reduction smears colour across edges
- shaders/out.frag:63-73.
- Scenario: `avg` is an unweighted 5x5 (stride up to 3, so up to 13 px) box; chroma of the average replaces chroma at the pixel, so a red edge on a blue background bleeds colour 6 px each way at strength 100, and the effect is applied with no edge weight.
- Fix: weight the average with the same bilateral `w`, or limit colour NR to pixels whose luma gradient is low.
- Size: S.

### AE-033 P3 Dehaze uses a blurred per-pixel minimum, not a patch minimum
- shaders/lowres.frag:14, engine.cpp:328-330.
- Scenario: the true dark channel prior takes the minimum over a patch; here min(r,g,b) per pixel is blurred (a mean), so bright neutral areas (white walls, overcast sky) read as heavy haze and Dehaze + darkens or boosts them heavily.
- Fix: a min filter pass (3 or 5 taps, separable) on `l0.y` before the blurs.
- Size: S.

### AE-034 P3 No GL error discipline in the setters and no FBO completeness checks
- engine.cpp:100-109, 149-158, 171-184, 403-421 (no check at all in `renderToScreen`), 216-230.
- Scenario: only `setSource` and `renderRegion` end with `glGetError`; `setSource` never drains stale errors first (one leftover error from any other GL call makes a good upload report failure, see AE-011), `ensureTarget` never calls `glCheckFramebufferStatus` (RGBA16F is not renderable on a GL ES 3.0 device without EXT_color_buffer_float), and `renderToScreen` swallows every error, so a failed render is a silent black frame.
- Fix: a `drainErrors()` helper at the top of each public method, a completeness check in `ensureTarget`, and a status return from `renderToScreen`.
- Size: S.

### AE-035 P3 Per-frame `glGetUniformLocation` string lookups
- engine.cpp:268-275, 306-322, 349-368, 375-401 (about 60 lookups per frame).
- Fix: cache locations after link in a struct per program.
- Size: S.

### AE-036 P3 `Engine::init` leaks on failure; `release()` ignores a half-built engine
- engine.cpp:79-98, 111-116, 160-161.
- Scenario: `build` does not delete the shader that compiled when its partner fails, nor the program on link failure; `init` leaves `lowres_` and `blur_` programs alive when `main_` fails, and `release()` returns early because `ready_` is false.
- Fix: RAII wrappers or cleanup in `build`.
- Size: S.

### AE-037 P3 Unsynchronised static initialisation of colour matrices
- engine.cpp:388-396.
- Scenario: `static float msrgb[9], mp3[9]; static bool init` is written by whichever thread renders first. The editor GL thread and an export thread can race; the non-atomic flag can be seen true before the matrix is visible (zero matrix, black frame). Low probability in practice because the editor usually initialises it first.
- Fix: `constexpr` tables or `std::call_once`.
- Size: S.

### AE-038 P3 JNI hardening gaps
- jni_engine.cpp:23-27 (`ParamsRef`: no length check against kParamFloats, a short array reads past the end), 86-94, 186-203 (`NewIntArray`, `NewFloatArray` results unchecked then used), 119-133 and 104-117 (handle 0 dereferences: `engineCreate` can return 0 and the caller passes it on).
- Fix: check `GetArrayLength >= kParamFloats`, null-check new arrays, and null-check handles at entry (return an error value).
- Size: S.

### AE-039 P3 RW2 and TIFF preview parser requires 4 bytes after the last IFD entry
- core/native/.../rw2_preview.cpp:331-332 (`buf(n*12+4)` then `r.read` all or nothing).
- Scenario: an IFD that ends exactly at the end of the file (no next-IFD offset written) fails the whole read, and the preview in that IFD is skipped.
- Fix: read `n*12` and then try the 4 bytes separately (treat a short read as next = 0).
- Size: S.

### AE-040 P3 Decode depends on re-opening the descriptor by path, and compressed DNG variants are compiled out
- jni_engine.cpp:58-59 (`/proc/self/fd/%d`), CMakeLists.txt:459-463 (no `USE_ZLIB`, no `USE_JPEG`; the source is guarded in `src/decoders/fp_dng.cpp:159,328` and `libraw_datastream.cpp:30,37`).
- Scenario: reopening `/proc/self/fd/N` re-runs the permission check on the target inode, which works for files in shared storage but fails for providers that hand out descriptors to files the app cannot open by path (some SAF providers and cloud-backed documents). Deflate-compressed and lossy JPEG compressed DNG (float or HDR DNG, some phone DNGs) are unsupported builds. RW2 is not affected.
- Fix: implement a `LibRaw_abstract_datastream` over the fd with `pread` (also removes the second open), define `USE_ZLIB` and link zlib; leave `USE_JPEG` out unless lossy DNG is wanted.
- Size: M.

### AE-041 P3 Orientation handling drops mirrored flips; the mirror-then-rotate combination is wrong in the shader
- raw_decode.cpp:63 (flip 1, 2, 4, 7 map to orientation 1), shaders/geometry.glsl:418-424 and Geo.kt:45-47.
- Scenario: LibRaw maps TIFF orientation through "50132467", so mirrored flips exist as 1, 2, 4, 7 and are reported as no rotation. Separately, the shader folds the EXIF mirror (flip0) into the display-axis flip before the user's 90 degree rotation, which is correct only for rot0 + user rotation even or flip0 false; EXIF mirror plus a user rotate of 90 flips the wrong axis. Not reachable today (decodeRaw never emits a mirrored orientation, and ImageDecoder pre-rotates bitmaps), so this is dormant.
- Fix: map all LibRaw flips; apply flip0 after the rotation in `srcUv` and `Geo.map`.
- Size: S.

### AE-042 P3 EditorSession cross-thread visibility
- EditorSession.kt:72-73, 68, 71 (`srcW`, `srcH`, `photo`, `orientation` plain fields written on the GL thread, read on the main thread via `sourceWidth`, `baseAspect()`); 110, 359 (`generation++` from main thread and from `reloadSource`, a volatile increment is not atomic).
- Fix: `@Volatile` on the fields (or one immutable snapshot object), `AtomicInteger` for `generation`.
- Size: S.

### AE-043 P3 `destroyEngine` can block the main thread for 500 ms on every detach
- EditorSession.kt:395-401, EditorGlView.kt:263-266.
- Scenario: `onDetachedFromWindow` waits up to 500 ms for the GL thread. If it is mid `ensureFull` upload (a 45 MP `glTexSubImage2D` takes well over 100 ms) or paused, the main thread stalls; if the wait times out the engine is destroyed after the thread has exited (leak until context teardown).
- Fix: never wait on the main thread; destroy on the GL thread from a queued event and let GLSurfaceView's own teardown run the same code.
- Size: S.

### AE-044 P3 AI denoise looks different in preview and export
- EditorSession.kt:119 (half-size 2x2 binned decode) versus Exporter.kt:76-81 (full size).
- Scenario: the model runs on binned data in the editor (lower noise, a quarter of the pixels, details at twice the scale) and on full-resolution data at export, so the same Amount produces different results; tile geometry is also different (176 tiles versus 672).
- Fix: preview with the half-size decode but document it; or run the denoiser on the 100 percent crop currently visible; at minimum scale `amount` by about 1.3 at full size after a phone comparison.
- Size: M (needs phone comparison).

### AE-045 P3 AI preprocessing and post-processing small defects
- AiMasks.kt:439-441 and 438 (`createScaledBitmap(..., true)` from 1024 to 256 is a 4x bilinear downscale, aliasing on fine texture; aspect is squashed to a square for sky and people, which is acceptable for those models but should be tested), 464 and 468-469 (the guided filter runs at half size and is sampled with nearest neighbour, giving 2 px steps on the mask), 305 (`invalidate()` writes `embedding` without `encodeLock`, can race an in-flight `encodeLocked`).
- Fix: pre-blur or multi-step downscale, bilinear sample the filter result, take the lock in `invalidate()`.
- Size: S.

### AE-046 P3 `Geo.fitCrop` cost on every straighten, keystone or crop drag step
- core/render/.../Geo.kt:123-160, 137-151.
- Scenario: the cache only helps when the geometry is unchanged. A changed key runs up to 22 bisection steps, each testing up to 17 placements, each with 4x65 edge samples: about 97 000 `map` evaluations (sin, cos, sqrt) on the GL thread via `rebuild()` per drag event, an estimated 5 to 15 ms on a phone (not measured). Placements also quantise to 1/16 of the move distance, so the fitted crop steps rather than slides.
- Fix: coarse-to-fine sampling (8 then 64 samples near failures), early exit order (test the corners first), and refine `t` by bisection.
- Size: S to M.

### AE-047 P3 Heal overlay allocates per pixel and copies 50 MB buffers
- HealOverlay.kt:148 (`floatArrayOf(...)` per pixel: about 1 M allocations for a 1024 px patch), 111, 157, 159 (`buf.copyOf()` 50 MB each for init, resend and clear; the copy sits in the GL queue until consumed).
- Fix: write into a local array, send clear/resend via a native-side clear, reuse one scratch array.
- Size: S.

### AE-048 P3 Grain differs between preview and export, and the seed is never set
- shaders/out.frag:97 (`max(size*0.04*uPxScale, 1.0)`), RenderParams.kt:340 (G_FX2+3 never written).
- Scenario: at fit zoom (uPxScale about 0.56) the cell is clamped to 1 screen pixel, giving relative grain about twice as coarse as the 6000 px export (cell about 3 px of 6000); the seed slot is always 0 so every photo has the same noise pattern.
- Fix: no clamp (or clamp at 1 reference pixel in uPxScale units), seed from the photo id.
- Size: S.

### AE-049 P3 Export tile buffers are heavy for TIFF16 and large tiles
- Exporter.kt:122-126, engine.cpp:448.
- Scenario: per 2048 px tile the TIFF path holds `band` (about 74 MB at 6000 px wide, 110 MB at 8000), `half` 33 MB (Java heap) and a 67 MB `std::vector<float>` in native code. Near the limit on 512 MB heap devices with the AI denoise results (AE-009) still referenced.
- Fix: 1024 px tiles for TIFF, write rows from `band` in place, read directly into the caller's buffer.
- Size: S.

### AE-050 P3 Heal "remove" patch resolution is fixed at 512 px
- Healer.kt:291-297.
- Scenario: the LaMa patch is 512x512 stretched over a region of up to the short side of the photo (side = box * 2.2 + 96, up to 4000 px at 24 MP). A 500 px object makes a region of about 1200 px, a 2.3x enlargement, so the filled area and its soft 3 px alpha edge (also scaled) are noticeably softer than the surroundings.
- Fix: process large regions in 512 tiles with overlap, or blend only the masked interior and use texture from the original outside it.
- Size: M.

### AE-051 P3 Local analysis also ignores the heal overlay
- engine.cpp:277-331, shaders/lowres.frag.
- Scenario: highlight and shadow weights and Texture use the pre-heal luminance, so a removed bright object still drives its old local tone. The cache key excludes it (correct for performance), the effect is small.
- Fix: optional, rebuild `l0` with the overlay when `overlayOn` changes (key it).
- Size: S.

### AE-052 P3 TfModel: FP16 GPU delegate and a zero-input warm-up
- core/ml/.../TfModel.kt:55-63 (`GpuDelegate()` default options), 65-70 (`warm` with zero tensors only proves the graph runs, not that outputs are sane).
- Scenario: NAFNet and LaMa on the GPU delegate run with reduced precision by default; a delegate that runs but returns blocky or shifted output is remembered as the good choice (line 43) and cannot recover.
- Fix: warm with a known test input and compare to the CPU result once (cache the verdict), and allow FP32 for denoise.
- Size: M.

### AE-053 P3 Preview parser, `findPreview` and `readBytes` can return non-JPEG or oversize ranges
- rw2_preview.cpp:317-323 only checks SOI; jni_bridge.cpp:428-437 allocates `len` as given (up to 128 MB by `consider`).
- Scenario: a crafted file can pass SOI with a 128 MB length and make `NewByteArray` plus the copy of the pinned array a 256 MB spike on the main process. Real RW2 previews are 1 to 3 MB.
- Fix: cap preview length at about 32 MB (a 45 MP embedded preview is below that), and verify EOI near the end.
- Size: S.

### AE-054 P3 Denoise output is clamped to display 1.0, discarding headroom
- Denoiser.kt:180-182, ColorSpaces.kt:45-47.
- Scenario: any pixel the model changes by at least 1e-4 is clamped to 0..1 in display and converted back, so highlight data above 1.0 working (reachable in R/B heavy highlights today, and in all highlights after AE-001) is cut, and out-of-sRGB-gamut colours are clipped to the sRGB gamut.
- Fix: apply the delta in working space (scale the original by the display-domain ratio) instead of replacing the pixel.
- Size: S.

### AE-055 P3 Resolution-dependent behaviour of the detail kernels
- shaders/out.frag:42-43, 47 (`st = clamp(round(radius*sc), 1, 3)`).
- Scenario: the radius is quantised to 1..3 tap spacings, so at export size (sc 3 or more) every radius from 1 px up is the same 3 px spacing; the 5x5 taps then skip two of every three neighbours, aliasing fine detail into the sharpening base (`yb`) and leaving residual noise at 1 to 2 px untouched by luminance NR.
- Fix: a proper separable Gaussian whose sigma is radius*sc, using a mip or a pre-blurred texture.
- Size: M.

---

## Counts

P0: 0. P1: 9 (AE-001 to AE-009). P2: 18 (AE-010 to AE-027). P3: 28 (AE-028 to AE-055). Total 55.

## Things checked and found correct (no action)

- Tiling seams: 8 px margin covers the largest detail reach (st 3 times 2 taps = 6 px), `mx = vis.w*margin/pw` gives exact pixel centres, grain uses whole-image pixel coordinates, analysis layers are sampled in image space. No seam found.
- Half float mip generation and sRGB matrices (ProPhoto to XYZ D50 to sRGB, and the ProPhoto to Display P3 matrix, checked by multiplication to 4 digits). Blur kernel weights (normalised, sigma about 2.55). Geometry mapping order (inverse pipeline) and its CPU copy in Geo.kt. Curve texture row addressing and the NEAREST/LINEAR choice per format. TIFF header layout and tag order. JNI release modes (`JNI_ABORT` for read-only, 0 for output).

# Studio spec: a layer-based compositing and retouching editor inside Rawline

Status: draft 1, 6 Oct 2026. Author: architect worker. Audience: the workers who build it. Read with SPEC.md, DECISIONS.md, UI_SPEC.md, AUDIT.md.
Conventions: Australian English, no em dashes, no AI assistant named anywhere in code, docs or commits. Nothing here is measured on the phone; every speed or look claim needs Jai's Copy report before it is claimed (CLAUDE.md).

## 0. What Studio is (read first)

Studio is a completely separate section of the app. Think Lightroom and Photoshop in one app: Develop is the Lightroom half, Studio is the Photoshop half. They share low-level libraries and nothing else.

- Own top-level entry. A two-way mode switch at the very top level of the app: **Develop | Studio**. It is a segmented control in the top bar of each home screen, plus a remembered last mode (opens in the mode you left, default Develop). Studio is not a tab inside the editor and not an editor panel.
- Own home screen: the **Studio project library** (grid of projects with thumbnails, name, size, modified time; actions New, Open, Duplicate, Rename, Delete, Import). Develop's library is untouched.
- Own navigation graph: `studio/home`, `studio/new`, `studio/canvas/{projectId}`, `studio/export/{projectId}`, `studio/settings`. Develop keeps its graph. The app module only holds a thin top-level host that picks which graph is visible.
- Own bottom tools and panels (Section 5), own project storage (Section 3.6), own undo history, own export path.
- Develop must not change behaviour or UI because of Studio. The only edits to Develop files allowed are (a) the mode switch in the two top bars, and (b) one optional menu item "Open in Studio" (Section 3.7). Both are behind a build-time flag `STUDIO_ENABLED` so Develop can be proven unchanged with the flag off (golden and unit tests identical).
- Shared, low-level only: the GL engine (`core/native`, `core/render`), mask types and AI (`core/ml`), export encoders, `core/ui` tokens (Lr theme, Lr components), `core/cache` primitives. Nothing in `feature:*` (Develop) is imported by Studio, and Develop never imports Studio.

## 1. Goals and non-goals

Goals (one user, one phone: Samsung S24 Ultra, S Pen, Android 14+, minSdk 31, arm64 only)
1. Do the 90 percent of Photoshop jobs Jai actually does on photos: composite two or three images, clean up, local colour and light, text and graphics on a photo, sky and subject swaps, remove people and objects, export for social and print.
2. Painting at 60 fps (120 Hz display where the pipeline allows) with S Pen pressure and tilt.
3. Non-destructive by default: adjustment layers, layer masks, smart objects (RAW from Develop stays editable in Develop's pipeline).
4. Fully on-device. No account, no cloud, no network except first-use model download (already the app's rule).
5. Crash-safe: Jai never loses more than the last stroke.
6. Looks and behaves like the Lr design language already in `core/ui`: the canvas dominates, controls recede.

Non-goals (decided, to keep one person's effort bounded)
- Photoshop file compatibility: no PSD import or export in v1 (reasoning in Section 6, R3). Open layers export as flat PNG/WebP set plus an ORA-style zip instead (3.6).
- Vector editing with pen/bezier paths and live boolean shapes: shapes are parametric primitives, not editable paths (v1). Path editing is deferred.
- Generative AI fill from a text prompt, super-resolution, neural filters: blocked, no vetted on-device model (Section 2.12).
- Video, animation, timeline, 3D, CMYK, spot colours, ICC soft-proofing, plug-ins, scripting, cloud sync, collaboration.
- Tablet or desktop layout beyond reasonable scaling. Phone portrait and landscape only.
- Smart object nesting of arbitrary Studio documents (only RAW/image smart objects in v1).

## 2. Features in priority order, with acceptance criteria

Priority tags: P0 ships in S1 to S3, P1 in S4 to S7, P2 in S8 to S10. "AC" is acceptance criteria. Host test = JVM or native host test with no phone.

### 2.1 Layer stack (P0 core, P1 rest)
Layer kinds: pixel, adjustment, text, shape, gradient/fill, group, smart object (image or RAW). Each layer has: id, name, visible, locked (pixels, position, all), opacity 0..100, fill opacity (affects pixels not effects; P2), blend mode, optional layer mask, optional clipping flag (clip to layer below), offset (x, y) and transform matrix (3x3, for non-pixel-destructive move/scale/rotate until committed), optional effects (stroke, drop shadow, outer glow; P2).
- Max 64 layers per project, nesting depth 4.
- Adjustment layer = one parameter block of the existing adjustment pipeline (Light, Colour, Curve, Colour mixer, Colour grading, Effects, Detail subset) plus new Studio adjustments (2.10). It affects everything beneath it (or only the layer below when clipped, or only the group when inside a pass-through-off group).
- Layer mask = 8-bit alpha, stored as 256 px tiles like pixels, in the layer's own coordinate space. Mask sources reuse existing types: brush (painted), linear, radial, colour range, luminance range, AI subject/sky/people/object (all as bitmap). Mask ops add/subtract/intersect reuse `MaskOp`. Masks can be inverted, feathered, applied (bakes into pixels, pixel layers only), disabled, and linked or unlinked to the layer.
- Clipping: a clipped layer's alpha is multiplied by the alpha of the nearest non-clipped layer below (the base). Clipped run shares one base.
- Groups: isolated by default (composited to a temporary, then blended with the group's mode and opacity); pass-through option (mode Pass Through) composites children straight onto the backdrop.
AC: add, delete, duplicate, reorder (drag handle), merge down, merge visible, flatten, group/ungroup, rename, lock, toggle visibility, all undoable; 20 layer 24 MP document composites correctly against the reference renderer (Section 4.5); layer thumbnails update within 200 ms of a stroke ending.

### 2.2 Blend modes and compositing maths (P0: Normal, Multiply, Screen; rest P1)
Notation: backdrop colour Cb, source colour Cs, per channel in [0,1], straight (non-premultiplied) alpha, alphas ab and as. Blend function B(Cb,Cs) operates on straight colour. Compositing (W3C Compositing and Blending Level 1, general formula):
- ar = as + ab(1 - as)
- Cr = [ (1 - as) ab Cb + as ( (1 - ab) Cs + ab B(Cb,Cs) ) ] / ar (Cr = 0 when ar = 0)
- Layer opacity o multiplies as before this step; mask value m also multiplies as: as' = as o m.
Separable modes (per channel), exact:
1. Normal: B = Cs
2. Darken: min(Cb,Cs)
3. Multiply: Cb Cs
4. Colour Burn: Cb = 1 -> 1; Cs = 0 -> 0; else 1 - min(1, (1 - Cb)/Cs)
5. Linear Burn: max(0, Cb + Cs - 1)
6. Lighten: max(Cb,Cs)
7. Screen: Cb + Cs - Cb Cs
8. Colour Dodge: Cb = 0 -> 0; Cs = 1 -> 1; else min(1, Cb/(1 - Cs))
9. Linear Dodge (Add): min(1, Cb + Cs)
10. Overlay: HardLight(Cs, Cb) (arguments swapped)
11. Soft Light (W3C): if Cs <= 0.5: Cb - (1 - 2Cs) Cb (1 - Cb); else Cb + (2Cs - 1)(D(Cb) - Cb), with D(x) = ((16x - 12)x + 4)x for x <= 0.25, else sqrt(x)
12. Hard Light: Cs <= 0.5 ? Multiply(Cb, 2Cs) : Screen(Cb, 2Cs - 1)
13. Vivid Light: Cs <= 0.5 ? ColourBurn(Cb, 2Cs) : ColourDodge(Cb, 2Cs - 1)
14. Linear Light: clamp(Cb + 2Cs - 1, 0, 1)
15. Pin Light: Cs <= 0.5 ? min(Cb, 2Cs) : max(Cb, 2Cs - 1)
16. Hard Mix: Vivid Light result >= 0.5 ? 1 : 0 (use the Linear Light variant threshold Cb + Cs >= 1 when channel clamped; we use Cb + Cs >= 1, documented as our definition)
17. Difference: |Cb - Cs|
18. Exclusion: Cb + Cs - 2 Cb Cs
19. Subtract: max(0, Cb - Cs)
20. Divide: Cs = 0 -> 1; else min(1, Cb/Cs)
Non-separable (W3C): Lum(C) = 0.3R + 0.59G + 0.11B; ClipColor and SetLum/Sat/SetSat as in the W3C text.
21. Hue: SetLum(SetSat(Cs, Sat(Cb)), Lum(Cb))
22. Saturation: SetLum(SetSat(Cb, Sat(Cs)), Lum(Cb))
23. Colour: SetLum(Cs, Lum(Cb))
24. Luminosity: SetLum(Cb, Lum(Cs))
Plus Dissolve (P2): stable hash(x, y, seed) < as ? opaque Cs : transparent, no blending.
Blend space: project flag `blendSpace` = GAMMA (default, matches what Jai expects from Photoshop: formulas run on sRGB-encoded values) or LINEAR (formulas on linear light, physically nicer for Screen/Add glows). Halation/glow filter (2.9) always works in linear. Colour management: documents are sRGB or Display P3 (chosen at creation, default Display P3 when the source is P3, else sRGB); no per-layer profiles.
AC: every mode has a host-side float reference (Kotlin, `core:studio-model`) and a GLSL implementation; a golden scene per mode compares GPU against reference within 1/255 + 1 half-float ulp on Mesa (Section 4.5); modes behave identically for opaque and partially transparent backdrops; clipping and mask alpha apply as stated.

### 2.3 Selection tools (P0: rect, ellipse; P1: rest)
Selection = one 8-bit alpha mask at document resolution (tiled, same store as layer masks) plus a marching-ants outline derived from a 1/4 resolution threshold contour (GPU edge shader, animated 1 dp line, ants pattern per UI_SPEC colours).
Tools: rectangle (with fixed ratio, from centre), ellipse, freehand lasso, polygon lasso (tap points, tap first point to close, double-tap to close), magic wand (tolerance 0..255, contiguous toggle, sample all layers or current, anti-alias), quick select (tap and drag positive points, long-press toggles negative; backed by the on-device object model, Section 2.12), AI subject, AI sky, AI background (inverse of subject), AI people parts, select all, deselect, reselect.
Modifiers: Add, Subtract, Intersect, Replace as a persistent segmented control above the tool tray (no keyboard modifiers on a phone). Invert, Feather (0..250 px, Gaussian), Expand/Contract (px, morphological on GPU), Smooth, Refine Edge (edge-aware: guided filter from `core/ml/GuidedFilter` using the composite as guide, radius and feather sliders, plus a refine brush that paints "find more edge" and "find less edge"), Save selection to a channel (alpha mask stored in project, up to 8), Selection to layer mask, Selection from layer alpha or mask.
AC: all ops are undoable and take under 150 ms for 24 MP except AI and refine (under 1.5 s); add/subtract/intersect obey set algebra on the alpha values (min/max/product defined in 4.2, tested on host); wand on a flat colour patch selects exactly the contiguous region within tolerance (host test with synthetic images); a selection limits every destructive tool (brush, fill, filter, delete, transform commit) and never limits adjustment layer evaluation (a selection when creating an adjustment layer becomes its mask).

### 2.4 Brush engine (P0: brush, eraser; P1: rest)
Stamp-based, GPU. Tools: brush, eraser, smudge, clone stamp, healing brush, spot heal (tap), dodge, burn (shadows/mids/highlights range), sponge (P2), mixer-lite is out. 
Parameters: size 1..2000 px (on document pixels, zoom independent), hardness 0..100, opacity (per stroke ceiling) and flow (per stamp), spacing 1..200 percent of diameter, smoothing/stabiliser 0..100 (see below), roundness and angle (P1), pressure mapping to size and/or flow/opacity with an editable response curve (reuse `CurveEdit`), tilt mapping to angle/roundness (P2), scatter and jitter (P2), texture (P2), brush tip from a 256 px alpha image (P2, import from gallery).
Pipeline: input events (including `MotionEvent` historical points and the Jetpack motion prediction library for S Pen) -> stabiliser -> Catmull-Rom resample at spacing -> stamp instances (position, size, flow, angle) in a per-stroke vertex buffer -> instanced draw into a stroke accumulation tile set (RGBA16F, only dirty tiles allocated) -> on pointer up, composite stroke buffer into the layer with the stroke opacity ceiling and blend mode, commit to RGBA8 tiles, push one history entry (dirty tile before/after list).
- Stabiliser: lazy-rope. The brush follows a point that trails the pen by up to `radius_px = stab * 0.5 * screen_dp` through an exponential pull; pulled string drawn as a thin line while active. Catch-up on lift optional.
- Pressure and S Pen: read pressure, tilt, orientation, hover distance (S24U supports pen events at about 240 Hz sampling; we do not depend on the rate); palm rejection: when a stylus tool type is seen, finger touches paint nothing (finger still pans and zooms). Button press on pen toggles eraser (if the platform reports `BUTTON_STYLUS_PRIMARY`). Finger painting supported with a constant pressure of 1 (per-brush toggle to simulate speed-pressure).
- Smudge: samples the pixels under the previous stamp, blends strength s; clone stamp: source offset set by long-press with the aligned toggle, sample current layer or all layers; healing: clone stamp followed by low-frequency colour/tone match (existing `Healer.clonePatch` logic, ported to a GPU pass or run on CPU on the stroke region); spot heal and "remove" use the LaMa path via Section 2.6.
- Dodge/burn: multiply or screen weighted by luminance range, exposure 1..100 percent, on a pixel layer only (or on a dedicated 50 percent grey Overlay layer, offered as "non-destructive dodge and burn" preset).
AC: a stroke of 4000 stamps on a 24 MP layer keeps frame time under 8.3 ms p95 on S24U (to be proven by Copy report); input to pixel latency under 40 ms with prediction on a pen (target, unmeasured); strokes are identical on re-render after restart (deterministic stamp positions, host test); undo of a stroke restores byte-identical tiles; eraser on a layer with a mask paints the pixels, with Alt-equivalent toggle "erase mask" to paint the mask instead.

### 2.5 Transform (P0 move/scale; P1 rest)
Tools: Move (layer or selection contents, or the selection outline only), Free Transform (scale, rotate, skew, flip H/V, numeric entry), Perspective (4-corner drag, homography), Warp (4x4 mesh, bicubic Bezier patches; P2), Crop (non-destructive until Commit; 2.5a), Canvas size (anchor 3x3, extend with transparent or fill), Image size (resample Lanczos3, P1), Rotate canvas 90, Flip canvas.
Behaviour: a transform is a live matrix on the layer; the layer is resampled once at Commit (bilinear while dragging, Lanczos3 or bicubic at commit chosen in settings). Smart objects stay live (matrix only, resample on every render from source). Snapping to canvas edges, centre and other layers' bounds (8 dp threshold, can be turned off), angle snap at 15 degrees. Two-finger rotate in transform mode rotates the layer, in navigation mode rotates the view (Section 2.14).
AC: scale 50 percent then 200 percent of a pixel layer loses detail only once (committed pairs), a smart object round trip is lossless; transform of 24 MP layer commits in under 1 s; numeric entry round trips; perspective homography maps the four corners exactly (host test).

### 2.6 Content-aware fill and remove (P1)
Uses the existing LaMa tiled path from `core/ml/Healer` (`aiPatch`): selection or brush mask grown by a few px, 512 px tiles around the mask with context, result blended with a feathered mask, output to a **new pixel layer** "Fill" (non-destructive), sampling the composite below (or the current layer). Also: Edit > Fill with foreground/background/pattern/history, Delete selection, Content-aware scale is out.
AC: removing a person-sized object from 24 MP completes in under 10 s (Develop's existing target; unmeasured), progress inline and cancellable, result appears on its own layer; memory use bounded by tile size, not canvas size.

### 2.7 Text (P1)
Text layer: string, font family, size (pt or px), colour, alignment (left, centre, right, justify), line height, tracking, bold/italic via font variant, box text and point text, rotation through the layer transform. Effects: stroke (width, colour, position outside/centre/inside), drop shadow (colour, opacity, angle, distance, blur, spread), outer glow (P2). Fonts: bundled set of about 8 open-licence families (SIL OFL: e.g. Inter, Roboto Flex, Playfair Display, Bebas Neue, Oswald, Merriweather, Space Mono, Caveat, with licences in THIRD_PARTY.md) plus user fonts imported from files (.ttf/.otf) into app storage.
Render: Android `StaticLayout` rasterised into an RGBA8 tile set at the layer's resolution (so text is crisp at current scale), re-rasterised when the text, style or scale changes by more than 25 percent; project stores text model and a cached raster. 
AC: text stays editable after save, reopen and export; stroke and shadow match preview and export pixel for pixel (same raster path); right-to-left and emoji render (platform text stack); 100 character box text re-rasterises in under 30 ms.

### 2.8 Shapes and gradients (P1)
Shapes: rectangle (corner radius), ellipse, line (with caps), polygon and star (sides, inner radius), rounded; fill, stroke, dash; parametric, rasterised analytically with anti-aliasing in the shader (signed distance), so they are resolution independent until merged or rasterised. Gradients: linear, radial, angle, reflected, diamond; up to 8 colour stops with per-stop opacity and midpoint, dithered output (blue-noise 8-bit dither) to avoid banding; gradient fill layer and gradient tool; reverse, opacity, and blend mode through the layer.
AC: dragging shape handles updates at 60 fps; a 16 stop gradient across 24 MP shows no visible banding (checked by dithered half-float to 8-bit, host test that quantisation error is at most 1 level).

### 2.9 Filters (P1; each is a destructive filter on a pixel layer or a live Smart Filter on a smart object, P2)
Blur: Gaussian (radius up to 250 px), box, motion (angle, distance), radial/zoom, lens blur (disc kernel, optional depth from a selection mask), surface/bilateral-lite. Sharpen: unsharp mask (amount, radius, threshold), high pass. Noise: add noise (Gaussian/uniform, mono/colour), reduce noise (median and the AI denoise tool, 2.12). Distort: Liquify-lite (push, twirl, pucker, bloat, reconstruct, face-unaware, mesh at 1/8 resolution displacement field), pinch, spherize, ripple, wave, polar. Stylise: vignette (amount, midpoint, roundness, feather), film grain (size, amount, roughness, luminance-weighted, tied to a seed), halation/glow (threshold in linear light, radius, tint, strength; screen into the layer), pixelate, emboss (P2).
All filters run on the GPU on the selection bounds plus a margin, honour the selection alpha as a mix mask, and show a live preview on the visible region at viewport resolution with Apply and Cancel.
AC: Gaussian blur radius 50 on 24 MP applies in under 600 ms (target, unmeasured); every filter previews at 30 fps or better on the visible region; filter then undo is byte-identical; vignette and grain match Develop's Effects when given equal numbers (golden against the engine).

### 2.10 Adjustments (P1; as adjustment layers or destructively on a pixel layer)
Reuse from the Develop engine: Light, Colour (temp/tint/vibrance/saturation), Curve, Colour mixer (HSL), Colour grading, Detail, Effects. New for Studio and implemented as small fragment passes beside the engine's main pass: Levels (input black/white/gamma, output, per channel), Curves RGB + channels (reuses `CurveEdit`/`CurveMath`), Hue/Saturation/Lightness with Colorize, Colour Balance (shadows/mids/highlights, preserve luminosity), Selective Colour (reds to blacks, relative/absolute), Gradient Map (gradient editor from 2.8), Black and White mixer (6 channels + tint), Brightness/Contrast, Invert, Threshold, Posterize, Photo Filter, **LUT import** (.cube 3D, sizes 17 to 65, stored as a 3D texture with tetrahedral or trilinear lookup, intensity slider; imported via Files picker; a library of imported LUTs lives in app storage).
Adjustment layers are evaluated in linear ProPhoto where the engine expects it: input converted from document space (sRGB/P3 working encoding) to linear ProPhoto, adjustment applied, converted back, in a single fused shader per adjustment run. A run of consecutive adjustment layers that all have no mask and normal blend are fused into one pass.
AC: with identical parameters, a Studio Light/Colour/Curve adjustment layer over a photo equals the Develop result within 1/255 (golden); .cube identity LUT is within 1 level; each adjustment adds under 4 ms per frame at viewport resolution.

### 2.11 Colour tools (P0 swatches/eyedropper basics, P1 rest)
Foreground/background colour chips (tap to open picker: HSV wheel plus HSB, RGB, hex sliders, Lr styling), swap, reset to black/white, eyedropper (tap/long-press loupe, sample point 1x1, 3x3 or 5x5 average, sample current layer or all), swatches (user list, up to 64, plus 16 defaults), colour history (last 24 used, project scoped), palette from image (k-means 6 colours, P2). Eyedropper reading is done on the composite via a GPU readback of a 5x5 region.
AC: eyedropper returns the exact composite value (within 1 level) on a 24 MP document; history updates only when a colour is committed to a tool, not while a slider moves.

### 2.12 AI tools: on-device models only, never faked
Existing models (`core/ml/ModelStore.kt`, downloaded on first use, LiteRT GPU delegate first, CPU fallback):

| Studio tool | Model today | Status |
| --- | --- | --- |
| Select subject / remove background to mask | Subject segmentation as used by `AiMasks.subject` (ML Kit), refined with guided filter | Available |
| Select sky, replace sky | SegFormer-B0 ADE20K (sky class) | Available. Sky replace = mask + user-chosen photo layer + auto colour match (non-AI statistics transfer) |
| Select people and parts (hair, face, clothes, skin) | MediaPipe selfie multiclass | Available |
| Quick select, tap-to-select object | MobileSAM encoder + decoder | Available |
| Content-aware fill, remove, spot heal | LaMa (dilated) | Available, tiled 512 px |
| AI denoise (fine detail only) | NAFNet, applied the Develop way (detail residual, amount slider) | Available |
| Refine edge, hair-ish edge | Guided filter (non-AI) | Available as an edge helper, not a matting model |
| Alpha matting (hair, fur, glass) | None | Blocked until a matting model is chosen, licensed and run on a PC against real photos (the Develop rule). Interim: guided-filter refine, clearly labelled as such |
| Generative fill, outpainting, text-to-image | None | Blocked: no vetted small on-device model; the control is not shown (no stub) |
| Super resolution / upscale | None | Blocked until a model is vetted; Lanczos3 resize remains the honest option |
| Depth blur, depth-range mask | None (Develop also omits depth range: "no depth model is bundled") | Blocked; lens blur uses a user-painted or gradient mask instead |
| Colourise, restore faces, style transfer | None | Blocked, not planned |
Rules: a blocked tool is absent or shows "needs a model" with the reason, never a fake. Adding a model needs: licence into THIRD_PARTY.md, pinned SHA-256 in `ModelPack`, run on a PC against real images to confirm conventions, then an entry in this table. Model release and inference stay serialised on the ai-model thread as in AUDIT third pass. The existing `AiMasksImpl` and `Healer` are tied to `EditorSession`; Studio needs an `ImageSource` interface (frame provider) so they run against a Studio composite (task in S5).
AC per available tool: runs from a cold model in under the Develop numbers (subject/sky ready under 2 s, tap-select under 300 ms, remove under 10 s; unmeasured), progress inline and cancellable, output lands as a selection, a mask or a new layer, never silently on the current pixels.

### 2.13 Undo, redo, history, snapshots (P0)
Linear history, default depth 100 entries and a 1.5 GB-on-disk cap, whichever first (oldest dropped). Entry kinds: pixel delta (list of changed tiles, before and after, LZ4-compressed on disk), layer-stack op (command pattern with inverse), parameter change (adjustment, text, shape: coalesced while a slider drags, one entry on release), selection change. History panel lists entries with names and thumbnails of the active layer (P1), tap to jump. **Snapshots** (P1): named, full project state reference (tile ids are immutable and content-addressed, so a snapshot is a small JSON of layer stack plus tile hash lists; tiles are garbage collected only when no history entry or snapshot refers to them). Non-linear branching is not supported; a new edit after jumping back truncates redo (shown in a confirm toast with Undo).
AC: 100 random operations then 100 undos returns byte-identical tiles and equal JSON (host test, seeded fuzz); undo of a stroke under 50 ms; snapshot create under 100 ms; memory for history on a 24 MP, 20 layer document stays under 300 MB RAM (history lives on disk).

### 2.14 Gestures (P0)
- Navigation (default, any tool when the pen is not down): one finger uses the tool when the tool is a drawing tool and no pen is present; **two-finger pan, pinch zoom, rotate** always navigate (rotation snaps to 0 within 5 degrees and shows a compass chip to reset). With S Pen, a finger always navigates and the pen always draws.
- **Two-finger tap = undo**, **three-finger tap = redo** (tap = under 250 ms, movement under 12 dp; ignored if any finger moved or a stroke began within 100 ms; a setting disables them). A three-finger long swipe is not used.
- **Double-tap = reset view** (fit to screen, rotation 0), two-finger double tap = 100 percent.
- Long-press with a drawing tool = eyedropper loupe (setting).
- Edge swipes are left to the system (gesture navigation); Studio never traps back swipes except to close a panel.
AC: gesture recogniser is a pure state machine with host tests (timings, finger counts, conflicts); accidental undo while painting is not possible (a stroke in progress suppresses the tap gestures); pinch zoom is smooth at 120 Hz render cadence with no tile pop (Section 2.17).

### 2.15 Project file format (P0)
A project is a directory in app-private storage `files/studio/{projectId}/` (not SAF; exports go to SAF). Contents:
- `project.json`: `schemaVersion` (int, starts 1), id, name, created, modified, canvas w/h, colour space, blendSpace, dpi, layer tree (all layer fields from 2.1, including parameter blocks, text model, shape model, selection channels, swatches, colour history, snapshots list), `appVersion`.
- `tiles/{layerId}/{level}/{tx}_{ty}.webp`: lossless WebP RGBA, 256 px tiles; empty tiles are absent (a missing tile is transparent). Masks: `tiles/{layerId}/mask/...` as lossless WebP 8-bit alpha (stored in the alpha channel of an otherwise-black image) or PNG. Tile files are named by content hash (`{sha1-12}.webp`) in a shared `blobs/` folder and referenced from JSON so that duplicate layers and snapshots share storage.
- `thumb.webp` 512 px, `smart/{id}/` for smart objects (the source file copied in, plus the Develop recipe JSON for RAW).
- `journal/`: append-only history deltas for crash recovery.
Crash safety: tiles and JSON are written to temp files and `rename()`d (atomic on the same filesystem); `project.json` is rewritten at most every 5 s while dirty and always on stroke end, background, and Back, using write-to-`project.json.new` then rename keeping `project.json.bak` for one generation. On open, if `.new` exists newer than `project.json` and parses, it wins; if `project.json` is unreadable, use `.bak` and replay `journal/`. Autosave is on a background thread, never on the GL thread. A recovered project shows a one-line "Recovered from autosave" note. Unknown `schemaVersion` greater than the app's: open read-only with a message, never overwrite (the Develop rule for newer recipes). Migrations are pure functions v(n) to v(n+1), each with a host test over a stored sample file in `core/studio-model/src/test/resources`.
Backup: "Export project" writes a zip of the directory (same caps and allowlist code as the Develop restore) that can be re-imported; layered export in 2.18.
AC: kill the process at a random point during 200 simulated strokes (instrumented host test killing the writer between steps): project always opens and loses at most the last uncommitted stroke; round trip save/load equals the in-memory model; a 20 layer 24 MP project (typical sparse content) saves incrementally in under 500 ms after one stroke (only changed tiles and JSON written).

### 2.16 Import (P0 gallery/files, P1 rest)
New project from: blank (presets: 4:5 social 1080x1350, 1:1, 16:9, A4 at 300 dpi, custom up to 12000 x 12000 and 100 MP total), photo (gallery picker, `PickVisualMedia`, no storage permission), file (Storage Access Framework: JPG, PNG, WebP, HEIC/HEIF, TIFF, DNG/RAW), Develop photo (hand-off, 3.7). Add to an open project: place image as a new layer (pixel, or smart object toggle), from gallery, files, clipboard (primary clip image), camera (system camera intent, P2). "Camera roll" here means the system photo picker; Studio does not build its own media index.
RAW and DNG in Studio: placed as a **smart object**: the source file is copied into the project, rendered through the Develop pipeline (LibRaw to linear ProPhoto, engine with the stored recipe, default recipe if none) to RGBA16F at the layer's current scale, converted into document space. "Edit in Develop" on the smart object is a one-way pop-out that returns the recipe JSON (P2). Rendering of a smart object is cached as tiles and invalidated when its recipe or matrix changes.
EXIF orientation is honoured; embedded ICC (sRGB, P3, AdobeRGB, ProPhoto) converted to document space on import; unknown profile treated as sRGB.
AC: a 24 MP JPEG imports to a one-layer project in under 1.5 s (target); a RW2 smart object shows within 2 s at preview scale and refines to full resolution in the background (targets, unmeasured); huge images are capped with a clear message.

### 2.17 Performance budget (S24 Ultra, 12 GB RAM, Adreno 750)
Principles: tiles everywhere, GPU compositing, lazy everything, no full-canvas CPU copies in an interactive path.
- Layer storage: 256 px tiles, RGBA8 (straight alpha in files, premultiplied on upload), 256 KB per tile. A 24 MP canvas (6000 x 4000) is 24 x 16 = 384 tiles = 96 MB per fully opaque layer; masks are 1 channel (R8, 64 KB per tile, 24 MB per full mask). Tiles that are fully transparent or fully constant are stored as flags (no texture).
- Worst case 20 full layers: 1.92 GB RGBA8 if every tile were resident. Therefore **GPU tile cache budget 1.2 GB** with LRU eviction by last use and visibility; evicted tiles re-decode from lossless WebP (about 1 to 3 ms per tile on a worker thread, prefetched one ring beyond the viewport). Typical documents (a photo plus sparse retouch layers) sit under 400 MB.
- Compositing targets: RGBA16F. Full-canvas 24 MP half float target = 192 MB. We keep at most three full-size caches (below-active, above-active, group scratch) = 576 MB only when painting at 100 percent zoom is requested over a full-canvas stack; otherwise the viewport target (screen size at the current zoom, about 3120 x 1440 x 8 = 36 MB) is all that is rendered. Total native plus GPU budget **2.2 GB hard cap**; at 90 percent the cache shrinks and a "low memory" chip appears; `onTrimMemory` drops to 50 percent immediately.
- Viewport render: only visible tiles, at the mip level matching zoom. Each layer keeps a resident 1/4 scale proxy (1500 x 1000 x 4 = 6 MB per full layer, 20 layers = 120 MB) so zoomed-out views and layer thumbnails never touch full tiles. Pinch zoom swaps in proxies first, then full tiles, never blank.
- Painting: brush strokes draw into the stroke accumulation set at 100 percent only in dirty tiles and into the viewport target for display. "Sandwich" caching: while a layer is active, composite of layers below and above are cached at viewport resolution, so a stamp batch costs one layer blend plus one final blend (about 2 full-screen passes). Budget 8.3 ms frame for 120 Hz, 16.6 ms minimum for 60 fps.
- Adjustment stack: fused runs (2.10); each unfused adjustment is one full-screen pass at viewport size (about 0.3 ms expected on Adreno; unmeasured).
- Commit paths (filter, transform, AI) run on a tiled 1024 px working region with a 64 px margin, so peak extra memory is bounded (a few tens of MB).
- Export of 24 MP flatten with 20 layers: tile by tile composite (2048 px regions as in Develop's export), under 6 s for JPEG (target, unmeasured).
- Instrumentation: reuse the Develop report. New timers `studio_frame_ms`, `studio_stroke_stamp_ms`, `studio_input_to_pixel_ms`, `studio_tile_decode_ms`, `studio_tile_cache_mb`, `studio_commit_ms`, `studio_autosave_ms`, `studio_export_ms`, `studio_ai_*_ms`, and a Studio debug overlay with the same Copy report button. Budgets above are checked only against a pasted report.

### 2.18 Export (P0 flatten JPEG/PNG; P1 rest)
Flatten and export: JPEG (quality 1..100, sRGB or P3, chroma subsampling off at 90+), PNG (8 bit, alpha, optional 16 bit P2), WebP (lossy quality or lossless, alpha), TIFF16 (reuse Develop's TIFF writer; layers flattened, 16 bit from the half float composite, no alpha or with alpha), PDF (single page, flattened JPEG at 300 dpi or chosen size, using `PdfDocument`; P2). Layer export: selected layers or all as individual PNG/WebP files, or a zip in OpenRaster layout (`stack.xml`, `data/*.png`, `mergedimage.png`, `Thumbnails/thumbnail.png`), the open interchange route instead of PSD. Share via the system sheet through the same temp-folder logic Develop's Share uses. Export runs through the existing export service for large sizes (foreground service, cancel, IS_PENDING semantics), with size presets (long edge 1080, 2048, original) and metadata choice (copy source EXIF where there is one; otherwise write software tag only, no model names).
AC: exported JPEG of a flattened project matches the on-screen composite within 2 levels (sRGB) in a golden test; TIFF16 header and rows pass the existing TIFF checks; layer export names collide-safe; export never blocks the UI, progress inline and cancellable.

### 2.19 Accessibility (P0 basics, P1 full)
- Every tool, layer row and panel control has a content description and role; sliders report range and value, support set and reset actions (the Develop rule from AUDIT second pass).
- Touch targets 48 dp minimum (layer row actions use 48 dp targets even where the visual is smaller).
- TalkBack: layer list is navigable, actions (rename, hide, delete, merge, move up/down) exposed as custom accessibility actions, so reorder works without dragging. Canvas painting is not a TalkBack goal; non-visual alternatives provided for numeric transform, fill and adjustments.
- Switch access and keyboard: undo/redo, tool switching and numeric entry reachable without gestures (the two-finger tap gesture has a toolbar button twin, always visible).
- Contrast: Lr tokens already meet it for text; selected state never relies on colour alone (tile shape plus accent). Font scale up to 200 percent without clipping in panels (scrollable). Reduced motion respected (no marching ants animation, no transition motion).
- Left-hand mode: swap side of the tool column in landscape.

## 3. Architecture

### 3.1 Modules (follow "add when first needed, no empty stubs", DECISIONS)
New:
- `:core:studio-model` (pure Kotlin/JVM-testable, Android-free): document, layer tree, layer kinds, blend mode enum and float reference implementation, selection algebra on byte masks, tile addressing, history (commands, tile deltas), project JSON schema and migrations, text/shape/gradient models. No Android imports so every test runs on the host.
- `:core:studio-render`: JNI wrapper, GL tile cache and compositor session (`StudioSession`, the analogue of `EditorSession`), brush session, stroke buffer, filters, transform, project IO (tile codecs, autosave), export. Depends on `:core:render`, `:core:native`, `:core:studio-model`.
- `:feature:studio`: Compose UI and navigation graph: Studio home, canvas screen, tool trays, layers panel, colour picker, selection and transform overlays, export sheet. Depends on `:core:studio-render`, `:core:ml`, `:core:ui`. Does not depend on any other `feature:*`.
Changed (small, flag guarded): `:app` gets `ModeHost` (the top-level Develop | Studio switch, 3.5); `core:native` gains `studio/` C++ sources (compositor, brush, filters) in the same shared library and a separate JNI file `jni_studio.cpp`; `core:ml` gets the `ImageSource` seam (S5). `core:render` is extended only by exposing already-existing pieces (adjustment pass and colour conversion shaders for reuse); no Develop behaviour change, proved by the existing golden set.
Dependency rule extension: core never depends on feature; `feature:studio` and Develop's `feature:*` never depend on each other.

### 3.2 Data model (core:studio-model)
`Document(id, w, h, colourSpace, blendSpace, root: GroupLayer, selection: SelectionState, channels, swatches, history refs)`; `sealed class Layer` with `Pixel`, `Adjustment(kind, params FloatArray in the engine's flat layout where reused)`, `Text`, `Shape`, `Gradient`, `Group`, `SmartObject`; each with `LayerCommon(id, name, visible, lock, opacity, blend, mask, clip, matrix)`. Tile store interface `TileStore { get(layer, tx, ty), put, hash }`, implemented on disk (project dir) and in memory (tests). Params reused from Develop remain mirrored: any new flat param block for Studio adjustments is mirrored in C++ and Kotlin with a test, as `RenderParams` is.

### 3.3 GPU compositor (reusing engine.cpp)
- Same C++ library, same GL thread rule: every Studio engine call goes through a `StudioSession.post` queue exactly like `EditorSession.post`. Studio owns its own GL context and surface (a separate `StudioGlView`), so Develop's engine instance and state are untouched; shader sources are shared files where reused.
- Reused from `engine.cpp`: program build helpers, `ensureTarget` (RGBA16F targets), VAO full-screen draw, colour space conversion (linear ProPhoto to display, P3/sRGB), the adjustment shader code (main.frag sections factored into `adjust.glsl` includes; the Develop `main.frag` is regenerated by the existing `gen_shaders.cmake` and must render identical goldens), the half-float helpers (`halfs.h`), debug-outside and golden harness style.
- New: `tile_cache` (texture array pages of 8 x 8 tiles for upload efficiency, ES 3.0 `GL_TEXTURE_2D_ARRAY`, LRU), `composite.frag` (layer blend with all 24 modes in a switch on a uniform, mask sampling, clip handling, opacity), `stamp.vert/frag` (instanced brush), `adjust_studio.frag` (levels, HSL, colour balance, selective colour, gradient map, B&W, LUT), `filter_*.frag`, `transform.frag` (homography and mesh), `select_*` (wand flood is CPU, scanline, tile aware; ants on GPU).
- Frame graph: for each visible tile region of the viewport: iterate the layer stack bottom to top into a ping-pong RGBA16F pair; groups render to a scratch pair then blend; adjustment layers apply onto the current accumulation (masked by their mask and selection-at-creation); the active layer's stroke buffer is blended in place at the correct stack position (live stroke preview). Blend mode switch is a uniform branch (the cost is the same for all pixels in a layer so divergence is nil). ES 3.2 on Adreno has framebuffer fetch via `GL_EXT_shader_framebuffer_fetch` (check at init and use it to avoid ping-pong; fall back to ping-pong, tested on Mesa which lacks it).
- Readback paths: tile commit (glReadPixels from RGBA8 target tile, or `AHardwareBuffer` later), eyedropper, export region, histogram.
- Context loss: tiles can always be rebuilt from the tile store; stroke in progress is journaled, the same recovery philosophy as the Develop overlay resend.

### 3.4 Tool state machines
Each tool is an object with `onDown/onMove/onUp/onCancel`, `onSettingsChange`, `commit()`, `cancel()`, driven by a single `ToolController` that owns the input router (finger vs pen, gesture recogniser). States: `Idle -> Active(pointerId) -> Committing -> Idle`; transform and crop add `Editing(handles)` with explicit Commit and Cancel. Rules: an `onCancel` (second finger lands, system gesture, app pause) rolls back the in-progress stroke without a history entry; a stroke that has begun and ends normally is exactly one history entry; tools never touch the GL thread directly, they emit `StudioCommand`s onto the session queue. The recogniser and every tool's logic over a recorded event list are host-testable because they take `InputEvent` data classes, not `MotionEvent`.

### 3.5 Plugging into app navigation
- `MainActivity` hosts `ModeHost`. State `mode: DEVELOP | STUDIO` persisted in settings (DataStore or the existing settings store). The two modes each own a full `NavHost` kept alive with `rememberSaveable`, so switching preserves position (Studio's open project stays loaded on its canvas unless Jai leaves to the Studio home).
- Switch control: a two-segment Lr toggle in the top bar of Develop's library and Studio's home only (never during editing; the editor and canvas are full-screen, Back returns to their home). Landscape: same place.
- Studio does not appear in Develop's bottom navigation, and Develop does not appear in Studio's. Each has its own bottom bar or dock.
- Deep link: `rawline://studio/project/{id}` opens a project (used by the post-export "Open in Studio" toast, P2).
- Back handling: Studio canvas Back closes the open panel, then asks nothing (autosave) and returns to the Studio home.
- Process death: Studio restores the project id and view transform; unsaved strokes come from the journal.

### 3.6 Project storage separate from Develop
Studio projects never enter Develop's Room database or catalogue; Studio has its own small index (Room database `studio.db` or a flat `index.json`; chosen: Room, because `MigrationTest` infrastructure exists) with one row per project (id, name, w, h, modified, thumb path, size on disk). Directory is the source of truth: the index can be rebuilt by scanning `files/studio/*/project.json`. Develop backup/restore does not include Studio in v1 (Studio export project zip exists, 2.15).

### 3.7 Develop hand-off (optional, one way)
Editor overflow menu item "Open in Studio" (flag guarded). It takes the Develop photo key and current recipe, creates a **new** Studio project with the photo as a smart object (RAW or image) at full resolution canvas with the recipe applied (a copy of the recipe JSON is stored in the project), and switches the mode to Studio. Nothing flows back; changes in Studio never alter the Develop recipe or catalogue; Develop shows no sign that Studio exists beyond the menu item. The hand-off writes only inside the Studio directory.

### 3.8 Shared with Develop (and what is not)
Shared: GL engine pieces and shaders, colour conversion, LibRaw decode, `CurveMath`, `CurveEdit` logic, `MaskType/MaskOp`, `GuidedFilter`, `ModelStore`, `TfModel`, `Healer` core, `Denoiser`, TIFF/JPEG/PNG writers, export service, `LrTheme` and Lr components, `OffscreenGl` for tests.
Not shared: navigation, screens, view models, history, project storage, recipe model (Develop `EditRecipe` stays Develop; Studio parameter blocks reference engine layouts but live in the Studio schema).

### 3.9 Testing strategy
Host unit tests (`./gradlew testDebugUnitTest`, no phone):
- Blend maths: every mode against hand-computed vectors and algebraic properties (Normal opaque identity, Multiply with white, Screen with black, Difference symmetry, Hue/Sat/Colour/Luminosity luminance preserved, premultiplied equals straight pipeline).
- Selection algebra: add/subtract/intersect/invert/feather/expand on small masks, wand flood fill on synthetic images, polygon rasterisation winding, refine edge monotonic.
- History: seeded fuzz of operations against undo/redo (state equality), tile delta compression, snapshot GC.
- Project format: round trip, schema migration from stored fixtures, truncated and corrupt file handling, crash simulation, zip caps.
- Gesture recogniser and tool state machines over recorded event scripts; stabiliser and stamp spacing determinism.
- Export: TIFF and JPEG headers, OpenRaster layout, naming.
Golden renders (extend `tools/golden`, `studio-golden` scene list, Mesa llvmpipe, strict ES 3.2): one scene per blend mode over a gradient backdrop with a partial alpha source and a mask; adjustment parity against Develop; text/shape/gradient; filters; tile boundary scene (strokes crossing 256 px tile edges and group nesting) compared with the host reference renderer, not only with stored images, so a wrong reference cannot be locked in. `--update` rewrites references as in Develop, and the reviewing worker diffs them by eye.
CI: studio host tests and goldens run in the existing job; model and lint jobs unchanged; the golden for Develop must remain identical after every Studio PR (guard against shared-shader regressions). Nothing about phone feel (60 fps, latency, memory) is claimed from CI.
Phone checks: new "Studio" section in the Copy report and PERF.md rows (below). Jai runs a fixed script (paint 20 strokes, 10 layer blend scene, export) and pastes the report.

PERF.md rows to add when S1 ships (all initially "not measured"): Studio open project; stroke frame time; input to pixel; 20 layer 24 MP pan/zoom frame; export flatten 24 MP; autosave after a stroke; memory high-water mark.

## 4. Supporting definitions

### 4.1 Coordinates
Document pixels, origin top-left, y down. Layer offset and matrix map layer pixels to document pixels. Tiles are in layer space. Smart objects have source space and a matrix.
### 4.2 Mask algebra on alpha a, b in [0,1] (bytes /255)
Add: a + b - ab (screen); Subtract: a(1 - b); Intersect: ab; Invert: 1 - a. Replace: b. Rounded to nearest byte; tests pin exact byte results.
### 4.3 Brush stamp alpha
Hardness h: falloff coverage c(r) = 1 for r <= h R; else smoothstep over [hR, R] using 1 - t where t = (r - hR)/(R - hR), cubic. Flow applied per stamp; stroke opacity ceiling applied at commit. Spacing in pixels = max(0.5, diameter x spacing percent).
### 4.4 Dirty tracking
Every command records touched tile ids; history stores before/after by hash; autosave writes only hashes not yet on disk.
### 4.5 Reference renderer
`core:studio-model` includes a slow CPU compositor (float, tile by tile) used by tests to produce expected images; the GPU path is compared to it in goldens.

## 5. UI layout sketches (Lr tokens: black canvas, #1C1C1C trays, #303030 selected tile, #437EE4 accent, 0 to 6 dp radii, Roboto, no springs)

Studio home (phone portrait)
```
+--------------------------------+
| [Develop | *Studio*]     [+ New]|  top bar, #1C1C1C, 48 dp
|--------------------------------|
| [thumb ] [thumb ] [thumb ]     |  3-column grid, 2 dp gaps,
| name     name     name         |  thumbnails black bg, no borders
| 6000x4000 ...                  |  name 12 sp, meta 11 sp grey
| [thumb ] [thumb ]              |
|                                |  long-press or overflow: Open,
|                                |  Duplicate, Rename, Delete, Export zip
|--------------------------------|
| Projects    Templates   Settings|  56 dp bottom nav (Studio's own)
+--------------------------------+
```
Empty state: single line "No projects" and a New button (UI_SPEC section 14 treatment).

Canvas, phone portrait
```
+--------------------------------+
| X   proj name   undo redo  [..]|  status strip 44 dp, transparent over black
|                                |
|                                |
|          CANVAS (black)        |  asset fit, no border; selection ants,
|                                |  transform handles drawn here
|                                |
|                          [fg|bg]|  colour chips 40 dp, bottom-right, floating
|--------------------------------|
| tool options (context row)     |  size / hardness / flow / opacity sliders
|--------------------------------|  or tool-specific strip; 64 dp category rail
|[Layers][Select][Brush][Move][Text][Adjust][AI][...]  | compact rail, selected = #303030 tile,
+--------------------------------+  active mode = 42 dp accent tile (per UI_SPEC 5.2)
```
Idle state is the floating 66 dp dock (labels visible); opening a tool turns it into the fixed stack (tool options tray, category rail, compact master rail), and the canvas refits rather than being covered (the Develop shell rule). The layers panel opens as the parameter tray: a vertical list (thumbnail 40 dp, name, eye 48 dp, lock, blend and opacity chip), drag handle to reorder, bottom row: add pixel, add adjustment, add mask, group, duplicate, delete. Blend mode picker is a scroll list grouped (Normal, Darken, Lighten, Contrast, Comparative, Colour) with the current formula name only; no previews that cost GPU time.

Canvas, phone landscape
```
+---------------------------------------------------+
| X  proj   undo redo                          [..] |
|[T]|                                    |  Layers  |
|[T]|          CANVAS (black)            |  panel   |
|[T]|                                    |  or tool |
|[T]|                                    |  options |
|[fg|bg]|                                |  280 dp  |
+---------------------------------------------------+
```
Tool column on the left (48 dp wide buttons, swappable to right for left-handed use), the right panel is 280 dp and holds layers, tool options or the history, one at a time (tabs at its top). Canvas takes all remaining space and refits when the panel is toggled.

Export sheet: a bottom sheet (Lr floating menu #262626): format segmented (JPEG, PNG, WebP, TIFF), quality slider, size presets, colour space, layers option, Export button (accent), progress inline.

## 6. Risks and decisions I made (no open questions)

R1 Memory. 24 MP x 20 full layers does not fit as half float (3.8 GB) or even as RGBA8 resident (1.9 GB). Chose RGBA8 tiles with lossless WebP backing, 1.2 GB GPU cache with LRU, 2.2 GB hard native cap, RGBA16F only for compositing targets at viewport size. Why: banding at 8 bit is avoided in compositing and adjustments (half float), and layer storage precision matches what 8-bit sources provide; 16-bit painting layers are deferred.
R2 Blend space. Chose GAMMA default with a per-project LINEAR option. Why: photographers moving from Photoshop expect its look; linear remains available and is used for glow/halation.
R3 PSD. Not supported. PSD parsing and writing (layer records, blend mode mapping, adjustment layers, text) is large and error-prone for one worker and cannot be verified without Photoshop on a PC. OpenRaster (stack.xml plus PNG) is a documented open format and is exported in S9; PSD would be a post-S10 item.
R4 Canvas implementation. Chose C++ tiled compositor in the existing shared library, not Compose canvas or Skia/RenderEffect. Why: needs 24 modes, half-float targets, tiled export identical to preview and Mesa golden testing, which the Develop engine already proved; Compose cannot do any of that at 24 MP.
R5 Separate GL context and engine instance for Studio. Why: Develop must not change; a bug in Studio cannot corrupt Develop's cached source or masks. Cost: one extra context, acceptable (the modes are never visible together).
R6 History on disk, not RAM. Tile deltas LZ4 on disk with RAM only for the last 5 entries. Why: 100 entries on 24 MP would be gigabytes.
R7 Stylus latency. Use `MotionEvent` historical points plus the Jetpack motion prediction library; skip the front-buffered low-latency rendering path in v1 (it needs `SurfaceControl` and a second surface, hard to golden test). Revisit in S10 only if the Copy report shows input to pixel above 40 ms. Sources below.
R8 Quick select = MobileSAM, not a fabricated "AI". If the pack is not downloaded the tool offers the download; the non-AI fallback is the magic wand with edge-aware refine.
R9 Text rendering via the platform text stack into tiles, not own shaping. Why: correct shaping, RTL, emoji for free; fidelity vs. export is exact because export uses the same raster.
R10 Storage: app-private directory per project plus own index, not SAF. Why: atomic rename, speed, no permission prompts; exports and project zips leave through SAF/Share. Risk: uninstall loses projects, so Settings has "Export all projects" (zip) in S9.
R11 Layer cap 64 and canvas cap 100 MP. Why: bound memory and test matrix; 24 MP is the camera size.
R12 Smart objects limited to image and RAW sources. Nested Studio documents are deferred.
R13 Selection model: one active selection plus 8 saved channels; no vector paths. Why: keeps tools uniform; the wand and AI produce rasters anyway.
R14 Colour: document is sRGB or Display P3, no ICC soft proofing, no CMYK. Why: single phone display, wide-gamut display P3 matches the S24U panel.
R15 AI scope risk: users will expect generative fill. Decision: do not ship a fake; listed as blocked in 2.12. Candidate research for a real on-device model is a separate task, not in S1 to S10.
R16 Risk that a Studio PR changes Develop output via shared shaders. Mitigation: factor shared shader includes first (S2), golden for Develop must be byte-identical, enforced in CI.
R17 Touch conflicts (palm, gesture undo while painting). Mitigation: stylus-aware routing, suppression window, toggle in settings, button twins for undo/redo.
R18 Unverified on phone: everything performance-related. Mitigation: the Copy report additions ship in S1 so each milestone's budget is measured by Jai before the next starts.

## 7. Milestone plan (strict order; each independently shippable as a release; effort in working days for one worker; each ends with CI green, APK on the release, Jai installs and pastes the Copy report)

**S1 (about 4 to 6 hours, one worker): Studio shell, project model, layers, 3 blends, move/scale, brush/eraser, flatten export.**

Status, 6 Oct 2026: S1a, S1b and S1c are delivered (docs/STUDIO_STATUS.md; phone checks pending). Deviations: Studio switches between its home and the canvas with plain state, not a NavHost; pixels are a deflate container, not WebP (DECISIONS.md S1b D1); the released APK carries Studio (the CI publish build passes `-PstudioEnabled=true`), local builds do not.
Scope (the small first slice, nothing else):
1. `:core:studio-model`: `Document`, `Layer.Pixel` only, `BlendMode { NORMAL, MULTIPLY, SCREEN }` with float reference functions and host tests (the three formulas above, opacity, straight/premultiplied compositing), tile addressing helpers, layer ops (add, delete, duplicate, reorder, visibility, opacity, blend) with undo of layer ops, project JSON v1 and round trip test (tiles as lossless WebP via `core:studio-render`, the model module holds only the schema).
2. `:core:studio-render` (kept minimal): `StudioSession` backed by an offscreen-capable GL compositor (no CPU compositor, the point is GPU) in `core/native/.../studio/` with `composite.frag` supporting Normal, Multiply, Screen, opacity, one full-canvas RGBA8 texture per layer (no tile cache yet; canvas capped at 12 MP in S1 and the cap shown in the new-project dialog), RGBA16F ping-pong target, viewport transform (pan/zoom), a golden scene `studio_blend3` (three modes on partial alpha) in `tools/golden`, and tile-free save of each layer as one lossless WebP.
3. `:feature:studio`: Studio home (project grid with thumbnails, New (blank presets and one photo from the system picker), Open, Duplicate, Delete), canvas screen (black canvas, layer panel with eye, opacity slider, blend picker for the 3 modes, add layer, delete, reorder with 48 dp up/down buttons), tools: Move (drag layer), Scale (two-finger on active layer in Move mode, plus numeric 25 to 400 percent), Brush (size, hardness, opacity, round stamps, colour from a minimal HSV picker, S Pen pressure to size) and Eraser, undo/redo buttons (two-finger tap and three-finger tap included if time allows, otherwise S2), pan/zoom with two fingers, autosave on stroke end with the atomic rename scheme.
4. Export: Flatten to JPEG and PNG via the existing encoders and SAF/Share path.
5. Navigation: the **Develop | Studio** mode switch in `:app` `ModeHost`, behind `STUDIO_ENABLED`, Studio own NavHost (`studio/home`, `studio/canvas/{id}`), own `studio.db`. "Open in Studio" is NOT in S1 (S2).
6. Report: Studio section in Copy report (frame ms, stroke ms, tile/texture MB), PERF.md rows.
Exit criteria: ./gradlew assembleDebug and testDebugUnitTest green; the existing golden set unchanged and `studio_blend3` matches the host reference; Develop screens identical with the flag off and no visible change with it on except the mode switch; Jai can make a project, add a photo, paint on a new layer with Multiply, move and scale, close the app mid-stroke, reopen and find the project, and export a JPEG; Copy report shows the Studio timers. Not in S1: tiles, selections, other blends, text, filters, AI, undo for strokes beyond a simple per-stroke full-layer snapshot (S1 may use whole-layer snapshots, limit 10, since tile deltas arrive in S2).

**S2 (5 days): Tiles, history, hand-off.** 256 px tile store and cache with LRU and WebP backing, stroke accumulation buffer and tile delta history (100 entries on disk), crash journal, gesture recogniser (undo/redo taps, double-tap reset, rotate), full project format 2.15 with migration test, shared shader includes factored with identical Develop goldens, "Open in Studio" hand-off for JPEG/PNG photos. Exit: 24 MP canvas with 10 layers opens; memory timers in report; fuzz history test green.

**S3 (6 days): All 24 blend modes, groups, masks, clipping.** Composite shader complete, group isolation and pass-through, layer masks with brush painting and invert/link, clipping, merge/flatten ops, golden per mode vs host reference. Exit: blend picker complete; 20 layer 24 MP scene pans at the target on the phone (report).

**S4 (6 days): Selections.** Rect, ellipse, lasso, polygon lasso, wand, add/subtract/intersect, invert, feather, expand/contract, ants overlay, selection to mask, saved channels, fill and delete. Exit: set algebra tests green; wand test on synthetic images green.

**S5 (6 days): Adjustments and colour tools.** Adjustment layers from Develop pipeline (Light, Colour, Curve, Colour mixer, Grading) plus Levels, Curves, Hue/Sat, Colour Balance, Selective Colour, B&W, Gradient Map, LUT import; colour picker, eyedropper, swatches, history; fused runs. Exit: parity golden vs Develop within 1 level.

**S6 (7 days): AI tools.** `ImageSource` seam in `core/ml`, subject/sky/people/object (quick select) selections, AI background, refine edge (guided filter), LaMa content-aware fill/remove and spot heal on a new layer, AI denoise on a layer; inline progress, cancel. Exit: each tool works on the phone with models downloaded; times pasted from the report; blocked tools absent.

**S7 (6 days): Brush engine complete.** Smudge, clone stamp, healing brush, dodge/burn, stabiliser, pressure curves, tilt, spacing, flow, palm rejection, pen button eraser, motion prediction, brush presets. Exit: stamp determinism test green; input to pixel and frame time recorded from the report.

**S8 (6 days): Transform, text, shapes, gradients.** Free transform with skew, perspective, flip, numeric, canvas and image size, crop, smart object live matrix; text layers with bundled fonts and stroke/shadow; shapes; gradient tool and fill layers. Exit: text round trips through save and reopen; homography test green.

**S9 (6 days): Filters, RAW smart objects, export complete.** Blur family, sharpen, noise, liquify-lite, distort, vignette, grain, halation/glow; RAW/DNG import as smart object through Develop pipeline; WebP, TIFF16, PDF, layer export, OpenRaster zip, project zip, export all; export service integration. Exit: golden filter scenes; TIFF16 checks; exported zip re-imports.

**S10 (5 days): Polish, accessibility, performance pass.** Snapshots panel, history panel with thumbnails, left-hand mode, full TalkBack actions for layers, font scale and reduced motion, low-memory behaviour, UI_SPEC visual QA, 30 minute soak, tuning from the Copy reports (tile prefetch, cache size, frame graph), docs updated. Exit: accessibility checklist, soak passes, PERF.md filled from reports.

Total about 58 working days after S1; each milestone leaves Develop untouched and releasable.

## 8. Sources (accessed 6 Oct 2026; search results gave no publication dates, so access date is stated)
- W3C, Compositing and Blending Level 1 (Candidate Recommendation Draft): https://www.w3.org/TR/compositing-1/ . Basis for the compositing equation, separable and non-separable blend functions (Soft Light, Hue, Saturation, Colour, Luminosity). Non-W3C modes (Linear Burn, Linear Dodge, Vivid/Linear/Pin Light, Hard Mix, Subtract, Divide) follow common raster-editor definitions as written in Section 2.2, and are verified only by our own tests, not against another product.
- Android Developers, Stylus input (pressure, tilt, orientation, hover, palm detection, historical points): https://developer.android.com/develop/ui/views/touch-and-input/stylus
- Android Developers, Advanced stylus features (low-latency graphics and motion prediction libraries): https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features and the codelab https://developer.android.com/codelabs/large-screens/advanced-stylus-support
- Android Developers blog, Stylus low latency: https://medium.com/androiddevelopers/stylus-low-latency-d4a140a9c982
- Internal: docs/SPEC.md, DECISIONS.md, UI_SPEC.md, AUDIT.md, PERF.md; `core/ml/ModelStore.kt` for the model list.

# Studio status

Studio is a separate section of the app (spec: docs/STUDIO_SPEC.md). Develop code is untouched by Studio work.

## S1a: model, three blends, GPU compositor, golden (delivered)
No app, UI, manifest or permission change. Nothing in `:app` depends on the new code, so there is no `STUDIO_ENABLED` flag yet (that arrives in S1c).

Delivered
- `:core:studio-model` (Android library, no Android imports in `src/main`, all tests on the host): `Blend.kt` (Normal, Multiply, Screen, W3C general formula on straight alpha), `Document.kt` (document, pixel layer, layer operations, bounded undo), `Tiles.kt`, `ReferenceCompositor.kt` (slow exact CPU compositor), `ProjectJson.kt` (project.json version 1, hand written writer, migration hook, newer files refused).
- 35 host tests: Blend 9, ReferenceCompositor 6, LayerOps 10, Tiles 3, ProjectJson 6, Python scene 1. Kotlin matches 315 vectors from the independent Python reference (`tools/studio/studio_ref.py`) within 2e-6, and a small scene within 1 level.
- Native compositor in `core:native`: `shaders/studio_composite.frag`, `studio/studio_compositor.{h,cpp}`, `jni_studio.cpp`, `StudioNative.kt`. One RGBA8 texture per layer, RGBA16F ping-pong, view transform, alpha weighted bilinear in the shader. Added to the `rawline_jni` source list only; Develop's engine, shaders and JNI are unchanged.
- Golden `studio_blend3` (`tools/golden/studio-golden.sh`, called at the end of `run-golden.sh`, so CI runs it in the golden job): three views within 1 level of the Python reference. Negative control: breaking the Multiply formula in the shader fails all three views (worst 171 to 204 levels, exit 1).
- PERF.md Studio rows, all "not measured".

Not verified (needs the phone or a device build)
- Anything on a real GPU or timed. The golden runs on Mesa llvmpipe only.
- `jni_studio.cpp` and `StudioNative.kt` are compiled by the arm64 build but never executed (no instrumented test). The GL parts they wrap are what the golden runs.

## S1b: canvas, brush, eraser, move and scale, layers, history, autosave (delivered, phone checks pending)
Reach it in a debug build only: Settings, long press the Version row. Release is unchanged (no code, no manifest entry, no permission); `STUDIO_ENABLED` is false by default.

Delivered
- `:core:studio-model` (+43 host tests, 78 in all): `Brush.kt` (stamp walker, coverage, commit), `ViewMath.kt` (view, placement, HSV, `gestured` pan then zoom), `History.kt`, `InputRouter.kt` (palm rejection, pinch, `InputSamples`), `ProjectStore.kt` + `JavaFs.kt` (crash safe store), Python reference `tools/studio/studio_brush.py` and generated resources.
- `core:native`: instanced stamp shader into an R16F stroke buffer, stroke applied inside the composite shader (preview equals commit), `readStroke`, JNI. Golden `studio_brush`: hard, soft, low flow, pressure, erase, each within 1 level of the Python reference. Negative control: a linear falloff in `studio_stamp.frag` fails four of the five (13 to 39 levels), restored afterwards.
- `:core:studio-render` (new, 30 host tests): `StudioSession` (document, history, active layer copy, autosave, undo across layers, graveyard for deleted layers, context restore), `StudioGl` (GL queue, frame plus checkerboard display pass), `PhotoImport`, `StudioProjects`, `MemoryGuard`, `Thumbs`, `StudioStats`. Tests use a fake GPU and in-memory and real file systems, including a randomised run (6 seeds, 250 operations) whose saved project must equal what is on screen.
- `:feature:studio` (new, 3 host tests): start screen (new blank project with the 12 MP cap shown, from a photo, open last), canvas with two finger pan and zoom, S Pen pressure to size, layers panel (eye, lock, blend picker of 3, opacity slider, add, duplicate, delete with confirm, reorder buttons and TalkBack actions, thumbnails), tool rail (Layers, Brush, Eraser, Move, Scale), size, hardness, opacity, flow sliders with typed values, scale slider, HSV colour picker with hex and 8 recent colours, undo and redo buttons.
- Copy report: Studio timers and gauges (PERF.md) and a Studio section in debug builds.

Deviations and limits
- Decision D1: pixels are a deflate container, not WebP (docs/DECISIONS.md).
- The brush options are two rows of two sliders (about 96 dp), not one 64 dp row: four sliders do not fit one row on a phone. Brush size runs 1 to 500 px in the slider (the brush itself allows 2000).
- A project from a newer app is refused, not opened read-only.
- Scale tool: pinch scales the layer, so the canvas cannot be panned or zoomed in that tool (switch tool). Brush, Eraser and Move: two fingers pan and zoom.
- Pressure of a pen move comes from the latest event and is interpolated by time across the batched positions (Compose gives no per-sample pressure).
- Frames go compositor, CPU byte array, display texture: a screen sized readback every frame. If `studio_frame_ms` is high on the phone the fix is a display pass inside the compositor.
- Rotating the phone re-creates the GL surface (the layers are uploaded again, the view refits).

Not verified (the Compose and GL code cannot run here; everything below is compiled, linted and reviewed only)
- Every screen, gesture, the pointer to session path from real `MotionEvent`s and S Pen pressure, the GL surface and display pass on a real driver, `PhotoImport`, the system photo picker, rotation, onPause flush, trim memory, the debug entry, and every phone number.
- What is verified: session logic (host tests above), the stamp shader and the live stroke against a Python reference on Mesa, the store under kills at every file operation, golden references of Develop unchanged.

Jai's check on the phone: open a debug build, Settings, long press Version. Make a project, add a photo, add a layer, set it to Multiply, paint 20 strokes (finger and S Pen), move and scale the photo layer, undo five and redo two, force stop in the middle of a stroke, reopen, Open the last project, check it is there, then Copy report.

## Handoff to S1c
- Entry: replace `DebugEntry` and `StudioDebugActivity` with `ModeHost` behind `BuildConfig.STUDIO_ENABLED`; `StudioHost` and `StudioProjects` become the project list backed by `studio.db`. Projects live in `files/studio/{id}` already.
- `StudioSession(fs, root, gl, env, doc, pixels, onDisk, recovered)` is the whole API; export (flatten) can render through `StudioNative.render` at document size with the same layer list (`StudioSession` builds `FrameSpec`).
- Known limits carried forward: no mip chain, bilinear only, LINEAR blend space stored but not applied, full texture per layer (tiles in S2), `PixelContainer` replaced by tiles in S2 through a schema bump.
- `project.json` stays version 1; never edit `sample_v1.json`.
- CI: `studio-golden.sh` now runs the brush scenes after `studio_blend3`.

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

## Handoff to S1b
- Build on `Compositor::updateLayerRegion` (brush dirty rectangles), `LayerHistory` and `ProjectJson`. Add `textureBytes()` to the Copy report and the `studio_*` timers from spec 2.17.
- `StudioNative.render` takes 6 floats per layer (slot, x, y, scale, opacity 0 to 1, blend id) bottom to top, hidden layers already removed by the caller; output is straight RGBA8, row 0 at the top. All calls except `create` and `abandon` must be on the GL thread that owns the context. After a context loss call `abandon` and create a new compositor; layer images must be uploaded again.
- Layer cap is 10 and canvas cap 12 MP (`Document.MAX_*`); slot count is 16 in native code.
- `project.json` stays version 1 until the meaning of a field changes. Never edit `sample_v1.json`; a version 2 adds `sample_v2.json` and a migration test.
- Known limits carried forward: no mip chain (zoom below about 0.5 aliases, S2); bilinear only; `blendSpace` LINEAR is stored but not applied (S3); the compositor has its own program build code (merge with `Engine::build` in S2); tiles and 64 layers arrive in S2.
- CI note: `run-golden.sh` is part of the golden cache key, so the first CI run after this change rebuilds the LibRaw cache once.

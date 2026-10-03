# Rawline working notes

Spec: docs/SPEC.md. Decisions: docs/DECISIONS.md. Timings: docs/PERF.md.

## Rules
- Jai is phone-only. Report in dots: Done / You need to do / Still to do / Issues. Australian English, no em dashes.
- Never claim speed/look results without the phone's Copy report. Never fake models or skip tests.
- Branch per task, merge to main when CI is green, tag milestones.
- Do not name any model in commits, code or docs.

## Commands
- Sandbox toolchain: `tools/setup-sdk.sh` then `source tools/env.sh`
- Build: `./gradlew assembleDebug` ; tests: `./gradlew testDebugUnitTest`

## Layout
core/{model,native(C++ engine, LibRaw, JNI),render(session, params, export, lens),cache,data,ml,ui}, feature/{library,loupe,editor,masking,remove,export,settings}, app (wiring, export service). Params layout is mirrored in engine/params.h and RenderParams.kt (a test checks it).

## Gotchas
- GitHub other than this repo is unreachable from the sandbox: LibRaw comes from libraw.org (CMake FetchContent, pinned hash).
- Shader edits: run `tools/golden/run-golden.sh` (Mesa llvmpipe compiles them like a strict ES 3.2 driver); `--update` rewrites the references.
- Every engine call must be on the GL thread (EditorSession.post queues until the engine exists).
- Tag pushes are rejected by the sandbox proxy.

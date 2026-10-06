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

## S1c: home, mode switch, studio.db, flatten export, flag and CI (delivered, phone checks pending)
Studio is in the CI release APK (`-PstudioEnabled=true`, the switch is visible); local and default builds have it off (`studio.enabled` is `false`). See DECISIONS.md "Studio S1c decisions".

Delivered
- `:core:studio-model` (+26 host tests, 104 in all): `Mode.kt` (`AppMode`, `ModeState`, `StartGuard`: two failed starts fall back to Develop with a notice), `Catalog.kt` (`ProjectCatalog` scan, duplicate, rename, delete; `NewProject` presets and the 12 MP fit; `IndexDiff`), `Flatten.kt` (strips, matte over white, streaming `PngWriter`), `Fs` gained `dirs`, `size`, `deleteTree`.
- `:core:studio-render` (+11 host tests, 41 in all): `StudioExporter` (flatten to PNG or JPEG, progress, cancel, `studio_export_ms`, thumbnails), `StudioSession.exportSnapshot / renderStrip / renderThumbnail`, `StudioGpu.render`, `StudioProjects.writeThumbnail`. `StudioNative.kt` moved here from `:core:native` (same package, same JNI names).
- `:feature:studio` (+2 host tests, 5 in all): `StudioRoot`, `StudioHome` (3 column grid, New with four presets and From a photo, Open, Duplicate, Rename, Delete with a question, "Cannot open" cells that can only be deleted, empty state, Projects tab only), `StudioHomeViewModel` (index first paint, then the scan; one operation at a time), `StudioDb` (Room, schema `schemas/.../1.json`), `ExportSheet` and `ExportTargets` (Pictures/Rawline, Save as, Share, progress, Cancel). The canvas menu has Export; leaving the canvas writes `thumb.jpg` (best effort, 3 seconds).
- App: `StudioEntry` in `src/studioOn` (ModeHost with `ModeState`, the switch, notice, Copy report block, Settings note) and a stub in `src/studioOff`. The `LrSegmentedToggle` (core:ui) is passed to `LibraryScreen` (`modeSwitch`, null leaves the bar as it was) and to the Studio home only; never to the loupe, editor or canvas. Settings has a Studio note ("Studio is new ...") in builds that contain Studio.
- Golden: `studio_blend3` has the flatten strip line (two 96 row strips stitched equal the whole render byte for byte). Develop goldens: all `ok`, references untouched.
- CI: `studio-flag` job (both values: tests, debug build, `tools/check-studio-apk.sh`), the publish build passes `-PstudioEnabled=true` and checks the APK before it is copied, lint job blocking.
- Housekeeping: `tools/studio/__pycache__` untracked and ignored; the stray directory beside `Blend.kt` deleted.

Not verified (the UI cannot run here; compiled, linted and reviewed only)
- Every Compose screen and dialog, the switch and mode memory, the notice toast, the Room database on a device, thumbnails and their decoding, the photo picker, the three export targets (MediaStore, the document picker, FileProvider share), JPEG and ICC embedding through the platform encoder, memory with a 12 MP 10 layer export, the start guard on a real crash, and every phone number (`studio_export_ms`, home first paint).
- Verified on the host: mode and guard, catalogue, flatten strips, PNG round trip (independent decoder), JPEG row handling and white matte (through a fake canvas), cancel and GPU failure, export through the reference compositor, thumbnails.

Jai on the phone (one message): make a project from a photo, paint, export a PNG and a JPEG and open them in Gallery; duplicate and delete a project; switch to Develop and back; force stop during a stroke, reopen; paste the Copy report (the Studio block is at the end).

Known limits carried forward: the export is on a coroutine in the app process (no foreground service until S9); a canvas that is left by a switch or a kill keeps its older thumbnail; Back on the home goes to Develop. The review findings of review-s1b.md are addressed in the next section.

## S1b fixes and autosave on a full phone (delivered, phone checks pending)
Studio blends in gamma encoded display space by default, as Photoshop does. A linear light option is stored per document (`BlendSpace.LINEAR`) and is off. The S3 reference and every S3 golden run in both spaces. The new project dialog gets the switch "Blend in linear light" (off) in S3, not before. (BK-488, decided by the PM on 6 Oct 2026.)

Delivered (review-s1b.md F1 to F3, F5, F7 to F10, F13, F16, F18, BK-479 to 481, 483, 484, 487, and BK-503)
- Per tile stroke commit (`StrokeTiles`): one rectangle per touched 256 px tile, one undo step per stroke (`Entry.Strokes`, `Step.SetPixelsMany`), baked bytes identical to the old whole box bake (tested against `StrokeReference`). A diagonal across a 4000 x 3000 layer keeps history under 12 MB where the box would be 96 MB (host test).
- Banded readback in native (`readStroke` in bands of 256 rows, one reusable band buffer); the session reads at most 8 tiles per GL round trip and bakes only after every read worked, so a failed read rolls the whole stroke back. Golden: coverage identical at bands of 1, 7, 64, 256 and 1000 rows (loop added to `studio-golden.sh`).
- Pen beats palm: a stylus DOWN cancels a finger stroke and draws; fingers are ignored while the pen hovers and for 600 ms after (`InputRouter.PALM_GRACE_MS`); hover comes from the canvas (`onHover`).
- Graveyard pruned to the layers history can still bring back (`restorableLayerIds`).
- GL lifecycle: init failure drops queued jobs at once; jobs wait while the view is paused (`paused`, `resumed`, and `onPause`/`onResume` follow the lifecycle); the compositor is destroyed only when the screen is released, and freed without GL calls if the view was already gone; one `StudioGlView` for the screen, moved between portrait and landscape with `movableContentOf`, so a rotation neither destroys nor re-uploads; after a size change a zoomed view keeps its zoom and centre. `GL compositors alive` in the Copy report Studio block (native live handle counter).
- `strokeStart` no longer waits for the GL thread (the failed start message is now "Could not finish that stroke."); the first stamp is input stamped.
- 48 dp touch targets on the blend and opacity chips and the two colour chips (visual size unchanged), with TalkBack descriptions.
- BK-503: autosave backs off 5, 10, 20, 40, then 60 s, stops after 10 failures in a row until the user edits, pauses or leaves, gives one notice per failure episode, shows "not saved: the phone is almost full" in the status strip, checks free space before writing, leaves a first save that failed with no half written folder, asks "Leave without saving?" (Stay, Export, Leave) when the last save failed, and checks free space (estimate plus 200 MB) before new project, from a photo, duplicate and export ("Not enough space. Free about N MB and try again."). The probe (200 retries and 200 toasts in 17 simulated minutes) is now a test: 10 attempts, 1 notice.
- Tests: `:core:studio-model` 121 (17 new: StrokeTiles 6, history 5, pen over palm 6), `:core:studio-render` 51 (S1bSessionTest 5, FullDiskTest 5 added), `:feature:studio` 5.

Not verified (compiled and reviewed only; nothing here ran on a device)
- Pen and palm on a real S Pen, the hover events, the movable GL view across a real rotation, pause and resume with a lost context, `liveHandles` after leaving, the 48 dp targets, the leave dialog, the free space messages on a real full phone, and every timing (`studio_commit_ms` before and after).
- Not done: BK-482 (frame path without CPU readback), the autosave debounce of F6, F11, F12, F14, F17 (system cancel: check on the phone whether a stroke is committed when the shade is pulled; no interop filter was added).
- Risk: an export that is running while the app is in the background now waits for the GL context (jobs queue while paused) and may time out.

## Handoff to S2
- `StudioSession.exportSnapshot` plus `renderStrip` is the way to render the whole canvas at any size; layered export reuses it.
- Open in Studio from Develop and the export service are S2 and S9. `project.json` stays version 1; never edit `sample_v1.json`.

## S1d: reopen, RAW honesty, honest start failures, thumbnail off the Close path (delivered, phone checks pending)
Entries BK-504 to BK-507, from the first release holes of the phone test.

Delivered
- Start mode (BK-504): Studio is the start mode only when it was used less than 30 minutes ago (`ModeState.RESUME_WINDOW_MS`). "Used" is stamped when the user switches to Studio, when a project opens, on pause and when a project is closed. A stored Studio mode with no time stamp (an older install) starts in Develop; a stale choice is rewritten to Develop.
- Continue (BK-504): `OpenMark` stores the open project's id. A normal Close clears it, a kill leaves it, and the Studio home shows a "Continue <name>" card first when the project still exists (`ContinueRule`). Leaving Studio through the mode switch leaves the mark, so Continue is there when the user comes back.
- RAW and DNG (BK-505): the home says "RAW photos open from Develop." A DNG that decodes opens with a toast, "Rendered by Android, so colours can differ from Develop."; an RW2 or other RAW that does not decode says "RAW photos open from Develop. Use Open in Studio there."; any other failure names its cause (incomplete, damaged, cannot be read, too large for a 12 megapixel canvas). `PhotoImport.decodeResult` carries the cause; an out of memory is reported as too large instead of crashing. Both pickers (new from a photo, add a photo layer) use it.
- Honest start failures (BK-506): a Studio start that never drew the home counts as failed only when the previous process crashed, failed to start, stopped responding, or was killed after more than 10 s; a swipe away or force stop never counts (`StartFailure`, `StartGuard.forgiveIfNotAFailure`, `ModeState.judgeLastStart`). The previous exit is read (`ExitReasons.last`) only when a start is pending, once, before the start mode is chosen. No record means not a failure.
- Thumbnail (BK-507): written from the autosave path, after 2 s without an edit, at most once a minute, only if something changed, and not before the first save of a new project worked (`ThumbScheduler`, `StudioSession.thumbnailer`, its own `studio-thumb` thread). Close no longer renders anything and has no 3 second wait: it flushes the save and leaves. `studio_leave_ms` (tap to leaving) is in the Copy report.
- Tests: `:core:studio-model` 143 (22 new: S1dTest 19, ModeJudgeTest 3), `:core:studio-render` 59 (8 new: S1dSessionTest), `:feature:studio` 5.

Deviations and limits
- A project closed within 2 s of its last edit, or within a minute of the previous thumbnail, keeps its older thumbnail: Close does not wait (decision D5). A new project closed within 2 s of being made shows the placeholder until it is opened and edited again.
- The Continue mark is cleared when Close is tapped, before the session is released (the save is already queued), so the Continue card cannot show for a project that was just closed.
- The exit mapping uses the platform reason numbers (user requested and user stopped are the user; signal, low memory and other count only after 10 s).

Not verified (the UI cannot run here; compiled, linted and reviewed only)
- The Continue card, the home line, the toast and dialogs of the picker, `ApplicationExitInfo` on a real kill (the record can lag a process or be missing on a first run), the thumbnail render on a real GL context during painting, `studio_leave_ms`, and the real picker offering an RW2 or DNG.
- Verified on the host: start mode window, open mark, continue rule, start failure judgement and mode integration, RAW and DNG outcomes, decode messages, thumbnail timing, and in the session (fake GPU, fake clock): first thumbnail, minimum gap, only if changed, postponed while painting, none on Close, a failure is one try, an edit during the write keeps it due, none before the first save.

Jai on the phone: paint, force stop in the canvas, reopen within 30 minutes (Studio home with Continue), reopen after 40 minutes (Develop); Close a project normally and kill (no Continue card); pick an RW2 and a DNG; swipe the app away twice in the first second of Studio, reopen (still Studio, no notice); Close a 12 MP project and paste the Copy report (`studio_leave_ms`).


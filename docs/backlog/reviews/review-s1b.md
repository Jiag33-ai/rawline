# Review of Studio S1b session code (read-only)

Reviewed on 6 Oct 2026 at main e75d824 plus the uncommitted W11 diff (previewScale, pushRecent, scaleBase); the Compose layer (commit 75cb498, feature/studio and the debug entry) was added to the review the same day, see section 4. Files read in full: StudioSession.kt (771 lines), StudioGl.kt, Gpu.kt, StudioState.kt, MemoryGuard.kt, Thumbs.kt, PhotoImport.kt, StudioProjects.kt, InputRouter.kt, History.kt, jni_studio.cpp, the readStroke and setLayerImage parts of studio_compositor.cpp, Brush.kt (Dirty, commitRect). Method: reading and reasoning. Nothing here was run on a phone, and I did not run the module's tests (no gradle in my brief). Each finding says how to prove it.

The Compose layer arrived in 75cb498 after the first pass and is reviewed in section 4 (read in full: CanvasInput, CanvasScreen, StudioHost, LayersPanel, plus the debug entry and the Develop diff).

## 1. Verdict
The threading model is sound as written: one model thread owns all session state, the GL thread never waits, `gpuCall` waits only from the model thread with a timeout and an onDrop release, saves run on their own thread, snapshots are copied before the save, and onSaved/onSaveFailed account for every in-flight array. Develop is untouched (see 3). The weak points are cost and memory at large stroke sizes, and one input-routing rule that will hurt on an S Pen.

## 2. Findings (ordered by what will hurt Jai first)

### F1 [P1] One bounding rectangle per stroke makes commit, history and upload cost scale with the box, not with the paint
`Dirty.rect` returns a single box around every stamp. A thin diagonal line across a 12 MP layer has a 12 MP box. On commit the session then: reads back the whole box from the GPU, bakes it on the CPU with `Blend.over` allocating an `Rgba` object per pixel (`Brush.bake`), cuts before and after copies (2 x 48 MB), pushes them to the GPU, and stores them in history (the 200 MB history cap holds about two such strokes). All on the model thread, so the next stroke starts late.
Proof: host test, one stroke from (0,0) to (w,h) on a 4000 x 3000 layer, assert commit time and `history.deltaBytes`. Phone: `studio_commit_ms` in the Copy report.
Fix: dirty tiles instead of one box (the S2 tile list: W26 `TileGrid.keysFor` per stamp), delta per touched tile, bake only tiles with coverage above zero, and bake without per-pixel objects (float maths inline). Interim cap: if the box is over 25 percent of the layer, split into 256 px tiles and skip tiles with no stamp inside.

### F2 [P1] `readStroke` allocates w x h x 16 bytes natively plus a 4 byte per pixel array in Kotlin
`Compositor::readStroke` builds `std::vector<float> rgba(w*h*4)` and then copies channel 0; `commitStroke` also allocates `FloatArray(w*h)`. A 12 MP box means 192 MB native plus 48 MB Kotlin plus the JNI copy, on a phone that is already holding the layer, a save snapshot and history. A `bad_alloc` is caught in JNI and surfaces as "Could not finish that stroke", and the stroke is lost with no undo entry.
Proof: golden or instrumented test with a 4096 x 3072 box; watch peak RSS.
Fix: read in bands of 256 rows in native (reuse one band buffer), and bake band by band so no box-sized float array exists. Same change fixes F1's memory half.

### F3 [P1] A palm that lands before the pen wins; the pen is then ignored
In `InputRouter` state STROKING: `if (e.kind == PointerKind.STYLUS) return out`. When a finger or palm starts a stroke and the S Pen touches down next, the pen is dropped and the palm keeps drawing. The documented rule says the opposite for palm rejection (a stylus seen means fingers paint nothing). Natural S Pen use rests the palm slightly before the tip touches.
Proof: add a router test: DOWN finger(1), DOWN stylus(2), MOVE stylus: expected StrokeCancel then StrokeStart(kind=STYLUS) and later moves of pointer 2; today it yields nothing. Phone: write with the pen resting the hand on the glass.
Fix: stylus DOWN while a finger stroke is active cancels it and starts the pen stroke (the cancelled stroke has no history entry, already supported). Also use `ACTION_HOVER_ENTER` with tool type stylus (Compose `PointerEventType.Enter` with `PointerType.Stylus`) to set a "pen near" flag that ignores finger DOWN for 600 ms after hover exit; test the state machine with a fake clock.

### F4 [P2] The screen frame goes GPU to CPU to GPU every frame
Known and documented in StudioGl: `render` reads the whole screen (3120 x 1440 x 4 = 18 MB) into a byte array and `Display.draw` uploads it again. On Adreno `glReadPixels` stalls the pipeline, and the JNI `Bytes(env, out, 0)` may copy 18 MB twice more (`GetByteArrayElements`), so a frame can cost 20 to 40 ms and drag brush latency with it.
Proof: `studio_frame_ms` and `studio_input_to_pixel_ms` from a Copy report on the S24 Ultra while drawing. Do not claim either target without it.
Fix order: (a) draw the compositor result directly to the default framebuffer (display pass inside the compositor); (b) until then pass a direct `ByteBuffer` and use `GetDirectBufferAddress`; (c) render only the dirty screen rectangle while a stroke is live.

### F5 [P2] `strokeStart` blocks the model thread on a GL round trip
`gpuCall { beginStroke }` waits for the GL thread, which may be in the middle of a frame (F4). Every pointer event after the first queues behind it, so the first stamps of a stroke appear late and in a clump.
Fix: post `beginStroke` with `gpuAsync` (order is FIFO with the stamps that follow). Handle failure inside the GL job by clearing a volatile `strokeOk` flag that `commitStroke` checks, which it already effectively does through `readStroke` returning false.

### F6 [P2] Autosave copies the active layer on the model thread right after a stroke
`startSave` does `rgba.copyOf()` of the whole active layer (48 MB at 12 MP, about 10 to 20 ms plus GC pressure) inside `commitStroke -> markDirty`, exactly when the next stroke begins. After 5 s of spacing any stroke end triggers it.
Fix: debounce to "stroke end plus 1.5 s idle" with a 5 s ceiling, `flush()` on pause stays immediate; for large layers snapshot only the dirty tile set (F1 fix gives the list).

### F7 [P2] A failed GL init leaves every waiter hanging for 10 to 20 s
`onSurfaceCreated`: on `StudioNative.init` error it logs, sets `handle = 0` and returns with `glReady = false` and the pending queue intact. `upload` then waits 20 s (`gpuCall(20_000)`) before the session reports ERROR.
Fix: on init failure drop the pending queue (`drop` each, which counts the latch down) and set a `failed` flag so later posts drop at once. Test with a fake GpuExecutor that never becomes ready: `start()` must report ERROR in under 1 s.

### F8 [P2] Jobs can run against a dead context before the new one exists (verify on device)
GLSurfaceView takes queued events before it re-creates a lost context. `post` sends to `queueEvent` as long as `glReady` is true, and `glReady` only becomes false inside `onSurfaceCreated`, i.e. after the new context exists. So between a context loss (screen off with preserveEGLContextOnPause refused by the driver, or a GPU reset) and `onSurfaceCreated`, native calls can run with no current context or against deleted names. Most return false, but a driver is allowed to crash with no context current. The session recovers afterwards (`onContextRestored` re-uploads everything) so the fault is a possible crash, not data loss.
Proof: on the phone, draw, lock the screen for 30 s, unlock, draw; also `adb shell am send-trim-memory`. Check logcat for GL errors.
Fix: override `onPause` in `StudioGlView` to set `glReady = false` and queue jobs, and set it true again in `onSurfaceCreated` or on resume when the context is still valid (`EGL14.eglGetCurrentContext() != EGL_NO_CONTEXT` on the GL thread).

### F9 [P2] `StudioGlView.onDetachedFromWindow` can block the main thread for 500 ms and still leak the compositor
`destroy` waits `done.await(500ms)` on the main thread. If the GL thread is busy (a 20 s upload, a long frame) the wait times out, the view detaches, the GL thread stops, and `StudioNative.destroy` never runs: GPU textures of the project stay until the process dies, and opening a second project doubles the footprint.
Fix: never wait on the main thread; post the destroy to the GL thread and let `onSurfaceDestroyed`-style cleanup in `release()` also free the handle when the thread winds down; add a native live-handle counter to the Copy report so a leak is visible.

### F10 [P2] Memory ceilings add up, and the graveyard is never pruned against history
Sum worst case on the Java heap at 12 MP: history deltas 200 MB, graveyard 256 MB, active layer 48 MB, a save snapshot 48 MB, `unsaved` layers (each 48 MB) while saves are pending. `largeHeap` is true, which helps, but there is no test that 10 layers plus a large delete does not OOM. The graveyard keeps pixels of a deleted layer for undo, but `StudioHistory.trim` can drop that delete entry long before; its pixels then sit in the graveyard until `trimMemory`, and `trimMemory` is wired from the canvas screen in 75cb498 (ComponentCallbacks2 registered in a DisposableEffect), so only the pruning is missing.
Fix: prune the graveyard to layer ids still named by an undo or redo `Doc` entry (history exposes `referencedLayerIds()`), add a memory test (10 layers x 12 MP, delete five, undo, check `Runtime.totalMemory` stays under a stated bound).

### F11 [P3] `stash` returns early on the undo and redo paths
In `applyStep -> syncSlots -> stash`, `history.document` has already moved on, so `history.document.layer(id)` is null and `stash` returns before saving the layer's pixels or clearing `activePixels`. It is safe today only because of an invariant: pixels not on disk still sit in `unsaved`, and linear history means a redo can only follow undos (no unsaved edits exist). Nothing enforces that.
Proof: add a host test: add photo layer, paint, wait for no save, delete it, undo, select it, redo (delete), undo, compare pixels; and the same with a save failure injected.
Fix: pass the document that still contains the layer into `stash` (`syncSlots(doc, prev)`), and assert the invariant.

### F12 [P3] Scale tool and canvas navigation
With the Scale tool active a two finger gesture only scales the layer (`gestureUpdate` ignores pan and zoom when `tool == SCALE`), so the canvas cannot be zoomed without switching tools; with a locked or hidden layer `gestureStart` returns silently and the pinch does nothing, with no toast.
Fix: in Scale tool, pinch scales the layer and a three finger or edge pan moves the canvas, or show "Layer is locked" the first time; decide in the S1b UX pass.

### F13 [P3] The first stamp of a stroke is never input-stamped
`strokeStart` calls `addPoint(st, x, y, pressure, 0L)` and `addPoint` stamps only when `timeMs > 0`, so `studio_input_to_pixel_ms` skips the first stamp, the one that decides how laggy the pen feels. Pass `e.timeMs` through `StrokeStart` (the router already receives it).

### F14 [P3] A partial upload failure leaves layers without a GPU slot and strokes on them do nothing silently
`uploadAll` clears `slots`, throws on the first failed `upload` (phase ERROR is only set from `start()`; `onContextRestored` just toasts). After a restore failure `strokeStart` hits `slots[activeId] ?: return` with no message. Set `phase = ERROR` with a Retry action, or retry once after trimming memory.

### F15 [P3] Smaller items
- `setTool` checks `drag == null` on the main thread, but a StrokeStart already queued on the model thread has not set `drag` yet, so a quick tool change can start the stroke with the new tool (brush becomes eraser). Take the tool inside the router action (capture it in `StrokeStart`).
- W11 diff: `previewScale` replaces `working` built from `history.document`, so a `previewOpacity` still in progress is overwritten (both sliders at once is unlikely, but a typed value after a drag would lose the opacity change). Build `working` from `curDoc()` for both.
- `scaleBase` is cleared in `commitWorking`, `cancelWorking`, `applyDocument`, but not in `selectLayer` when `working == null`; harmless today because `previewScale` rereads the layer, but a layer switch between two previews would scale the new layer from the old layer's base. Clear it in `activate`.
- `PhotoImport.toPixels` allocates `ByteBuffer.allocate(w*h*4)` after decoding a bitmap of the same size (2 x 48 MB at the cap) and `bmp.copy` for non-ARGB_8888; acceptable at 12 MP, not at the S2 100 MP cap (stream rows).

## 3. Checks that came out clean
- Develop untouched: `git diff 6164874 e75d824` outside core/studio-model, core/studio-render, feature/studio, tools/studio, docs touches only core/native studio files (jni_studio.cpp, studio_compositor, studio shaders, StudioNative.kt), settings.gradle.kts (module lines), tools/golden/{numcheck.py, studio-golden.sh, studio_golden.cpp}. No Develop source file changed. The `numcheck.py` edit is shared with Develop goldens (3 lines): worth a CI run with the Develop goldens to confirm identical output.
- GL thread rules: every compositor call goes through `post`; JNI array sizes are validated (`fits`, readStroke bounds, addStamps count) and exceptions are caught; `readStroke` rejects out of range rectangles.
- Crash safety: `startSave` freezes `files`, builds the document from it, copies the active layer, records `inFlight`; `onSaved` adopts new file names and drops snapshots only if still the same array; `onSaveFailed` returns snapshots to `unsaved` or marks dirty again; `release()` saves before setting `released`; timer saves check `released`.
- Stale closures in the session: closures given to `gpuAsync` capture values (xyr, slot, rect, after), not mutable fields; `env.later` re-enters through `env.model.execute`.
- Layer cap, memory guard (600 MB estimate) and blank-layer bookkeeping behave as the tests state.

## 4. Compose layer (75cb498), checklist applied
Clean: one `pointerInput(Unit)` block with `rememberUpdatedState` (no stale session after recomposition); a `finally` that cancels every pointer still down when the block is cancelled; historical samples expanded with `InputSamples.expand`; tool type from `c.type` (stylus and eraser end both map to the pen); `flush()` on `ON_PAUSE`; `registerComponentCallbacks` with `trimMemory` at TRIM_MEMORY_BACKGROUND and RUNNING_CRITICAL; Back closes the layers panel first, then saves and leaves; all disk and codec work (`PhotoImport.decode`, `projects.open`, `newBlank`, `newFromPhoto`, `latestId`) is on `Dispatchers.IO`; the GL surface is always composed so the first upload cannot wait for itself; `collectAsStateWithLifecycle`; `LaunchedEffect` keyed on the message id (no stale message); layer rows carry TalkBack actions for move up and down; the debug activity declares `configChanges` like MainActivity so rotation does not rebuild it. Develop untouched: the commit changes MainActivity (+1 line, `DebugEntry.versionLongPress`), SettingsScreen (+9 lines, a long press on the Version row, null in release), ReportBuilder (+1 line, null in release) and `app/build.gradle.kts` (`STUDIO_ENABLED` and a `debugImplementation`). `src/release/DebugEntry.kt` returns null, so release carries neither the code nor a manifest entry. That fits the spec's "mode switch and one menu item" allowance, but S1c must replace `debugImplementation` with the flagged source sets or release builds will never contain Studio.

### F16 [P2] Every rotation rebuilds the GL view and re-uploads every layer
`StudioCanvasScreen` calls `Landscape(...)` or `Portrait(...)`, and each calls `CanvasSurface`, so the `AndroidView` with `StudioGlView` sits at two different call sites. Rotating disposes one view and creates the other: `StudioGlView.onDetachedFromWindow` calls `gl.destroy(view)` (up to 500 ms on the main thread, see F9), the compositor is destroyed, the new surface calls `onSurfaceCreated` with `everCreated` true, and the session re-uploads every layer through `onContextRestored` (10 layers at 12 MP is about 0.5 GB through JNI). The canvas flashes and the view is re-fitted by `onSurfaceSize`, so zoom and pan are lost.
Proof: phone, open a project with five layers, zoom in, rotate, watch `studio_texture_mb` and the time to the first frame; `adb shell dumpsys gfxinfo` for the hitch.
Fix: create the `StudioGlView` once (`remember(gl) { StudioGlView(context, gl) }`) and place it with `movableContentOf` so it moves between the portrait and landscape layouts without being detached, or keep one layout with `Modifier` changes; keep the view transform across a size change when only the orientation changed.

### F17 [P3] A system cancel probably commits the stroke instead of rolling it back (verify)
`canvasInput` maps `changedToUp()` to UP and has no cancel path except the block being cancelled. A touch the system takes away (the notification shade, a three finger gesture, palm rejection sending ACTION_CANCEL) reaches Compose as pointers going up, so the partial stroke is committed to history. Harm is small (one undo), but it is not the documented behaviour. Proof: pull the shade down mid-stroke on the phone and check whether an undo entry exists. Fix: treat `c.isConsumed` plus `!c.pressed` with an unchanged position as a cancel only if the Compose version delivers it that way (test on device), otherwise add a `pointerInteropFilter` for ACTION_CANCEL.

### F18 [P2] Several touch targets are under 48 dp
The blend and opacity chips in each layer row are 24 dp high (`SmallChip`), and the two colour chips are 40 dp (`ColourChips`). The row itself is 56 dp, so a thumb mostly hits the row (which selects the layer) instead of the chip. UI_SPEC and the Develop screens use 48 dp. Fix: keep the visual size, widen the clickable area (`Modifier.minimumInteractiveComponentSize()` or padding before `clickable`), and add TalkBack descriptions "Blend mode Normal" and "Opacity 100 percent" to the chips.

### F19 [P3] Smaller items
- Pen hover is delivered to the same block (`PointerEventType.Enter` and `Exit`) but ignored; BK-481 needs it for palm rejection.
- A change of pen pressure without movement is ignored (`positionChanged()` is required), so pressing harder in place does not thicken the stamp until the pen moves.
- `StudioHost.openSession` builds `StudioGl` and `StudioEnv` on the main thread (cheap), and `latestId` is read in a `LaunchedEffect` keyed on `open == null`, so a project saved by this screen shows up in "Open the last project" only after the effect reruns on return (it does, because `open` flips).
- All visible text is a literal (BK-486); the Studio screens add about 40 more.

## 5. New backlog entries logged from this review
BK-479 (stroke dirty region: tiles, not one box), BK-480 (banded stroke readback), BK-481 (pen priority over palm in the router), BK-482 (Studio frame path without CPU readback), BK-483 (Studio GL lifecycle: dead-context jobs, init failure, destroy on the main thread), BK-484 (Studio memory ceilings: graveyard pruning, memory test), BK-487 (Studio canvas Compose: rotation rebuilds the GL view, system cancel, touch targets).

# Audit, 5 Oct 2026

Method: static read of the repo by the main session, three read-only reviewers (native engine, data/app wiring, UI/tests/CI), then fixes verified by build, unit tests and the golden render check.

## Checks run in the sandbox
- Unit tests (`./gradlew testDebugUnitTest`): PASS
- Debug build (`./gradlew assembleDebug`): PASS
- Golden shader renders (8 scenes on Mesa): PASS, zero difference
- Anything on a real phone (speed, look, gestures, TalkBack): NOT_RUN, needs the Copy report

## Fixed in this pass
- Export: handle was freed twice when the GPU upload failed (native frees it either way).
- Export: a cancel on the queue could abort the next Share render.
- Export: failed JPEG/PNG encode (full disk) was reported as success; a Denoiser was leaked per export.
- Catalogue: opening a photo whose saved edit could not be read (for example from a newer build) deleted that edit on exit.
- Editor: a failed save left Back frozen behind the spinner.
- Library: pinch to change columns restarted after one step.
- Engine: NaN or overflow written to the half float source became Inf; now 0 or the largest finite half.
- RW2 preview parser: capped IFD visits (hang on crafted files) and checked claimed JPEG length against the file size.
- CI: runs on branches and pull requests; the release is only published from main.

## Still open (ranked)
1. Model downloads have no hash or size check, follow any redirect, and PEOPLE uses a `/latest/` URL.
2. Unguarded coroutines (RawPrefetch, ensureFull, share export) can crash on a missing file or GPU failure.
3. XMP export overwrites an existing Lightroom sidecar.
4. Export service can strand a job enqueued during shutdown; MediaStore rows lack IS_PENDING.
5. Restore: old backup overwrites newer ratings, whole zip entries read into memory, not transactional.
6. Edit key `name|size|modified` collides for identical files in different folders.
7. DeviceScanner prunes every row on an empty or partial listing; no downgrade path in Room.
8. JNI array lengths are not checked; C++ exceptions can escape JNI; `setLayer` with a new size wipes other layers; `smoothstep` with reversed or equal edges (main.frag 61, 211, 214) is undefined and may differ on real GPUs.
9. Accessibility: sliders have no range semantics for TalkBack, several targets under 48 dp, rotation resets library selection.
10. CI: no lint, no Gradle or SDK cache, actions not pinned, the public sideload key is used when secrets are absent and `contents: write` is granted to every job.
11. Tests: nothing for library, loupe or core/ui. Cheap targets: `gridRows`, `exifLine`, slider maths, `EditorState` history.
12. Crop "Help" button does nothing.

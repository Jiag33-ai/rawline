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

## Fixed in the second pass
- Model downloads: https only, redirects not followed blindly, SHA-256 pinned for the four versioned model zips (checked before any file is kept), cut-short downloads rejected, `.part` files deleted on failure. The People model uses a `/latest/` link, so it has no pinned hash.
- Crashes: background scope has an exception handler; RAW prefetch, full-resolution decode and Share export no longer crash on a deleted file or GPU failure; a failed full decode can be retried by zooming again and frees its handle.
- XMP export no longer overwrites a sidecar written by another editor.
- Export service restarts itself if a job arrives while it is stopping; exported photos stay hidden (IS_PENDING) until fully written.
- Native: JNI array sizes are checked before use, out-of-memory in raw reads and conversions returns an error instead of aborting, shader `smoothstep` calls no longer use reversed or equal edges.
- TalkBack and switch access: sliders now report their range and value, can be set, and have a Reset action.
- Tests: export settings round trip, TIFF header and row checks, model link and checksum checks.
- CI: Gradle and SDK caching, write permission only on the job that publishes.

## Still open (ranked)
1. Restore from backup: old backup overwrites newer ratings, whole zip entries read into memory, not transactional.
2. Edit key `name|size|modified` collides for identical files in different folders.
3. DeviceScanner prunes every row on an empty or partial listing; no downgrade path in Room.
4. `setLayer` with a new size wipes other mask layers; C++ exceptions elsewhere in the engine can still escape JNI.
5. Touch targets under 48 dp (needs a look on the phone so layouts do not shift); rotation resets library selection; checkbox and toggle semantics.
6. CI: no lint step, actions not pinned to commit hashes, the public sideload key signs builds when secrets are absent.
7. Tests: nothing for library, loupe or core/ui screens.
8. Crop "Help" button does nothing.
9. The People model link is not versioned, so it cannot be hash-pinned.

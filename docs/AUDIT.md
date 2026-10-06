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

## Fixed in the third pass (render, engine, ML; static reads, verified by build, unit tests and goldens)
- Masks: every bitmap layer now lives in one fixed 1024 by 1024 array texture and is resampled on upload, so adding a layer of a different size (brush after rotate, AI mask) no longer blanks the others. The non core `glGetTexLevelParameteriv` call is gone. A context restore re-uploads each layer at its own size. New golden scene `layers2` (two layers, two sizes, both visible); the old engine fails it.
- Editor: saved layer bytes are deflated (a full set is well under a megabyte instead of about 48 MB); slot and bytes change in one locked block.
- AI models: release is serialised with inference on the ai-model thread, and a late run after release throws instead of building a new interpreter and GPU delegate that leaks.
- Editor teardown: every queued GL block re-checks the engine when it runs; blocks still waiting at release (or running after the engine is gone) are dropped and free any decoded raw they own; release is idempotent and no longer blocks the main thread.
- Heal and remove: the patch source was rendered with the baseline look and the main pass applied the baseline again on top. Patches are now rendered without it. No existing golden changes (the harness has no heal path); covered by a unit test.
- Native: JNI entry points catch C++ exceptions and return an error value; array pin failures are checked.

## Fixed in the fourth pass (data, export, tooling; static reads, verified by unit tests and build only, nothing run on a phone)
- Restore: old backup no longer overwrites newer ratings, flags or labels (`updatedAt` on meta, Room v4, newer wins; backups without times only fill gaps).
- Restore: zip entries are streamed with per-entry and total caps, names are allowlisted (no path tricks), JSON and PNG headers are checked, all database writes are one transaction, image files are moved in only after it commits. Tested with crafted zips (including a 70 MB entry that compresses to under 1 MB).
- Room: schema export on and committed, host-side migration tests (2 to 3, 3 to 4, 4 to 3), explicit downgrade that keeps every row. Wipe only from version 1 (photos index only).
- Export service: `onTimeout` and `onDestroy` put the running job back to waiting and stop cleanly; type is mediaProcessing on Android 15+ (dataSync before); the app resets jobs stuck at running and restarts the queue when it opens.
- Export: retry no longer accepts a running job; JPEG EXIF is written to a private file before it is copied to the destination (no "rw" reopen of the document); Share no longer deletes files another app may be reading (own folder per share, old ones cleared after an hour).
- Tooling: `tools/env.sh` is path independent and setup-sdk.sh no longer rewrites it; a local version code can be set with `RAWLINE_VERSION_CODE` or `-PversionCode=N` (CI unchanged).
- Edit key collision (item 1 below) was reviewed and left as is, with the reasoning in docs/DECISIONS.md; the key is now built in one place.
- Not verified: the mediaProcessing service type, `onTimeout`, and the EXIF-then-copy path need a real Android 15+ phone and a SAF destination (Copy report).

## Still open (ranked)
1. Edit key `name|size|modified` collides for identical files in different folders and is orphaned when the modified time changes (decision and plan in docs/DECISIONS.md).
2. DeviceScanner prunes every row on an empty or partial listing.
3. Touch targets under 48 dp (needs a look on the phone so layouts do not shift); rotation resets library selection; checkbox and toggle semantics.
4. CI: no lint step, actions not pinned to commit hashes, the public sideload key signs builds when secrets are absent.
5. Tests: nothing for library, loupe or core/ui screens.
6. Crop "Help" button does nothing.
7. The People model link is not versioned, so it cannot be hash-pinned.

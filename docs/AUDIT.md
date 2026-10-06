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

## Fixed in the fifth pass (quality audit: failures, report, CI; static reads, verified by unit tests and build only, nothing run on a phone or on GitHub Actions)
- Export, paste and sync: an edit that exists but cannot be read (corrupt, or a mask type from a newer build) no longer exports as an unedited photo or gets pasted over. Export fails the job with a plain message, Share shows it, paste and sync skip those photos and say so (`RecipeRead`).
- Export: Share and the queue no longer share one cancel flag (a Share used to clear a Cancel aimed at the queue); a failed "make visible in Gallery" step now fails the job instead of leaving a hidden photo marked Done; Share EXIF failures are logged; exported names are capped at 120 characters and never blank.
- Device scan: a listing that is empty or under half of what is known is not believed until a second scan agrees (partial photo access, SD card mounting). Replaces open item 2.
- Copy report: now has a timestamp, build type, memory (Java, native, phone), thermal state and battery saver, battery, free storage, permissions, GL renderer and float render target support, how the app last ended (Android's ApplicationExitInfo: native crash, ANR, low memory, with signal and library names from a native tombstone), library counts (total, RAW, edited, waiting, unreadable; the old "Photos in this source" line is now labelled by what the grid shows), export queue counts and recent failures, model download state, saved model delegates, settings and LibRaw version, errors and events with times, the last three crashes, and the previous session's errors and timings. Paths are cut to the file name, addresses and secrets are hidden. Plain text.
- Crash file: time, build, thread, memory, last photo opened and the root cause line first; first 3000 and last 3000 characters of the trace (the old cut at 6000 dropped the root cause); the newest three are kept; Settings shows a crash only when it came from the current build.
- Timings and errors are saved to a file after each error and when the app stops, so they survive a crash or a low memory kill. Variable counts in timing names (`n=1200`, `6000x4000`) are now a tag, so the table no longer grows one line per library size.
- CI: the model link check moved to its own non-blocking job (nightly, manual, and HEAD only on main); the nightly run also downloads the small model zips and compares their SHA-256 with `Models.kt`; an offline check that `models.json` and `Models.kt` agree is part of the build. The golden job caches the LibRaw host build and the sample RAW and checks the RAW's SHA-256 (retried download). Concurrency groups, an `ubuntu-24.04` runner, actions pinned to exact release tags (setup-java 6.0.1, cache 6.1.0, checkout 7.0.1, gradle setup 6.4.0, upload-artifact 7.0.1) with Dependabot, one build step with a retry for the libraw.org download, and a publish step that refuses to publish unless its commit is still the head of main. Releases carry `rawline.apk.sha256` and a list of the commits since the previous release.
- Lint: a `lint` job runs `lintDebug` on every module (non-blocking, HTML reports attached).
- R8: rules for JNI, Room, enums stored by name and the manifest components are in `app/proguard-rules.pro`; `-PminifyRelease=true` builds with them (assembleRelease passes, APK 61 MB to 37 MB, `Native` keeps its name). Minifying stays OFF by default, see docs/DECISIONS.md.
- Manifest: `uses-feature` OpenGL ES 3.2. README no longer says CI builds a debug APK.
- Tests added (host only): RecipeRead and save decision, scan prune and reapply planning, Xmp round trip, Photo key and file types, PerfLog, CrashStore, redaction, exit reasons, export file names.

## Still open (ranked)
1. Edit key `name|size|modified` collides for identical files in different folders and is orphaned when the modified time changes (decision and plan in docs/DECISIONS.md).
2. Touch targets under 48 dp (needs a look on the phone so layouts do not shift); rotation resets library selection; checkbox and toggle semantics.
3. Lint (non-blocking until fixed): `OutputStream.nullOutputStream` in core/ml `ModelStore.kt` needs API 33 but minSdk is 31 (it would crash a model download on Android 12 and 12L, not on the S24); lint reports `HalfFloat` errors in core/render (12 spots, not checked whether they are false positives); a stray byte-order mark in two files.
4. CI: actions are pinned to release tags, not commit hashes (hashes could not be confirmed offline); the build job still downloads LibRaw from libraw.org through CMake (one retry only, no cache); the public sideload key signs builds when secrets are absent (decision in docs/DECISIONS.md); nothing here has run on GitHub yet.
5. Tests: nothing for library, loupe or core/ui screens, nor for Catalog or DAO queries against a real Room database (needs Robolectric or an emulator); ExportRunner wiring is only covered through its pure parts.
6. Crop "Help" button does nothing.
7. The People model link is not versioned, so it cannot be hash-pinned; model download retries deterministic failures three times and has no free-space check (ModelStore).
8. Settings (feature/settings) has no Clear button for the last crash and no note that reports contain photo file names.
9. Smaller: TIFF export ignores Display P3 (no ICC tag); no launcher icon; permission prompts repeat each launch; thumbnails and orphaned mask or heal files are never swept; XMP sidecar write is not atomic; the export render buffer is a direct buffer per photo; no native debug symbols are attached to releases; release numbers follow the run number so they have gaps.

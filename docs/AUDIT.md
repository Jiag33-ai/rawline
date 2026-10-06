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

## Fixed in the sixth pass (UI audit AU-001 to AU-069 and quick wins; static reads, verified by unit tests and build only, nothing run on a phone)
Every finding was checked against the code first. Two were partly stale: AU-040 (the app already reset a stuck job at start; the real gap was no Stop or Remove for it) and AU-045 (`onTimeout` already existed). AU-044's `{n}` collision was not a data risk (Gallery and the document providers rename a duplicate, nothing is overwritten) and was left alone.
- P1: toasts are drawn above every screen and clear the navigation bar and each screen's bottom bar (AU-001); the photo permission has a state machine (`MediaAccess`): "Open settings" once Android stops asking, re-checked on every resume, All files access counts as access (AU-002, AU-003); rating, flag, label, copy, paste, sync and queueing run on the IO dispatcher (AU-062, BK-308); XMP sidecars are only written for folders the user added, and the user is told once when camera roll or imported photos get none; the Settings text says the same (AU-063); thumbnail write errors never reach the grid (AU-056).
- Permissions and lifecycle: first launch asks by itself once, the notification permission is asked with the first export (AU-004); the viewer and editor browse the list that was on screen, so rating under a filter or editing under "Unedited" no longer removes or jumps photos (AU-009, AU-010), Back from the editor lands on the photo edited last (AU-008), the grid scrolls back to the photo last viewed (AU-011); library column count, selection and filter bar survive rotation and navigation (AU-005); a double tap opens one viewer or editor (AU-007); rotating no longer rebuilds the Activity, so the editor keeps its undo history (AU-014, `configChanges`); viewer and editor wait for the first list read instead of popping back (AU-006); dark system bar icons fixed (AU-017); cold start background black (AU-016).
- Export: a job marked running with nothing behind it can be retried or removed and the queue is recovered on every resume (AU-040); Share stays open with a spinner, says why it failed and cancels when closed (AU-039); every finished batch posts saved and failed counts and opens the Queue (AU-041); plain failure sentences (AU-044); next-to-run order and a note that settings apply to photos added next (AU-042); Use Pictures/Rawline, inline size error (AU-043).
- Cost: grid rows are built off the main thread, list updates are throttled harder while indexing, device rescans are coalesced instead of cancelling each other, the rescan waits while an export runs, `Catalog.reapply` is one query per 500 photos (AU-060, AU-061, AU-059); a folder is stored directory by directory with "Listing photos, N found" (AU-023); FrameMonitor stops when the grid leaves (AU-064).
- Thumbnails: atomic writes (temp file then rename), damaged files dropped, disk cache capped at 300 MB least recently used first, size and Clear thumbnails in Settings (AU-057, AU-058 part).
- Library and viewer: ghost "0 selected" gone, Clear filters, Reading your photos, scroll in the paste dialog, selected state and long press label for TalkBack, Australian dates, "1 photo", viewer spinner and Try again, info sheet closes with the bars and has Close, histogram off main, star and toggle semantics, queue icon, sort check, Sync from top photo (AU-018 to AU-022, AU-026, AU-027, AU-012, AU-013, AU-034, AU-035, AU-037, AU-038, AU-029 part, AU-031 part, AU-032, AU-028 part, AU-015).
- Controls: slider typing uses the plain number, parses units and decimal commas, has a Done key, focus, a plus/minus key and an inline error (AU-051); toggles and checkboxes use `toggleable`, tabs expose a click action, buttons grow with the font (AU-050 part, AU-049 part); the export dialog has its own value feedback (AU-052).
- Settings: Clear crash reports, restore asks first, no stale "Backup saved", backup to a null stream is a failure (AU-067 part, AU-068).
- Quick wins: arm64-v8a only (BK-323; debug APK 71.6 MB to 48.0 MB, release APK 39.4 MB, `lib/` holds only arm64-v8a, every library 16 KB aligned by `zipalign -P 16` and LOAD alignment 0x4000), screen kept on while culling, importing and exporting (BK-346), Undo for rating, flag and label changes (BK-352). Default behaviour (rejects stay visible) is unchanged.
- Tests added (host only, 232 to 300): MediaAccess, Toasts, SidecarNotice and ImportNotice, ListThrottle, RescanGate, Browse, QueueOrder, UndoRules, ExportNotice and ExportErrors, CacheTrim, FolderScan, XmpSupport, GridRows, InfoText, Plurals and dates, HoldCounter, SliderInput.
- Not verified: everything visual or touch based (toast position and z-order, bar icon colour, rotation with insets, TalkBack, the permission flow on Android 14 partial access, notification tap, the Undo toast sitting over the viewer bar), and speed claims (needs the phone's Copy report: `grid jank_frames`, `index_per_file_ms`, `device_scan_ms`).

## Still open (ranked)
1. Edit key `name|size|modified` collides for identical files in different folders and is orphaned when the modified time changes (decision and plan in docs/DECISIONS.md).
2. Touch targets under 48 dp (AU-048: chips, text buttons, icon buttons, checkboxes; needs a look on the phone so layouts do not shift); the bottom tab bar and the viewer top bar still have fixed heights at large font sizes (AU-049); chip selected state, nav item selected state and badge count, and the panel header reset action have no TalkBack semantics (AU-050); a long press still counts as a tap on tabs and chips (AU-053).
3. Lint (non-blocking until fixed): `OutputStream.nullOutputStream` in core/ml `ModelStore.kt` needs API 33 but minSdk is 31 (it would crash a model download on Android 12 and 12L, not on the S24); lint reports `HalfFloat` errors in core/render (12 spots, not checked whether they are false positives); a stray byte-order mark in two files.
4. CI: actions are pinned to release tags, not commit hashes (hashes could not be confirmed offline); the build job still downloads LibRaw from libraw.org through CMake (one retry only, no cache); the public sideload key signs builds when secrets are absent (decision in docs/DECISIONS.md); nothing here has run on GitHub yet.
5. Tests: nothing runs a composable, and nothing runs Catalog, DAO queries or the Indexer against a real Room database (needs Robolectric or an emulator); ExportRunner wiring is only covered through its pure parts.
6. Crop "Help" button does nothing.
7. The People model link is not versioned, so it cannot be hash-pinned; model download retries deterministic failures three times and has no free-space check (ModelStore).
8. Library sources: no Remove or Refresh in the source menu and only the last added folder is rescanned at start (AU-025); picked-file grants are warned about near Android's cap, not managed (AU-024); the folder listing cannot be cancelled by the user (AU-023); the album list has no density option other than pinch and no width aware column count (AU-029).
9. Viewer: a zoomed pan swallows the swipe to the next photo and pan limits ignore letterboxing (AU-033); prefetch window and full decode on dwell are memory heavy, untouched until the phone gives numbers (AU-036); in flight decodes cannot be cancelled (AU-065).
10. Queue rows have no "Saved to" or Open (AU-042); Share and a queue render can still run at the same time (AU-046); the share dialog is a dialog, not the full subview the spec asks for, and other spec drift (AU-055); selected chip contrast and white on accent contrast need a design decision (AU-054).
11. Thumbnails of system images are still written to the cache (they could come from the system each time, needs phone timings); thumbnails of deleted photos are only removed by the size cap; the label dot can still touch the stars at 6 columns and a tile whose thumbnail fails has no glyph (AU-028).
12. A transient index failure is still permanent (`markFailed` sets indexed, AU-066); "Restored N edits" counts every edit in the zip, not those applied (AU-067); the first-run photo prompt on Android 14 "Select photos" is handled by the Open settings path but was not run.
13. Smaller: TIFF export ignores Display P3 (no ICC tag); no launcher icon; orphaned mask or heal files are never swept; XMP sidecar write is not atomic; the export render buffer is a direct buffer per photo; no native debug symbols are attached to releases; release numbers follow the run number so they have gaps.

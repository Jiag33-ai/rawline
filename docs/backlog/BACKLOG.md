# Rawline improvement backlog

Compiled 6 Oct 2026 by the backlog thinker. Read-only audit of docs/SPEC.md, DECISIONS.md, AUDIT.md, PERF.md, UI_SPEC.md and the source at commit b16994f. Nothing here has been run on the phone, so every speed or look claim is a hypothesis until a Copy report says otherwise (project rule).

Conventions: IDs are permanent (BK-001 to BK-512). Priority P0 (do first: data loss, security, measurement, or blocks other work), P1 (high value), P2 (worth doing), P3 (nice to have). Size S (under a day), M (a few days), L (a week or more). Entries inside each area are ranked P0 to P3 (IDs are therefore not in order). "Src" lines cite the source and the date it was checked; sources are listed at the end. Australian English, no em dashes.

# NEXT 25 TASKS (dispatch order, ready to launch workers from)

DISPATCH ORDER (one worker at a time, files, durations): docs/backlog/DISPATCH.md.

Written 6 Oct 2026 against main at e75d824 (Studio S1a, the S1b host parts, GPU brush, session, autosave and GL surface are merged; W18 is done); engine state as of 3ad453d (after 10aac4b P2 fixes, e9cd055 arm64-only APK and undo for rating, flag and label, 71918bf per-directory indexing, restore confirm and Settings cache controls, ee38b8b engine correctness, 3ad30b3 robustness and memory, 3ad453d performance; KeepAwake exists but no screen calls it yet). The engine audit items AE-002 to AE-012, AE-014, AE-015, AE-018, AE-021, AE-028, AE-034 to AE-036 are merged (the old plan for them, W09-engine-fixes.md, is superseded: do not dispatch it; its residue is W09b). Re-checked against git log on 6 Oct 2026: 5ca42a0 finished the IO-dispatch half of W04, part of W16 (photo permission state machine, first launch asks once) and part of W17 (keep-awake helper in progress); those lines are trimmed below. Each task is sized for one worker and one branch; "Owns" is the only set of files that worker may edit, so two tasks that own the same file must run one after the other. Tasks with "Starts: now" own files nothing else touches and can run in parallel.

Rules for every worker (from CLAUDE.md and the audit passes): branch per task, merge when CI is green; run `./gradlew testDebugUnitTest` and, for any shader or engine change, `tools/golden/run-golden.sh` (the existing goldens must stay identical unless the task says otherwise); Australian English, no em dashes, no model named anywhere; never claim a speed or look result without a Copy report; do not push tags (the proxy rejects them); update docs/AUDIT.md or DECISIONS.md when a decision changes; report in dots (Done / You need to do / Still to do / Issues). "Jai" lines are the phone steps to ask for after the release.

## W01 CI and APK hygiene (Starts: now)
- Entries: BK-323, BK-229, BK-218, BK-427, BK-241.
- Owns: app/build.gradle.kts, .github/workflows/build.yml, core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt, core/ml tests.
- Acceptance: (1) DONE in e9cd055 (`ndk { abiFilters += "arm64-v8a" }`); still to do: a CI step fails the build if `unzip -l rawline.apk` lists any `lib/` folder other than `arm64-v8a`; (2) a CI step runs `zipalign -c -P 16 4 rawline.apk` (must pass) and prints the APK size and its 10 biggest entries; (3) `java.io.OutputStream.nullOutputStream()` in `ModelStore.download` replaced by an own null stream and a unit test drains a small zip through that path; `lintDebug` no longer reports NewApi for ModelStore; (4) the release notes add "Previous build: v0.1.N-1".
- Exit check: CI green; the APK is at least 15 MB smaller uncompressed than the 5 Oct build (61.6 MB); alignment step green.

## W02 Data tests and upgrade fixtures (Starts: now)
- Entries: BK-305, BK-361, BK-147.
- Owns: core/data/src/test/**, core/model/src/test/** (resources included). No production code except a test seam if unavoidable.
- Acceptance: (1) fixtures for Room v3 and v4 with representative rows (photos with ratings, edits, snapshots, presets, export jobs in every status); migration tests assert row counts and values after 3 to 4 and the 4 to 3 downgrade; (2) recipe JSON fixtures per era (no `lensv`, old mask/heal keys, unknown future fields) read without loss and `lensv` default behaves as documented; (3) backup zip fixtures (older format without times) restore with the newer-wins rules; (4) scan prune and reapply tests with 50 000 fake rows finish under 300 ms; (5) all tests run in `testDebugUnitTest` on the host.
- Exit check: tests green in CI; a short table of fixture versions in docs/RELEASE.md or the test README.

## W03 Pre-release gate (Starts: now)
- Entries: BK-429.
- Owns: tools/prerelease-check.sh, tools/apk-allowlist.txt, docs/RELEASE.md.
- Acceptance: `tools/prerelease-check.sh <sha>` prints PASS/FAIL for each item listed in BK-429 (CI green for the SHA; release exists for the run number; asset hash equals the notes; signing certificate equals the previous release; versionCode above the previous; zipalign 16 KB; APK size within 10 percent; `aapt2 dump badging` permissions and uses-feature against the allow-list; ABIs; schema JSON for the Room version; no newer `dist/` APK; notes mention the signing source); docs/RELEASE.md has the checklist, the message to send Jai (back up first, what to open, what to paste) and the forward-fix rule (no downgrade).
- Exit check: the script runs against the current latest release and reports its real result, including any FAIL.

## W04 Catalogue off the main thread, reapply in SQL, indexes (Starts: after the in-flight library work commits)
- Entries: BK-308 (remaining part), BK-261, BK-263 (also audit AU-060, AU-061).
- Owns: app/src/main/kotlin/app/rawline/LibraryViewModel.kt, core/data/Catalog.kt, core/data/Db.kt, core/data/schemas/**/5.json, MigrationTest, CatalogLogicTest.
- Acceptance: (1) DONE in 5ca42a0 (rate, flag, label, copy, paste, sync and queueing run on IO; XMP on IO inside Catalog with a notice for photos that cannot have a sidecar): verify only, plus `saveMeta` must not query per photo and XMP writes should be one queued worker with one summary message; (2) `Catalog.reapply` uses SQL (one statement or one join for mismatches), no `photos.all()`, with a `reapply_ms` timer; (3) Room version 5 adds `(folderUri, modified DESC)`, `takenAt`, `rating` indexes with an explicit migration, the committed schema JSON and a MigrationTest case, no destructive path; (4) unit tests as above.
- Exit check: CI green. Jai: rate 400 selected photos with XMP sidecars on; the Copy report shows no `grid jank_frames` spike.

## W05 Restore preview and backup manifest (Starts: after W04)
- Entries: BK-303, BK-304.
- Owns: core/data/BackupFormat.kt, the backup and restore functions in Catalog.kt, BackupReaderTest, the restore dialog in feature/settings.
- Acceptance: the zip gains `manifest.json` (counts, per-entry SHA-256, app version, created time) and `settings.json` (allow-listed prefs only, no folder URIs); restore first shows a dry-run summary ("312 edits, 1 204 ratings, 18 presets; 41 edits and 230 ratings are newer on this phone and will be kept") with Cancel; a checksum mismatch aborts before any write; older zips without a manifest still restore under the existing rules; tests for each case including a damaged entry.
- Exit check: CI green; a round trip backup then restore on the phone shows the summary.

## W06 Automatic backups outside the app (Starts: after W05; shares app/build.gradle.kts with W01 so after W01)
- Full task file with tested code (29 host tests, failure injection on the write order) and the revised target order (all-files folder first because it survives reinstall, then a chosen SAF folder, then MediaStore): docs/backlog/tasks/W06-auto-backups.md. Use it instead of the one-line acceptance below where they differ (the MediaStore-only plan below loses the zips on uninstall of the owning app).
- Entries: BK-142, BK-143.
- Owns: new core/data/AutoBackup.kt, new app/BackupWorker.kt, app/build.gradle.kts (androidx.work dependency only), feature/settings (backup section).
- Acceptance: a rotating set of 7 zips written to MediaStore Documents `Rawline/backups` (no permission needed), triggered daily and after every 25 saved edits; Settings shows last backup time and size and a Back up now button; on first launch with an empty catalogue and a backup present, offer Restore (using the W05 preview); the zip is validated with `BackupReader` after writing; tests with a fake clock and file system.
- Exit check: CI green. Jai: backups appear in Files under Documents/Rawline/backups; uninstall is no longer a loss (the restore offer appears after reinstall).

## W07 Speed test and richer report (Starts: now)
- Entries: BK-001, BK-299, BK-432, BK-331, BK-180 (rest).
- Owns: new app/SpeedTest.kt, app/ReportBuilder.kt, core/cache/DeviceReport.kt, core/cache/GlInfo.kt, docs/PERF.md, one Developer button in feature/settings.
- Acceptance: Settings > Developer "Run speed test" runs the fixed script (open 20 photos cold, swipe 20, grid scroll 10 s, Edit on 5, slider drag 5 s, one JPEG export), saves the result to a file and prints one pasteable block; the report adds embedded preview sizes (BK-299), One UI version and security patch (BK-432), refresh rate and HDR capability, GL compute and array texture limits (BK-331); PERF.md gets a "S24U result, build N" column.
- Exit check: CI green. Jai: runs the test once and pastes the report; PERF.md rows are filled from it by the next worker.

## W08 Editor start and rotation (Starts: now; owns EditorHost so nothing else edits it meanwhile)
- Entries: BK-292, BK-293, BK-358, BK-005.
- Owns: app/src/main/AndroidManifest.xml (configChanges only), app/EditorHost.kt, core/render/RawPrefetch.kt, the dwell constant in feature/loupe/LoupeScreen.kt, docs/ROTATION.md.
- Acceptance: (1) rotating the phone in the editor does not record a second `edit_decode_ms`, keeps the Undo history and the open panel and mask tool (via `configChanges` or a retained holder, decision recorded); (2) `session.load(photo)` starts before the recipe, snapshot and mask set-up reads; (3) dwell prefetch starts at 300 ms with the existing capacity cap; (4) docs/ROTATION.md lists every screen with what survives; (5) a unit test shows the history holder survives a simulated config change.
- Exit check: CI green. Jai: rotate mid-edit, undo still works, no spinner.

## W09 Slider, pan and zoom latency (Starts: now; owns the engine files until it merges)
- Already merged in 3ad453d: the histogram uses its own small targets, runs at most every 100 ms while a slider moves (HistogramGate) and no longer queues a second screen frame; threaded LibRaw (BK-007; the 24 MP full decode measured 3.0 s to 1.6 to 1.9 s on the host, phone timing still needed); cached uniform locations. Entries still open: BK-009, BK-053 (the GPU reduction read back as about 3 KB, optional now), BK-389, BK-010.
- Owns: core/render/EditorSession.kt, core/native/src/main/cpp/engine/**, core/native/src/main/cpp/jni_engine.cpp, feature/editor/EditorScreen.kt, tools/golden (only to add scenes).
- Acceptance: (1) at most one pending render, the newest recipe wins (test with a flood of 100 recipes); (2) during pinch, pan and zoom the last frame is transformed with no pipeline render, then a real render 100 ms after movement stops; (3) during a slider drag a half-resolution render, then a settle frame; (4) existing goldens byte-identical; new timers `slider_to_frame_ms`, `pan_zoom_frame_ms`.
- Exit check: CI green and goldens identical. Jai: report shows `frame_render_ms` p95 and the new timers, and the first full decode time against the 3.0 s host figure.

## W09b Engine residue (Starts: after W09; small)
- Full task file with tested code: docs/backlog/tasks/W09b-engine-residue.md. Two items: local contrast must see heal and remove patches (AE-051: a patched area is boosted by +35 levels at Texture and Clarity 80; fix verified) and an orientation regression test plus the LibRaw mirrored flip map (AE-041 is verified correct in the shader; the test and the map are what is left). AE-025 (mip chain) is a wrong premise (the analysis needs the mips) and must not be done; the rest of the old residue (AE-017, AE-024, AE-026) is merged in cb58ce4.
- Entries: BK-460 (done), BK-465 (closed: not to be done), new work is AE-051 and AE-041 only.
- Owns: shaders/lowres.frag (overlay lines only), engine.cpp (overlay binding and cache invalidation), raw_decode.cpp (orientation map), tools/golden (two checks, one key).
- Exit check: CI green; the two checks in run-golden.sh; every existing scene identical.

## W10 Corrupt and truncated RW2 (Starts: after W09)
- Entries: BK-355, BK-356.
- Owns: core/native raw_decode.cpp and raw_decode.h, rw2_preview.cpp, core/nativelib/Native.kt, the JNI entry for decode in jni_engine.cpp (that function only), core/data/Indexer.kt (failure handling only), tools/test-preview-parser.sh, new tools/corrupt-corpus/.
- Acceptance: `LIBRAW_DATA_ERROR` and unexpected EOF from `unpack` are a partial result (continue, return `partial = true` and the percentage of rows decoded); a completely undecodable RAW opens its embedded JPEG as an image edit with a banner; the 12 broken variants (BK-356) run without a crash or a hang over 2 s and give a defined error state; a failed index leaves a reason in the report, never a retry loop; the grid badge is left to W12.
- Exit check: CI green; corpus test green.

## W11 Memory plan and GPU out of memory (Starts: after W08 and W09)
- Already merged in 3ad30b3: a failed upload keeps the previous source and the editor says why zoom stays at preview detail; the decode converts LibRaw's block in place (24 MP peak RSS 420 to 242 MB full, 144 to 98 MB half on the host); finished pictures go to native straight from the bitmap; denoise holds two tile rows; the exporter caps its decode at 8192. Left in this task: the plan and the table.
- Entries: BK-324, BK-363, BK-013.
- Owns: new core/render/MemoryPlan.kt, RawPrefetch.kt, Exporter.kt, the allocation code in engine.cpp, core/cache/PreviewCache.kt, ThumbStore.kt (trim only), docs/PERF.md (memory table).
- Acceptance: the memory table (24, 44, 96 MP) is in PERF.md and `MemoryPlan` chooses prefetch count, half-size-only mode and export band height from pixel count and `availMem`; `glGetError` after large allocations gives a distinct code and the editor retries at half size with the message "Opened at half size to fit the GPU"; `onTrimMemory` at RUNNING_LOW shrinks caches and frees prefetched decodes; a unit test with the three sizes and a debug switch "pretend GPU memory is 256 MB".
- Exit check: CI green. Jai: opens a 96 MP high-resolution RW2 if he has one; the report shows `native_heap`.

## W12 Culling workflow (Starts: after W04)
- Entries: BK-339, BK-338, BK-104, BK-352.
- Owns: core/model/Library.kt, core/model/src/test ModelTest, feature/loupe/LoupeScreen.kt, feature/library/LibraryScreen.kt, app/LibraryViewModel.kt (cull parts only).
- Acceptance: `hideRejects` default true with a Rejects chip and count; the loupe shows "312 decided of 800" and a Next undecided button; Cull mode with swipe pick and reject, number keys for rating, auto-advance, position saved per source, undo snackbar for every rate or flag action; the "Damaged" badge for photos with `width = 0` (from W10); tests for the filter matrix and next-undecided search.
- Exit check: CI green. Jai: culls 100 photos with swipes; position survives a restart.

## W13 Reject-to-trash (Starts: after W12)
- Entries: BK-097.
- Owns: app/LibraryViewModel.kt (delete parts), core/data/DeviceScanner.kt (trash calls), core/data/Catalog.kt (keep edits 30 days), LibraryScreen.kt (action and Recently deleted view).
- Acceptance: "Delete rejected" shows count and size and needs a confirm; device photos use `MediaStore.createTrashRequest`, SAF photos `deleteDocument`; Rawline keeps edits and ratings for 30 days so a restore keeps them; a Recently deleted view; never deletes without a confirm; tests with a fake resolver.
- Exit check: CI green. Jai: deletes 3 rejected test photos from a throwaway folder and restores one.

## W14 Export workflow (Starts: after W13)
- Entries: BK-341, BK-342, BK-359, BK-135 (rest), BK-354, BK-157.
- Owns: app/ExportRunner.kt, ExportService.kt, ExportNaming.kt, feature/export/ExportSheet.kt, QueueScreen.kt, the export queries in core/data/Db.kt (Room 6 with schema JSON), LibraryScreen.kt selection bar (Share action only).
- Acceptance: exported badge and "Not exported" filter from finished jobs kept 90 days; Share of several photos at once with a share preset; the queue pauses (not fails) when the destination permission or volume is gone and offers Choose folder or Pictures/Rawline; collision policy Replace, Skip or Add copy; low battery (below 20 percent, not charging) warns before and below 8 percent pauses; free space check before each job; tests for each.
- Exit check: CI green. Jai: exports 5 photos, shares 5 at once, pulls the export folder away mid-batch.

## W15 Card import engine, no UI (Starts: now)
- Full task file: docs/backlog/tasks/W15-card-import.md (14 host tests: naming, ledger, copy with verify and resume, report, speed class, DNG probe on synthetic files; the engine there is called CopyEngine, the name IngestEngine below is the same thing) plus the Samsung Expert RAW DNG decode risk (JXL, VC-5 and lossy DNG are not decodable in the current LibRaw build; spike with 3 real files first).
- Entries: BK-096 (slice 1), BK-344, BK-364.
- Owns: new core/data/ingest/** and its tests only.
- Acceptance: `IngestEngine` copies a list of source URIs or files to a dated destination with a rename pattern, copies to `.part` then renames, verifies size and checksum, skips duplicates by name, size and capture time, can write a second destination, resumes after interruption, reports counts, bytes and MB/s, and writes an import report file; a fake file system test that throws after N bytes proves partial files are removed and the batch resumes; no Android UI.
- Exit check: CI green; engine tests green.

## W16 Platform compliance and small fixes (DONE: merged in eec8960; the task file stays as the record)
- Full task file: docs/backlog/tasks/W16-platform.md. Smaller than first written: the notification ask is already at the first export and columns and selection already survive rotation. Left: explicit `enableOnBackInvokedCallback` (a host test reads the real manifest and is red until the line is added), guard tests for the manifest rules and for every BackHandler in the app (also run against the real tree), the saved filter, sort, columns and scroll position on a cold start, docs/PLATFORM.md. 9 host tests pass in my harness.
- Entries: BK-246, BK-426, BK-285, BK-120.
- Owns: app/MainActivity.kt, app/src/main/AndroidManifest.xml, app/build.gradle.kts (one testOptions line), feature/library/LibraryScreen.kt (state), app/LibraryViewModel.kt (filter persistence), docs/PLATFORM.md.
- Exit check: CI green; Jai: back gesture from each screen goes where the table in the file says, insets on both navigation modes.

## W17 Card import UI (Starts: after W15 and W16)
- Needs from W15: ImportReport.summary, SpeedClass, the DNG probe line for the Copy report, and the Preview only badge rule (decision D7 in W15-card-import.md).
- Entries: BK-096 (slice 2), BK-431, BK-334, BK-345, BK-346.
- Owns: new feature/import/**, settings.gradle.kts (one line), a launch entry in LibraryScreen.kt, MainActivity wiring (nav route only).
- Acceptance: import screen lists removable volumes with file system and free space, shows embedded-preview thumbnails of new files, preselects files newer than the last import, copies with progress, speed, Pause and Cancel (using W15), keeps the screen on (use the `KeepAwake` helper from the working tree), ends with a verified summary and "You can remove the card now"; surprise removal pauses cleanly; works with a USB-C reader (no SD slot on the S24 Ultra).
- Exit check: CI green. Jai: imports a real card through a reader and pastes the report.

## W18 Studio S1a: DONE (merged in 3a1f250, cd53d6d, 6164874; 35 host tests, GPU golden studio_blend3). Nothing left.

## W19 Studio S1b: canvas, brush, eraser, move and scale, layers panel, history, autosave (Starts: now)
- State at c6f8a39: the host parts are merged (brush maths, view maths, history, input router, crash safe store, 40 more host tests); the GPU stroke buffer and brush golden are merged (d6c5afc); session and autosave (e75d824) are merged too; the Compose canvas and panels are the remaining work (check git log before dispatching).
- Full task file with tested code (40 Kotlin tests, GPU brush golden within 1 level, kill-at-every-file-operation store test): docs/backlog/tasks/W19-studio-s1b.md. It adds Move and Scale (in the spec's S1, missing here before), dirty-rectangle history, a 600 MB GPU guard and records the decision to store layers as a lossless deflate container until S2.
- Entries: BK-411, BK-372, BK-373 (spec version), BK-375.
- Owns: new core/studio-render/**, new feature/studio/** (canvas only), a debug-only `StudioDebugActivity` under app/src/debug, brush shaders under core/native/.../studio/.
- Exit check: (1) the debug activity opens a blank project (12 MP cap shown) or a photo from the system picker; (2) layers panel with eye, opacity slider, the three blend modes, add, delete and 48 dp up/down reorder buttons; layer cap 10 in S1; (3) brush (size, hardness, opacity, minimal HSV colour, S Pen pressure to size) and eraser, one history entry per stroke, whole-layer snapshots limited to 10 and counted in a budget; (4) two-finger pan and zoom; undo and redo buttons; (5) autosave at stroke end with `project.json.new` rename and `.bak`, layers as lossless WebP; (6) host tests: kill the writer mid-save (fake file system) and reopen loses at most the last stroke, stroke determinism, layer op undo; (7) the Copy report has Studio timers `studio_frame_ms`, `studio_stroke_stamp_ms` and texture MB; (8) Develop unchanged (goldens identical).

## W20 Studio S1c: home, mode switch, studio.db, flatten export, flag and CI matrix (Starts: after W19)
- Full task file with tested code (24 host tests, GPU strip equality, Fs patch that applies): docs/backlog/tasks/W20-studio-s1c.md. Includes the flag mechanism (`studio.enabled` file plus `-PstudioEnabled`, Studio code absent from the APK when off, dex grep in CI), the `studio-flag` CI matrix job, ModeHost, home, Room index with a deliberate destructive fallback, streaming PNG and JPEG flatten export, the two-crash fallback and the memory release.
- Entries: BK-411, BK-403, BK-399, BK-409.
- Owns: new feature/studio home and export screens, app/ModeHost.kt and the studioOn/studioOff source sets, `studio.enabled`, studio.db, the Studio block of ReportBuilder, .github/workflows/build.yml (the studio-flag job), docs/PERF.md rows.
- Exit check: the spec's S1 exit criteria; section 4 of the task file.

## W21 Colour pipeline verification and RAW quality harness (Starts: now; tools, docs and tests only)
- Full standalone spec with tested code and measured expected values: docs/backlog/tasks/W21-colour-verification.md (copy its section 9 files verbatim into `tools/colour/`). A prototype already ran through the real engine on 6 Oct 2026.
- Entries: BK-435, BK-436, BK-437 (tests only; comment fixes wait for W22), BK-441, BK-421, BK-423, BK-422 (measurement part), BK-021 (plan), BK-470 (measurement only).
- Owns: new tools/colour/** (make_dng.py, reference.py, run_fixtures.py, run-colour.sh), docs/COLOUR.md, docs/QUALITY.md, docs/CALIBRATION.md, one host test under core/render/src/test (matrix identity), one CI step in .github/workflows/build.yml; optional small additions to tools/golden/golden.cpp (`space=p3`, `basecurve=identity`).
- Acceptance: (1) docs/COLOUR.md with the stage table, domains, invariants and tolerances of BK-435, including the measured findings of the W21 spec section 2; (2) `tools/colour/run-colour.sh` builds nine synthetic DNG fixtures (F1 to F9) deterministically with standard-library Python only and prints per-fixture worst error against the independent reference; on main it reproduces the output in the W21 spec section 8 (F1 to F5 PASS within 0.3 level of the recorded numbers, F6 to F8 and F9 warm fail as expected failures, matrix check M1 passes); (3) the script exits 0 when every PASS fixture passes and every XFAIL still fails, and exits 1 with "XPASS, remove the marker" when a fix lands; (4) one host test asserts `ColorSpaces.m` times the inverse of LibRaw's `prophoto_rgb` is identity within 5e-4; (5) the CI step runs the script after the golden step; (6) the demosaic CPSNR script and the quality benchmark of BK-421 to BK-423 are separate follow-up commits in the same task if time allows.
- Exit check: CI green with the colour step; the first result table is committed in docs/COLOUR.md including every XFAIL (AE-023 K = min(neutral), BK-470 adjust_maximum, AE-001 clipped warm neutral lands at 250).

## W22 Colour contract: white point, white balance neutrality and the base curve behind a look version (Starts: after W21; owns the decode and curve files)
- Full task file with tested code and the exact migration plan for saved edits: docs/backlog/tasks/W22-colour-contract.md. Design: look 2 = decode with `adjust_maximum_thr = 0`, a source gain (`wbGain` for look 2, LibRaw's old rescale factor `v1Scale` for look 1) and a second base curve with a shoulder to white; every saved edit keeps look 1 (`EditRecipe.lookVersion`, missing key reads as 1) and renders as before (existing goldens mean difference 0.00 under `look=1`), new edits and unedited photos use look 2; "Update look" is one undoable step.
- Entries: BK-470, BK-438, BK-440, BK-437, BK-023 (first step); includes the white balance work that W23 used to hold.
- Owns: raw_decode.*, engine gain and base_curve.h, the gain multiply in main.frag and lowres.frag, Native.kt and three JNI functions, core/model EditRecipe.kt, BaseCurve.kt, ColorSpaces.kt, RenderParams.kt (curve calls), EditorSession.kt and Exporter.kt (look plumbing), the editor Look row, tools/looks, tools/colour, tools/golden (look key, scenes), docs/COLOUR.md.
- Acceptance: `tools/colour/run-colour.sh` prints the two blocks of the W22 file section 8 (look 1 unchanged from main; look 2 all PASS including F6, F7, F8 and both F9); existing golden scenes on `look=1` byte-identical; `base2`, `tone2`, `clip2` reviewed by eye; curve property script passes; recipe, preset, paste and cache key tests as listed.
- Exit check: CI green. Jai: three old edits look unchanged; a bright sky photo reaches white after Update look and Undo returns; a warm indoor RAW matches daylight brightness.

## W23 Shader correctness bundle (Starts: after W22; owns main.frag)
- Already merged in ee38b8b: negative dehaze (AE-002), lens gain in the local analysis (AE-003), mask baseline (AE-012), grading tint luminance (AE-014), range masks in the display domain (AE-015), heal overlay before the vignetting gain (AE-005). Left from BK-459: HSL partition of unity (AE-013), clarity weight above 1.0 (AE-029), manual vignette centre (AE-016), the white balance work moved to W22.
- Full task file with the built and measured look 2 patch (HSL partition, fine Texture ring, ProPhoto grading luma, gated by one global slot G_LOOK, byte-identical on all 19 existing scenes with look 1) and the check script (passes look 2, fails 3 of 5 on look 1): docs/backlog/tasks/W23-shader-correctness.md. It depends on W22 only for `EditRecipe.lookVersion` (Kotlin hunk). Items already merged are listed there so nobody redoes them.
- Entries: BK-459 (remainder), BK-443, BK-032 (shared transfer include), BK-485 (follow-up).
- Owns: shaders/main.frag, shaders/geometry.glsl (transfer include only), engine/params.h only if a uniform is added, feature/editor/AutoTools.kt, tools/golden scenes and refs, core/render tests.
- Acceptance: each remaining AE fix of BK-459 has its golden (HSL equal-strength sweep, clarity above 1.0, manual vignette centre); the Temp and Tint sliders change luminance by under 1 percent and the eyedropper round trip makes a neutral R=G=B within 0.5 percent (fixtures F6 and F7 already pass after W22); colour range masks select the picked colour at the default range; one `transfer.glsl` include replaces the 2.2 and 2.4 shortcuts with the documented functions; look-version flag as W22.
- Exit check: CI green; goldens reviewed; BK-439 neutral ramp test passes.

## W24 Signing key rotation and updater plan (Starts: after W06; needs Jai's taps)
- Entries: BK-226, BK-240 (CI half), BK-362, BK-238, BK-239.
- Owns: the signing block in app/build.gradle.kts, .github/workflows/build.yml (signing and notes), docs/RELEASE.md, README.md.
- Acceptance: a tap-by-tap guide for adding the four Actions secrets from the phone; a rehearsal on a second package id (`app.rawline.test`) that proves the update path across a key change (APK Signature Scheme v3 rotation) before touching the real app; CI fails on main if secrets are missing after the switch; Settings > About shows the certificate fingerprint and build info; install failure guidance on every release page; developer verification notes (BK-238).
- Exit check: the rehearsal installs over itself on the phone; only then is the real app switched, after a fresh backup (W06).

## W25 AI quality and timing (Starts: after W09, W01 and W23; W23 owns main.frag first)
- Entries: BK-294, BK-295, BK-296, BK-298, BK-071, BK-072.
- Owns: core/ml/**, the denoise hooks in core/render/EditorSession.kt, app/EditorHost.kt (denoise effect only), tools/models, the denoise sampler in main.frag (golden scene added).
- Acceptance: AI denoise amount is a live GPU blend of a cached detail layer (no decode or model run per slider change; amount 0 equals no denoise in a golden); the denoiser streams tiles with under 60 MB extra memory; a model that failed on the GPU is retried after an app update or 7 days; select object supports several points and a box and offers the model's other masks; the Copy report has a per-model timing table and delegate; the People model is pinned by hash.
- Exit check: CI green; Jai pastes the AI timings from the report.

## W26 Studio S2: tiles, selections (rect, ellipse, lasso), layer masks, Open in Studio (Starts: after W20)
- Full task file with tested code (11 host tests, byte algebra, sparse tile planes with kill safety), schema v2 and shader goldens listed: docs/backlog/tasks/W26-studio-s2.md. GPU, UI and hand-off parts are instructions, not compiled.
- Entries: BK-376, BK-377 (the hand-off half), BK-400 (WebP versus deflate device test), BK-414.
- Owns: core/studio-model (Selection.kt, schema v2), core/render studio shaders (mask pass), feature/studio canvas tools, one Develop menu item behind STUDIO_ENABLED.
- Exit check: CI green; five mask goldens; Jai pastes the Copy report for selection and mask timings.

## W27 First run onboarding, help, glossary, messages and the copy rules check (Starts: after W16; new module feature/onboarding)
- Full task file: docs/backlog/tasks/W27-onboarding-help.md. 32 host tests pass (copy rules, flow state machine, resource deck), the Compose screens and Gradle wiring are specified, not compiled. The deck is exact (Australian English, no em dashes).
- Entries: BK-392, BK-393, BK-394, BK-395, BK-396, BK-486.
- Owns: core/model copy and onboarding packages and tests, core/ui res strings_*.xml and HelpSheet, new feature/onboarding, docs/COPY.md, docs/copy-allow.txt.
- Exit check: CI green; Jai taps through a fresh install and pastes the result.

## W29 The library's first impression: stable grid order and RAW photos first (Starts: right after W16; shares LibraryViewModel and LibraryScreen)
- Full task file: docs/backlog/tasks/W29-library-first-impression.md. OrderGate (the grid does not move while the user is busy), DefaultView, WhatsNew, `LibraryFilter.rawOnly`: compiled and tested on the host (23 core/model tests, 11 new). Android edits specified, not compiled.
- Entries: BK-497, BK-498 (and the capture time read for the first screenful).
- Owns: core/model Library.kt and LibraryView.kt, core/data DeviceScanner.kt and the DAO count, app/LibraryViewModel.kt, feature/library.
- Exit check: CI green; Jai: first run on the phone opens on RAW photos with an All photos chip, a copied card does not make the grid jump, Copy report shows grid_resort_count.

## Studio first release gate (PM decision)
- BK-502 (P0): no release with Studio visible until W13 (S1b fixes plus BK-503) and S1c have merged. BK-503 (P1): full phone, endless save retries. Jai's baseline: PHONE-TEST-S1.md.

## W31 Studio S1d: reopen, RAW honesty, honest start failures, thumbnail off Close (Starts: after W13 and W16)
- Full task file: docs/backlog/tasks/W31-studio-s1d.md. Start in Develop unless Studio was used in the last 30 minutes, a Continue card after a kill, RAW file notices and named decode failures, start failures that ignore a swipe away, the thumbnail written from the autosave path. 124 studio-model tests pass on the host; the app and Compose edits are specified, not compiled.
- Entries: BK-504, BK-505, BK-506, BK-507.
- Owns: core/studio-model Mode.kt and ResumeRules.kt, feature/studio StudioRoot, StudioHome, CanvasScreen, core/studio-render StudioSession (thumbnail timer), app/src/studioOn StudioEntry.
- Exit check: CI green; Jai repeats PHONE-TEST-S1 steps 5 and 7.

## W30 Commit message check (DONE: merged in 6bc276b)
- Short DONE note: docs/backlog/tasks/W30-commit-message-check.md. Hook, range check, tests and the CI job are in main (tools/commit-msg-check.sh, tools/check-commit-messages.sh, tools/test-commit-msg-check.sh).
- Entries: BK-501 (done).

## W28 Studio S1b fixes (DONE: merged in 1fed051 and b63e9df; task file name W13-s1b-fixes.md)
- Merged: tiled stroke commit, banded native readback, pen over palm with hover grace, GL lifecycle with a paused state, graveyard pruning, autosave on a full phone (BK-503 part 1). Reviewed in docs/backlog/reviews/review-w13.md.
- Entries: BK-479, 480, 483 done; 481, 484, 487 partly done (leftovers are BK-510, BK-512, BK-508); BK-503 partly done (BK-511).

## W32 W13 follow-ups (Starts: after W31; shares CanvasScreen and the session)
- Full task file: docs/backlog/tasks/W32-w13-followups.md. Two patches that apply at b5b06ac (`docs/backlog/patches/w32-core.patch`, `w32-ui.patch`): hover that lapses, the retry gap from the failure, flushAndWait and the leave check, a duplicate that cleans up, the GL detach rule. 164 host tests pass for the core patch; the UI patch and the export-while-backgrounded edit are written, not compiled.
- Entries: BK-508, BK-509, BK-510, BK-511 (BK-512 is an optional bundle, not in the task).
- Owns: core/studio-model (InputRouter, Catalog), core/studio-render (StudioSession, StudioGl), feature/studio (CanvasScreen, ExportSheet), StudioExporter.
- Exit check: 164 host tests green; Jai: five rotations with the three counters, pen with the hand on the glass, Back on a full phone, export with Home pressed (PHONE-TEST-S1 steps 3, 4, 8, 10).

## Review of the merged Studio S1b session code (done, read only)
- docs/backlog/reviews/review-s1b.md (review of W13 is review-w13.md, 10 findings, R1 and R2 are P1): 19 findings including the Compose layer of 75cb498, BK-479 to BK-484 and BK-487 (stroke dirty region, banded readback, pen over palm, frame path, GL lifecycle, memory, rotation and touch targets). Fix before the S1b canvas ships: F1, F2, F3.

After these 25: baseline profile and macrobenchmarks (BK-002, BK-003, after W01 and W06), onboarding and help copy (BK-191, 192, 392, 393, 350, 394, after W17), BK-024 and BK-422 (GPU demosaic, after W21 has numbers), BK-021 and BK-445 (look refit and camera matrix from Jai's chart), BK-051/052 (undo and before/after), BK-460 and BK-466 (lens consistency), Studio S2 onward (spec milestones), then the rest of M2 to M6.

---

## Counts

| Area | P0 | P1 | P2 | P3 | Total |
| --- | --- | --- | --- | --- | --- |
| A. Speed | 4 | 14 | 18 | 3 | 39 |
| B. Editing quality and colour | 8 | 22 | 24 | 7 | 61 |
| C. Editor UX | 2 | 14 | 18 | 5 | 39 |
| D. Masking and AI | 2 | 17 | 16 | 6 | 41 |
| E. Library workflow | 3 | 20 | 20 | 9 | 52 |
| F. Export | 1 | 12 | 9 | 2 | 24 |
| G. Reliability and data | 7 | 19 | 12 | 3 | 41 |
| H. Accessibility | 0 | 4 | 5 | 1 | 10 |
| I. Battery, thermal, storage | 0 | 6 | 13 | 1 | 20 |
| J. Onboarding, help, settings | 0 | 10 | 17 | 3 | 30 |
| K. Testing and CI | 0 | 18 | 25 | 4 | 47 |
| L. Security, privacy, release | 4 | 9 | 8 | 1 | 22 |
| M. Platform and Panasonic | 1 | 6 | 13 | 6 | 26 |
| N. Studio | 6 | 32 | 19 | 3 | 60 |
| **All** | 38 | 203 | 217 | 54 | 512 |

Status of the 512 entries: 25 DONE, 48 PARTLY DONE, 10 MERGED into another entry (dedupe pass, 6 Oct 2026), 19 SPEC COVERS and 2 SPEC WINS (Studio entries settled by docs/STUDIO_SPEC.md), 1 DECLINED and 1 BLOCKED; the rest are open. Merged and done entries are kept for their history and acceptance details.

## Top 30 overall (ranked)

Order weighs risk of losing Jai's work first, then measurement, then speed and look, then workflow. Items marked with a dependency must follow it.

| # | ID | Area | Title | Why now |
| --- | --- | --- | --- | --- |
| 1 | BK-142 | G | [DONE] Automatic backups of the catalogue (edits, meta, presets, snapshots) on every N edits and daily, kept as the last 7 rotating files | Automatic rotating catalogue backups outside the app: uninstall or a key change still wipes everything. |
| 2 | BK-226 | L | Move release signing to a private key held only in Actions secrets (and plan the one-time reinstall safely) | Private release signing key via Actions secrets with key rotation: the public key lets anyone forge an update. |
| 3 | BK-001 | A | Get the first real phone timings and gate CI on a budget file | First real phone timings and a one-tap speed test: all speed work is guesswork until measured. |
| 4 | BK-096 | E | [PARTLY DONE] Import from SD card or USB-C reader: copy RW2/JPG into a dated folder with a progress queue, skip duplicates, verify, then eject prompt | Import from the SD card or camera over USB: the first step of every shoot, missing today. |
| 5 | BK-291 | G | Lighter identity fix after the BK-141 decision: content fingerprint as a secondary lookup, never replacing the legacy key | Content fingerprint as a secondary lookup: protects edits from a changed modified time without the re-key the decision rejected. |
| 6 | BK-308 | G | [PARTLY DONE] Catalogue work runs on the main dispatcher: XMP writes, edit pastes and ratings can block the UI | Catalogue work (XMP writes, pastes, ratings) runs on the main thread: a real jank and ANR risk. |
| 7 | BK-261 | A | Catalog.reapply loads the whole photos table and runs one SELECT per photo on every device scan | Stop the full-table reload and per-photo queries on every MediaStore change (exports trigger it too). |
| 8 | BK-003 | A | Ship a Baseline Profile (and startup profile) for the app and the Compose paths used on first open | Baseline Profile (plus R8, BK-004): faster first launch after every sideloaded update. |
| 9 | BK-009 | A | Slider latency budget: coalesce recipe updates to vsync and render only the visible region at screen resolution | Slider latency: coalesce renders, GPU histogram, no readback per tick. |
| 10 | BK-292 | C | Rotating the phone rebuilds the whole editor: undo history is lost and the raw is decoded again | Rotation rebuilds the editor: Undo history is lost and the raw is decoded again. |
| 11 | BK-021 | B | Validate the base look on many scenes, not one: collect 15-20 RW2 plus matching camera JPEGs and refit | Validate and refit the base look on 15-20 scenes: it touches every photo, fitted on one scene today. |
| 12 | BK-022 | B | Make the camera's colour exact: use the real S5IIX colour matrix and a dual-illuminant interpolation | Camera-space white balance and the real S5IIX colour matrix: hue accuracy of skin and sky. |
| 13 | BK-023 | B | Highlight reconstruction beyond LibRaw blend mode 2 | Highlight reconstruction beyond LibRaw blend: clipped skies and lights are common on the S5IIX. |
| 14 | BK-024 | B | Full-resolution GPU demosaic for the edit base, 100 percent zoom and export (replace AHD CPU path) | GPU full-resolution demosaic: fast edit base, instant 100 percent zoom, better detail than AHD. |
| 15 | BK-267 | A | Loupe 100 percent zoom shows real pixels (spec tier 3), not a stretched 2048 px preview | Loupe 100 percent zoom with real pixels (spec tier 3): focus checking while culling. |
| 16 | BK-269 | C | Make "what I am seeing" obvious: preview and export parity at 100 percent (half-size base versus full decode) | Preview versus export parity at 100 percent (binned half-size base): honest sharpening and NR decisions. |
| 17 | BK-294 | D | Changing the AI denoise amount re-decodes and re-runs the whole model; it also runs again for the full-size decode | AI denoise slider re-decodes and re-runs the model on every change: make the amount a live GPU blend. |
| 18 | BK-052 | C | Split before/after and side-by-side compare, not only "hold the photo" | Split and side-by-side before/after, not only hold-to-see. |
| 19 | BK-097 | E | Reject-to-trash and delete with undo (MediaStore trash and a 30 day bin) | Reject-to-trash with a confirmation and undo: culling never frees space today. |
| 20 | BK-098 | E | Search: text, camera, lens, ISO, aperture, focal length, date range, rating, label, edited, file type, with saved searches | Structured search (lens, ISO, date, rating) with saved searches. |
| 21 | BK-104 | E | Cull mode: full-screen one-at-a-time with pick/reject on swipe, rating on number tap, and auto-advance | Cull mode with swipe pick/reject and auto-advance (then BK-310 suggestions). |
| 22 | BK-246 | M | [DONE] Adopt Android 16 behaviours now: predictive back and edge-to-edge (targetSdk is 37) | Android 16 predictive back and edge-to-edge compliance (targetSdk is 37). |
| 23 | BK-145 | G | [DONE] DeviceScanner/Indexer must never prune on a partial listing (AUDIT open item 3), and add a downgrade/rollback path for Room | No pruning on a partial listing (the downgrade half is done). |
| 24 | BK-166 | H | Bring every interactive control to 48 dp minimum hit area without changing the visual size | 48 dp touch targets without changing the look. |
| 25 | BK-323 | M | [PARTLY DONE] The release APK carries three unused ABIs: about 22.7 MB of native code, so every sideload update is a third bigger than needed | Drop the three unused ABIs: about 22.7 MB less in every sideload update, one line in app/build.gradle.kts. |
| 26 | BK-227 | L | Stop committing release APKs to git | Stop committing the 61 MB APK to git; Releases are the channel. |
| 27 | BK-240 | L | [PARTLY DONE] In-app update check and install (sideload update flow with SHA-256 verification) | In-app update check and one-tap install with SHA-256 verification. |
| 28 | BK-303 | G | [DONE] Restore preview ("dry run") and a backup integrity manifest | Restore preview (dry run) and backup integrity manifest. |
| 29 | BK-355 | G | Corrupt, truncated or unsupported RW2: partial decode, preview-only editing and clear states | Corrupt or truncated RW2: partial decode and preview-only editing instead of a dead end. |
| 30 | BK-338 | E | Culling 800 photos: "next undecided" jump, a visible progress count, and resume where I stopped | Culling 800 photos: next undecided, progress count and resume. |

See the milestone plan below for the order of attack and dependencies. Already done (BK-144, BK-146, BK-137, BK-148, BK-150 and others) are marked DONE in their entries and left out of this list.


## Quick wins under 2 hours each (ranked by value for Jai)

Each is a small, low-risk change with the exact file and edit. Times are for a person who knows the code, without phone testing. Most only need the unit tests and one look on the phone. Ranking: size of the benefit to Jai first (download size, jank, lost state), then how many later items it unblocks.

| # | ID | Time | File | Change | Check |
| --- | --- | --- | --- | --- | --- |
| 1 | BK-323 | 20 min | app/build.gradle.kts | In `defaultConfig` add `ndk { abiFilters += "arm64-v8a" }` (the filter in core/native does not reach dependency AARs). Optional: `packaging { jniLibs { excludes += setOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") } }`. | `unzip -l app-release.apk / grep lib/` lists only arm64-v8a; APK about 22 MB smaller uncompressed. |
| 2 | BK-308 | 45 min | app/src/main/kotlin/app/rawline/LibraryViewModel.kt (lines 192-194, 197, 205, 210, 232) | Change `viewModelScope.launch {` to `viewModelScope.launch(Dispatchers.IO) {` for rate, flag, label, copyEdits, pasteEdits, syncEdits and enqueueExport. `applyToTargets` loops over every target parsing JSON and writing XMP; it must not run on Main. `message.value` is a StateFlow so setting it from IO is safe. | Rate 400 selected photos with XMP sidecars on: no frame over 50 ms in the grid (Copy report `grid jank_frames`). |
| 3 | BK-427 | 20 min | core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt (`download`, the checksum drain line) | Replace `java.io.OutputStream.nullOutputStream()` (API 33; minSdk is 31) with a tiny own stream: `object : java.io.OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }`. Found by lint in the fifth audit pass; it would crash a model download on Android 12 and 12L. | Lint no longer reports NewApi for ModelStore; a unit test drains a zip through `download`-like code. |
| 4 | BK-120 | 1 h | feature/library/src/main/kotlin/app/rawline/feature/library/LibraryScreen.kt (lines 120 and 122) | `var columns by rememberSaveable { mutableIntStateOf(5) }` and wrap `selected` in `rememberSaveable(saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })) { mutableStateOf(setOf<Long>()) }`. Fixes AUDIT open item "rotation resets library selection". | Select 5 photos, rotate the phone: selection and column count stay. |
| 5 | BK-339 | 90 min | core/model/src/main/kotlin/app/rawline/core/model/Library.kt (`LibraryFilter.apply`), feature/library/.../LibraryScreen.kt (filter chips), core/model/src/test/.../ModelTest.kt | Add `hideRejects: Boolean = true` to `LibraryFilter`; in `apply` skip `flag == -1` unless `flag` is `REJECT` or hideRejects is false; add a "Rejects" chip that sets it; add tests for each `FlagFilter` with the new default. Mention the new default in What's New. | Reject a photo: it leaves the grid; the Rejects chip brings it back. |
| 6 | BK-285 | 45 min | app/src/main/kotlin/app/rawline/MainActivity.kt (line 135) | Remove the `notifPermission.launch(POST_NOTIFICATIONS)` from the first-launch `LaunchedEffect`; call it once (flag in prefs) from the code path that queues the first export (`vm.enqueueExport`), before the service starts. | Fresh install shows only the photos prompt at launch; the notification prompt appears on the first export. |
| 7 | BK-346 | 30 min | feature/loupe/.../LoupeScreen.kt (top of `LoupeScreen`) and feature/export/.../QueueScreen.kt | `val view = LocalView.current; DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }` in the loupe; the same in the queue screen while any job is running. Setting toggle later. | The screen stays on while swiping photos for 5 minutes with a 30 second timeout. |
| 8 | BK-229 | 30 min | .github/workflows/build.yml (after "Build signed release APK" and "Build release APK with the committed sideload key") | Add a step: `source tools/env.sh && "$ANDROID_HOME/build-tools/37.0.0/zipalign" -c -P 16 4 rawline.apk` (it passes on the 5 Oct APK). Add `for f in lib/arm64-v8a/*.so` readelf check later. | CI fails if a library is not 16 KB aligned. |
| 9 | BK-218 | 15 min | .github/workflows/build.yml (same step) | Add `ls -l rawline.apk` and `unzip -lv rawline.apk / sort -k1 -n -r / head -10` so the APK size and top contents are in every run log. | APK size visible in the job log; use it to confirm BK-323. |
| 10 | BK-264 | 1 h | app/src/main/kotlin/app/rawline/RawlineApplication.kt (class Graph) | Make `modelStore`, `rawPrefetch`, `previews`, `maskStore`, `patchStore` and `thumbs` `by lazy` (catalog and indexer reference some of them: keep their order). ModelStore does file checks for five packs in its constructor. | Startup trace: `Application.onCreate` under 20 ms; app still opens the library. |
| 11 | BK-296 | 90 min | core/ml/src/main/kotlin/app/rawline/core/ml/TfModel.kt (`create()`) | Store `accel_ver_$name` = app `longVersionCode` next to the saved delegate; when the version differs (or after 7 days) ignore a saved `CPU` and try the GPU first again. Keep recording the error text. | After an update a model that failed on GPU once is tried on GPU again (report shows `ai_<name>_GPU_first_ms`). |
| 12 | BK-157 | 1 h | app/src/main/kotlin/app/rawline/ExportRunner.kt (top of `exportOne`) | Before rendering: if `context.cacheDir.usableSpace` is under 300 MB (or 2 x the estimated output) throw `IllegalStateException("Not enough free space: about 300 MB needed")`; the job then shows a clear message in the queue. | Fill the phone storage in a test: the job fails fast with the message. |
| 13 | BK-166 | 45 min | feature/loupe/src/main/kotlin/app/rawline/feature/loupe/LoupeScreen.kt (`BarIcon` and the star `Box`), feature/masking/.../MaskTray.kt (line 141) | Change `Modifier.size(44.dp)` to `48.dp` (icon sizes stay 22 and 24 dp). Phone check that the bottom bar still fits six items on a 1440 px wide screen. | Hit areas are 48 dp; no layout shift beyond 4 dp per icon. |
| 14 | BK-169 | 30 min | core/ui/src/main/kotlin/app/rawline/core/ui/LrTheme.kt (line 38) and five call sites | Add `val AccentText = Color(0xFF6C9CF0)` (about 6.2:1 on #1C1C1C) and use it instead of `Lr.Accent` where it colours text: QueueScreen.kt lines 50 and 80, LibraryScreen.kt line 186, Controls.kt line 282, GeometryPanel.kt line 151. Fills and icons keep #437EE4. | Contrast of accent text over Surface1 is at least 4.5:1 (add the unit test from BK-169 if time allows). |
| 15 | BK-227 | 20 min | repo root: `dist/`, `.gitignore`, README.md | `git rm --cached dist/rawline.apk dist/rawline.apk.sha256`, add `dist/` to .gitignore, and say in README that the APK comes from Releases. First confirm nobody downloads `dist/` directly (commit 0c6441b added it deliberately). | `git ls-files dist` is empty; clone size stops growing by 60 MB per APK commit. |
| 16 | BK-299 | 1 h | core/data/src/main/kotlin/app/rawline/core/data/Db.kt (PhotoDao) and app/src/main/kotlin/app/rawline/ReportBuilder.kt | Add `@Query("SELECT width, height, COUNT(*) AS n FROM photos WHERE isRaw = 1 AND width > 0 GROUP BY width, height ORDER BY n DESC LIMIT 5") suspend fun previewSizes()` (no schema change) and print it in the "Library" section as `embedded preview sizes: 6000x4000 (n=412)`. | The next Copy report states whether the S5IIX embeds a full-size JPEG (decides BK-267 and BK-333). |
| 17 | BK-058 | 45 min | core/ui/src/main/kotlin/app/rawline/core/ui/Controls.kt (`RawSlider`, `doReset`) | Get `LocalHapticFeedback.current` and call `performHapticFeedback(HapticFeedbackType.Confirm)` inside `doReset`; later add `SegmentTick` when the value crosses the default during a drag. | Double tap a slider label: a short confirm haptic. |
| 18 | BK-241 | 20 min | .github/workflows/build.yml ("Publish release", variable `NOTE`) | Append ` Previous build: v0.1.$(( ${{ github.run_number }} - 1 )).` and the milestone name from a `MILESTONE` file; makes the rollback target visible in the release page. | New release notes name the previous build. |
| 19 | BK-301 | 20 min | core/data/src/main/kotlin/app/rawline/core/data/Db.kt (EditDao.addSnapshot) and Catalog.kt (`addSnapshot`) | `@Insert suspend fun addSnapshot(s: SnapshotEntity): Long` and `return edits.addSnapshot(...)` instead of re-reading the newest row. | Two snapshots in the same millisecond get two ids (unit test). |
| 20 | BK-205 | 90 min | app/src/main/kotlin/app/rawline/MainActivity.kt (where `onCopyReport` builds the report) and feature/settings/SettingsScreen.kt | Add a "Share report" button that sends the same text with `Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), null)`; pass an `onShareReport` lambda to SettingsScreen. | Share sheet opens with the full report text. |
| 21 | BK-203 | 1 h | new core/model/src/test/kotlin/app/rawline/core/model/TextRulesTest.kt | Walk `docs/*.md` and every `src/main/**/*.kt` under the repo root (use `File("../..")` from the module dir) and fail on the em dash character (U+2014) and on a short list of American spellings (colour is correct; flag `color ` in prose strings only, not code identifiers). | `./gradlew testDebugUnitTest` fails when a string or doc breaks the project writing rule. |
| 22 | BK-072 | 1 h | core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt (`Models.PEOPLE`), tools/models/models.json | Download the file once, record its SHA-256 in `sha256 = ...` and keep the `latest` URL; the download is then rejected if upstream changes it (and CI `fetch.sh` should compare the hash and fail loudly). Needs network access to storage.googleapis.com from the sandbox, which may be blocked; if so Jai pastes the hash from a PC-free tool or the CI log prints it. | Download of the People pack matches the pinned hash; a changed file fails with "does not match the expected checksum". |

Total about 17 hours for all 22. Do items 1 to 6 first (about 4.5 hours): they fix the biggest download, the main-thread risk, two visible annoyances and keep alignment honest.


## Milestone plan (all P0 and P1 entries, seven ordered milestones (M7 is the separate Studio line))

Effort units are rough working days (S 1, S-M 2, M 3, M-L 5, L 8) for one person with the sandbox build loop; calendar time depends on how many sessions work in parallel and they ignore waiting for Jai's phone tests, which dominate M2 and M3. "Value" is my judgement for Jai, not a measurement. Items marked partly done keep only their left-over scope. Done entries are listed under the plan.

| Milestone | Entries | Effort (days) | Value | Value per effort |
| --- | --- | --- | --- | --- |
| M1 Safety net, measurement and release hygiene | 36 | 66 | Very high | Highest |
| M2 Fast to open, fast to edit | 25 | 68 | High | High |
| M3 Look and image quality | 45 | 178 | High (risky) | Medium |
| M4 Editor and AI daily workflow | 25 | 71 | Medium-high | Medium |
| M5 Library and card import | 31 | 87 | High for workflow | Medium-high |
| M6 Export, accessibility, polish and the stretch items | 27 | 94 | Medium | Lower |
| M7 Studio (separate product line, scheduled after M1 to M3; the spec's own milestones S1 to S10 set the inner order) | 46 | 172 | High if wanted | Large; set by the spec |

### M1: Safety net, measurement and release hygiene

Highest value for effort: mostly S and M items that protect Jai's edits and make every later claim measurable.

Exit check: an automatic backup exists outside the app; a release is signed with a private key and passes the APK check; the first full Copy report (speed test) is pasted and PERF.md has real rows; no database work on the main thread.

Dependencies: BK-142 and BK-143 before BK-226 (key rotation needs a safe backup first); BK-001 before any claim in M2 and M3; BK-147 before more schema work.

| ID | P | Size | Status | Title |
| --- | --- | --- | --- | --- |
| BK-473 | P1 | S |  | After a reinstall the new app may not be able to read backup zips that the old install wrote through MediaStore (verify on the phone) |
| BK-001 | P0 | M |  | Get the first real phone timings and gate CI on a budget file |
| BK-002 | P0 | M |  | Macrobenchmark module for cold start, grid scroll and open-from-grid |
| BK-071 | P0 | S | PARTLY DONE | Phone timings for every AI step with a pass/fail table (mask ready under 2 s, tap-select under 300 ms, remove under 10 s) |
| BK-072 | P0 | S |  | Model integrity: pin the People model by hash and by version |
| BK-142 | P0 | M | DONE | Automatic backups of the catalogue (edits, meta, presets, snapshots) on every N edits and daily, kept as the last 7 rotating files |
| BK-143 | P0 | S-M | PARTLY DONE | Survive uninstall: keep a copy of the catalogue outside the app's private storage |
| BK-145 | P0 | M | DONE | DeviceScanner/Indexer must never prune on a partial listing (AUDIT open item 3), and add a downgrade/rollback path for Room |
| BK-147 | P0 | M | PARTLY DONE | Migration and backup round-trip test harness that runs on the JVM (Robolectric) in CI |
| BK-152 | P1 | S | PARTLY DONE | Atomic writes for mask layers, heal patches and thumbnails (write temp, fsync, rename) |
| BK-153 | P1 | S-M |  | Garbage collect orphaned mask layers, heal patches and thumbnails |
| BK-154 | P1 | M | PARTLY DONE | Recipe schema versioning with forward-compatible reading and a "newer than this app" lock |
| BK-155 | P1 | S |  | Preserve unknown JSON fields when round-tripping recipes (older app reading newer file) |
| BK-157 | P1 | S |  | Low storage handling: check free space before export, model download, import and cache writes |
| BK-226 | P0 | M |  | Move release signing to a private key held only in Actions secrets (and plan the one-time reinstall safely) |
| BK-227 | P0 | S |  | Stop committing release APKs to git |
| BK-229 | P0 | S | PARTLY DONE | Verify 16 KB page size compatibility of every native library and fail CI otherwise |
| BK-261 | P1 | S-M |  | Catalog.reapply loads the whole photos table and runs one SELECT per photo on every device scan |
| BK-264 | P1 | S |  | Make Application.onCreate cheap: lazy Graph members and no I/O on the main thread before first frame |
| BK-291 | P1 | M |  | Lighter identity fix after the BK-141 decision: content fingerprint as a secondary lookup, never replacing the legacy key |
| BK-303 | P1 | M | DONE | Restore preview ("dry run") and a backup integrity manifest |
| BK-308 | P1 | S-M | PARTLY DONE | Catalogue work runs on the main dispatcher: XMP writes, edit pastes and ratings can block the UI |
| BK-206 | P1 | S-M | PARTLY DONE | Add Android lint and Kotlin static analysis (detekt or ktlint) to CI (AUDIT item 5) |
| BK-207 | P1 | S | PARTLY DONE | Pin GitHub Actions to commit SHAs and enable Dependabot for actions and Gradle (AUDIT item 5) |
| BK-230 | P1 | S |  | Network security config: cleartext off, only the model hosts allowed, optional pinning |
| BK-234 | P1 | S-M |  | Dependency and native library vulnerability monitoring, with LibRaw patch policy |
| BK-238 | P1 | S |  | Prepare for Google's Android developer verification for sideloaded apps |
| BK-239 | P1 | S |  | Install integrity: show the app's signing fingerprint and build info in Settings > About |
| BK-241 | P1 | S |  | Release channels and rollback: keep the last 5 builds addressable and mark milestones |
| BK-149 | P1 | S | PARTLY DONE | Breadcrumbs: last 50 user and engine events in the crash report |
| BK-323 | P1 | S | PARTLY DONE | The release APK carries three unused ABIs: about 22.7 MB of native code, so every sideload update is a third bigger than needed |
| BK-353 | P1 | M |  | Disk full: handle SQLite and cache write failures without losing the session |
| BK-361 | P1 | M |  | Upgrade from an old build: a fixture matrix and a first-launch-after-update check |
| BK-429 | P0 | M |  | Pre-release checklist and an automatic gate script (what can go wrong with a release, and how to check each item before telling Jai to install) |
| BK-451 | P1 | S |  | SPEC.md versus what is built: the gap table (docs/SPEC_GAPS.md) with an owner for every row |
| BK-458 | P1 | S |  | One tracker: map the three audit documents (AE, AQ, AU ids) to backlog ids so nothing is lost or done twice |

### M2: Fast to open, fast to edit

High value (goal 1 in the spec). Needs the M1 speed test to choose what to do first; drop any item the numbers say is not a problem.

Exit check: PERF.md shows phone numbers for every row; open under 150 ms, Edit first frame under 1.2 s, slider frame p95 under 16 ms, or a written reason and a new target.

Dependencies: BK-001 (numbers); BK-003 and BK-004 together; BK-292 before BK-156; BK-176 and BK-177 before wider prefetch (BK-005, BK-008).

| ID | P | Size | Status | Title |
| --- | --- | --- | --- | --- |
| BK-003 | P0 | M |  | Ship a Baseline Profile (and startup profile) for the app and the Compose paths used on first open |
| BK-004 | P0 | M | PARTLY DONE | Turn on R8 shrinking and resource shrinking for release, keep rules for JNI and reflection |
| BK-005 | P1 | S |  | Reduce Edit-to-first-frame: decode half-size in the background as soon as a photo has been on screen 300 ms, not 1.5 s |
| BK-007 | P1 | S-M | DONE | Enable OpenMP (or a thread pool) inside LibRaw for the full-size decode |
| BK-008 | P1 | M |  | Persist the half-size decode (and the 2048 px preview) in a disk cache keyed by photo key |
| BK-009 | P1 | M |  | Slider latency budget: coalesce recipe updates to vsync and render only the visible region at screen resolution |
| BK-010 | P1 | M |  | Use a lower-resolution render during slider drag and refine on release |
| BK-011 | P1 | M |  | Skip unchanged passes: cache the analysis blurs and the adjusted image per parameter group |
| BK-012 | P1 | M |  | Grid: precompute and persist 360 px thumbnails sized to the column count, in a durable store (not cacheDir) |
| BK-013 | P1 | M | PARTLY DONE | Memory governor: one place that decides how many 100 MB decodes, previews and masks may live |
| BK-053 | P1 | M | PARTLY DONE | Compute the histogram on the GPU from a 256 x 170 downsample, 4 times a second |
| BK-126 | P0 | M |  | Export size and speed: measure 24 MP JPEG under 3 s and fix the known slow spots |
| BK-156 | P1 | M | PARTLY DONE | GPU context loss and driver failure recovery in the editor (not only the heal/mask layer re-upload) |
| BK-158 | P1 | M |  | Process death restoration: reopen the same photo and tool after the system kills the app |
| BK-176 | P1 | M |  | Thermal-aware work: listen to `PowerManager.addThermalStatusListener` and back off prefetch, AI warmup and export concurrency |
| BK-177 | P1 | S-M |  | AI work policy: speculative AI (warm SAM, background denoise, semantic index) only when charging or battery above 40 percent, never in Battery Saver |
| BK-181 | P1 | M |  | 30 minute soak test mode (scripted) with leak and thermal readout |
| BK-267 | P1 | M |  | Loupe 100 percent zoom shows real pixels (spec tier 3), not a stretched 2048 px preview |
| BK-292 | P1 | M |  | Rotating the phone rebuilds the whole editor: undo history is lost and the raw is decoded again |
| BK-293 | P1 | S |  | Start the raw decode in parallel with the recipe, snapshot and mask set-up (Edit start is serialised behind database reads) |
| BK-354 | P1 | S-M |  | Low battery policy: warn before and pause long jobs, and cheapen the editor |
| BK-355 | P1 | M |  | Corrupt, truncated or unsupported RW2: partial decode, preview-only editing and clear states |
| BK-358 | P1 | M |  | Rotation and configuration change audit: what survives and what is lost, screen by screen |
| BK-363 | P1 | M |  | GPU out of memory while editing or exporting a large file: detect it and degrade instead of failing |
| BK-389 | P1 | M |  | Keep pan and zoom fluid in the Develop editor: transform the last frame during the gesture, re-render when it settles |

### M3: Look and image quality

High value (goal 2) but the riskiest: every item changes how photos look, so each is behind a recipe flag and needs Jai to judge on the phone.

Exit check: golden scenes cover each stage; Jai approves side by side comparisons against the camera JPEG and desktop output on 15 files; export parity at 100 percent verified.

Dependencies: BK-021 before BK-022, BK-033, BK-287; BK-024 before BK-025, BK-027 and the 96 MP work (BK-253, BK-127); BK-211 to BK-213 alongside; BK-294 and BK-295 before more AI denoise work.

| ID | P | Size | Status | Title |
| --- | --- | --- | --- | --- |
| BK-021 | P0 | M |  | Validate the base look on many scenes, not one: collect 15-20 RW2 plus matching camera JPEGs and refit |
| BK-022 | P0 | L |  | Make the camera's colour exact: use the real S5IIX colour matrix and a dual-illuminant interpolation |
| BK-023 | P0 | L |  | Highlight reconstruction beyond LibRaw blend mode 2 |
| BK-024 | P0 | L |  | Full-resolution GPU demosaic for the edit base, 100 percent zoom and export (replace AHD CPU path) |
| BK-006 | P1 | L |  | Replace the LibRaw half-size decode with a GPU "bin and upload" from packed 16-bit raw |
| BK-025 | P1 | L |  | Raw-domain noise reduction before demosaic |
| BK-026 | P1 | M |  | Dual native ISO aware noise profile for the S5IIX |
| BK-027 | P1 | M-L |  | Smarter default sharpening: capture sharpening in raw domain and deconvolution option |
| BK-028 | P1 | M |  | Chroma noise reduction in a perceptual chroma space, not a 25-tap box on RGB ratios |
| BK-029 | P1 | L |  | Local tone mapping that does not halo: replace the Gaussian-blur base with a guided or edge-aware pyramid |
| BK-030 | P1 | M-L |  | HSL colour mixer in a perceptually better space with 8 bands that do not overlap badly |
| BK-031 | P1 | M |  | Colour grading wheels: pass through a perceptual model and add per-wheel luminance, balance and blend that match user expectation |
| BK-032 | P1 | S-M |  | Replace the global 2.2 gamma shortcuts with the true sRGB or a shared transfer in all shader code |
| BK-033 | P1 | M |  | Tone curve with a proper camera-style profile selector: Standard (baseline), Neutral, Vivid, Monochrome, Flat/V-Log style |
| BK-034 | P1 | M |  | Lens correction verified on a real Lumix S lens, and a manual fallback when no profile matches |
| BK-035 | P1 | M |  | Embed the camera's own lens correction data: read Panasonic RW2 distortion and vignetting maker tags when present |
| BK-287 | P1 | L |  | Real DNG profiles (DCP-like) for the S5IIX: use the camera's embedded ForwardMatrix and tone curve if present, and let Jai import his own .dcp |
| BK-252 | P1 | M |  | Panasonic Photo Style and in-camera settings: read and show them, and offer a matching starting look |
| BK-253 | P1 | M |  | High-resolution (96 MP) and multi-shot RAW handling rules |
| BK-269 | P1 | M |  | Make "what I am seeing" obvious: preview and export parity at 100 percent (half-size base versus full decode) |
| BK-127 | P1 | L |  | Stream-encode JPEG in tiles so memory stays bounded (needed for 96 MP and 50 MP files) |
| BK-275 | P1 | M-L |  | Edit non-RAW files at full bit depth (HEIF 10-bit, 16-bit PNG/TIFF) and treat the S5IIX HEIF/HLG files properly |
| BK-294 | P1 | L |  | Changing the AI denoise amount re-decodes and re-runs the whole model; it also runs again for the full-size decode |
| BK-295 | P1 | M | PARTLY DONE | Denoiser memory and CPU cost: the tile loop keeps every output in RAM and converts colour per pixel in Kotlin |
| BK-211 | P1 | M |  | Expand the golden shader suite to every feature and enforce coverage |
| BK-212 | P1 | S-M |  | Golden tests for tiled export versus untiled preview (seam detection) |
| BK-213 | P1 | M |  | Parity test between CPU-side helpers and shaders (curves, crop fit, tone) with property-based inputs |
| BK-324 | P1 | M |  | Memory budget by megapixel count: write the table, then pick modes automatically (24 MP, 44 MP, 96 MP) |
| BK-325 | P1 | M |  | RW2 maker note reader: pull Photo Style, white balance mode, stabiliser mode, AF mode and point, ISO gain, HLG and Real Time LUT state into the catalogue |
| BK-421 | P1 | M-L |  | RAW quality benchmark suite: objective, repeatable numbers for demosaic, noise, colour, sharpness, highlights and lens correction |
| BK-422 | P1 | M |  | Choose the demosaic by ISO and subject, following published raw converter guidance (Rawline uses AHD today) |
| BK-423 | P1 | S |  | Jai's calibration shoot: one 30 minute protocol that produces the test files every quality item needs |
| BK-430 | P1 | S |  | Samsung Expert RAW and phone-camera DNG files: decide support, test with LibRaw 0.22.2, and fail clearly |
| BK-470 | P1 | S | DESIGNED | LibRaw `adjust_maximum_thr` rescales exposure by up to +0.4 EV depending on the brightest pixel in the frame (measured) |
| BK-435 | P0 | M |  | Colour pipeline contract (docs/COLOUR.md) and the verification plan for a Panasonic S5IIX RW2, end to end |
| BK-436 | P0 | M |  | Synthetic DNG chart writer: a known scene as a Bayer file, with analytic expected output (no phone, no camera) |
| BK-437 | P0 | S-M |  | Matrices and transfer functions derived from first principles, and the D50 versus D65 labelling fixed |
| BK-438 | P0 | M |  | White level and clipped highlight contract: a clipped neutral must render as 255/255 (audit AE-001), with fixtures that prove it |
| BK-439 | P1 | M |  | Neutral ramp and exposure exactness through the whole engine, with analytic expected values |
| BK-440 | P1 | M |  | Base tone curve audit: properties, hold-out validation and a guard against its low dynamic range origin |
| BK-441 | P1 | M |  | An independent reference pipeline in Python and the exposure-bracket test for highlight recovery |
| BK-442 | P1 | M |  | ICC embedding and Display P3 versus sRGB cross-check using Pillow's ImageCms |
| BK-443 | P1 | S-M |  | White balance neutrality: luminance drift, eyedropper round trip and the Kelvin display (audit AE-023) |
| BK-445 | P1 | M |  | Camera matrix provenance for the S5IIX and a per-camera override fitted from a chart |
| BK-459 | P1 | M | PARTLY DONE | Shader correctness bundle from the engine audit: dehaze sign, analysis ignoring lens gain, mask baseline, HSL partition, grading luma, clarity sign |

### M4: Editor and AI daily workflow

Medium to high value: the everyday feel of editing and trust in the AI tools.

Exit check: before/after split, clip overlay and fine slider control in daily use; AI tools show states, refine edges and have known phone timings; no second undo system.

Dependencies: BK-053 (GPU histogram) before BK-054; BK-296 before BK-075; BK-076 before BK-078 and BK-084 (shared refine step); BK-077 with BK-078 (layer sizes).

| ID | P | Size | Status | Title |
| --- | --- | --- | --- | --- |
| BK-051 | P0 | M | PARTLY DONE | Undo/redo that survives leaving a panel, with a visible stack and long-press scrub |
| BK-052 | P0 | M |  | Split before/after and side-by-side compare, not only "hold the photo" |
| BK-054 | P1 | S-M |  | Clipping warnings: highlight and shadow overlay (zebra) on the photo plus histogram corner triangles |
| BK-055 | P1 | M-L | PARTLY DONE | Tap-to-edit on the photo: touch a spot and drag up/down to change the most relevant slider (targeted adjustment) |
| BK-056 | P1 | M |  | Favourite sliders: pin the six sliders Jai uses most to a one-tap "Quick" panel |
| BK-057 | P1 | S-M |  | Slider fine control: long-press to enter fine mode, and accessible +/- steppers |
| BK-058 | P1 | S |  | Haptics for detents, reset, and mask handle snaps |
| BK-059 | P1 | M |  | Edit while zoomed: persistent loupe at 100 percent with a navigator mini map |
| BK-060 | P1 | M |  | Panel-aware canvas refit animation (FLIP) without resizing the GL surface per frame |
| BK-061 | P1 | M |  | Shared element transition from grid thumbnail to loupe to editor |
| BK-073 | P1 | M |  | Model download robustness: resume, Wi-Fi only default, storage check, background download with notification |
| BK-074 | P1 | S |  | Models screen in Settings: size on disk, delete, re-download, licence, last used |
| BK-075 | P1 | M |  | Warm-start models at idle: lazily load the SAM encoder after the user enters Masking, not when they choose the tool |
| BK-076 | P1 | M |  | Mask edge refinement: feather/refine with a guided filter on the full-res preview, and a "Refine edge" slider per AI mask |
| BK-077 | P1 | M |  | Raise the mask layer resolution for large exports (layer texture is a fixed 1024 x 1024) |
| BK-078 | P1 | M-L |  | Detail (sharpen, noise reduction) as local adjustments inside masks |
| BK-079 | P1 | M |  | Colour range mask in a perceptual space with a picker loupe, and a live mask preview overlay |
| BK-082 | P1 | S-M |  | Mask readiness UX: show "Preparing" states, never block the UI, and keep a one-line failure reason |
| BK-083 | P1 | M |  | Subject select quality: combine ML Kit Subject Segmentation with MobileSAM refine and let Jai add/subtract points |
| BK-084 | P1 | M |  | Brush: smoothing, pressure, flow build-up, auto mask (edge aware) quality, and brush size preview ring |
| BK-296 | P1 | S-M |  | One GPU delegate failure makes a model CPU-only forever |
| BK-298 | P1 | M |  | Select object: multiple points, negative points and a box, and use the model's other mask proposals |
| BK-200 | P1 | M |  | Human readable errors with a "Copy details" action |
| BK-340 | P1 | M |  | Editing the 40 picks: show edit progress, jump to the next pick that has no edit, and mark photos done |
| BK-460 | P1 | M | PARTLY DONE | Heal, clone, remove and AI masks must use the same lens geometry as the picture (audit AE-004, AE-005, AE-017, AE-026) |

### M5: Library and card import

High value for the real workflow (shoot, import, cull, find). Large items (BK-096, BK-106) can ship in two steps.

Exit check: Jai imports a card, culls with swipes, deletes rejects, and finds a photo by lens and date without leaving Rawline.

Dependencies: BK-263 and BK-098 before BK-099; BK-096 before BK-112 and BK-256; BK-100 needs the Room migration test (BK-305); BK-097 before the cull "delete rejects" step; BK-310 after BK-104.

| ID | P | Size | Status | Title |
| --- | --- | --- | --- | --- |
| BK-497 | P1 | M | DESIGNED | The grid reorders itself while indexing finishes, because the sort key changes from file time to EXIF capture time (found by reading DeviceScanner and Indexer) |
| BK-498 | P1 | S | DESIGNED | First run shows everything on the phone (`device:*`), so the S24 Ultra's thousands of JPEG and HEIC photos, screenshots and chat images bury the RAW files |
| BK-474 | P1 | S |  | Imported RAWs must keep the card file's modified time, or `Photo.keyOf` (`name/size/modified`) makes every imported copy a new photo and breaks sync with edits made on the original |
| BK-475 | P1 | M |  | A DNG that LibRaw opens but cannot decode shows no error: `unpack` returns -2 and the user sees a blank or grey frame |
| BK-476 | P1 | S |  | Samsung Expert RAW spike: find out what compression the S24 Ultra writes before promising DNG support |
| BK-096 | P0 | L | PARTLY DONE | Import from SD card or USB-C reader: copy RW2/JPG into a dated folder with a progress queue, skip duplicates, verify, then eject prompt |
| BK-097 | P0 | M |  | Reject-to-trash and delete with undo (MediaStore trash and a 30 day bin) |
| BK-098 | P0 | M |  | Search: text, camera, lens, ISO, aperture, focal length, date range, rating, label, edited, file type, with saved searches |
| BK-099 | P1 | L |  | Natural language search over the library (on-device, no cloud) |
| BK-100 | P1 | M |  | Collections (albums) and smart collections |
| BK-101 | P1 | M |  | Keywords, captions and copyright metadata in the catalogue, written to XMP when enabled |
| BK-102 | P1 | S-M |  | Date, lens, focal length and ISO filters, plus sort by capture time, ISO, file size |
| BK-103 | P1 | M | PARTLY DONE | Group by day with sticky date headers and a fast scrubber with date labels |
| BK-104 | P1 | M |  | Cull mode: full-screen one-at-a-time with pick/reject on swipe, rating on number tap, and auto-advance |
| BK-105 | P1 | M |  | Survey and compare: view 2-6 selected photos side by side with synced zoom |
| BK-106 | P1 | M-L |  | Burst and bracket stacks, auto-grouped by capture time and exposure |
| BK-107 | P1 | M |  | RAW+JPEG pairs: show as one tile, with a toggle to treat the JPEG as a sidecar or as its own photo |
| BK-108 | P1 | S-M |  | Recents and favourites: "Recently edited", "Recently imported", and quick access to Picks |
| BK-109 | P1 | M | BLOCKED | Library-side handling of identity changes: "edits relinked" notices and a manual relink screen |
| BK-277 | P1 | M |  | Drag-to-select across grid tiles (swipe over tiles to select a range) and select-all / invert / select-day |
| BK-310 | P1 | M |  | Cull assistant: blur, exposure and eyes-open flags to find the keepers quickly |
| BK-186 | P1 | M | PARTLY DONE | Storage screen: sizes of thumbnails, previews, masks, heals, models, backups, exports; clear and cap each |
| BK-191 | P1 | M |  | First-run onboarding in four screens: what it is, permissions explained, pick where photos live, three gestures |
| BK-192 | P1 | S-M | PARTLY DONE | Permission rationale and recovery states in the library (denied, partial, all-files off) |
| BK-338 | P1 | S-M |  | Culling 800 photos: "next undecided" jump, a visible progress count, and resume where I stopped |
| BK-339 | P1 | S |  | Rejected photos hidden from the grid by default, with a "Rejects (n)" chip to bring them back |
| BK-350 | P1 | M |  | First-time user friction audit: write the five-minute path and fix every dead end |
| BK-392 | P1 | S | DESIGNED | First-run onboarding copy deck: exact words for every screen (Australian English, no em dashes) |
| BK-393 | P1 | S | DESIGNED | In-app help copy for each screen (the text for the "?" sheets, BK-193) |
| BK-447 | P1 | S |  | First-run reviewer scorecard: reproduce what a Lightroom or Snapseed reviewer would do in the first ten minutes and score it each release |
| BK-450 | P1 | M |  | First launch with a big library: honest progress, a skeleton grid and a "RAW files hidden" hint instead of "Nothing here yet" |

### M6: Export, accessibility, polish and the stretch items

Medium value each, but they finish the product: export that matches Jai's needs, accessibility, updates, tests and the large optional features.

Exit check: export presets with preview, Ultra HDR/HEIC decision made, 48 dp targets and a TalkBack pass done, in-app update works, screenshot and emulator tests green.

Dependencies: BK-040 before BK-132; BK-128 before BK-315 and BK-130; BK-166 before BK-208 screenshot baselines; BK-226 before BK-240 and BK-238 registration.

| ID | P | Size | Status | Title |
| --- | --- | --- | --- | --- |
| BK-128 | P1 | M |  | Export presets: named bundles (Instagram 1350, Web 2048, Full JPEG, Print TIFF, Archive 16-bit) with one-tap export |
| BK-129 | P1 | M |  | More size modes: by percentage, by megapixels, by exact width/height with aspect lock, and "max file size in MB" with auto quality |
| BK-130 | P1 | M |  | Watermark and signature overlay (text and image), position, opacity, and size relative to the image |
| BK-131 | P1 | M |  | HEIC export (10-bit, Display P3) via HeifWriter, and AVIF via a bundled encoder if the licence allows |
| BK-132 | P1 | L |  | Ultra HDR JPEG export (JPEG_R with gain map) for HDR-edited photos |
| BK-133 | P1 | M | PARTLY DONE | Colour space embedding: confirm the ICC profile is written for P3 and add Adobe RGB / Rec 2020 and "preserve ProPhoto" TIFF |
| BK-134 | P1 | M |  | Metadata export: carry GPS (optional), orientation, lens make/serial, white balance, flash, metering, plus XMP and IPTC |
| BK-281 | P1 | M |  | Export preview of the result: estimated size, pixel dimensions and a 100 percent crop preview before exporting |
| BK-166 | P1 | M |  | Bring every interactive control to 48 dp minimum hit area without changing the visual size |
| BK-167 | P1 | M |  | TalkBack pass on a real phone: grid tiles, loupe, editor, masking, export |
| BK-168 | P1 | M |  | Custom accessibility actions on every direct manipulation (rating, flag, copy/paste, mask handles, curve points) |
| BK-169 | P1 | S |  | Colour contrast audit of UI_SPEC tokens (accent text on charcoal falls under 4.5:1) |
| BK-193 | P1 | M |  | Help centre inside the app: one-page cheat sheet per screen with a "?" button |
| BK-196 | P1 | M |  | Rebuild Settings in the Lr style as grouped sections with search |
| BK-208 | P1 | M |  | Screenshot tests (Paparazzi or Roborazzi) for library, loupe chrome, editor panels, export sheet, settings at 3 font scales and dark theme |
| BK-209 | P1 | M |  | Compose UI behaviour tests (Robolectric `createComposeRule`) for selection, filter, rating, copy/paste flows |
| BK-210 | P1 | M-L |  | Gradle Managed Devices emulator smoke test in CI: launch, grant permissions, import a sample, open loupe, open editor, move a slider, export |
| BK-240 | P1 | M-L | PARTLY DONE | In-app update check and install (sideload update flow with SHA-256 verification) |
| BK-245 | P1 | S-M |  | Licence compliance pass: SegFormer weights, ML Kit beta, lensfun CC BY-SA, LibRaw LGPL relink, and an in-app licences screen |
| BK-246 | P0 | M | DONE | Adopt Android 16 behaviours now: predictive back and edge-to-edge (targetSdk is 37) |
| BK-232 | P1 | S-M | PARTLY DONE | In-app privacy statement and a verified "no data leaves the phone" claim |
| BK-228 | P1 | S-M | PARTLY DONE | Choose the right foreground service type and declare it correctly for each background job |
| BK-080 | P1 | L |  | Depth range mask using on-device depth estimation (spec item not done) |
| BK-081 | P1 | L |  | Mask a face region: eyes, skin, teeth and hair via the existing People parts, with per-part sliders (skin smoothing, eye brighten) |
| BK-341 | P1 | M |  | Remember what has been exported: an "Exported" badge, a "Not exported yet" filter and a per-photo export history |
| BK-342 | P1 | M |  | Share five photos in one go from the library selection, with a share preset |
| BK-359 | P1 | S-M |  | Export destination lost or changed mid-queue: revalidate, fall back and tell Jai once |

### M7: Studio (separate product line, scheduled after M1 to M3; the spec's own milestones S1 to S10 set the inner order)

Studio is a new product inside the app, not a feature of Develop, so its effort is large and its inner order is the spec's S1 to S10 (docs/STUDIO_SPEC.md). Backlog effort figures for M7 count only the review, test and risk entries added here, not the spec's own roughly 58 days of build work, and the spec's S1 estimate of 4 to 6 hours is not credible (BK-398).

Exit check (first Studio release): open a 6000 x 4000 photo from Develop as a layer, paint on 10 layers at 60 fps or better, undo 100 steps, save, kill the app, recover, and export through the queue, with the Studio test plan (BK-382) green.

Dependencies: BK-365 first; BK-366 and BK-367 before everything; BK-368 and BK-369 before the brush ships; BK-385 before any shared shader code; BK-374 before the layers panel gestures; BK-372 and BK-373 together.

| ID | P | Size | Status | Title |
| --- | --- | --- | --- | --- |
| BK-502 | P0 | S | DECIDED | The Develop / Studio switch ships in the CI release APK now, before the S1b fixes: decide who sees Studio first |
| BK-503 | P1 | M | PARTLY DONE | Studio on a nearly full phone: the autosave retries every 5 seconds forever with a toast each time, and nothing checks free space (measured on the host) |
| BK-508 | P1 | S | DESIGNED | Studio rotation: `movableContentOf` probably still detaches the GL view, so a rotation rebuilds the compositor, and jobs posted in the gap wait out their timeout |
| BK-509 | P1 | M | DESIGNED | A Studio export that is running when the app goes to the background stalls for 60 seconds and then fails with a wrong message |
| BK-479 | P1 | M | DONE | Studio stroke commit works on one bounding box, so a thin diagonal line costs as much as a full-canvas fill (review of S1b) |
| BK-480 | P1 | S-M | DONE | Studio stroke readback allocates 16 bytes per pixel of the stroke box natively (192 MB at 12 MP) |
| BK-481 | P1 | S | PARTLY DONE | S Pen: a palm that lands first wins and the pen is ignored (InputRouter) |
| BK-488 | P1 | M | DECIDED | Studio blend space: decide gamma or linear per document before the S3 blend modes are written (numbers from a host calculation) |
| BK-489 | P1 | M |  | Studio blend modes: the W3C formulas are not what Photoshop does for several modes, and the golden is only as independent as its reference |
| BK-490 | P1 | M-L |  | Studio transform quality: bilinear resampling aliases when shrinking and destroys fine detail when a layer is moved or rotated several times |
| BK-491 | P1 | M |  | Studio group isolation and pass-through need an offscreen target per nesting level, which the 600 MB memory guard does not count |
| BK-492 | P1 | M |  | Studio adjustment layers must not apply Develop's baseline look twice, and must say how out of gamut values are held in an 8 bit layer |
| BK-365 | P0 | S | DONE | Reconcile every Studio requirement with the spec when docs/STUDIO_SPEC.md appears, and keep one traceability table |
| BK-366 | P0 | L | SPEC COVERS | Tiled, sparse layer storage with an LRU memory budget and disk spill (the core data structure) |
| BK-367 | P0 | M | SPEC COVERS | Memory budget model for Studio: canvas size x layers x bit depth x undo, with hard caps and honest warnings |
| BK-368 | P0 | L | SPEC COVERS | Undo and redo by copy-on-write tile diffs, with a memory cap that spills to disk |
| BK-369 | P1 | L | SPEC COVERS | Document format and crash safety: a versioned package with atomic saves, autosave journal and recovery |
| BK-370 | P1 | M-L | SPEC COVERS | Compositing correctness: premultiplied alpha, blend modes, and the linear versus gamma decision, with golden tests |
| BK-371 | P1 | L | SPEC COVERS | GPU compositor architecture that does not recompose the whole stack on every stroke |
| BK-372 | P1 | L | SPEC COVERS | Brush engine v1: GPU stamping, spacing, pressure and tilt, flow accumulation, eraser, and our own brush tips |
| BK-373 | P1 | M-L | SPEC WINS | Stroke input pipeline and smoothing: batched points, prediction, a stabiliser slider, and a low-latency path |
| BK-374 | P1 | M | SPEC COVERS | Studio gesture map with conflict rules, palm rejection and Android edge dead zones |
| BK-375 | P1 | M-L | SPEC COVERS | Layers panel: compact and detailed views, GPU thumbnails, drag reorder, blend and opacity sheet, groups, clipping, alpha lock |
| BK-376 | P1 | L | PARTLY DESIGNED | Selections: shapes, lasso, magic wand on tiles, AI select, feather and expand, selection to mask |
| BK-377 | P1 | L | SPEC WINS | Studio and Develop together: send an edited photo as a linked layer, update it from the edit, and flatten with the recipe |
| BK-378 | P1 | L | SPEC COVERS | Transform tool: move, scale, rotate, perspective and mesh warp with high quality resampling and non-destructive preview |
| BK-382 | P1 | M | SPEC COVERS | Studio test plan: what to test before the first line ships |
| BK-385 | P1 | M | SPEC COVERS | Do not stretch the Develop engine into a layer editor: define a separate Studio engine and GL context rules |
| BK-398 | P0 | S | PARTLY DONE | The Studio plan's own estimates are not credible: split S1 into three releasable slices and re-plan with buffers |
| BK-399 | P1 | M |  | Two modes alive at once: define what each mode releases when hidden (GL textures, native decodes, models) and prove the memory high-water mark |
| BK-400 | P1 | M |  | Stroke-end cost: lossless WebP encoding of dirty tiles may not fit "autosave on stroke end" |
| BK-401 | P1 | S |  | Studio dependency, licence and size checklist: LZ4, WebP codec, fonts, JNI additions, 16 KB alignment |
| BK-402 | P1 | M |  | The real Adreno code path is not covered by CI: self-test both framebuffer-fetch and ping-pong paths on the phone |
| BK-403 | P1 | S-M |  | CI matrix for `STUDIO_ENABLED`: prove Develop is unchanged with the flag off and with it on |
| BK-404 | P1 | M |  | Untrusted input in Studio: images, .cube LUTs, fonts, project zips, clipboard images |
| BK-410 | P1 | M |  | Project export-all and backup should land early (spec has it in S9); uninstall and a key change currently lose Studio projects |
| BK-411 | P1 | M |  | S1 test and risk entry (shell, model, layers, 3 blends, move and scale, brush and eraser, flatten export) |
| BK-412 | P1 | M |  | S2 test and risk entry (tiles, history, crash journal, gestures, shared shader includes, hand-off) |
| BK-413 | P1 | M |  | S3 test and risk entry (24 blend modes, groups, masks, clipping) |
| BK-414 | P1 | M |  | S4 test and risk entry (selections) |
| BK-415 | P1 | M |  | S5 test and risk entry (adjustments and colour tools) |
| BK-416 | P1 | M |  | S6 test and risk entry (AI tools) |
| BK-417 | P1 | M |  | S7 test and risk entry (brush engine complete) |
| BK-418 | P1 | M |  | S8 test and risk entry (transform, text, shapes, gradients) |
| BK-419 | P1 | M |  | S9 test and risk entry (filters, RAW smart objects, complete export) |
| BK-420 | P1 | M |  | S10 test and risk entry (polish, accessibility, performance pass) |

### Already done (kept in the backlog, marked in each entry)

- BK-144 Make restore safe: transactional, streaming, versioned, newer-wins per item: DONE
- BK-146 Room: export schemas, write all migrations explicitly, and test them with MigrationTestHelper: DONE
- BK-137 Handle the Android 15+ dataSync foreground service time limit (6 hours per 24 h) with onTimeout: DONE
- BK-151 Autosave the editor recipe on a debounce, not only on exit: DONE
- BK-086 Raise the 8-mask and 16-layer limits only if needed; show the limit and a clear message: DONE
- BK-063 Preset amount slider and partial apply (only tone, only colour, only grain): DONE
- BK-148 Capture native crashes and ANRs: use ApplicationExitInfo on next start and attach to the Copy report: DONE
- BK-150 Persist PerfLog samples across restarts and rotate files: DONE
- BK-145 DeviceScanner/Indexer must never prune on a partial listing (AUDIT open item 3), and add a downgrade/rollback path for Room: DONE
- BK-365 Reconcile every Studio requirement with the spec when docs/STUDIO_SPEC.md appears, and keep one traceability table: DONE
- BK-285 Ask for notification permission when the first export starts, not at launch together with the photo permission: DONE
- BK-007 Enable OpenMP (or a thread pool) inside LibRaw for the full-size decode: DONE
- BK-444 The "16 bit" TIFF must carry 16 bits: a ramp test and the linear readback contract (audit AE-018): DONE
- BK-461 Colour range and luminance range masks compare in a different domain from the picker (audit AE-015): DONE
- BK-467 floatToHalf rounds values near 65520 up to infinity (audit AE-028): DONE
- BK-142 Automatic backups of the catalogue (edits, meta, presets, snapshots) on every N edits and daily, kept as the last 7 rotating files: DONE
- BK-303 Restore preview ("dry run") and a backup integrity manifest: DONE
- BK-246 Adopt Android 16 behaviours now: predictive back and edge-to-edge (targetSdk is 37): DONE
- BK-426 API 37 readiness checklist (edge-to-edge, predictive back, resizability) with tests on the S24 Ultra and a large-screen emulator: DONE
- BK-120 Persist per-source scroll position, selection and sort across rotation and process death: DONE
- BK-501 45 of 73 commits carry a model name in a Co-Authored-By trailer, against the CLAUDE.md rule "Do not name any model in commits, code or docs": DONE
- BK-479 Studio stroke commit works on one bounding box, so a thin diagonal line costs as much as a full-canvas fill (review of S1b): DONE
- BK-480 Studio stroke readback allocates 16 bytes per pixel of the stroke box natively (192 MB at 12 MP): DONE
- BK-483 Studio GL lifecycle: jobs on a dead context, init failure hangs waiters, destroy blocks the main thread: DONE
- BK-352 Undo for the last rating, flag, label or batch change (snackbar): DONE

Parked with a reason: BK-141 (declined in DECISIONS.md, see BK-291), BK-109 (blocked on it). P2 and P3 entries are not scheduled; pull them in when they unblock a P0/P1 item or when a milestone finishes early.

## Studio spec traceability (docs/STUDIO_SPEC.md, commit 6d62008)

The spec wins wherever it differs from an earlier backlog entry; those entries are marked SPEC WINS or SPEC COVERS. S1 to S10 are the spec's milestones.

| Spec section | Backlog entries |
| --- | --- |
| 0 Modes, navigation, flag `STUDIO_ENABLED` | BK-399, BK-403, BK-409, BK-246 |
| 1 Goals and non-goals (no PSD, no generative AI) | BK-384 |
| 2.1 Layer stack | BK-375, BK-411, BK-413 |
| 2.2 Blend modes and compositing | BK-370, BK-413 |
| 2.3 Selections | BK-376, BK-414 |
| 2.4 Brush engine | BK-372, BK-373, BK-383, BK-417 |
| 2.5 Transform | BK-378, BK-418 |
| 2.6 Content-aware fill and remove | BK-416 |
| 2.7 Text | BK-380, BK-418, BK-401 |
| 2.8 Shapes and gradients | BK-418 |
| 2.9 Filters | BK-379, BK-419 |
| 2.10 Adjustments, LUT import | BK-415, BK-404 |
| 2.11 Colour tools | BK-415 |
| 2.12 AI tools (blocked list) | BK-416, BK-404 |
| 2.13 Undo, redo, snapshots | BK-368, BK-406 |
| 2.14 Gestures | BK-374, BK-412 |
| 2.15 Project format and crash safety | BK-369, BK-400, BK-405, BK-410 |
| 2.16 Import (images, RAW smart objects) | BK-404, BK-407 |
| 2.17 Performance budget | BK-366, BK-367, BK-371, BK-381, BK-399 |
| 2.18 Export | BK-419, BK-386 |
| 2.19 Accessibility | BK-388, BK-420 |
| 3.1 Modules | BK-385, BK-401 |
| 3.3 GPU compositor | BK-371, BK-402 |
| 3.5 Navigation, back handling, process death | BK-399, BK-409, BK-246 |
| 3.6 Storage | BK-386, BK-410 |
| 3.7 Develop hand-off | BK-377, BK-407 |
| 3.9 Testing strategy | BK-382, BK-411 to BK-420 |
| 6 Risks R1 to R18 | R1 BK-366 and 367; R2 BK-370; R3 BK-384; R4 BK-371; R5 BK-385 and 399; R6 BK-368; R7 BK-373; R8 BK-416; R9 BK-380; R10 BK-410; R11 BK-367; R12 BK-407; R13 BK-376; R14 BK-370 and 415; R15 BK-416; R16 BK-385 and 403; R17 BK-374; R18 BK-381 |
| 7 Milestones S1 to S10 | BK-398 (schedule), S1 BK-411, S2 BK-412, S3 BK-413, S4 BK-414, S5 BK-415, S6 BK-416, S7 BK-417, S8 BK-418, S9 BK-419, S10 BK-420 |

What the spec does not cover (new entries): BK-398 schedule realism, BK-399 two modes in memory, BK-400 tile encode cost, BK-401 dependencies and size, BK-402 Adreno path self-test, BK-403 flag CI matrix, BK-404 untrusted input, BK-405 blob integrity, BK-406 history jump and disk full, BK-407 stale smart object recipe, BK-408 Studio onboarding copy, BK-409 crash-loop safety, BK-410 early project export, BK-387 thermal rules.


## Cross-reference to the other audit documents (audit-engine.md AE, audit-quality.md AQ, audit-ui.md AU)

Rule: an audit id is authoritative for the finding text and its evidence; a BK id is authoritative for scheduling and acceptance. Commits that cite an AU, AE or AQ id are reflected in the Status lines of the BK entries. "audit only" means the finding is small and stays in its own document.

| Audit ids | Backlog entry or state |
| --- | --- |
| AE-001 | BK-438 (white level), BK-023, BK-440 |
| AE-002, 003, 012, 013, 014, 016, 029, 030 | BK-459 (AE-016 manual vignette centre: add to that task) |
| AE-004, 005, 017, 026 | BK-460 |
| AE-006, 011, 034 | BK-363, BK-156 |
| AE-007, 008, 009 | BK-324, BK-295 |
| AE-010 | BK-009, BK-053 |
| AE-015 | BK-461 |
| AE-018 | BK-444, BK-133, BK-442 |
| AE-019 | BK-462 |
| AE-020 | BK-463 |
| AE-021 | BK-007 |
| AE-022 | BK-077 |
| AE-023 | BK-443 |
| AE-024 | BK-464 |
| AE-025 | BK-465 |
| AE-027 | BK-466 |
| AE-028 | BK-467 |
| AE-039, 040, 041 | BK-468, BK-430 |
| AE-044, 048, 054, 055 | BK-469 |
| AE-031 to 033, 035 to 038, 042, 043, 045 to 047, 049 to 053 | audit only |
| AQ-001, 015, 016 | BK-216, BK-217 (partly done, 3cd7bb9) |
| AQ-002 | BK-226 |
| AQ-003 | BK-147, BK-305 |
| AQ-004, 005, 007, 008, 009 | DONE (2ef67a7, 7e0272f, 3cd7bb9, dd68762) |
| AQ-006 | BK-141 (declined), BK-291 |
| AQ-010 | BK-004 |
| AQ-011, 012 | BK-206, BK-207 |
| AQ-013 | DONE (3cd7bb9 concurrency and publish guard) |
| AQ-014 | BK-241, BK-242 |
| AQ-017, 018 | BK-072, BK-073 |
| AQ-019 | BK-133, BK-442 |
| AQ-020 to 023 | DONE (15fb461) |
| AQ-024, 025, 026 | DONE or partly (GL feature and onTimeout done), BK-115 |
| AQ-032, 033 | BK-153, BK-152 |
| AQ-035, 036, 037, 038 | BK-285, BK-232, BK-234, BK-451 |
| AU-001, 002, 003, 056, 057, 058, 062, 063, 064, 069 | DONE (5ca42a0) |
| AU-004 | BK-285 (media half done) |
| AU-005 | BK-120 |
| AU-006, 014, 015 | BK-158, BK-292 |
| AU-022, 023, 060 | BK-450 |
| AU-024, 025 | BK-457, BK-111 |
| AU-026, 027 | BK-203 (partly done) |
| AU-036 | BK-452 |
| AU-038, 050, 019 | BK-167, BK-168 |
| AU-039, 046 | BK-342 |
| AU-040, 041, 044, 045 | BK-159, BK-359 |
| AU-048, 049, 054 | BK-166, BK-170, BK-169 |
| AU-055 | BK-449 |
| AU-061 | BK-261 |
| AU-007 to 013, 016 to 018, 020, 021, 028 to 035, 037, 042, 043, 047, 051 to 053, 059, 065 to 068 | audit only (UI polish; schedule from audit-ui.md) |
## Contents

- Area A: SPEED (open, swipe, grid, edit start, slider latency) (39 entries)
- Area B: EDITING QUALITY AND COLOUR SCIENCE (61 entries)
- Area C: EDITOR UX AND WORKFLOW (39 entries)
- Area D: MASKING AND AI (41 entries)
- Area E: LIBRARY WORKFLOW (ratings, flags, collections, search, filters, import, folders) (52 entries)
- Area F: EXPORT (formats, sizes, watermark, metadata) (24 entries)
- Area G: RELIABILITY, DATA AND MIGRATION (41 entries)
- Area H: ACCESSIBILITY (10 entries)
- Area I: BATTERY, THERMAL AND STORAGE (20 entries)
- Area J: ONBOARDING, IN-APP HELP AND SETTINGS (30 entries)
- Area K: TESTING AND CI (47 entries)
- Area L: SECURITY, PRIVACY, RELEASE AND UPDATE FLOW (22 entries)
- Area M: PLATFORM, PANASONIC AND COMPOSE SPECIFICS (26 entries)
- Area N: STUDIO (layer-based pixel editor, separate from Develop) (60 entries)


---

# AREA A: SPEED (open, swipe, grid, edit start, slider latency)

Ground truth: nothing in docs/PERF.md is measured on the phone yet. Every entry here that claims a gain must end with a Copy report number from the S24 Ultra. Entries are sized S (under a day), M (a few days), L (a week or more).

### BK-001 [P0] Get the first real phone timings and gate CI on a budget file
- Problem: docs/PERF.md has "not measured" in every row. All speed work is guesswork until Jai pastes a Copy report. PerfLog keeps only in-memory samples (500 per name) and loses them on process death.
- Why it matters to Jai: the two stated goals are speed first. Without numbers nobody knows if the 150 ms open target is met or by how much.
- Acceptance: (1) Settings has a "Run speed test" button that walks a fixed script (open 20 photos cold, swipe 20 warm, scroll grid 10 s, press Edit on 5, drag a slider 5 s, export 1 JPEG) and ends with one pasteable block; (2) results are saved to a file so they survive a restart; (3) docs/PERF.md gains a column "S24U result, build number"; (4) a `perf-budget.json` in the repo lists the targets and the Copy report parser prints PASS/FAIL per row.
- Size: M. Files: core/cache/PerfLog.kt, feature/settings/SettingsScreen.kt, app/MainActivity.kt, docs/PERF.md, new tools/perf/check-report.py. Risk: low (additive).
- Src: Android macrobenchmark guidance (S5, checked 6 Oct 2026).

### BK-002 [P0] Macrobenchmark module for cold start, grid scroll and open-from-grid
- Problem: only a hand-rolled Choreographer counter (FrameMonitor) measures jank, and it only counts frames over 25 ms. No startup number exists at all (no `reportFullyDrawn`, no benchmark module).
- Why: "Open under 150 ms" and "grid 120 fps" cannot be defended from a counter alone; frame percentiles (P50/P90/P99) and startup need FrameTimingMetric.
- Acceptance: a `benchmark` module (macrobenchmark, self-instrumenting on the phone, not in CI) with StartupBenchmark, GridScrollBenchmark, OpenPhotoBenchmark; results table added to PERF.md; `reportFullyDrawn()` called when the first grid thumbnails are visible.
- Size: M. Files: new benchmark/ module, app/build.gradle.kts, settings.gradle.kts, MainActivity.kt. Risk: low; needs Jai or a connected device to run, so also expose the same script inside the app (BK-001).
- Src: Android Developers, Baseline Profiles and Macrobenchmark (S5, 6 Oct 2026).

### BK-003 [P0] Ship a Baseline Profile (and startup profile) for the app and the Compose paths used on first open
- Problem: release build has `isMinifyEnabled = false`, no profile, so on every install and every update the first launch runs Compose and the grid code interpreted/JIT. The sideload flow reinstalls often, so cold paths are hit often.
- Why: Google quotes about 30 percent faster code execution from first launch with a profile; this directly attacks "open under 150 ms" right after an update.
- Acceptance: baseline-prof.txt generated by a Macrobenchmark `BaselineProfileGenerator` covering launch, grid scroll, open loupe, swipe, open editor, move a slider; AGP `baselineProfile` plugin wired; Copy report shows `cold_start_ms` before/after in PERF.md.
- Size: M. Files: app/build.gradle.kts, new baselineprofile module, .github/workflows/build.yml (generate on a hosted emulator or commit the profile). Risk: medium (profile must be regenerated when code moves; an emulator in CI is slow, so commit the file and regenerate per milestone).
- Src: S5.

### BK-004 [P0] [PARTLY DONE] Turn on R8 shrinking and resource shrinking for release, keep rules for JNI and reflection
- Status: Commit 3cd7bb9 and the fifth audit pass: proguard-rules.pro written; `-PminifyRelease=true` builds and the APK drops from 61 MB to 37 MB (measured by the main session); minification stays off by default on purpose (docs/DECISIONS.md) until a phone run proves it. Left over: that phone run, then turn it on.
- Problem: `isMinifyEnabled = false` in app/build.gradle.kts. Larger dex, slower class load, larger APK (the APK is committed in dist/ and downloaded over the phone).
- Why: smaller APK downloads faster on Jai's phone; R8 also improves startup. Risk is a missing keep rule crashing JNI callbacks.
- Acceptance: release builds with R8; keep rules for every JNI-called Kotlin method and Room/LiteRT classes; a CI step installs nothing but runs `./gradlew assembleRelease` and a smoke unit test that loads Native; APK size before/after recorded in PERF.md.
- Size: M. Files: app/build.gradle.kts, new app/proguard-rules.pro, core/native/proguard. Risk: medium-high until verified on phone (JNI name stripping). Do behind a build flag first.

### BK-005 [P1] Reduce Edit-to-first-frame: decode half-size in the background as soon as a photo has been on screen 300 ms, not 1.5 s
- Problem: SPEC says edit base starts "on Edit press or 1.5 s dwell". Half-size LibRaw decode on the x86 sandbox is about 0.9 s single thread (docs/PERF.md); the budget for Edit to first preview is 1.2 s. A dwell of 1.5 s means most Edits start cold.
- Why: Jai opens a photo to look, then taps Edit within a second in most workflows; the decode should already be warm.
- Acceptance: RawPrefetch is started at 300 ms dwell for the shown photo and for the next one in swipe direction (capacity stays 3, about 100 MB each so memory guard in BK-013 applies); Copy report shows `edit_first_frame_ms` p50 under 600 ms when prefetched and under 1200 ms cold.
- Size: S. Files: core/render/RawPrefetch.kt, feature/loupe/LoupeScreen.kt, app/EditorHost.kt. Risk: memory pressure and battery (BK-176 throttles prefetch when warm).

### BK-006 [P1] Replace the LibRaw half-size decode with a GPU "bin and upload" from packed 16-bit raw
- Problem: the CPU does unpack, white balance, 2x2 bin, matrix to ProPhoto and half-float conversion before any GPU work (raw_decode.cpp, dcraw_process). On the phone this is the longest step of Edit start.
- Opportunity: unpack Bayer data only (LibRaw `unpack()` without `dcraw_process`), upload as an R16UI texture, and do black level, WB, 2x2 bin (or demosaic, see BK-024), camera matrix to ProPhoto in a shader. The CPU then only does decompression.
- Why: removes about half the CPU time of the edit-start path and moves the heavy work to the Adreno GPU, which sits idle during decode.
- Acceptance: golden test shows the GPU path within 0.5 percent of the CPU path on the three sample RW2 files; `edit_decode_ms` drops by at least 40 percent on the phone; no change to exports (full decode path unchanged until BK-024).
- Size: L. Files: core/native/cpp/raw_decode.cpp, engine.cpp, new shader raw_pack.frag, tools/golden. Risk: high (colour parity with LibRaw output, highlight handling `highlight=2` moves into the shader).

### BK-007 [P1] [DONE] Enable OpenMP (or a thread pool) inside LibRaw for the full-size decode
- Status: Done in commit 3ad453d (LibRaw built with OpenMP, -O3, static libomp, KMP_BLOCKTIME 0; host: 24 MP full decode 3.0 s to 1.6 to 1.9 s, pixel data identical). Left over: a phone timing from the Copy report.
- Problem: the full decode used for export and 100 percent zoom runs AHD (`user_qual = 3`) single threaded unless LibRaw was built with OpenMP; CMake config for the FetchContent build is not documented as enabling it. Only the half-float conversion is threaded (4 threads).
- Why: export target is under 3 s for a 24 MP JPEG; AHD single thread on a phone core is the likely blocker. Zoom to 100 percent also waits on it.
- Acceptance: CMake confirms `-fopenmp` for libraw.so and links libomp; `full_decode_ms` recorded before and after; no new crash on a 30 minute soak (BK-181). Fall back to single thread when `thermalStatus >= MODERATE`.
- Size: S-M. Files: core/native/CMakeLists.txt. Risk: medium (NDK libomp packaging, 16 KB alignment check BK-229).
- Src: LibRaw 0.22 notes (S11, 6 Oct 2026) for current build flags.

### BK-008 [P1] Persist the half-size decode (and the 2048 px preview) in a disk cache keyed by photo key
- Problem: every Edit press re-decodes the raw, even for a photo edited five minutes ago. RawPrefetch holds only 3 in memory.
- Opportunity: write the half-float half-size image (about 3000x2000x8 bytes = 48 MB) or a compressed tile-friendly version (lossless 16-bit WebP/PNG-like via own encoder, about 12 MB) to cache with LRU of about 1 GB.
- Why: re-editing and swiping between recently edited photos becomes near instant, which is how Lightroom feels on a recent catalogue.
- Acceptance: second Edit of the same photo in a session has `edit_first_frame_ms` under 300 ms; cache has a size cap and is wiped by "Clear cache"; cache entries are invalidated when file size or modified time change.
- Size: M. Files: core/cache (new RawCache), core/render/EditorSession.kt, Settings. Risk: storage use (BK-186) and I/O cost versus decode cost must be measured first; if flash read of 48 MB is slower than decode, drop it.

### BK-009 [P1] Slider latency budget: coalesce recipe updates to vsync and render only the visible region at screen resolution
- Problem: `EditorSession.setRecipe` queues `rebuild(); requestRender()` per call and sets `wantHistogram = true` each time, so histogram readback (glReadPixels style) can piggyback on every slider tick (EditorSession.kt line 144 and 509-531).
- Why: slider to preview under 16 ms is a stated target; a per-tick GPU readback stalls the pipeline.
- Acceptance: (1) one pending render at most (drop intermediate recipes); (2) histogram computed at most 4 times a second and from a 256x170 downsample on the GPU (BK-053); (3) `frame_render_ms` p95 under 12 ms on the S24U at preview size; (4) slider drag records `slider_to_frame_ms` end to end using `MotionEvent.eventTime` to `eglSwapBuffers` timestamp.
- Size: M. Files: core/render/EditorSession.kt, jni_engine.cpp, feature/editor/Controls. Risk: medium.

### BK-010 [P1] Use a lower-resolution render during slider drag and refine on release
- Problem: the preview renders at whatever the surface size is. At 1440 px wide on the S24U with 5 analysis blurs and up to 8 masks each running the full `adjust()` per pixel (main.frag, a 5x5 bilateral plus 25-sample chroma average in out.frag), heavy edits can exceed 16 ms.
- Opportunity: render at 0.5 scale while a drag is active, then a full-resolution frame 100 ms after the last change (the `uLod` and `uPxScale` uniforms already exist).
- Acceptance: with 4 masks and NR + sharpen on, drag frame time stays under 8 ms (half-res) and the settle frame under 40 ms; no visible pop (cross fade not needed, user is dragging).
- Size: M. Files: engine.cpp, EditorSession.kt. Risk: low-medium (texture-size dependent radii must scale: `uPxScale` already handles it).

### BK-011 [P1] Skip unchanged passes: cache the analysis blurs and the adjusted image per parameter group
- Problem: the pipeline is raw -> blurs -> main -> out. When only vignette, grain or sharpening change, passes 1 and 2 need not rerun; when only a mask changes, the global block need not rerun.
- Why: cuts GPU time per slider tick roughly in half for detail and effects sliders, which are the heaviest per pixel.
- Acceptance: engine keeps a dirty mask per stage; detail/effects changes only run `out.frag`; golden tests prove byte-identical output versus always rerunning; `frame_render_ms` p50 drops for those panels.
- Size: M. Files: engine.cpp, engine.h, jni_engine.cpp. Risk: medium (cache invalidation bugs; the golden harness covers it).

### BK-012 [P1] Grid: precompute and persist 360 px thumbnails sized to the column count, in a durable store (not cacheDir)
- Problem: ThumbStore writes `$id.jpg` (320 px) into `cacheDir`, which Android may clear under storage pressure, and keys by Room row id (changes when a file is re-indexed because the row is deleted and reinserted on a modified-time change). At 5 columns on a 1440 px display tiles are about 288 px wide so 320 is adequate, but at 3 columns tiles are 480 px and thumbnails look soft.
- Why: after the system clears the cache, the whole grid regenerates from the RAW preview parse, which is slow for thousands of photos; stale ids leave orphans.
- Acceptance: thumbnails stored under `filesDir/thumbs` by photo key (BK-141 stable id), two sizes (320 for dense, 640 for 2-3 columns generated lazily), orphan sweep after scan, "Rebuild thumbnails" in Settings.
- Size: M. Files: core/cache/ThumbStore.kt, core/data/Indexer.kt, LibraryScreen.kt. Risk: low.

### BK-013 [P1] [PARTLY DONE] Memory governor: one place that decides how many 100 MB decodes, previews and masks may live
- Status: Commit 15fb461: RawlineApplication.onTrimMemory and onLowMemory record events. Left over: actually shrinking caches and RawPrefetch, native byte counters.
- Problem: PreviewCache takes 1/4 of heap, ThumbStore 1/6, RawPrefetch up to 3 x about 100 MB native (outside the Java heap), mask layers, heal overlay (up to 3072 px half float = about 75 MB), AI models on GPU. `largeHeap="true"` hides the limit. No `onTrimMemory`/`ComponentCallbacks2` handler exists.
- Why: OOM kills on a 12 GB phone are rare but background kills when the user switches to the camera and back are not; Jai loses nothing because edits save, but the next open is cold.
- Acceptance: a `MemoryGovernor` registers `onTrimMemory`, frees RawPrefetch and shrinks caches at TRIM_MEMORY_RUNNING_LOW, logs `trim_level` into the Copy report, and exposes native bytes in use; a unit test with a fake budget proves eviction order.
- Size: M. Files: new core/cache/MemoryGovernor.kt, RawlineApplication.kt, RawPrefetch.kt, PreviewCache.kt. Risk: low.

### BK-261 [P1] Catalog.reapply loads the whole photos table and runs one SELECT per photo on every device scan
- Problem: `Catalog.reapply` calls `photos.all()` (every row of every source) then filters in memory and runs `edits.get(k)` once per photo to see whether an edit exists. `DeviceScanner.scanDevice` calls it after each scan, and the scan is triggered 1.5 s after any MediaStore change (a new photo taken, an export written). With 20 000 device photos that is a 20 000 row load plus up to 20 000 point queries on the IO thread for each trigger.
- Why: this will cause stalls and battery drain right after exporting (an export itself triggers a MediaStore change) and when Jai shoots with the card in an adapter.
- Acceptance: one SQL statement `UPDATE photos SET edited = EXISTS(SELECT 1 FROM edits WHERE key = ...)` or a join query returning only mismatched rows; no `photos.all()` in hot paths; `reapply_ms` recorded; unit test with 50 000 rows finishing under 300 ms in Robolectric.
- Size: S-M. Files: core/data/Catalog.kt, Db.kt (key as a column, see BK-141). Risk: low.

### BK-264 [P1] Make Application.onCreate cheap: lazy Graph members and no I/O on the main thread before first frame
- Problem: `RawlineApplication.onCreate` builds the whole `Graph` eagerly (Room builder, ThumbStore, PreviewCache, MaskStore, PatchStore, Catalog, Indexer, DeviceScanner, RawPrefetch, ModelStore with disk checks, `prefs`), and `ModelStore` computes `isReady` for 5 packs with `File.exists/length` in its constructor.
- Why: this is on the cold-start critical path of every launch; each extra millisecond delays the first grid frame.
- Acceptance: members `by lazy`; ModelStore readiness computed on first use; startup trace (BK-019) shows `Application.onCreate` under 20 ms; `reportFullyDrawn` after first thumbnails.
- Size: S. Files: app/RawlineApplication.kt, core/ml/ModelStore.kt. Risk: low.

### BK-267 [P1] Loupe 100 percent zoom shows real pixels (spec tier 3), not a stretched 2048 px preview
- Problem: LoupePage scales the cached 2048 px bitmap with `graphicsLayer` up to 8x, so zooming in the viewer is blurry; the spec's tier 3 (full-size embedded JPEG tiles or a half-size raw decode) is only used inside the editor.
- Why: checking focus and sharpness at 100 percent is the main reason a photographer zooms; Jai culls on the phone.
- Acceptance: when scale exceeds 1.5, decode the embedded full-size JPEG region with `BitmapRegionDecoder` (the offset and length are stored per photo) for the visible rectangle and swap it in without a flash; if the embedded JPEG is smaller than the sensor, fall back to the half-size raw decode (BK-005 prefetch) and label "Preview quality"; double tap goes to 100 percent (not 3x) with the zoom percentage shown; `zoom_tile_ms` p95 under 80 ms.
- Size: M. Files: feature/loupe/LoupeScreen.kt, core/cache/PreviewDecoder.kt. Risk: medium.

### BK-293 [P1] Start the raw decode in parallel with the recipe, snapshot and mask set-up (Edit start is serialised behind database reads)
- Problem: `EditorHost` runs `loadRecipe`, builds `EditorState`, loads snapshots, builds `MaskingFeature` and `AiMasksImpl`, and only then calls `session.load(photo)`, which starts the half-size decode. Each Room read is small, but they stack up on the critical path of Edit-first-frame, and the preview placeholder load (`previews.load`) also runs first when not cached.
- Acceptance: `session.load(photo)` (or a `RawPrefetch.prefetch`) is launched in the same coroutine before the first database read; the recipe is applied when it arrives (AI denoise flag handled: decode first, denoise only if the saved recipe has it); `edit_first_frame_ms` drops by the measured amount (record before/after on the phone).
- Size: S. Files: app/EditorHost.kt, core/render/EditorSession.kt. Risk: low.

### BK-389 [P1] Keep pan and zoom fluid in the Develop editor: transform the last frame during the gesture, re-render when it settles
- Finding: during pinch and pan the editor calls `session.setView(zoom, cx, cy)` on every event and each call asks for a full render of the whole shader graph (global adjust plus up to 8 masks, detail pass) at screen resolution. With masks, NR and sharpening on, this can exceed a frame. Fast mobile editors treat the viewport as a cheap transform of an already rendered result and refine afterwards: Procreate's engine is described as keeping 120 fps on supported iPads, and Photoshop's touch shortcuts and gestures never wait for the filter stack.
- Acceptance: while two fingers are down or the view animates, draw the last rendered frame (and a coarse tile pyramid when zoomed in) with a matrix transform, no pipeline render; 100 ms after the last movement render the true view (BK-010 style) and cross-fade only if the difference is visible; `pan_zoom_frame_ms` p95 under 8 ms with 4 masks and NR on; the existing hold-for-original and tool gestures are unaffected.
- Size: M. Files: feature/editor/EditorScreen.kt, core/render/EditorSession.kt, engine.cpp. Risk: medium.
- Src: Procreate 5 and Valkyrie coverage (80.lv, checked 6 Oct 2026).

### BK-014 [P2] Swipe: decode the next preview at the swipe velocity, not after the pager settles
- Problem: prefetch is next 3 / previous 2 around the current index, which wastes work when Jai swipes quickly in one direction and starves the far side.
- Opportunity: weight prefetch by recent direction; cancel the opposite side after 3 same-direction swipes; add a "velocity" mode that drops to 1024 px previews during a fling and upgrades at rest.
- Acceptance: cold swipe (not prefetched) p95 under 250 ms (current target) and `swipe_prefetched_miss` count appears in the report; fling through 50 photos shows no more than 2 blank frames.
- Size: S-M. Files: core/cache/PreviewCache.kt, LoupeScreen.kt. Risk: low.

### BK-015 [P2] Parse and cache the embedded JPEG offset/length at index time for every file (already stored) and read via a pooled FileChannel
- Problem: each preview open does a `ContentResolver.openFileDescriptor` + pread. Binder round trips to the media provider cost milliseconds each and stack up during a fast swipe.
- Opportunity: for device photos where the real path is accessible (all files access is already granted), use `File`/`FileChannel` directly with a small pool; keep SAF only for tree URIs.
- Acceptance: `tier2_read_ms` p50 under 3 ms; no change in correctness when the file disappears.
- Size: S. Files: core/cache/PreviewDecoder.kt. Risk: low-medium (path resolution from `content://media` rows; use `MediaStore.MediaColumns.DATA` or `/proc/self/fd` as a fallback).

### BK-016 [P2] Index 1000 files in under 60 s: use batched reads, bigger thumbnail concurrency on big cores, skip re-decode of unchanged files
- Problem: Indexer gates at 4 parallel and writes one DB row per file (`markIndexed` per file). It decodes a 320 px thumbnail via the full preview JPEG decode path (software decode).
- Opportunity: decode the embedded JPEG with `inSampleSize` (JPEG DCT scaling gives 1/4 size nearly free), use a transaction per 50 files, and use `ImageDecoder` hardware path where allowed. Skip when a thumb already exists for this key.
- Acceptance: `index_per_file_ms` p50 under 40 ms on the phone (1000 files under 40 s); Room writes batched.
- Size: M. Files: core/data/Indexer.kt, core/cache/PreviewDecoder.kt, Db.kt (batched update). Risk: low.

### BK-017 [P2] Avoid the 250 ms re-emission delay and full-list remapping in LibraryViewModel
- Problem: `allPhotos` maps every row to a Photo model on each Room emission and `transform { emit(it); delay(250) }`; with 20 000 device photos that is a lot of allocation per update, and `LibraryFilter.apply` sorts the whole list again on every filter or rating change.
- Opportunity: Room paging (Paging 3 or a custom keyset window) or at least query sorting and filtering in SQL with indexes on takenAt, rating, flag.
- Acceptance: with a 50 000 row synthetic DB the filter change shows results in under 100 ms and memory stays under 30 MB for the list; unit test with a generator.
- Size: M-L. Files: LibraryViewModel.kt, Db.kt, Library.kt, LibraryScreen.kt. Risk: medium (selection by id across pages).

### BK-018 [P2] Keep the GL context alive between Edit sessions (warm engine)
- Problem: each EditorSession creates an engine, compiles shaders and builds textures (Exporter does it again per export: `engineCreate` + `engineInit` every job).
- Opportunity: keep one headless EGL context and compiled programs in a process-wide engine pool; serialise access.
- Acceptance: `engine_init_ms` measured and, if over 50 ms, the second open shows zero compile time; shader binary cache (`glProgramBinary`) saved to disk for next launch.
- Size: M. Files: core/render/EditorSession.kt, Exporter.kt, OffscreenGl, engine.cpp. Risk: medium (context loss, thread affinity rule in CLAUDE.md).

### BK-019 [P2] Report `Trace` sections for each tier so perfetto traces are possible
- Problem: PerfLog gives medians only. For jank root causes (GC, binder, shader compile) a system trace is the tool.
- Opportunity: `android.os.Trace.beginSection` around tier decode, upload, render, histogram and export tiles; document the Perfetto recipe in docs/PERF.md; Settings button "Record 10 s trace" via `ProfilingManager` (Android 15+).
- Acceptance: a trace opened in Perfetto shows named slices for all of the above.
- Size: S. Files: PerfLog.kt, EditorSession.kt, PreviewDecoder.kt, docs/PERF.md. Risk: low.

### BK-262 [P2] Incremental device scans using MediaStore generation numbers instead of re-reading the whole table
- Opportunity: `MediaStore.getGeneration` and `MediaColumns.GENERATION_MODIFIED` (API 30+) return only rows added or modified since the last stored generation, so each ContentObserver event costs a tiny query rather than a full listing of the Files table.
- Acceptance: store the last generation per volume; scan reads `GENERATION_MODIFIED > last`; a full reconciliation runs at app start once a day; `device_scan_ms` for an incremental scan under 50 ms on a 20 000 photo phone.
- Size: M. Files: DeviceScanner.kt, LibraryViewModel.kt. Risk: low-medium (deleted rows need the periodic full scan).

### BK-263 [P2] Database indexes for the sorts and filters the library actually uses
- Problem: `photos` has indexes on `folderUri` and unique `uri` only, but every library query is `ORDER BY modified DESC, id DESC` over a folder or LIKE prefix; `LIKE 'device:%'` can use an index only with the right collation; rating/flag/takenAt filters are done in Kotlin after loading everything.
- Acceptance: composite indexes (`folderUri, modified DESC`), (`takenAt`), (`rating`), (`camera`), plus `COLLATE NOCASE`/`BINARY` choice for the LIKE; `EXPLAIN QUERY PLAN` shown in a unit test for the three hot queries; no table scans.
- Size: S. Files: Db.kt (+migration BK-146). Risk: low.

### BK-265 [P2] Use the Android 12+ SplashScreen API so cold start shows the app, not a white or black window
- Acceptance: `androidx.core:core-splashscreen` with a black canvas icon and `setKeepOnScreenCondition` until the first grid page is ready (capped at 500 ms); no flash of the wrong theme.
- Size: S. Files: app themes (res/values), MainActivity.kt. Risk: low.

### BK-266 [P2] [PARTLY DONE] Faster texture uploads: persistent PBOs or `glTexStorage2D` + `glTexSubImage2D` and avoid a second copy of the half-float image
- Status: Commits 3ad30b3 and 3ad453d: 8 MB strip upload, swap only on success, LibRaw block converted in place (24 MP peak RSS 420 to 242 MB on the host). Left over: persistent PBOs.
- Problem: `raw_decode.cpp` builds a 3000 x 2000 x 8 byte half-float array (48 MB) on the CPU and `engineSetSource` uploads it; `edit_upload_ms` is not yet measured on the phone.
- Opportunity: upload in strips from the decode threads into a PBO (GLES 3.0 pixel unpack buffers) so decode and upload overlap, and release the CPU copy immediately.
- Acceptance: `edit_upload_ms` drops by at least 30 percent on the phone relative to the baseline captured by BK-001; no extra peak RAM.
- Size: M. Files: engine.cpp, raw_decode.cpp. Risk: medium.

### BK-268 [P2] Preview surface in 10-bit or half-float to avoid banding on screen
- Problem: `EditorGlView` picks `setEGLConfigChooser(8,8,8,8,0,0)`, an 8-bit surface. Gradients (skies, vignettes) can show visible steps on the S24U's 10-bit panel even though the pipeline is RGBA16F.
- Opportunity: request RGBA_1010102 (wide colour) or FP16 on a surface set to `COLOR_MODE_WIDE_COLOR_GAMUT`/HDR (links to BK-040/046) and dither (BK-045) when 8-bit.
- Acceptance: a smooth gradient test photo shows no steps at 1:1 on the phone; falls back safely when the config is unavailable (record the chosen config in the Copy report).
- Size: M. Files: core/render/EditorGlView.kt, engine.cpp. Risk: medium.

### BK-299 [P2] Record the embedded preview size of every RW2 and use it to decide the tiers with data
- Problem: tier 2 decodes the embedded JPEG at up to 2048 px; whether the S5IIX embeds a full-size (6000 x 4000) or a reduced JPEG decides if a 100 percent loupe (BK-267) can use it. The index stores `width`/`height` (of the JPEG, in `markIndexed`) but nothing reports the distribution.
- Acceptance: the Copy report prints `preview_dims: 6000x4000 (n=412), 1920x1280 (n=3)` from the catalogue; BK-267 uses the largest available; documented in docs/PERF.md.
- Size: S. Files: Db.kt (query), ReportBuilder.kt. Risk: low.

### BK-330 [P2] Use the Android adaptive refresh APIs so the S24 Ultra runs 120 Hz while sliders move and drops to 24 to 30 Hz when still
- Facts: the S24 Ultra panel is 1 to 120 Hz LTPO (Samsung specification page, checked 6 Oct 2026). Android exposes `Surface.setFrameRate()` (API 30) and, from Android 15, view-level requested frame rate and categories (verify the exact API names on developer.android.com before coding). A `GLSurfaceView` with `RENDERMODE_WHEN_DIRTY` only produces frames when asked, so the system may pick a lower rate than the drag deserves.
- Acceptance: during a slider drag, pan, zoom or brush stroke the editor requests 120 Hz (high frame rate category); when idle for 500 ms it requests the lowest rate; `Choreographer` frame interval recorded in the report shows 8.3 ms during drag; battery effect measured with a 10 minute session (BK-178). Supersedes the thinner BK-020.
- Size: S-M. Files: EditorScreen.kt, core/render/EditorGlView.kt, LoupeScreen.kt (zoom animation). Risk: low.
- Src: Samsung Galaxy S24 series refresh rate page and Android Developers, frame rate guide (checked 6 Oct 2026): https://developer.android.com/media/optimize/performance/frame-rate

### BK-333 [P2] Choose the loupe preview size from the display and the memory plan (2048 px is a fixed constant)
- Problem: `PreviewCache.longEdge = 2048`. The S24 Ultra is 3120 x 1440; a portrait photo shown full height is 2160 px tall and a 2x zoom needs 4000 px, so the loupe is slightly soft at fit on tall photos and soft at any zoom until BK-267 lands. Raising the constant to 2560 costs about 56 percent more memory per cached preview (13 MB vs 21 MB as ARGB_8888).
- Acceptance: long edge = min(display long edge x 0.9, plan limit from BK-324, embedded JPEG size); recorded in the report; `tier2_decode_ms` p95 compared for 2048 and 2560 on the phone before changing the default.
- Size: S. Files: core/cache/PreviewCache.kt. Risk: low.

### BK-425 [P2] Compose performance gate: stability report, recomposition budget and a grid benchmark (the grid already uses stable keys and content types)
- Finding: the library grid already passes `key = id` and `contentType` and uses a stable item model (verified in LibraryScreen.kt), so the next gains come elsewhere: item composables taking classes the compiler cannot prove stable (ThumbStore, LibraryActions), state read inside the item lambda (`selected.value` for every visible tile), allocations inside the lambda, and the editor panels (BK-249). No compiler metrics or benchmarks exist in the repo.
- Acceptance: Compose compiler reports enabled in a CI job (`reportsDestination`), with a list of unstable parameters on hot composables and a decision per item; the selection state read moved to a per-tile `derivedStateOf` or passed as a lambda so toggling one tile does not recompose all visible tiles; a macrobenchmark (BK-002) for grid fling and selection toggling with frame timing; guidance from Android's Compose performance docs applied and noted (keys, `contentType`, hoisting allocations out of `items`).
- Size: M. Files: build files, LibraryScreen.kt, CI. Risk: low.
- Src: Compose lazy layout performance guidance (stable keys, contentType, stability) as summarised in the search results of 6 Oct 2026; Android baseline profile docs (S5).

### BK-433 [P2] S24 Ultra display facts to design against: 3120 x 1440, 1 to 120 Hz LTPO, 2600 nits peak, 505 ppi
- Facts (GSMArena and Samsung support pages, checked 6 Oct 2026): 6.8 inch Dynamic LTPO AMOLED 2X, 120 Hz adaptive, HDR10+, 2600 nits peak. Consequences for Rawline: a 2048 px preview is slightly short for full-height portrait photos (BK-333); a 1:1 pixel view at 505 ppi shows far less than a desktop 100 percent so sharpening judged at 1:1 on the phone is harsher than on a monitor (BK-269); dark UI on AMOLED saves power but pure black next to bright photos can crush shadow judgement outdoors (BK-348); HDR headroom exists for Ultra HDR and HLG photos (BK-040, BK-327).
- Acceptance: a short "Display" section in docs/UI_SPEC.md or PERF.md stating the numbers and the four design consequences above, each linked to its entry; the Copy report prints the active mode (resolution, refresh rate, colour mode) at report time; no code change required beyond BK-432.
- Size: S. Files: docs. Risk: low.

### BK-462 [P2] The detail pass does 25 taps and many transcendentals on every frame even when noise reduction is off (audit AE-019)
- Finding: the baseline sharpen means `sharp > 0` for every raw, so each pixel runs the 5 x 5 bilateral accumulation and chroma average whether or not luminance NR is on; about 220 M transcendental operations per 1080 x 2300 frame.
- Acceptance: guard the NR accumulation with `nrL > 0`; precompute luma to the power 1/2.4 once per pixel (pack in a spare channel; move `inside` to a bit), replace `exp` with a small table or polynomial, use a separable blur for the sharpen base; `frame_render_ms` p95 before and after recorded on the phone (BK-001); goldens unchanged within 1 level.
- Size: M. Files: shaders/out.frag, engine.cpp. Risk: medium.
- Src: audit-engine.md AE-019.

### BK-465 [P2] [DECLINED] Export builds a full mip chain and can hold two full-resolution textures (audit AE-025)
- Status: Closed 6 Oct 2026: the mip chain at 1:1 export is needed because the local analysis samples the source at a high mip level; dropping it would change every export (docs/DECISIONS.md, W09b-engine-residue.md section 0).
- Acceptance: skip `glGenerateMipmap` for export at 1:1 and when the output is at least half the source; release the half-size texture before the full upload; peak GPU memory for a 24 MP and a 45 MP export recorded (BK-324) and reduced by at least 25 percent.
- Size: S-M. Files: engine.cpp, Exporter.kt. Risk: low-medium.
- Src: audit-engine.md AE-025.

### BK-020 [P3] [MERGED] Native 120 Hz awareness: request `preferredDisplayModeId` for the editor and loupe, 60 Hz for idle screens
- Status: Superseded by BK-330, which names the Android adaptive refresh APIs and the acceptance numbers.
- Problem: the S24U is LTPO 1-120 Hz; Compose scrolls at the refresh rate it is given. The editor surface may be pinned to 60 Hz by the GL view's default, making sliders feel less fluid than the grid.
- Opportunity: `Surface.setFrameRate` on the GL surface (API 30+) to 120 while dragging and drop to 24 or 30 when idle to save battery.
- Acceptance: `frame_render_ms` plus Choreographer frame interval show 8.3 ms during drag; idle drops.
- Size: S. Files: EditorScreen.kt GL view setup. Risk: low.

### BK-309 [P3] Avoid the extra bitmap copy when applying a stray IFD orientation to the preview
- Problem: `PreviewDecoder.applyOrientation` creates a second bitmap with a Matrix for RW2 previews whose JPEG has no orientation tag (software decode forced when `extra > 1`).
- Opportunity: use `ImageDecoder`'s `setPostProcessor` or draw with the rotation on the GPU in the loupe, saving one 2048 px allocation per photo that needs it.
- Acceptance: for photos with `extra > 1` the decode records the same orientation result with one allocation (verify with the existing orientation tests); `tier2_decode_ms` equal or lower.
- Size: S. Files: core/cache/PreviewDecoder.kt. Risk: low.

### BK-452 [P3] Prefetch window and bitmap reuse: make code, comment and spec agree, and reuse bitmaps if the numbers say it helps
- Facts: the loupe prefetches the current photo, four ahead and three behind (code and its comment in LoupeScreen.kt) while the spec says next 3 and previous 2 (AU-036 notes the comment is also wrong); `PreviewCache` allocates a new `Bitmap` for each decode and holds a quarter of the heap; the spec asks for bitmap reuse.
- Acceptance: the window is a named constant in one place with the spec's reasoning or a recorded change; after BK-001 numbers, if GC time during fast swipes is visible (`Debug` GC counts in the report), implement `inBitmap`/`BitmapPool` for software decodes (hardware bitmaps cannot be reused, BK-251); a test of the window logic at list ends.
- Size: S-M. Files: core/cache/PreviewCache.kt, LoupeScreen.kt. Risk: low.


---

# AREA B: EDITING QUALITY AND COLOUR SCIENCE

Reading of the pipeline (core/native/cpp/shaders/main.frag, out.frag, raw_decode.cpp): working space linear ProPhoto, WB as RGB multipliers, tone via local blurs, saturation and mixer in HSV, a fitted base curve and baseline look from 3 same-scene RW2 files, NR as 5x5 bilateral plus a 25 tap chroma average, sharpening as an unsharp on a Gaussian-weighted neighbourhood, edit base = LibRaw half-size bin (no demosaic).

### BK-021 [P0] Validate the base look on many scenes, not one: collect 15-20 RW2 plus matching camera JPEGs and refit
- Problem: DECISIONS.md says the base curve and baseline are fitted from three real S5IIX files that are all one scene; highlight behaviour is extrapolated.
- Why: every unedited photo Jai opens passes through this curve. A bad shoulder shows as clipped skies or dull shadows on every photo, which makes the editor feel worse than Lightroom immediately.
- Acceptance: a `tools/looks/` script takes N RW2 + embedded JPEG pairs (backlit, night, skin, foliage, snow, low ISO and ISO 6400) and reports rms per tonal range and per hue band; the curve is refit with a cross-validation split; PERF-style table in docs/DECISIONS.md. Jai supplies files by dropping them in testdata/ (git-ignored) or via the free raw.pixls.us Panasonic sample set already used by CI.
- Size: M. Files: tools/looks (new), core/render/BaseCurve.kt, core/native/engine/base_curve.h. Risk: medium (a single curve cannot match JPEG local tone mapping; accept mean match plus no clipping).

### BK-022 [P0] Make the camera's colour exact: use the real S5IIX colour matrix and a dual-illuminant interpolation
- Problem: colour comes from LibRaw's built-in matrix (`output_color = 4`, `use_camera_wb = 1`); temperature and tint are RGB multipliers in ProPhoto, described as "approximation of a camera space shift".
- Why: skin and sky hues are the first thing a photographer judges; a multiplier WB in ProPhoto drifts hue and saturation when temperature moves far from as-shot (Lightroom does WB in camera space with interpolated matrices).
- Acceptance: (1) read the DNG-style ColorMatrix1/2 and CalibrationIlluminants for the S5IIX (LibRaw exposes `color.dng_color` or the built-in table; verify) and interpolate by CCT; (2) white balance applied in camera RGB before the matrix; (3) a golden test shows a neutral patch stays neutral across 3000-9000 K; (4) before/after hue shift of 8 test hues reported.
- Size: L. Files: core/native/raw_decode.cpp, engine.cpp, main.frag (WB moves), EditorSession.kt, RenderParams.kt. Risk: high (changes every look; ship behind a recipe `wbv` flag like `lensv` so old edits keep their look).

### BK-023 [P0] Highlight reconstruction beyond LibRaw blend mode 2
- Problem: `P.highlight = 2` blends clipped channels; any pixel with one clipped channel loses colour and goes cyan/magenta or flat white. S5IIX skies and lights clip often.
- Opportunity: do highlight recovery in the shader after WB and before the matrix: reconstruct clipped channels from the unclipped ones using a smooth desaturation toward the neutral axis plus a guided inpaint of colour ratios from the surrounding unclipped neighbourhood (the same idea used by darktable "inpaint opposed" and RawTherapee "colour propagation").
- Why: this is where Lightroom looks visibly better than simple converters, and where Highlights -100 either works or goes grey.
- Acceptance: golden scene with a blown sky and a lit lamp; no magenta or green fringes at the clip edge; histogram clipping indicator (BK-054) agrees with the result; opt-out slider "Recovery" in Light panel default 50.
- Size: L. Files: raw_decode.cpp (stop LibRaw blending, keep a clip mask), main.frag, params.h, RenderParams.kt, EditRecipe.kt. Risk: high.
- Src: darktable and RawTherapee documentation (general knowledge, not re-fetched; verify before relying).

### BK-024 [P0] Full-resolution GPU demosaic for the edit base, 100 percent zoom and export (replace AHD CPU path)
- Problem: Edit base is a 2x2 binned half-size (no demosaic) image; zoom past it triggers the LibRaw full AHD CPU decode. AHD is slow on phone CPU and is not the best quality for fine detail.
- Opportunity: upload packed Bayer and demosaic in a compute shader (GLES 3.1 compute is on every Adreno) using RCD or a gradient-corrected linear (Malvar-He-Cutler) for preview and a higher quality method (RCD or LMMSE) for export, tile by tile.
- Why: Edit-first-frame budget improves because nothing waits for the CPU; 100 percent zoom is instant; export quality is at least as good as LibRaw AHD with fewer maze artefacts.
- Acceptance: golden image comparison versus LibRaw AHD on the three sample files (PSNR above 45 dB in smooth areas, no zipper on the ISO chart edge); `full_decode_ms` under 400 ms on the phone; export time unaffected or better.
- Size: L. Files: raw_decode.cpp, new shaders/demosaic.comp, engine.cpp, EditorSession.kt, Exporter.kt. Risk: high but isolated behind a flag; keep the AHD path as a Settings fallback.
- Src: RawPedia (RawTherapee) demosaicing guidance: AMaZE best at low ISO, RCD close and better on round edges, LMMSE and IGV for noisy high ISO files, AHD old and inferior (checked 6 Oct 2026); see BK-422 for the choice by ISO: https://rawpedia.rawtherapee.com/Demosaicing

### BK-435 [P0] Colour pipeline contract (docs/COLOUR.md) and the verification plan for a Panasonic S5IIX RW2, end to end
- Why: colour is the first thing a photographer judges, and today nothing proves any stage. Facts from reading the code and the engine audit (audit-engine.md AE-001, AE-023, AE-018, AE-015, AE-030, AE-054): LibRaw decodes with `use_camera_wb`, `output_color = 4`, gamma 1, `highlight = 2` and AHD (raw_decode.cpp); for an S5IIX file LibRaw has no dual-illuminant profile, only one 3 x 3 matrix for the `DC-S5M2` prefix (`10308,-4206,-783,-4088,12102,2229,-125,1051,5912` divided by 10000, colordata.cpp line 1288), which also matches `DC-S5M2X`; the white level is rescaled by LibRaw so a clipped neutral lands at about 0.5 linear (AE-001, measured by a probe on the CI sample); the working space is ProPhoto primaries with a white of (1,1,1) (dcraw's `-o 4` is documented as "ProPhoto RGB D65"; the app's code comments say D50, see BK-437); white balance is applied again as RGB multipliers in that space (main.frag, AE-023); a base tone curve fitted from three low dynamic range photos of one scene and a baseline of saturation +10, texture +25, clarity +10, sharpen +45 follow; the output transform is a 3 x 3 matrix to linear sRGB or Display P3 then the sRGB OETF through the base curve table (out.frag, engine.cpp).
- Acceptance: `docs/COLOUR.md` states for each stage (S0 decode: black and white level, camera multipliers, demosaic, highlight blend; S1 camera to working space; S2 upload as half float; S3 lens correction; S4 global adjustments including WB; S5 local masks; S6 detail and effects; S7 base curve and OETF; S8 output matrix and ICC; S9 quantise and encode) its input domain, output domain, white point, encoding, valid range and the invariant a test can check (for example "a neutral input stays neutral", "exposure +1 doubles linear values below the shoulder", "a clipped neutral reaches 255/255"); each invariant is bound to a test id in BK-436 to BK-446; a table lists tolerances (neutral channel difference at most 0.5 percent, patch delta E 2000 at most 2 for mid-tone patches and at most 4 for saturated ones, exposure ratio within 1 percent, TIFF16 distinct levels on a ramp at least 4000 of 65536); the doc names what is deliberately approximate (WB in the working space, a fitted base curve) so reviewers do not report it as a bug.
- Size: M. Files: docs/COLOUR.md, docs/DECISIONS.md (link). Risk: low. Everything in BK-436 to BK-446 hangs from this.

### BK-436 [P0] Synthetic DNG chart writer: a known scene as a Bayer file, with analytic expected output (no phone, no camera)
- Idea: write a small Python tool (standard library plus numpy if present, else pure Python; Pillow is already used by the golden job) that builds a valid DNG with chosen `ColorMatrix1`, `CalibrationIlluminant1` (D65), `AsShotNeutral`, `BlackLevel`, `WhiteLevel`, CFA pattern RGGB and a mosaic made from flat patches. Flat patches make demosaic exact away from patch borders, so any difference is the colour pipeline, not the demosaic. LibRaw reads the matrix and neutral from the DNG, so this tests the mechanics (matrix, WB, ProPhoto, curve, output) independently of the S5IIX table.
- Fixtures (each a 1200 x 800 DNG with 6 x 4 patches of 150 px): (1) neutral ramp, 24 patches from 0.2 percent to 100 percent linear scene reflectance; (2) 24 chart-like colours defined by sRGB values with the published reference table recorded in the repo (cite the source in the test); (3) the six saturated primaries and secondaries at three luminances; (4) skin-like patches (4 values); (5) a high-ISO variant (same scene plus Gaussian noise at known sigma and a Poisson component) for NR tests; (6) clipped variants: each patch scaled so one, two or all channels reach the white level; (7) a black level offset variant (BlackLevel 512, WhiteLevel 16383, as the S5IIX uses 14 bit) to prove subtraction and scaling; (8) a non-trivial AsShotNeutral (tungsten 3000 K and shade 7500 K) to prove WB.
- Expected values: computed by an independent Python implementation (BK-441) from the scene linear values, the DNG matrix and the output space definition; stored as JSON next to each DNG so the C++ golden harness can compare without Python at test time.
- Acceptance: `tools/fixtures/make_dng.py` generates all fixtures deterministically (seeded noise), `tools/fixtures/expected.json` is committed with the generator version; the golden harness decodes each fixture through `decodeRaw` + the engine at default settings and prints per-patch RGB and delta E 2000 against expected; first run results are recorded in docs/QUALITY.md; at least the neutral ramp and the black level variant pass the tolerances of BK-435.
- Size: M. Files: new tools/fixtures/**, tools/golden (a `colour_chart` scene and a comparison script). Risk: low-medium (DNG tag details; validate the files with `dng_validate` if available, else with LibRaw itself and `exiftool`-like dumps).

### BK-437 [P0] Matrices and transfer functions derived from first principles, and the D50 versus D65 labelling fixed
- Findings: `ColorSpaces.m` and `inv` (sRGB to ProPhoto and back) have rows that sum to 1, so neutral white is preserved; `rawFromSrgb8` uses the same numbers as the dcraw ProPhoto D65 matrix (0.5293, 0.3300, 0.1406 ...); the comments in ColorSpaces.kt and engine.cpp call the space "ProPhoto (D50)" and "Bradford", while LibRaw's `-o 4` space is documented by dcraw as ProPhoto with a D65 white. The two ProPhoto-to-P3 and ProPhoto-to-sRGB matrices in engine.cpp (`proPhotoToSrgb`, the hand-typed `mp3`) are a third and fourth copy of similar numbers. If any pair disagrees on the white point the neutral axis gets a small cast that no slider explains.
- Acceptance: a host test derives from published primaries and white points (ProPhoto x,y of R, G, B; sRGB; Display P3; D50 and D65 whites; Bradford matrix) the matrices sRGB to working, working to sRGB, working to P3 for both candidate definitions and asserts that every copy in the code (ColorSpaces.m, ColorSpaces.inv, `rawFromSrgb8`, `proPhotoToSrgb`, `mp3`) equals the definition that matches LibRaw's output (decided by decoding fixture (1) with a known neutral and checking that the working RGB is (v, v, v) within 1e-3) to 5e-4; round trip `displayToWorking` then `workingToDisplay` returns the input within 1/255 on a 17 x 17 x 17 grid; the sRGB OETF/EOTF used in Kotlin, GLSL (`srgbOetf`) and the 2.2 and 2.4 shortcuts in main.frag are compared and the maximum differences listed (feeds BK-032); the comments are corrected to the true white point and each matrix has one source of truth (a generated header or a single Kotlin object used by tests).
- Verified 6 Oct 2026 by computation and by the W21 harness: the app matrices, the engine `proPhotoToSrgb` and LibRaw's `prophoto_rgb` table are the same Bradford-adapted ProPhoto matrix to about 4 digits (engine P3 matrix times `prophoto_rgb` equals nominal sRGB to P3 within 3.2e-4), and the labelling is a comment problem only. The one known disagreement is the third row of `rawFromSrgb8` (3 to 5e-4), so the test should pin that.
- Size: S-M. Files: core/render/ColorSpaces.kt, core/native/engine/engine.cpp, tests. Risk: low.
- Src: dcraw documentation of output colour spaces (`-o 4` ProPhoto D65) and LibRaw source (checked 6 Oct 2026, to verify against the actual `out_rgb` table in the fetched LibRaw tree).

### BK-438 [P0] White level and clipped highlight contract: a clipped neutral must render as 255/255 (audit AE-001), with fixtures that prove it
- Finding (audit-engine.md AE-001, measured on the CI sample by the engine auditor): with `highlight = 2` LibRaw divides all multipliers by the largest, so the green channel tops out at 0.497 of full scale; a fully clipped neutral sky or lamp enters the pipeline at about 0.50 linear and displays near 240/255, never white; every threshold written for white at 1.0 (Whites, highlight weights, grading zones, luminance masks, the histogram right edge) is about one stop off; the three fitted base-curve samples never clipped, so the shoulder was never fitted at the clip point.
- Acceptance: fixture (6) of BK-436 (neutral patch at the white level on all channels) decodes to working RGB 1.0 +/- 0.01 and displays 255/255 +/- 1 at default settings; the fix is a documented rescale (multiply by `dmax / pre_mul[G]` or pass the clip scale to the shader), a refit of the base curve with a clipped sample (BK-021, BK-440), and all golden references regenerated with the changes reviewed by eye (they change by about one stop); Whites +100 can clip a mid-bright patch in a golden; the histogram shows a spike at the right edge for a blown sky; a unit test pins the clip scale for the S5IIX sample file (probe numbers from AE-001: pre_mul 1.000, 0.497, 0.864; max 15807).
- Size: M. Files: core/native raw_decode.cpp, shaders/main.frag, engine/base_curve.h, tools/golden/ref. Risk: medium-high (changes every render; behind a recipe look-version flag so saved edits keep their look until Jai opts in, BK-154).
- Src: audit-engine.md AE-001 (probe run by that audit on 6 Oct 2026).

### BK-025 [P1] Raw-domain noise reduction before demosaic
- Problem: all NR runs after demosaic and the matrix on RGB (out.frag), a 5x5 bilateral with step at most 3 px and a 25-sample box chroma average. Noise is spatially correlated after demosaic, so detail loss is higher for the same noise removal; the chroma blur is a plain box.
- Opportunity: add a Bayer-domain denoise (per-CFA-channel bilateral or a wavelet shrink on 4 half-size planes, with a noise model built from ISO and the S5IIX dual native ISO switch points) before demosaic.
- Why: Jai shoots high ISO; Lightroom Denoise is the benchmark and the one feature photographers pay for. Better classic NR means the AI denoise (430 MB model) is needed less.
- Acceptance: on ISO 6400 and 12800 S5IIX files, measured noise standard deviation in a flat patch drops 50 percent at equal edge MTF50 versus current luminance NR at 50; golden harness gets a noisy fixture.
- Size: L. Files: new shaders/denoise_raw.comp, engine.cpp, Detail panel. Risk: high (needs a noise model; start with simple variance stabilising transform).

### BK-026 [P1] Dual native ISO aware noise profile for the S5IIX
- Problem: the S5IIX has dual native ISO (two base ISOs per mode), so noise does not rise monotonically with ISO. A noise model based on ISO alone over-smooths just above the switch point.
- Why: auto NR strength and AI-denoise defaults should respect where the camera switches gain, otherwise ISO 640 looks worse than ISO 800.
- Acceptance: a small table keyed by camera model + ISO giving read noise and gain used by BK-025; default NR amount derived from it; documented source of the numbers (measure from sample files, not guessed).
- Size: M. Files: new core/render/NoiseProfile.kt, Detail defaults. Risk: low-medium.
- Src: B&H S5IIX product page (S16, 6 Oct 2026) confirms dual native ISO and 96 MP high-resolution mode.

### BK-027 [P1] Smarter default sharpening: capture sharpening in raw domain and deconvolution option
- Problem: sharpening is an unsharp mask on a Gaussian weight window with a global detail/masking control; there is an invisible baseline sharpen +45.
- Opportunity: Richardson-Lucy style deconvolution (small radius) as an alternative "Capture sharpen", and a halo clamp; sharpen applied at output scale for exports (already `uPxScale`).
- Why: the S5IIX has an AA filter; correct capture sharpening makes screen and print results noticeably crisper without halos.
- Acceptance: slanted-edge test image shows MTF50 lift without overshoot beyond 5 percent; a "Sharpen" preview at 100 percent matches the exported JPEG.
- Size: M-L. Files: out.frag, new deconv pass, DetailPanel. Risk: medium.

### BK-028 [P1] Chroma noise reduction in a perceptual chroma space, not a 25-tap box on RGB ratios
- Problem: out.frag computes `chroma = avg / ya` over a fixed 5x5 (stepped) window, which bleeds colour across edges and shifts saturation.
- Opportunity: do chroma NR in an opponent space (Lab or IPT) with an edge-stopping weight from luminance, at a larger radius via a downsampled pyramid so it is both cheaper and stronger.
- Acceptance: golden fixture with a saturated red-blue edge shows no colour bleed; at NR colour 50 coloured speckle gone at ISO 12800.
- Size: M. Files: out.frag or new chroma pass, engine.cpp. Risk: medium.

### BK-029 [P1] Local tone mapping that does not halo: replace the Gaussian-blur base with a guided or edge-aware pyramid
- Problem: highlights/shadows/clarity/texture use three blurred layers (512 px analysis, bilateral not used). Strong shadow lifts will show halos around high-contrast edges, e.g. a tree line against sky.
- Opportunity: local Laplacian or a guided-filter base layer (GuidedFilter.kt already exists on the CPU for AI masks) computed once per geometry at 512-1024 px.
- Acceptance: golden scene "tree against sky" with Shadows +80 and Highlights -80: halo amplitude below 2 percent of the edge step; analysis time under 8 ms on the phone.
- Size: L. Files: blur.frag, engine.cpp, main.frag. Risk: high.

### BK-030 [P1] HSL colour mixer in a perceptually better space with 8 bands that do not overlap badly
- Problem: the mixer uses HSV bands with a smoothstep of width 0.0833 x 1.6 around fixed centres (main.frag). HSV hue is not perceptual: shifting "blue" changes luminance wildly and the aqua/blue transition is abrupt.
- Opportunity: do the mixer in an LCh-like space (OKLab/OKLCh) with lum shifts that hold perceptual lightness, optionally add per-band range sliders.
- Why: colour mixer and colour grading are what Jai will use for "look".
- Acceptance: a swatch ramp test shows a Hue slider moves hue only (delta lightness under 1 L*), saturation moves chroma only; recipes keep working (`mixv` flag).
- Size: M-L. Files: main.frag, RenderParams.kt, EditRecipe.kt (flag), ColourPanel.kt. Risk: medium-high (changes existing edits; flag it).

### BK-031 [P1] Colour grading wheels: pass through a perceptual model and add per-wheel luminance, balance and blend that match user expectation
- Problem: `grade()` works on gamma-encoded RGB with HSV tints scaled by 0.35 and lum by 0.25 (main.frag), which makes saturation of a tint depend on the hue.
- Acceptance: equal "sat" gives equal chroma across hues (delta C within 10 percent in OKLab); shadows/mids/highlights masks defined on linear luminance with Stops-based pivots; legacy recipes keep their look via version flag.
- Size: M. Files: main.frag, RenderParams.kt. Risk: medium.

### BK-032 [P1] Replace the global 2.2 gamma shortcuts with the true sRGB or a shared transfer in all shader code
- Problem: `toGamma`/`toLinear` use pow 2.2 while the output uses the exact sRGB OETF (out.frag) and local tone uses 2.4 (main.frag). Mixed encodings mean sliders behave slightly differently in shadows than the preview shows.
- Why: consistent maths avoids banding and colour shifts near black; and makes the curve editor, which is drawn in the displayed tone, match the adjusted image.
- Acceptance: one `transfer.glsl` include with named functions; golden render deltas documented (expect small change, re-bless goldens); curve tool still matches displayed tone (CurveEditTest passes).
- Size: S-M. Files: main.frag, out.frag, geometry.glsl, tools/golden/ref. Risk: low-medium (updates goldens).

### BK-033 [P1] Tone curve with a proper camera-style profile selector: Standard (baseline), Neutral, Vivid, Monochrome, Flat/V-Log style
- Problem: the baseline look is hidden and fixed (saturation +10, texture +25, clarity +10, sharpen +45 plus fitted curve); there is no way to see or turn off the baseline, nor pick a neutral starting point.
- Why: Lightroom has Adobe Color/Neutral/Landscape profiles; knowing the starting point makes edits predictable, and turning baseline off helps when comparing.
- Acceptance: a "Profile" row at the top of the Light panel with at least Standard, Neutral (baseline off, plain sRGB curve), Vivid, Mono; saved in the recipe; presets store profile; the baseline numbers visible in a help sheet.
- Size: M. Files: EditRecipe.kt (profile field), BaseCurve.kt, RenderParams.kt, Panels.kt, ColourPanel.kt. Risk: medium (migration of existing recipes to Standard).

### BK-034 [P1] Lens correction verified on a real Lumix S lens, and a manual fallback when no profile matches
- Problem: DECISIONS.md states radius normalisation is "NOT verified on a real Lumix S lens file yet". A wrong convention gives wrong straight lines at the corners and a fitCrop that eats frame.
- Acceptance: test with one real RW2 per owned lens (Jai supplies body and lens names via Copy report: the report should print `lens`, `focal`, `aperture`, profile match name, and distortion/vignetting coefficients used); straight-line test image shows residual under 0.2 percent at the corner; manual distortion/vignette sliders (already present) and a "profile not found" banner.
- Size: M. Files: core/render/LensProfiles.kt, PerfLog report, GeometryPanel.kt, tools/golden. Risk: medium.

### BK-035 [P1] Embed the camera's own lens correction data: read Panasonic RW2 distortion and vignetting maker tags when present
- Problem: the S5IIX applies in-camera lens correction using data stored in RW2 maker notes for Lumix S lenses. LibRaw exposes some of this (check `makernotes.panasonic`), but the app only uses the lensfun XML.
- Opportunity: when the file carries correction parameters, prefer them over lensfun; log which source was used.
- Acceptance: for a file shot with an L-mount lens, the maker-note data (if exposed by LibRaw 0.22) is displayed in Optics as "Camera profile" and matches the embedded JPEG's geometry within 0.1 percent.
- Size: M. Files: raw_decode.cpp, LensProfiles.kt. Risk: medium (tag decoding may not be exposed; research first: a spike task with a half-day cap).
- Src: LibRaw release notes (S11, 6 Oct 2026).

### BK-287 [P1] Real DNG profiles (DCP-like) for the S5IIX: use the camera's embedded ForwardMatrix and tone curve if present, and let Jai import his own .dcp
- Opportunity: the colour of a photo is mostly the matrix and the tone curve; Lightroom's "Adobe Color" profile is a DCP. Rawline cannot copy Adobe profiles, but a user-owned DCP (for example from a colour checker via ColorChecker Camera Calibration, or a free community profile with a licence permitting use) could be imported for personal use.
- Acceptance: parser for .dcp (TIFF-based) reading ColorMatrix1/2, ForwardMatrix1/2, ProfileHueSatMap, ProfileLookTable, ProfileToneCurve; the shader applies the hue/sat map in a 3D LUT; Settings > Colour profiles lists imported profiles; default stays the current baseline; legal note in THIRD_PARTY.md that profiles are the user's own files.
- Size: L. Files: new core/render/Dcp.kt, engine.cpp (3D LUT), main.frag. Risk: high.

### BK-422 [P1] Choose the demosaic by ISO and subject, following published raw converter guidance (Rawline uses AHD today)
- Evidence: RawPedia (RawTherapee) describes AMaZE as the best general choice at low ISO, RCD as nearly as detailed and better on round edges, DCB as similar with fewer false colours on cameras without an anti-aliasing filter, LMMSE and IGV as the right choice for noisy high ISO files (they avoid the maze patterns and the washed-out look from heavy noise reduction), and AHD, EAHD and HPHD as old, slow and inferior. `raw_decode.cpp` sets `user_qual = 3` (AHD) for the full decode and bins for the half-size base.
- Acceptance: the GPU demosaic work (BK-024) implements RCD (default) and a smoother option for high ISO (LMMSE-like or a VNG4 blend) and selects by ISO (threshold set by BK-421 results and BK-026 noise profile) with a manual override in Detail; an interim change to LibRaw `user_qual` for the full decode (DHT 11 or AAHD 12 are available in LibRaw 0.22; AMaZE and LMMSE need the GPL demosaic pack, so not usable without a licence decision) measured with BK-421 before and after; documented in DECISIONS.md.
- Size: M (interim) / L (GPU). Files: core/native/raw_decode.cpp, Detail panel. Risk: medium.
- Src: RawPedia demosaicing (checked 6 Oct 2026): https://rawpedia.rawtherapee.com/Demosaicing

### BK-423 [P1] Jai's calibration shoot: one 30 minute protocol that produces the test files every quality item needs
- Why: BK-021, 022, 026, 034, 421 all need real S5IIX files with known content, and Jai owns the camera.
- Acceptance: a one page phone-friendly protocol in docs/CALIBRATION.md: (1) a 24 patch colour chart in daylight, RAW+JPEG, base ISO, with and without Photo Style Standard; (2) a slanted edge target at f/4, f/8 and f/11; (3) the same static scene at ISO 100, 400, 800 (note the dual native switch), 1600, 3200, 6400, 12800; (4) an exposure bracket of a high contrast scene (a window) in 1 stop steps; (5) a grid of straight lines with each lens he owns at the wide and long ends; (6) one file each for HLG HEIF, Real Time LUT and V-Log if he uses them; (7) the same scene shot in Photo Style Natural and Vivid; the files are copied to testdata/ (git-ignored) or a Release asset with Jai's permission (BK-216), each named by what it tests; nothing is invented about the camera.
- Size: S (document) then Jai's time. Files: docs. Risk: low.

### BK-439 [P1] Neutral ramp and exposure exactness through the whole engine, with analytic expected values
- Acceptance: golden scene `colour_ramp` renders fixture (1) at default settings; expected display value per patch is `base(srgbOetf(M x))` where M is the verified matrix (BK-437) and `base` is the table; the test asserts within 1/255; with the look flag "Neutral" (BK-033: no base curve, no baseline) the expected value is the plain sRGB OETF exactly; Exposure +1, +2 and -1 multiply linear patches by 2, 4 and 0.5 within 1 percent (read back through the 16-bit float path, which must be linear, see BK-444); Display P3 output of the six primaries matches the analytic P3 values within 1/255; a flat 18 percent patch with all sliders at 0 and the Neutral look equals 118/255 (sRGB 0.461) within 1.
- Size: M. Files: tools/golden, tests. Risk: low.

### BK-440 [P1] Base tone curve audit: properties, hold-out validation and a guard against its low dynamic range origin
- Facts: `base_curve.h` and `BaseCurve.TABLE` (256 entries, mirrored by a test) were fitted from three same-scene S5IIX files with rms 0.005 in sRGB units over the observable range and the shoulder forced to white (DECISIONS.md); the 3 samples never reached clipping (AE-001), so everything above their maximum is extrapolation; AE-001 means a shoulder fitted without clipped data is placed about one stop wrong.
- Acceptance: tests on the table: monotonic, `f(0) = 0`, `f(1) = 1`, derivative everywhere above 0.1 (no flat top), no step larger than 2/255 between neighbours, second difference sign changes limited to the documented toe and shoulder; a hold-out check (fit with 2 of 3 samples, predict the third, report rms, which tells how far the curve is from general); the fit script and the three samples' summary statistics (not the images) committed in tools/looks so the curve can be refitted reproducibly (BK-021); a documented extrapolation policy: above the highest fitted level the curve follows a fixed shoulder (a stated function), not an accident of the table; the "Standard" profile (BK-033) records which samples it came from.
- Size: M. Files: core/render tests, tools/looks, docs/COLOUR.md. Risk: low.

### BK-441 [P1] An independent reference pipeline in Python and the exposure-bracket test for highlight recovery
- Acceptance: `tools/fixtures/reference.py` implements the intended maths independently of the C++ code (black and white level, WB multipliers, matrix, working space, base curve from the same table, output OETF, quantise) on flat patches; it produces `expected.json` for BK-436 and is itself tested against closed-form values (a neutral stays neutral; matrix inverse identity). Highlight recovery: generate a scene with known radiance (float) and expose it twice: the base exposure with some patches clipped and a version 2 stops lower with nothing clipped; define the truth as the low exposure scaled by 4; after decode, the clipped version's recovered regions (BK-023) must be within delta E 2000 of 5 outside the fully clipped core, with no hue shift larger than 8 degrees on tinted clips (sky blue, lamp orange); the metric and thresholds are stored with the fixture; used to accept or reject BK-023 changes without a phone.
- Size: M. Files: tools/fixtures, tools/golden. Risk: low.

### BK-442 [P1] ICC embedding and Display P3 versus sRGB cross-check using Pillow's ImageCms
- Facts: AQ-019 and AE-018 report that the TIFF has no ICC tag and a P3 export is read as sRGB; JPEG and PNG exports carry the Bitmap colour space profile (platform behaviour, not yet tested in this repo).
- Acceptance: a test exports the six primaries and a ramp as JPEG, PNG and TIFF16 in sRGB and Display P3, extracts the embedded profile (Pillow `ImageCms` with LittleCMS is available where the golden job runs; confirm in CI) and checks: the profile exists and its description matches; converting the P3 file to sRGB with its profile gives the sRGB export within 1 level for in-gamut colours; the primaries of the P3 file are out of the sRGB gamut as expected; TIFF carries tag 34675 (BK-133 implements it); a test fails if an export is untagged.
- Size: M. Files: tools/golden or a new tools/export-check, core/render tests (TIFF writer), CI. Risk: low.

### BK-443 [P1] White balance neutrality: luminance drift, eyedropper round trip and the Kelvin display (audit AE-023)
- Finding: WB is a multiplier in the working space with no luminance compensation (Temp +50 raises Y about 14 percent, Temp -50 lowers it about 10 percent, AE-023), so Exposure appears to move with Temperature.
- Acceptance: a test over Temp -100..100 and Tint -100..100 on fixture (1): the luminance of every patch changes by less than 1 percent (after the fix: normalise the multipliers so `dot(wb, Y) = 1`, or apply WB with the camera matrix, BK-022); neutral patches stay on the neutral axis only when the sliders are 0; the eyedropper (`AutoTools.wbFromSample`) applied to a neutral patch under fixture (8) tungsten and shade neutrals makes R=G=B within 0.5 percent after one pick and two picks agree; the Kelvin number shown (relative to a nominal 5500 K, DECISIONS.md) is monotonic and its slope table is documented; a regression test for the old behaviour fails before the fix.
- Size: S-M. Files: shaders/main.frag, feature/editor/AutoTools.kt, tests. Risk: low-medium (changes the look of Temp edits: flagged, BK-154).
- Src: audit-engine.md AE-023.

### BK-445 [P1] Camera matrix provenance for the S5IIX and a per-camera override fitted from a chart
- Findings: for the `DC-S5M2` prefix LibRaw has only a single matrix, `10308,-4206,-783,-4088,12102,2229,-125,1051,5912`; the S5IIX body name is `DC-S5M2X`, which matches by prefix; there is no second illuminant, so mixed or tungsten light uses the same matrix with different multipliers (BK-022). A matrix taken from a generic table can be a few delta E off for saturated colours.
- Acceptance: a spike that (1) confirms from the real S5IIX sample that LibRaw selected this entry (print `imgdata.color.cam_xyz` or the `rgb_cam` product and the model string in the Copy report); (2) uses Jai's chart shoot (BK-423) to fit a 3 x 3 matrix by least squares in linear light (patch means from the demosaiced raw, reference XYZ or sRGB values of the chart) and compares its delta E to the table's on the same chart and on a second scene; (3) if the fitted matrix wins by more than 1 delta E mean, adds a `CameraProfile` table keyed by model with an override applied at decode (`rgb_cam` replaced) behind the look-version flag; (4) records the result and the chart conditions (daylight, no lens flare) in docs/COLOUR.md.
- Size: M. Files: raw_decode.cpp, new core/render/CameraProfile.kt, tools/fixtures. Risk: medium (needs Jai's files; do not guess matrices).

### BK-459 [P1] [PARTLY DONE] Shader correctness bundle from the engine audit: dehaze sign, analysis ignoring lens gain, mask baseline, HSL partition, grading luma, clarity sign
- Status: Commit ee38b8b merged dehaze sign, lens gain in the analysis, mask baseline, grading tint luminance, range masks in display terms, clarity clamp, manual vignette centre. Left: HSL partition, fine Texture radius and ProPhoto grading luma, now built and measured in W23-shader-correctness.md (look 2, not merged).
- Findings (audit-engine.md): AE-002 negative Dehaze increases contrast instead of adding haze (`t = 1.0 - d * 0.5` is above 1; fix `t = 1.0 + d * 0.5`); AE-003 the local analysis (Texture and Clarity, shadow and highlight weights) ignores lens vignetting gain, so corners get a spurious boost (up to +32 percent from Texture at 20 mm f/3.5); AE-012 mask blocks measure local contrast against a baseline that excludes the global edit; AE-013 HSL band weights do not sum to 1 so slider strength depends on hue (orange is 1.64 times stronger than green); AE-014 colour grading tints are not luminance neutral; AE-029 the Clarity weight goes negative above 1.0; AE-030 grading zones use Rec 709 luma on ProPhoto gamma values.
- Acceptance: each fix has a new or updated golden scene (negative dehaze; a vignetted corner with Texture +50; a mask with global exposure +1 and Clarity +50 showing no brightening; an HSL sweep with equal slider values giving equal strength within 10 percent at orange and green; a grading tint at saturation 100 changing luminance by less than 2 percent); the existing goldens are re-blessed only where the finding says they must change; changes are behind the look-version flag so saved edits do not shift without consent (BK-154).
- Size: M. Files: shaders/main.frag, tools/golden. Risk: medium (visible changes to saved edits; flag it).
- Src: audit-engine.md AE-002, 003, 012, 013, 014, 029, 030.

### BK-470 [P1] [DESIGNED] LibRaw `adjust_maximum_thr` rescales exposure by up to +0.4 EV depending on the brightest pixel in the frame (measured)
- Status: Fully specified and verified in W22-colour-contract.md (decode with adjust_maximum_thr 0, look version 1 reproduces the old factor exactly, look 2 passes fixture F8). Not merged yet.
- Finding: LibRaw lowers its white point to the frame's brightest pixel whenever that pixel is between 75 percent and 100 percent of the white level (`adjust_maximum`, default threshold 0.75; `raw_decode.cpp` never sets it). Measured through the golden harness with a synthetic DNG ramp: frame peak 0.74 of white gives output scale K = 0.99, peak 0.76 gives 1.31, 0.80 gives 1.25, 0.95 gives 1.05, 1.00 gives 1.00. Two frames of the same scene at the same exposure therefore differ by up to 0.4 EV purely on whether one specular highlight sits just below clipping. The base curve was fitted from three samples that each had their own peak, so the fitted look also contains this effect.
- Why it matters to Jai: batch edits and sync of exposure across a burst or a series look inconsistent by a third of a stop for no visible reason; the exposure slider and auto tone are measured against a moving white point.
- Acceptance: `P.adjust_maximum_thr = 0.0f` in `decodeRaw` (values below 0.00001 return early, so it is disabled); W21 fixture F8 (ramp peak 0.8) flips from XFAIL to PASS; a unit or golden test with two otherwise identical frames whose peaks are 0.70 and 0.90 gives equal output within 1 level; the base curve offset is refitted on the S5M2X samples (BK-021, BK-440) and every golden reference is regenerated and reviewed; behind the recipe look-version flag of W22 so existing edits keep their old look until Jai opts in.
- Size: S for the one line, M with the refit and golden review. Files: core/native/src/main/cpp/raw_decode.cpp, engine/base_curve.h, BaseCurve.kt, tools/golden refs. Risk: medium (changes the look of every photo; one decision for Jai on the phone).
- Src: measured 6 Oct 2026 with tools in scratchpad/w21 (see W21-colour-verification.md section 2); LibRaw 0.22.2 `src/utils/utils_libraw.cpp` `adjust_maximum`.

### BK-036 [P2] Defringe and purple fringing removal
- Problem: only lateral CA is handled (lens profile). Longitudinal CA and purple fringing on backlit edges remain.
- Acceptance: a Defringe slider (purple, green) in Optics that desaturates pixels near high-contrast edges within a hue window; fixture with purple fringe; no loss on legitimately purple flowers (mask with edge distance).
- Size: M. Files: main.frag or a new pass, OpticsPanel. Risk: low-medium.

### BK-037 [P2] Perspective: Upright-style auto (level, vertical, full) and guided lines
- Problem: AutoTools has estimateKeystone from a bitmap, but the UI has no guided line mode. Lightroom's Guided Upright is a favourite for architecture.
- Acceptance: user draws 2-4 lines on the photo; solver sets keystone V/H, angle; result inside crop fit; test with synthetic images in CropMathTest.
- Size: M-L. Files: GeometryPanel.kt, CropMath.kt, AutoTools.kt. Risk: medium.

### BK-038 [P2] HDR merge and panorama merge from RAW (bracketed sets and overlapping frames)
- Problem: no multi-frame operations. Lightroom Mobile offers HDR and panorama merge; Jai shoots with an S5IIX that has bracketing.
- Acceptance: select 3-9 RW2 with different exposures, run Merge HDR, get a linear 16-bit float DNG-like internal source; deghost option; saved as a virtual master (recipe references the set). Panorama: stitch 2-6 frames cylindrical.
- Size: L each. Files: new core/merge module, native alignment code, Library actions. Risk: high (heavy memory; do 1 at a time with the governor BK-013).

### BK-039 [P2] [MERGED] Focus stacking and high-resolution mode (96 MP) support
- Status: Merged into BK-253 and BK-324 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-253 and BK-324.
- Problem: the S5IIX High Resolution mode makes a 96 MP RW2 (12000 x 8000). The app has not been tested at that size: full decode of 96 MP at 8 bytes per pixel RGBA16F is about 768 MB for the source texture, plus the `ByteBuffer.allocateDirect(tw * th * 4)` in Exporter (384 MB).
- Why: the first time Jai opens a high-res file the app may crash or fall back to a poor path.
- Acceptance: a size guard that, for sources over 50 MP, uses tiled sources (stream the demosaic in strips), caps the preview base at half size and exports in horizontal bands directly to the JPEG encoder; test using a synthetic 96 MP DNG; no OOM on the phone.
- Size: L. Files: Exporter.kt, EditorSession.kt, raw_decode.cpp. Risk: high.
- Src: B&H S5IIX product page, 96 MP High Resolution mode (S16, 6 Oct 2026).

### BK-040 [P2] HDR display support in the editor: preview in Ultra HDR / extended range on the S24U panel
- Problem: pipeline is RGBA16F internally but the display transform clamps to SDR 0..1. The S24 Ultra panel is HDR capable; Lightroom shows HDR edit previews.
- Opportunity: `window.colorMode = COLOR_MODE_HDR` and `Window.setDesiredHdrHeadroom` (Android 15) with a scRGB or PQ surface for the editor, only while an "HDR" toggle is on; an exposure/headroom aware tone map.
- Acceptance: with the toggle on and an HDR scene, specular highlights exceed SDR white on screen; export to Ultra HDR JPEG (BK-132); toggle is off by default; no change to SDR output.
- Size: L. Files: MainActivity.kt window setup, EditorScreen GL surface, out.frag. Risk: high (surface formats, battery BK-178).
- Src: Android Ultra HDR docs (S2, 6 Oct 2026).

### BK-041 [P2] Tone mapping curves for high dynamic range scenes: filmic/highlight roll-off option
- Problem: highlights clip hard at 1.0 after the baseline curve forced to white at the shoulder. There is no highlight roll-off control other than Whites/Highlights.
- Acceptance: a "Highlight roll-off" slider (soft shoulder) in Curve or Light; plus the Highlights slider shows no colour shifts on saturated highlights (apply tone to luminance and preserve chroma ratio).
- Size: M. Files: main.frag. Risk: medium.

### BK-042 [P2] Black and white conversion: B&W mixer with channel sliders and film-like response
- Problem: B&W is only saturation -100 and a preset. No channel mix, no red/yellow/green filter look.
- Acceptance: "Black & white" toggle in Colour panel with 8 band luminance sliders (reuse the mixer UI), plus grain/tint presets; before/after matches expectation of a red filter darkening skies.
- Size: S-M. Files: main.frag, ColourPanel.kt, EditRecipe.kt. Risk: low.

### BK-043 [P2] Point colour and colour picker based range editing (Lightroom Point Color)
- Problem: mixer has only 8 bands. Point colour lets the user click a hue and adjust hue/sat/lum with a range.
- Acceptance: tap on the photo picks a colour; sliders Hue shift, Sat, Lum, range; up to 8 points saved; golden fixture.
- Size: M. Files: main.frag, ColourPanel.kt, EditRecipe.kt. Risk: medium.

### BK-044 [P2] Film grain that matches real grain: amplitude by luminance, per-channel, correct size at export
- Problem: grain uses a hash noise cell grid (out.frag) without filtering, so it looks blocky at large sizes and pixel-level at small ones.
- Acceptance: grain is a blurred, band-limited noise sampled at physical size relative to image height (not px), identical between preview and 24 MP export (check at 100 percent crop), luminance-weighted.
- Size: S-M. Files: out.frag. Risk: low.

### BK-045 [P2] Output dither to remove banding in 8-bit sRGB and P3 JPEG exports
- Problem: out.frag writes 8-bit RGBA; smooth skies with strong contrast can band. No dither is applied.
- Acceptance: blue-noise or triangular dither (1 LSB) applied before quantise only for the 8-bit path, off for 16-bit TIFF; gradient test image shows no bands at normal viewing; negligible file size increase for JPEG at Q92.
- Size: S. Files: out.frag. Risk: low.

### BK-046 [P2] Soft proofing and an accurate wide-gamut preview on the S24U screen
- Problem: preview is rendered in sRGB (or P3 if chosen at export) while the S24U panel is wide-gamut with Samsung colour modes (Vivid vs Natural). The app does not set a colour mode, so Vivid may stretch sRGB.
- Acceptance: Settings option "Wide colour" sets `COLOR_MODE_WIDE_COLOR_GAMUT` and the preview is converted to Display P3 so colours match the exported P3 file; an out-of-gamut warning toggle.
- Size: M. Files: MainActivity.kt, EditorSession.kt, out.frag (`uToSrgb` already). Risk: medium.

### BK-047 [P2] Auto tone/auto colour that learns from Jai's own edits
- Problem: AutoTools.autoLight is a fixed histogram heuristic.
- Opportunity: a small regression from image statistics (histogram percentiles, mean chroma, ISO) to the user's final recipe, trained only on the local catalogue (no cloud). Fall back to the heuristic with fewer than 100 edited photos.
- Acceptance: "Auto" suggestions on a held-out set of Jai's edited photos reduce the mean absolute difference from final exposure/contrast versus the heuristic; opt-in; data never leaves the device.
- Size: L. Files: AutoTools.kt, new LearnedAuto.kt, Catalog stats query. Risk: medium (small data); also needs the privacy statement in BK-232.

### BK-286 [P2] Raw clipping and exposure meter in the editor based on raw data (not on the rendered image)
- Opportunity: the histogram is computed from the rendered 8-bit image, so it shows clipping only after the base curve; photographers shoot "expose to the right" and want to know how much raw headroom exists (after highlight recovery, BK-023).
- Acceptance: a toggle "Raw histogram" computes per-channel clipping percentage from the source texture (white level) and displays "Red clipped 0.4 percent" next to the histogram; golden fixture with a known clipped area.
- Size: M. Files: engine.cpp, EditorSession.kt, Controls.kt. Risk: low.

### BK-288 [P2] 3D LUT support (.cube) for creative looks and in-camera look matching
- Opportunity: Panasonic distributes .cube LUTs for V-Log and Lumix Lab looks; photographers use LUTs as presets. A GLES 3.0 3D texture makes this cheap.
- Acceptance: import .cube (17-65 grid) up to 64 MB total, applied after tone and before output with an Amount slider; saved by reference in a recipe (hash + file in app storage), included in backup (BK-144 limits); golden test with an identity LUT and a known swap-channel LUT.
- Size: M. Files: main.frag or out.frag, engine.cpp, new LutStore, Presets. Risk: medium.

### BK-312 [P2] Profile browser with thumbnails and an Amount slider (profiles as separable looks, not baked into presets)
- Opportunity: Lightroom separates Profiles (tone and colour rendering) from Presets and gives Adaptive Color and Adaptive B&W profiles with an Amount slider for raw/DNG files. Rawline has one fixed baseline (BK-033 proposes the selector).
- Acceptance: a Profile row with thumbnail previews of the current photo for Standard, Neutral, Vivid, Mono, camera Photo Styles (BK-252) and imported DCP/LUT profiles (BK-287, BK-288); an Amount slider 0-200 blending the profile with Neutral; profile stored separately from presets; changing profile keeps all slider values.
- Size: M. Files: EditRecipe.kt, BaseCurve.kt, Panels.kt. Risk: medium (depends on BK-033).
- Src: Adobe Help, Apply profiles in Lightroom mobile and Adaptive Profiles pages (checked 6 Oct 2026): https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/apply-profiles.html

### BK-313 [P2] Tone mapper selector: current baseline, a filmic and an AgX-like display transform
- Opportunity: darktable 5.4 (Dec 2025) added an AgX tone mapper alongside Filmic and Sigmoid, with explicit white and black exposure points and an adjustable pivot at 18 percent grey, which many photographers prefer for high-contrast scenes and for gentle highlight colour behaviour. Rawline has a single fitted curve plus Highlights/Whites sliders.
- Acceptance: a Tone mapping option in the Light panel (Standard, Filmic-like, AgX-like) implemented in out.frag with pivot, white and black points; each documented with a reference image; golden scenes for a dynamic-range ramp; default unchanged.
- Size: M-L. Files: out.frag, BaseCurve.kt, EditRecipe.kt, Panels.kt. Risk: medium (colour behaviour must be judged by eye on the phone).
- Src: darktable 5.4 release notes and coverage (Dec 2025): https://www.darktable.org/2025/12/darktable-5.4.0-released/

### BK-314 [P2] Capture sharpening that knows the aperture: diffraction and anti-aliasing filter compensation as part of demosaic
- Opportunity: darktable 5.4 added a Capture Sharpening section to the demosaic module to recover detail lost to diffraction, anti-aliasing filters and other Gaussian-type blur. The EXIF f-number is available per photo; a deconvolution radius can be derived from aperture and pixel pitch (S5IIX 24 MP full frame, about 5.9 micron pitch, diffraction visible from about f/8).
- Acceptance: a "Capture sharpening" default derived from f-number (radius 0.4 to 0.9 px, amount 0-50), applied at the end of the demosaic pass (BK-024) in linear light with a halo clamp; the Detail panel shows it as its own section; fixtures with slanted edges at f/2.8 and f/11 show MTF50 improvement without overshoot above 5 percent.
- Size: M. Files: new demosaic pass or out.frag, Panels.kt. Risk: medium.
- Src: darktable 5.4 coverage (Alternativeto and Linuxiac, Dec 2025).

### BK-326 [P2] "Match camera look": fit a per-photo correction from the raw render to the embedded JPEG (covers Photo Styles and Real Time LUT)
- Opportunity: with Real Time LUT, Photo Style or HLG on, the camera's embedded JPEG shows a look the raw render (our fitted Standard baseline, BK-021) does not reproduce, because those looks are baked into the JPEG and not into the raw data. Lightroom offers camera matching profiles for known styles; Rawline can instead learn the match per photo from data it already has.
- Acceptance: a "Match camera look" action in the Profile row (BK-312) renders the raw at 256 px with a neutral recipe, reads the embedded JPEG at the same size, and fits three 1D tone curves plus a 3x3 matrix (least squares in linear light, with a smoothness limit) that become a recipe-level profile; reports the residual (rms in sRGB units) and refuses above a threshold; golden fixture with a synthetic LUT-graded pair; documented limits (local tone mapping and in-camera noise reduction cannot be matched).
- Size: M. Files: core/render (new LookMatch.kt), EditRecipe.kt (profile field), Panels.kt. Risk: medium.

### BK-327 [P2] HLG photo and 10-bit HEIF from the S5IIX: tone map for SDR, and show the HDR on the S24 Ultra panel
- Problem: the S5IIX records HEIF 10-bit with HLG ("HDR (HLG)" photo style restricts the choice to Standard HLG, Monochrome HLG and Real Time LUT). Rawline's non-RAW path decodes with `ARGB_8888` and `setTargetColorSpace(SRGB)` (EditorSession.decodeFor and Exporter.decode), so the HLG range above SDR white is clipped or mis-tone-mapped and the loupe shows them flat or washed.
- Acceptance: detect HLG files (HEIF colour info or the maker note, BK-325); loupe and thumbnails tone-map HLG to SDR using the system's conversion (draw the 10-bit `Bitmap` with its colour space) and show true HDR when the display has headroom (`COLOR_MODE_HDR`, `Window.setDesiredHdrHeadroom`, BK-040); editing converts to linear working space keeping values above 1.0 (BK-275); export choices: SDR JPEG (tone mapped), HLG HEIC (BK-131) or Ultra HDR (BK-132).
- Size: L. Files: core/cache/PreviewDecoder.kt, EditorSession.kt, Exporter.kt, MainActivity.kt window setup. Risk: high (HDR surfaces, per-device tone mapping). Extends BK-275.
- Src: Panasonic LUMIX S feature pages (HEIF 10-bit and HLG Photo, checked 6 Oct 2026); Android Ultra HDR docs (S2).

### BK-444 [P2] [DONE] The "16 bit" TIFF must carry 16 bits: a ramp test and the linear readback contract (audit AE-018)
- Status: Done in commit ee38b8b (float32 intermediate and output, one quantisation in native code, 1024 px tiles, chunked writer; the golden counts distinct values and compares 16 bit with 8 bit).
- Finding: the output target is RGBA16F display-referred, so above 0.5 only 2048 levels per unit survive and a smooth sky bands; the JNI comment calls it linear but it is encoded.
- Acceptance: a smooth ramp rendered to TIFF16 has at least 4000 distinct levels per channel across the ramp (the half-float path gives about 1500 to 2000); the render target for TIFF is RGBA32F or an integer path; one function name and doc say whether a readback is linear or encoded, and tests assert it (BK-439 uses the linear one); banding test for 8-bit JPEG with and without dither (BK-045).
- Size: M. Files: engine.cpp, Exporter.kt, tests. Risk: low-medium.
- Src: audit-engine.md AE-018.

### BK-446 [P2] Real-file colour regression bands on the CI sample (a non-blocking report, tightening over time)
- Acceptance: CI renders the downloaded S5M2X sample at default settings and records, for large flat regions chosen once by hand (sky, wall, grass if present), the mean ratio of each channel of Rawline's render to the embedded camera JPEG and the mid-grey display value; the numbers go into a job summary and a JSON artefact; a test compares against stored bands (initially wide, then narrowed as BK-021, 022 and 438 land); a change that moves a band by more than 3 percent shows in the summary so a colour change is never silent.
- Size: S-M. Files: tools/golden, CI. Risk: low.

### BK-463 [P2] Texture works at pixel scale and clarity at broad scale: the analysis radii are tied to a 512 px layer (audit AE-020)
- Finding: `kAnalysisEdge = 512`, so the finest blur has a radius of about 30 px at 6000 px; "Texture" boosts mid-frequency structure with halo risk and the baseline Texture +25 is not micro contrast.
- Acceptance: a small-radius blur at working resolution (separable, 9 to 13 taps, or a half-resolution target) feeds Texture; Clarity, Highlights, Shadows keep the 512 layers; a slanted-edge and a fine-texture golden show Texture changing detail at 1 to 3 px without halos beyond 2 percent of the edge step.
- Size: M. Files: shaders/lowres.frag, main.frag, engine.cpp. Risk: medium.
- Src: audit-engine.md AE-020.

### BK-466 [P2] Lens profiles: interpolate vignetting over focal length and aperture, and honour crop factor and calibration aspect (audit AE-027)
- Finding: vignetting jumps to the nearest calibration (the 20 to 60 mm k1 changes from -0.87 to -0.73 between entries) while distortion and TCA are interpolated; `cropfactor` and calibration `aspect` in the XML are ignored, so APS-C entries and non 3:2 captures use a wrong radius unit.
- Acceptance: bilinear interpolation over focal and log aperture of the vignetting coefficients; `cropfactor` and `aspect` read and applied to the radius normalisation; a test sweeping focal length 20 to 60 shows a continuous correction (no step above 2 percent); a chart-free test uses the lens data's own calibration points to reproduce them exactly.
- Size: M. Files: core/render/LensProfiles.kt, tests. Risk: low-medium.
- Src: audit-engine.md AE-027.

### BK-469 [P2] Preview and export must agree: grain seed, AI denoise domain, denoise headroom clamp and detail kernel quantisation (audit AE-044, AE-048, AE-054, AE-055)
- Findings: AI denoise looks different in preview (half-size source) and export; grain differs between preview and export and the seed is never set; denoise output is clamped to display 1.0, discarding highlight headroom; the detail kernel radius is quantised to 1 to 3 tap spacings so at export size every radius from 1 px up is the same 3 px spacing, aliasing the sharpening base.
- Acceptance: a golden comparing a 100 percent crop rendered at preview scale and export scale shows grain statistics within 5 percent and sharpening MTF50 within 10 percent; the grain seed is stored in the recipe; the denoise delta is applied in working space so headroom survives; the kernel spacing scales continuously with a bounded tap count (BK-462).
- Size: M. Files: shaders/out.frag, core/ml/Denoiser.kt, RenderParams.kt. Risk: medium.
- Src: audit-engine.md AE-044, 048, 054, 055.

### BK-048 [P3] Texture and clarity: use a separate high-frequency band from the analysis layers and expose Texture at fine radius for portraits
- Problem: texture uses `bs` (small radius) and clarity `bl` with a midtone weight; there is no negative-texture skin smoothing tuned for portraits.
- Acceptance: Soft Portrait preset uses it and shows less pore loss; no halo at hair edges; done together with BK-029.
- Size: S. Files: main.frag. Risk: low.

### BK-049 [P3] Calibration panel: camera profile hue/sat shifts for primaries (like Lightroom Calibration)
- Acceptance: three primary hue and saturation sliders applied in camera space before the matrix; saved in recipes; used in presets to match the camera JPEG look for specific lighting.
- Size: M. Files: main.frag, EditRecipe.kt, ColourPanel.kt. Risk: medium (depends on BK-022).

### BK-050 [P3] Split toning legacy preset compatibility and preset import from XMP/DNG profile tags
- Problem: users bring Lightroom presets (.xmp, .lrtemplate). None can be imported; Spec says own naming, never copy Adobe assets (so do not bundle), but importing Jai's own presets is fine.
- Acceptance: import crs: settings (Exposure2012, Contrast2012, Highlights2012, Shadows2012, Whites2012, Blacks2012, Temperature, Tint, Vibrance, Saturation, Clarity2012, Texture, Dehaze, ToneCurvePV2012, HueAdjustment*, SaturationAdjustment*, LuminanceAdjustment*, ColorGrade*) to the nearest recipe fields and report unmapped keys.
- Size: M. Files: new core/data/XmpImport.kt, PresetsPanel.kt. Risk: low (mapping approximate; show a notice that results will differ).

### BK-289 [P3] Black point and white point compensation for the output transform when exporting to P3 and 16-bit TIFF, with ICC accuracy test
- Acceptance: a test converting 24 patches through the shader matrix and comparing with an ICC engine result (LittleCMS in a host test) within delta E 1.0; documented in DECISIONS.md.
- Size: M. Files: ColorSpaces.kt, golden. Risk: low.

### BK-328 [P3] Flat profiles (V-Log, Cinelike) and in-camera LUT workflows: detect them and offer the right conversion
- Opportunity: the S5IIX ships with V-Log and photo styles such as Cinelike; a flat embedded JPEG or HEIF looks wrong with the Standard baseline.
- Acceptance: when BK-325 reports a log or flat style on a JPEG/HEIF (or the raw's matching style), the editor offers "Convert from V-Log" using a user-imported .cube (BK-288; Panasonic publishes its V-Log to Rec.709 LUT free to download, licence checked and recorded in THIRD_PARTY.md before bundling anything); otherwise a notice only.
- Size: S-M (after BK-288 and BK-325). Files: Panels.kt, LutStore. Risk: low.
- Src: Panasonic S5IIX feature descriptions (V-Log preinstalled), B&H product page (S16).

### BK-467 [P3] [DONE] floatToHalf rounds values near 65520 up to infinity (audit AE-028)
- Status: Done in commit ee38b8b (floatToHalf clamps to the largest finite half; host test in tools/golden/halfs_test.cpp, run by run-golden.sh). The Kotlin twin is in 3ad30b3.
- Acceptance: after rounding, clamp `(h & 0x7FFF) >= 0x7C00` to `0x7BFF` in `halfs.h`; unit test for 65519, 65520, 65535, NaN, -65520; the same function is the one used for AI output and heal overlay writes.
- Size: S. Files: core/native/engine/halfs.h, tests. Risk: low.
- Src: audit-engine.md AE-028 (reproduced by the audit's probe).

### BK-485 [P3] HSL bands are computed from ProPhoto gamma hue, so band names do not match the hues the eye sees
- Finding: measured through the golden harness on 24 sRGB hues at saturation 0.5: a full green band moves the sRGB pure green patch (hue 0.333) by 78 of 129 levels and reaches full strength at sRGB hues 0.375 to 0.42; cyan-ish 0.458 still moves 25 (W23-shader-correctness.md section 3).
- Acceptance: a look 3 candidate that computes the HSL in an sRGB-like space (or shifts the band centres) so that the pure sRGB primaries and secondaries land on their own band centre within 0.02 hue; Jai compares on three photos before it ships; behind the look version.
- Size: M. Files: shaders/main.frag, tools/golden. Risk: medium (changes every HSL edit).
- Src: measured 6 Oct 2026 (W23).


---

# AREA C: EDITOR UX AND WORKFLOW

### BK-051 [P0] [PARTLY DONE] Undo/redo that survives leaving a panel, with a visible stack and long-press scrub
- Status: Already in the code: Undo and Redo icons in the editor top bar, 200 step history with labels. Left over: one history for brush strokes (BK-300), gestures, long-press scrub.
- Problem: undo exists (EditorState, History panel) but there is no gesture or always-visible undo/redo pair on the dock documented in UI_SPEC for the idle state; unknown if brush strokes, heals and mask edits share one stack with slider changes.
- Why: fear of losing a change makes people avoid trying things; Lightroom has undo/redo always at the top.
- Acceptance: undo/redo icons always visible in the top bar, one unified stack across global, masks, heals, crop; two-finger tap = undo, three-finger tap = redo (optional setting); history entries labelled ("Exposure +0.4"); stack is capped at 200 and coalesces slider drags into one entry; unit test in EditorTest.
- Size: M. Files: EditorState.kt, EditorChrome.kt, EditorScreen.kt, EditorHost.kt. Risk: low-medium.

### BK-052 [P0] Split before/after and side-by-side compare, not only "hold the photo"
- Problem: only press-and-hold shows the original (Settings gesture text). Cannot compare a snapshot with the current state, or view a 50/50 split.
- Acceptance: (1) a Before/After toggle in the top bar with modes Hold, Split slider, Side by side (landscape), Snapshot vs now; (2) split follows pan/zoom; (3) the "original" mode shows the unedited baseline look (BK-033 profile) not a flat raw.
- Size: M. Files: EditorScreen.kt, EditorSession.kt (second recipe pass or two-texture split in main.frag via a uniform), new CompareOverlay. Risk: low.

### BK-053 [P1] [PARTLY DONE] Compute the histogram on the GPU from a 256 x 170 downsample, 4 times a second
- Status: Commit 3ad453d: histogram on its own small targets, at most every 100 ms with a trailing frame (HistogramGate), no second screen frame. Left over: the GPU reduction read back as about 3 KB.
- Problem: `computeHistogram()` reads the rendered frame back and bins on the CPU (EditorSession.kt around line 516), triggered by every setRecipe.
- Acceptance: GPU reduction into a 256-bin per channel buffer (compute or additive-blend point sprites), read back 3 KB; throttled to 4 Hz while dragging, immediate on release; log `histogram_ms`; CPU path removed.
- Size: M. Files: EditorSession.kt, jni_engine.cpp, engine.cpp, new histogram shader. Risk: low-medium.

### BK-054 [P1] Clipping warnings: highlight and shadow overlay (zebra) on the photo plus histogram corner triangles
- Problem: a histogram exists (Controls.kt mentions clipping, verify extent) but no on-image clip overlay.
- Acceptance: press J-style toggle in the histogram; blown highlights red, crushed shadows blue, per channel mode; shown only while holding the histogram or toggled; measured on the final display value, not raw.
- Size: S-M. Files: out.frag (uniform), EditorScreen.kt, Controls.kt histogram. Risk: low.

### BK-055 [P1] [PARTLY DONE] Tap-to-edit on the photo: touch a spot and drag up/down to change the most relevant slider (targeted adjustment)
- Status: Already in the code: PhotoMode.TARGET_MIXER (tap the photo to pick the mixer band). Left over: two-finger drag on the photo for any slider.
- Problem: all adjustments need sliders in a panel; Snapseed's one-finger drag (vertical selects, horizontal changes) and Lightroom's "touch to adjust" are the fastest phone workflows.
- Acceptance: with the tray open, a two-finger vertical drag on the photo changes the focused slider; long-press then drag on the photo picks tone range (Highlights/Shadows/Whites/Blacks) under the finger via luminance; setting to turn off.
- Size: M-L. Files: EditorScreen.kt, Gestures.kt, Panels.kt. Risk: medium (conflicts with pan/zoom and mask handles).
- Src: Snapseed gesture model (general; verify with a hands-on session before design).

### BK-056 [P1] Favourite sliders: pin the six sliders Jai uses most to a one-tap "Quick" panel
- Problem: Light, Colour, Detail, Effects, Optics, Geometry panels mean 3-4 taps to reach a common slider.
- Acceptance: a Quick tab with pinned sliders chosen by long-press on any slider label; defaults Exposure, Highlights, Shadows, Vibrance, Texture, Noise; usage counts auto-suggest.
- Size: M. Files: Panels.kt, EditorState.kt, prefs. Risk: low.

### BK-057 [P1] Slider fine control: long-press to enter fine mode, and accessible +/- steppers
- Problem: sliders are 46 dp tall blocks; dragging over a whole range on a 1440 px wide panel gives about 0.3 units per pixel for a -100..100 slider, adequate, but exposure steps of 0.01 are hard.
- Acceptance: long-press slider = fine mode (10x slower) with haptic tick; tapping the number opens a numeric entry (exists) and the arrow buttons step by one; step sizes per slider documented; haptic at zero crossing and defaults.
- Size: S-M. Files: core/ui/Controls.kt. Risk: low.

### BK-058 [P1] Haptics for detents, reset, and mask handle snaps
- Problem: no haptic feedback anywhere (grep shows none). Android `HapticFeedbackConstants` such as SEGMENT_TICK and CONFIRM exist for exactly this.
- Acceptance: tick at slider zero and defaults, confirm on reset, snap on crop thirds/angle level, reject on error; Settings toggle for haptics (default on) respecting system setting.
- Size: S. Files: core/ui/Controls.kt, GeometryPanel.kt, MaskOverlay.kt. Risk: low.

### BK-059 [P1] Edit while zoomed: persistent loupe at 100 percent with a navigator mini map
- Problem: zoom triggers the full decode; there is no minimap and no way to check sharpening/NR at 100 percent while moving a slider except pan/zoom manually.
- Acceptance: a small navigator thumbnail with a viewport rectangle; "100 percent" button in the top bar; Detail panel offers a split 100 percent loupe inset in the corner.
- Size: M. Files: EditorScreen.kt, Gestures.kt. Risk: low-medium.

### BK-060 [P1] Panel-aware canvas refit animation (FLIP) without resizing the GL surface per frame
- Problem: DECISIONS.md known gap: refit on panel open is a snap because resizing the GL surface per frame is too costly.
- Opportunity: render the photo into a fixed-size surface and transform it with a matrix animation (`SurfaceView` position/scale or draw into a TextureView with scaling), then settle to a crisp re-render after the animation.
- Acceptance: panel open/close animates within 150 ms with no black flash; final frame is pixel-sharp.
- Size: M. Files: EditorScreen.kt, GL view container. Risk: medium.

### BK-061 [P1] Shared element transition from grid thumbnail to loupe to editor
- Problem: DECISIONS.md lists "no shared-element return to the library thumbnail" as a gap; SharedPhoto.kt exists in core/ui (partial).
- Acceptance: tap grid tile expands from the tile; back returns to the right tile even after swiping in the loupe; works with predictive back (BK-246); animation under 200 ms and drops to a cross-fade when frames are late.
- Size: M. Files: MainActivity.kt nav, SharedPhoto.kt, LoupeScreen.kt, LibraryScreen.kt. Risk: medium.

### BK-269 [P1] Make "what I am seeing" obvious: preview and export parity at 100 percent (half-size base versus full decode)
- Problem: the edit base is the half-size LibRaw decode (2x2 binned, about 12 MP) and the full 24 MP decode only loads when zoomed past 0.9 scale or exporting (`ensureFull`). Sharpening and noise reduction are pixel scale sensitive; what Jai judges at 50 percent zoom on the binned image is not what the exported 24 MP file will look like at 100 percent (binning also averages noise).
- Why: Detail panel decisions (sharpen amount, NR) would be wrong, and the export may look noisier than the editor.
- Acceptance: opening the Detail panel (or any zoom past 0.5) triggers the full decode and a small "Full resolution" badge shows state; a one-time hint explains; `uPxScale` parity verified by a golden test comparing a downscaled 24 MP full render with the half-size render for NR/sharpen at equal settings (documented expected difference).
- Size: M. Files: EditorSession.kt, feature/editor/DetailPanel (Panels.kt), docs. Risk: low-medium (memory for the full source, see BK-013).

### BK-275 [P1] Edit non-RAW files at full bit depth (HEIF 10-bit, 16-bit PNG/TIFF) and treat the S5IIX HEIF/HLG files properly
- Problem: `Exporter.decode` and the session decode non-RAW files through `ImageDecoder` with `ARGB_8888` and `setTargetColorSpace(sRGB)` then `rawFromRgba` (8-bit sRGB to linear ProPhoto). The S5IIX can record HEIF 10-bit HLG; those files lose their HDR range and bit depth, so strong edits band and highlights clip at SDR white. JPEGs from the camera are fine at 8-bit but have no highlight latitude.
- Acceptance: decode 10-bit HEIF into `Bitmap.Config.RGBA_F16` (software allocator) in the file's own colour space (BT.2020 HLG/PQ via the bitmap's ColorSpace), convert to linear working space preserving values above 1.0, and tone map for SDR output with an exposure compensation; a test on a real HLG HEIF from Jai's camera; JPEG sources keep the 8-bit path with a note.
- Size: M-L. Files: core/render/EditorSession.kt (decodeFor), Exporter.kt decode, raw_decode.cpp rawFromRgba (add a half-float path). Risk: medium-high (HLG handling; depends on BK-040 for HDR output).
- Src: Android Ultra HDR / HDR display docs (S2, 6 Oct 2026).

### BK-292 [P1] Rotating the phone rebuilds the whole editor: undo history is lost and the raw is decoded again
- Problem: the editor session and `EditorState` live in `remember(photo.id)` inside the activity; the manifest has no `configChanges` and no ViewModel holds them. A rotation (the landscape side panel exists, so Jai will rotate) recreates the activity, releases the GL engine, loses the history list (200 steps) and re-decodes. The code even carries a `GeometrySaver` and a crop-cancel path for "the editor was rebuilt, for instance by a rotation".
- Why: rotating mid-edit silently drops Undo history and costs a second Edit-first-frame.
- Acceptance: either `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"` on MainActivity with Compose reacting to size changes (simplest, test the GL surface recreation path in BK-156), or move `EditorSession`/`EditorState` into a ViewModel scoped to the nav entry; rotation shows no re-decode (`edit_decode_ms` not recorded again), history survives, mask tool and open panel stay.
- Size: M. Files: AndroidManifest.xml, app/EditorHost.kt, EditorState.kt. Risk: medium (GL context and surface lifecycle).

### BK-340 [P1] Editing the 40 picks: show edit progress, jump to the next pick that has no edit, and mark photos done
- Scenario: Jai filters Picks (40 photos), edits one, swipes to the next. The editor neighbour order follows the filtered list (`openEditor` uses `photos`), which is right, but nothing says "7 of 40 edited", and an edited photo that he considers finished looks the same as one he abandoned halfway.
- Acceptance: the editor header shows "7 of 40" for the current filtered list (position) and the grid header shows "12 of 40 edited" for the Picks filter; a "Next unedited pick" action in the editor menu; a Done tick (a label or a new `done` flag with a small badge in the grid, included in backup/XMP as a label) that Jai sets with one tap; "Not done" filter; unit test of the next-unedited search.
- Size: M. Files: feature/editor/EditorScreen.kt (header), EditorHost.kt, LibraryScreen.kt, Library.kt, Db.kt (done in meta). Risk: low-medium (meta column migration, BK-146).

### BK-358 [P1] Rotation and configuration change audit: what survives and what is lost, screen by screen
- Findings from the code: editor panel state survives (`rememberSaveable`) but the editor session, history and mask tool do not (BK-292); `exportSettingsFor` and the paste dialog are `remember` and vanish on rotation; library columns, selection and filters reset (BK-120); the crop workspace restores the geometry by a saved copy; folder and file pickers use launchers that do survive; text fields in dialogs (preset name, snapshot name) are `remember` and are lost.
- Acceptance: a table in docs/ROTATION.md (screen, state, survives: yes/no) maintained as a test list; every item either survives (saveable state or ViewModel) or is documented as intentionally reset; a manual script Jai runs (open each screen, rotate, check) with the result; a decision recorded on `configChanges` versus ViewModel retention (BK-292); export sheet, dialogs, filters, columns, selection and tool pages pass.
- Size: M. Files: MainActivity.kt, LibraryScreen.kt, ExportSheet.kt, PresetsPanel.kt, docs. Risk: low-medium.

### BK-062 [P2] Preset browser with live preview thumbnails of the current photo
- Problem: PresetsPanel lists built-in and user presets; unclear if previews are rendered. Live thumbnails make presets 10x faster to use.
- Acceptance: each preset row shows a 96 px render of the current photo (GPU pass from the cached preview, cached per preset until the photo changes); amount slider 0-150 percent; favourites; categories (Colour, B&W, Film, Portrait, Landscape, Night).
- Size: M. Files: PresetsPanel.kt, EditorSession (offscreen render for thumbnails), Presets.kt. Risk: low-medium (GPU time while panel open).
- Src: Lightroom mobile preset UX and Trending presets, release notes July 2026 (S4, 6 Oct 2026).

### BK-063 [P2] [DONE] Preset amount slider and partial apply (only tone, only colour, only grain)
- Status: Already in the code: PresetsPanel has a Strength slider that re-blends the look onto the edit as it was before the preset. Left over: partial apply (tone only, colour only).
- Acceptance: apply preset at x percent by interpolating each field toward the preset (curves by blending point lists with equal count); applying a preset never touches crop, masks or heals; undo is one step.
- Size: S-M. Files: Presets.kt, RecipeMerge (Library.kt). Risk: low.

### BK-064 [P2] Adaptive presets (subject/sky aware) using the existing AI masks
- Problem: Lightroom's Adaptive presets (2024 on) apply a preset to the subject, sky or background separately. Rawline already has sky/subject/people masks.
- Acceptance: a preset can carry mask slots (Subject, Sky, Background) that create AI mask components when applied, with the models downloaded on demand and a clear "needs model" state.
- Size: M. Files: Presets.kt, AiMasks.kt, MaskLogic.kt. Risk: medium (model readiness latency, BK-082).
- Src: Adobe Lightroom mobile release notes (S4, 6 Oct 2026).

### BK-065 [P2] Edit history timeline with named versions (virtual copies) per photo
- Problem: snapshots exist (SnapshotEntity) but there is no "virtual copy": two different edits of one raw side by side (colour and B&W).
- Acceptance: a version switcher at the top of the editor (Version 1, Version 2, + New), each with its own recipe and mask layers; library shows a stack badge; export can pick all versions.
- Size: L. Files: Db.kt (version column or key suffix), Catalog.kt, EditorHost.kt, LibraryScreen.kt. Risk: high (key scheme change, relates to BK-141).

### BK-066 [P2] Crop tool: composition overlays (thirds, golden, diagonal), straighten by drawing a line, aspect lock memory
- Problem: crop workspace exists with ruler; spec lists Aspect and Geometry trays; "Help" button does nothing (AUDIT open item 7).
- Acceptance: overlays selectable and cycling by tap; "straighten by line" tool; last aspect remembered per session; implement or remove Help (open item 7).
- Size: S-M. Files: GeometryPanel.kt, CropMath.kt. Risk: low.

### BK-067 [P2] Lens Blur / depth blur using a depth model (Lightroom Lens Blur equivalent)
- Opportunity: on-device monocular depth (MiDaS-small or Depth Anything v2 small via LiteRT) feeding a depth range mask (spec lists depth range) and a bokeh blur pass.
- Why: portraits from f/4 lenses look better with background separation; depth range mask is already in the spec but not implemented (MaskType has no DEPTH).
- Acceptance: new model pack with licence recorded in THIRD_PARTY.md (check weights licence, Depth Anything small is Apache-2.0 but the base/large are CC-BY-NC, verify), depth mask type, blur amount and bokeh shape; inference under 2 s on the S24U GPU delegate.
- Size: L. Files: core/ml (new Depth.kt), MaskType, main.frag, Models. Risk: high (model licence and quality).
- Src: Adobe Lightroom release notes (S4, 6 Oct 2026) for the feature benchmark.

### BK-068 [P2] Generative expand / fill is out of scope: record the decision and a non-generative alternative (content-aware edge fill for straighten)
- Problem: Lightroom mobile now has generative expand (S4). Rawline never fakes a model and has no on-device generative model approved.
- Acceptance: DECISIONS.md entry saying no generative fill; a simple "fill empty corners" option for rotate/keystone using LaMa (already integrated) at the border only, offered as an opt-in, so crop constrain (fitCrop) has an alternative to losing frame.
- Size: M. Files: GeometryPanel.kt, Healer.kt, docs/DECISIONS.md. Risk: medium (LaMa on a large border region is slow; limit to 3072 px overlay).

### BK-270 [P2] Filmstrip at the bottom of the editor to jump between photos without returning to the grid
- Problem: the editor supports moving to neighbours (`openEditor(from, delta)`) but there is no visual filmstrip.
- Acceptance: optional filmstrip above the dock (toggle in the top bar), thumbnails from ThumbStore, current highlighted, edited badge, scrolls with the current photo; tapping saves the edit and opens the other photo with prefetch (BK-005).
- Size: M. Files: EditorScreen.kt, EditorHost.kt. Risk: low.

### BK-271 [P2] Match exposure and white balance to another photo (one tap batch tool)
- Opportunity: Lightroom's Match Total Exposures; useful for a burst or timelapse with exposure drift.
- Acceptance: select a reference and targets; computes exposure offsets from median luminance of the base preview and WB offsets from grey-ish regions, applies as recipe deltas with undo; shows before/after values per photo.
- Size: M. Files: AutoTools.kt, LibraryViewModel.kt. Risk: low.

### BK-272 [P2] White balance by sampling a neutral (eyedropper) with a loupe, and a grey-card batch WB
- Problem: a white balance eyedropper exists (`session.sample` and `AutoTools.wbFromSample`, used from EditorScreen), but there is no magnifier ring or readout while placing it, and the result cannot be reused across a series.
- Acceptance: eyedropper with a 5x5 average readout and magnifier ring; "Apply to selection" syncs temp/tint to photos shot within 10 minutes with the same camera; result shown as the Kelvin delta.
- Size: S-M. Files: ColourPanel.kt, AutoTools.kt. Risk: low.

### BK-273 [P2] Auto-set whites and blacks with a clipping preview while holding the slider (Lightroom shift-double-click behaviour)
- Acceptance: hold the Whites or Blacks slider label to show the clip overlay (BK-054); double tap the value sets the point where clipping just starts; undoable.
- Size: S. Files: Panels.kt, AutoTools.kt, main.frag overlay. Risk: low.

### BK-300 [P2] Two undo systems: brush stroke undo is separate from the editor history
- Problem: `MaskingFeature.undoStroke()` undoes the last brush stroke, while the top-bar Undo walks `EditorState.history`. Jai can undo a mask change in one place and not the other, and a stroke undo may not appear in History at all.
- Acceptance: brush strokes commit to the history on stroke end with the label "Brush stroke" (the recipe holds the stroke vectors, so undo is a normal jump); the brush panel's undo button calls the same `state.undo()` while a brush tool is active; layer pixels are regenerated from the recipe (already the design: strokes are vectors); test in MaskLogicTest/EditorTest.
- Size: S-M. Files: feature/masking/MaskingFeature.kt, EditorState.kt. Risk: low.

### BK-302 [P2] Share and import presets as files (and later as a short link or QR)
- Problem: user presets live in Room and in the backup zip only; there is no way to send one to another phone or keep a library of looks outside the app. Snapseed shares looks by QR code.
- Acceptance: "Share preset" writes a small `.rawlinepreset` JSON (recipe without masks/heals/crop, schema version) through the share sheet; "Import preset" opens such a file (also via the VIEW intent, BK-113); name clashes ask Replace/Keep both; unit test round trip.
- Size: S-M. Files: PresetsPanel.kt, Catalog.kt, manifest. Risk: low.
- Src: Snapseed release notes (looks shared by QR code), via APKMirror changelogs (checked 6 Oct 2026).

### BK-319 [P2] Per-panel eye toggles: switch each panel's edits on and off to see what they do
- Opportunity: Lightroom has an on/off switch per panel; Snapseed's edit stack lets you see each tool's contribution. Rawline has hold-to-see-original for everything and Reset per panel, but not "show without Colour only".
- Acceptance: each panel header has an eye icon that renders the recipe with that panel's fields at defaults (`before` in EditorSession already exists for the whole recipe; add a per-group mask), without changing the saved recipe; state shown with an accent dot; included in the screen reader description.
- Size: S-M. Files: Panels.kt, EditorSession.kt, RenderParams.kt. Risk: low.
- Src: Snapseed stacks description via APKMirror changelogs (checked 6 Oct 2026).

### BK-390 [P2] Two-finger tap for undo and three-finger tap for redo in the Develop editor (with a setting)
- Opportunity: the same convention is used by Procreate and Photoshop on iPad, so users who know it expect it. Develop already has visible Undo and Redo buttons (the buttons stay).
- Acceptance: a two-finger tap on the photo (both fingers down and up within 250 ms, little movement) calls `state.undo()`; hold repeats while held (Procreate behaviour: tap and hold for rapid undo); a three-finger tap calls redo; zoom pinch is not mistaken for a tap (movement threshold); disabled while a mask or crop tool owns the photo; Settings toggle (default on); feedback pill "Undo: Shadows +20"; tests with simulated pointer sequences.
- Size: S-M. Files: feature/editor/EditorScreen.kt (photo gesture block), EditorState.kt. Risk: low-medium (conflicts with existing gesture claims).
- Src: Procreate gestures handbook; Adobe Learn Photoshop on iPad (checked 6 Oct 2026).

### BK-448 [P2] Show the histogram by default in the Light panel and put Auto where a reviewer expects it
- Facts: `showHist` starts false (EditorScreen.kt, `rememberSaveable`), the Auto tools live in an overflow menu (AutoTools.kt: auto light, WB, level, perspective). Lightroom, Snapseed and every desktop raw editor open with a histogram and an Auto button visible.
- Acceptance: the histogram is visible at the top of the Light panel by default (a setting turns it off, remembered); an Auto button sits in the Light panel header (auto light) and the Colour panel (auto WB), each undoable as one step and labelled with what it changed ("Auto light: Exposure +0.4, Highlights -20"); the histogram updates on the GPU path (BK-053); screenshot test.
- Size: S-M. Files: feature/editor/EditorScreen.kt, Panels.kt, ColourPanel.kt. Risk: low.

### BK-454 [P2] Leaving the editor must not block on a dimmed spinner (the spec says no modal spinners)
- Facts: `EditorHost.leave()` sets `saving`, waits for the write, and after 150 ms draws an overlay at `OverlayHeavy` with a spinner and "Saving your edits..."; swiping to the next photo already saves in the app scope without waiting. Edits are also autosaved on a 400 ms debounce and on pause (BK-151).
- Acceptance: Back saves in the app scope exactly like the swipe path and returns at once; if the write fails the user sees a toast with Retry (and the recipe stays in memory, BK-353) instead of being trapped behind a spinner; the overlay is removed; a unit test with a fake catalog that fails the first write keeps the edit and retries.
- Size: S-M. Files: app/EditorHost.kt. Risk: low-medium (never lose the last edit: the app-scope job must survive the screen).

### BK-486 [P2] The app has no strings.xml: every user visible word is a literal in Compose code, so copy cannot be reviewed, checked or translated in one place
- Finding: 135 distinct literals match `Text(`, `text =`, `label =`, `title =` and similar patterns; none live in resources (`find` shows no strings.xml outside the SDK). The copy rules check (W27) scans both, but resources are the maintainable home and the onboarding and help copy ship as resources.
- Acceptance: new screens use `stringResource`; a CI count of literal strings in feature code must not grow; existing screens move to resources as they are touched (no big bang); the copy test reads both.
- Size: M over time. Files: feature/*, core/ui. Risk: low.
- Src: W27-onboarding-help.md section 1.

### BK-069 [P3] Tablet/foldable and landscape layouts: adopt adaptive layouts and enforce rotation behaviour
- Problem: DECISIONS lists no tablet or desktop recomposition; Android 16 ignores orientation locks on large screens for apps targeting 36; Jai only has a phone, so keep it small.
- Acceptance: editor and library handle `WindowSizeClass` Expanded with a side panel; rotation keeps selection and open tool (AUDIT item 4: rotation resets library selection); `rememberSaveable` for selection and filter.
- Size: M. Files: LibraryScreen.kt, EditorScreen.kt, MainActivity.kt. Risk: low.
- Src: Android 16 behaviour changes (S6, 6 Oct 2026).

### BK-070 [P3] Keyboard, stylus and external display basics for S24 Ultra S Pen
- Problem: the S24 Ultra has an S Pen. Brush masking with pressure and hover would be a real advantage; no stylus handling exists in BrushLayer/MaskOverlay.
- Acceptance: brush size/flow modulated by `MotionEvent.getPressure` when `TOOL_TYPE_STYLUS`; palm rejection (ignore touch while pen is down); eraser end of the pen = erase mode; keyboard shortcuts (Z undo, backslash before/after, 0-5 rating, P/X flags).
- Size: M. Files: feature/masking/BrushLayer.kt, MaskOverlay.kt, Gestures.kt, EditorScreen.kt. Risk: low-medium.

### BK-274 [P3] More scopes: RGB parade, waveform and vectorscope in addition to the histogram
- Acceptance: a swipe on the histogram cycles Histogram, RGB parade, Waveform; computed from the same 256 px GPU reduction (BK-053).
- Size: M. Files: new shader, Controls.kt. Risk: low.

### BK-320 [P3] Share a look by QR code or short text
- Opportunity: Snapseed generates a QR code for a saved look. A compact recipe (no masks) encodes to a few hundred bytes, which fits a QR code; Jai could hand a look to a friend in person.
- Acceptance: "Show QR" on a preset renders a code (use a small own encoder, no network); "Scan" reads a code with the camera via CameraX or ML Kit barcode scanning; limit to recipes under 1.5 KB compressed; falls back to the file share (BK-302).
- Size: M. Files: PresetsPanel.kt, new QR helper. Risk: low (new permission: camera only for scanning).
- Src: Snapseed changelogs (checked 6 Oct 2026).

### BK-348 [P3] Outdoor visibility: a brightness boost button in the loupe and editor for sunlight
- Problem: Jai shoots outdoors; the S24 Ultra panel is very bright (peak figures are manufacturer claims), but system auto brightness may not rise while the screen shows a dark UI.
- Acceptance: a toggle sets `WindowManager.LayoutParams.screenBrightness = 1f` for the current screen only and restores on leaving; a warning in the editor that colour judgement outdoors is unreliable; off by default.
- Size: S. Files: LoupeScreen.kt, EditorScreen.kt. Risk: low.


---

# AREA D: MASKING AND AI

### BK-071 [P0] [PARTLY DONE] Phone timings for every AI step with a pass/fail table (mask ready under 2 s, tap-select under 300 ms, remove under 10 s)
- Status: Commit 15fb461: the report lists each model pack state and its saved delegate. Left over: the phone timing table and pass/fail targets.
- Problem: PERF.md lists targets for AI mask readiness, tap-to-select and remove a person-sized object, none measured. The GPU delegate is tried first then CPU, and the delegate is remembered per model, which can lock a model onto the slow CPU path after one transient GPU failure.
- Acceptance: Copy report prints per model `delegate`, `ai_*_run_ms` p50/p95, and the reason for any CPU fallback; the remembered delegate is re-probed after an app update; PERF.md table filled.
- Size: S. Files: core/ml/TfModel.kt, PerfLog.kt, docs/PERF.md. Risk: low.

### BK-072 [P0] Model integrity: pin the People model by hash and by version
- Problem: AUDIT open item 8: the MediaPipe `latest/` link cannot be pinned, so a silent upstream change could break the app or supply a different file. CI `fetch.sh` only checks the link is alive.
- Acceptance: download the file once, store the SHA-256 in `Models.PEOPLE`, and change the URL to a versioned path (MediaPipe publishes numbered folders for some models; if none, mirror to a GitHub Release asset of this repo and pin that); CI verifies the hash.
- Size: S. Files: core/ml/ModelStore.kt, tools/models/models.json, fetch.sh. Risk: low.

### BK-073 [P1] Model download robustness: resume, Wi-Fi only default, storage check, background download with notification
- Problem: downloads (up to 430 MB) start on first use; no resume (`.part` deleted on failure), no Wi-Fi/metered check, no free-space check mentioned.
- Why: a 430 MB download on mobile data in the field costs Jai money; a failed download restarts from zero.
- Acceptance: HTTP Range resume, `ConnectivityManager.isActiveNetworkMetered` prompt, `StatFs` check with a clear message, progress in a notification via a user-initiated data transfer job (`JobInfo.setUserInitiated(true)` with the right foreground service type), "Models" screen listing size, licence, delete.
- Size: M. Files: core/ml/ModelStore.kt, Settings, app manifest. Risk: low-medium.

### BK-074 [P1] Models screen in Settings: size on disk, delete, re-download, licence, last used
- Acceptance: lists the five packs, sizes, ready state, delegate in use; delete and re-download buttons; total model storage shown in the storage screen (BK-186).
- Size: S. Files: feature/settings, core/ml/ModelStore.kt. Risk: low.

### BK-075 [P1] Warm-start models at idle: lazily load the SAM encoder after the user enters Masking, not when they choose the tool
- Problem: MobileSAM encoder takes 1.3 s on x86 CPU, a visible delay for tap-to-select; the spec wants tap-to-select under 300 ms.
- Acceptance: encoder embeddings computed in the background as soon as the editor opens (if the SAM pack is present and battery/thermal is fine), cached per photo key + crop frame (AiMasks already has a content probe); first tap returns within 300 ms; cancelled on exit.
- Size: M. Files: core/ml/AiMasks.kt, EditorSession.kt. Risk: medium (battery BK-177, memory).

### BK-076 [P1] Mask edge refinement: feather/refine with a guided filter on the full-res preview, and a "Refine edge" slider per AI mask
- Problem: AI masks are low resolution alpha (1024 layer texture, resampled). GuidedFilter exists on CPU. Lightroom shipped edge refinement for masks (S4 mentions masks with edge refinement).
- Acceptance: each bitmap mask gets Feather and Refine (guided filter radius/eps) controls; hair against sky shows less halo; refinement computed once and cached; rendered at the layer's native size.
- Size: M. Files: core/ml/GuidedFilter.kt, MaskTray.kt, MaskStore.kt. Risk: medium.
- Src: Adobe Lightroom release notes, "masks with edge refinement" (S4, 6 Oct 2026).

### BK-077 [P1] Raise the mask layer resolution for large exports (layer texture is a fixed 1024 x 1024)
- Problem: all bitmap layers are resampled to 1024 x 1024 in one array texture; export of 24 MP samples a 1024 mask over 6000 px, so brush edges and AI mask edges get soft or blocky at 100 percent.
- Opportunity: 2048 per layer (array texture with 16 layers at 2048 is 16 x 4 MB R8 = 64 MB) or sparse layers sized to the content; use bicubic sampling; keep brush strokes as vectors and rasterise at export resolution per tile.
- Acceptance: golden export with a fine brush stroke at 24 MP has edge softness within 2 px of ideal; texture memory budget documented.
- Size: M. Files: params.h (kLayerTex), engine.cpp, MaskStore.kt, Exporter.kt. Risk: medium.

### BK-078 [P1] Detail (sharpen, noise reduction) as local adjustments inside masks
- Problem: DECISIONS: "Detail (sharpen, noise) is global only". Lightroom lets you add noise reduction or sharpness to a mask (the classic eyes-sharpen, sky-denoise workflow).
- Acceptance: each mask has Sharpness and Noise sliders; the detail pass samples a "detail mask" texture (render mask alpha into an R8 buffer in `out.frag`'s space); golden scene "sky denoise".
- Size: M-L. Files: out.frag, main.frag (write mask to alpha or a second target), EditRecipe.kt Adjust, MaskTray.kt. Risk: medium-high (extra render target and param layout change; params.h mirror test).

### BK-079 [P1] Colour range mask in a perceptual space with a picker loupe, and a live mask preview overlay
- Problem: colour range uses Euclidean distance in gamma RGB to the target (main.frag), which is not perceptual and can't separate similar greens.
- Acceptance: distance in OKLab; sampling a 5x5 average on tap; overlay preview during picker drag; range and softness sliders; test with a colour checker fixture.
- Size: M. Files: main.frag, MaskTray.kt. Risk: low-medium.

### BK-080 [P1] Depth range mask using on-device depth estimation (spec item not done)
- Problem: SPEC lists depth range masks; `MaskType` has no depth.
- Acceptance: see BK-067 for model; mask type DEPTH(6) with near/far/feather; golden fixture with a synthetic depth ramp.
- Size: L. Files: MaskType (EditRecipe.kt), params.h, main.frag, core/ml. Risk: high.

### BK-081 [P1] Mask a face region: eyes, skin, teeth and hair via the existing People parts, with per-part sliders (skin smoothing, eye brighten)
- Problem: people parts exist (MediaPipe multiclass: hair, body skin, face skin, clothes, others) but there is no eyes or teeth part and no portrait retouch presets.
- Acceptance: Portrait tools: "Smooth skin", "Brighten eyes", "Whiten teeth" built from face landmarks (MediaPipe Face Landmarker, check licence) creating masks with sensible default adjustments; works for up to 4 faces.
- Size: L. Files: core/ml, MaskTray.kt, Presets. Risk: medium-high (extra model, privacy statement BK-232).
- Src: Adobe Lightroom mobile release notes, blemish removal with AI (S4, 6 Oct 2026).

### BK-082 [P1] Mask readiness UX: show "Preparing" states, never block the UI, and keep a one-line failure reason
- Problem: AI masks require model download and a first run; there is no spec'd skeleton for the states (Not downloaded, Downloading, Preparing, Ready, Failed).
- Acceptance: mask picker shows each AI option with its state and cost ("170 MB"), failures show a reason and Retry, cancelling is possible; no modal spinner (SPEC rule).
- Size: S-M. Files: feature/masking/MaskTray.kt, MaskUi.kt. Risk: low.

### BK-083 [P1] Subject select quality: combine ML Kit Subject Segmentation with MobileSAM refine and let Jai add/subtract points
- Problem: `mlkitSubject = 16.0.0-beta1` is a beta Google Play services dependency (model delivered through Play Services, which a sideloaded app on a Samsung can use but depends on Play availability and a beta API).
- Acceptance: either replace with MobileSAM-only subject selection (no Play services) or keep and add a fallback; user can add positive and negative points; selected result goes through BK-076 refine.
- Size: M. Files: core/ml/AiMasks.kt, build config. Risk: medium (dependency decision; record in DECISIONS.md).

### BK-084 [P1] Brush: smoothing, pressure, flow build-up, auto mask (edge aware) quality, and brush size preview ring
- Problem: BrushLayer has strokes with size/feather/flow/erase/autoMask; an eraser and ring exist (commit log). Auto mask quality and build-up behaviour are untested on a device.
- Acceptance: stroke smoothing (Catmull-Rom with 1 px lag), flow accumulation matches expectation (3 passes at 30 percent reach about 66 percent), auto mask uses a guided filter on the local colour, unit tests in MaskLogicTest for flow math; S Pen pressure (BK-070).
- Size: M. Files: BrushLayer.kt, MaskLogic.kt. Risk: low.
- Src: stabilisation methods (moving average, exponential moving average, lazy radius or pulled string) described by Toon Boom and the Lazy Brush library (checked 6 Oct 2026): https://docs.toonboom.com/help/harmony-24/premium/drawing/about-stabilization.html ; see BK-391 for the concrete input pipeline.

### BK-294 [P1] Changing the AI denoise amount re-decodes and re-runs the whole model; it also runs again for the full-size decode
- Problem: `EditorHost` calls `session.reloadSource()` whenever the committed `aiDenoise` or `aiDenoiseAmount` changes, which decodes the raw again and runs the NAFNet tiles again (`maybeDenoise`), and `ensureFull` runs it a second time when Jai zooms to 100 percent. The amount is a simple blend ((denoised minus original) high-pass, scaled), so it does not need a new model run.
- Why: moving a slider that costs minutes of CPU/GPU is unusable and heats the phone.
- Acceptance: run the model once per (photo key, geometry-independent raw) and store the detail delta as a half-float layer (or a tile cache on disk); the amount is applied on the GPU as `source + amount * delta` in the shader (new sampler) so the slider is live; the half-size and full-size delta are cached separately; toggling off frees the layer; `denoise_total_ms` recorded once per photo; a golden test shows amount 0 equals no denoise and amount 100 matches the CPU result within 1 level.
- Size: L. Files: EditorHost.kt, EditorSession.kt, core/ml/Denoiser.kt, main.frag, engine.cpp. Risk: medium-high.

### BK-295 [P1] [PARTLY DONE] Denoiser memory and CPU cost: the tile loop keeps every output in RAM and converts colour per pixel in Kotlin
- Status: Commit 3ad30b3: tile results are written back one row late (two rows held), per tile scratch arrays reused, host test of the order. Left over: the per pixel colour conversion in Kotlin.
- Problem: `Denoiser.run` collects `results` (each tile's `FloatArray(cw*ch*3)`) for the whole image before writing, so a 24 MP image holds about 24 M x 3 floats = 288 MB in addition to the raw (about 200 MB for 24 MP RGBA half) and the model buffers; per tile it allocates several 256 x 256 float arrays, runs a Kotlin `ColorSpaces.workingToDisplay`/`displayToWorking` per pixel, and a box filter per channel. With stride 192 a 24 MP image is about 670 tiles; at the 1.8 s per tile measured on the x86 CPU that is 20 minutes if the GPU delegate is not used.
- Acceptance: write each tile into the raw as soon as its neighbours have been read (keep a one-row halo buffer) or double-buffer by tile rows; move the colour conversions into a small native routine (`Native.rawReadDisplay/rawWriteDisplay`); reuse arrays; peak extra memory under 60 MB; a measured `denoise_total_ms` for 12 MP and 24 MP on the phone (BK-001 table); progress and cancel keep working.
- Size: M. Files: core/ml/Denoiser.kt, core/native (raw read/write helpers), ColorSpaces.kt. Risk: medium.

### BK-296 [P1] One GPU delegate failure makes a model CPU-only forever
- Problem: `TfModel.create` writes `accel_<name> = CPU` to preferences after any GPU failure (including a transient out-of-memory while the editor holds large textures) and the saved choice is trusted from then on (`order = listOf(saved, CPU)`). The warm-up with zero inputs also runs a full inference on every model creation.
- Why: a model stuck on the CPU is 5 to 10 times slower (SAM encoder 1.3 s on x86 CPU) and Jai would never know.
- Acceptance: record the failure reason and the app version with the saved choice; re-probe the GPU after an app update, after 7 days, or via a Settings button "Retry GPU for AI"; classify errors (unsupported op: keep CPU; out of memory or EGL failure: try again next time); show the delegate in use next to each model in the Models screen (BK-074) and in the report (the report already lists delegates).
- Size: S-M. Files: core/ml/TfModel.kt, feature/settings. Risk: low.

### BK-298 [P1] Select object: multiple points, negative points and a box, and use the model's other mask proposals
- Problem: `AiMasksImpl.objectAt` sends one positive point (`point_labels = 1`) to the MobileSAM decoder and uses the first mask output only (`mapOf(0 to masks, 1 to scores)`); scores are ignored. One tap often selects a part (a shirt, not the person) with no way to fix it.
- Acceptance: tap adds a positive point; a long-press adds a negative point; a drag draws a box prompt; the decoder is re-run (about 0.1 s on CPU per the notes) with all prompts against the cached embedding; offer the three SAM hypotheses as small thumbnails (largest score first) and let Jai cycle; undo last point; unit test with a fake decoder checking the prompt tensors.
- Size: M. Files: core/ml/AiMasks.kt, feature/masking/MaskTray.kt, MaskingFeature.kt. Risk: low-medium (decoder input names: the code already looks inputs up by name).

### BK-460 [P1] [PARTLY DONE] Heal, clone, remove and AI masks must use the same lens geometry as the picture (audit AE-004, AE-005, AE-017, AE-026)
- Status: Commit ee38b8b: heal overlay before the vignetting gain (AE-005), Healer places strokes through the lens polynomial (AE-004). Left over: AI mask input frame with lens and optics (AE-017), heal overlay colour conversion for finished pictures (AE-026).
- Findings: Healer converts strokes to source coordinates without the lens distortion the shader applies (about 100 to 150 px error at a 20 mm corner of a 24 MP shot); the heal overlay is composited after lens vignetting and TCA but is built without them (a dark blob near corners); AI mask input frames ignore lens and manual distortion (mask edges displaced by up to about 4 percent of frame height); heal and denoise colour conversions assume the camera base curve for JPEG, HEIC and PNG, so patches on those files come out too dark.
- Acceptance: `Geo.frameToSource` receives `RenderParams.lensDistFor(recipe, lens)` in both Healer call sites; the overlay is composited before the vignette divide or built with the gain removed; `renderFrame` takes the lens and optics so the AI sees what the user sees; `ColorSpaces` has a `useBase` flag passed from Healer, HealOverlay and Denoiser; tests: a stroke at a corner lands on the touched object within 2 px under a strong profile (host geometry test), a golden with a corner patch, a JPEG patch brightness test.
- Size: M. Files: core/ml/Healer.kt, core/render/EditorSession.kt, ColorSpaces.kt, main.frag. Risk: medium.
- Src: audit-engine.md AE-004, 005, 017, 026.

### BK-085 [P2] [PARTLY DONE] Mask groups, duplicate to another photo, and a "mask library" (save and reuse a gradient or brush recipe)
- Status: Already in the code: duplicateMask, rename, show/hide, invert per mask. Left over: save a parametric mask set as a reusable item.
- Problem: `RecipeMerge` pastes masks but does not allow saving mask sets as reusable items; AI masks are image-specific.
- Acceptance: "Save as preset" for a parametric mask set (linear/radial/range) with its adjustments; apply to another photo without AI layers; masks reorder by drag; rename.
- Size: M. Files: MaskTray.kt, Presets.kt, Library.kt RecipeMerge. Risk: low.

### BK-086 [P2] [DONE] Raise the 8-mask and 16-layer limits only if needed; show the limit and a clear message
- Status: Already in the code: MaskingFeature.limitMessage says "A photo can have 8 masks. Delete one to add another." Left over: measure render cost per mask.
- Problem: kMaxMasks = 8 and kMaxLayers = 16 are silent engine limits; what does the UI say at the 9th mask?
- Acceptance: adding past the limit shows "8 masks is the maximum on this phone" (not silent failure); analysis of shader cost per mask measured (`frame_render_ms` vs mask count) and recorded in PERF.md; unit test for the limit message.
- Size: S. Files: MaskLogic.kt, MaskTray.kt. Risk: low.

### BK-087 [P2] Remove tool: object remove quality and speed (LaMa 512 px tile takes 5.8 s on x86 CPU)
- Problem: LaMa dilated at 512 px tiles; remove "person-sized object from 24 MP under 10 s" untested; DECISIONS say tiled.
- Acceptance: measure on phone; use FP16 GPU delegate; run at 512 px crop around the stroke (not the full photo), blend with Poisson-like feather; show a progress bar with cancel (no modal); multiple strokes batch into one inference when overlapping.
- Size: M. Files: core/ml/Healer.kt, RemoveFeature.kt. Risk: medium.

### BK-088 [P2] Heal and clone: source preview, aligned sampling, and feathered texture-matched healing without the model
- Problem: heal/clone write into a half-float overlay; no live preview of the sample location while dragging (verify in RemoveFeature).
- Acceptance: draggable source circle with a live preview of the result; "Visualise spots" (an edge-enhanced view like Lightroom) to find dust; spot removal for sensor dust with auto-source selection; strokes are vectors, stored patch PNG (exists).
- Size: M. Files: RemoveFeature.kt, Healer.kt. Risk: low-medium.

### BK-089 [P2] Dust spot finder: automatic detection of sensor dust across a series
- Opportunity: sensor dust is the same pixel position across a shoot; detect candidates by local low-frequency dark blobs, heal all and copy the positions to every photo from the same session (same camera + lens + date).
- Acceptance: "Find dust" in Remove tool marks candidates; apply to one or sync to a selection of photos with the same size; false positive rate on 50 test photos under 10 percent.
- Size: M-L. Files: core/ml/Healer.kt, Library sync action. Risk: medium.

### BK-090 [P2] AI denoise: quality validation and an honest "Preview denoise" on a 100 percent crop
- Problem: DECISIONS: the NAFNet output is used only for fine detail ((denoised - original) minus its own low frequencies), tiled 256 px; model run cost 1.8 s per 256 tile on x86 CPU. For 24 MP that is about 370 tiles.
- Why: if full-image AI denoise takes minutes on the phone, Jai will not use it; he needs a fast preview and a background full run.
- Acceptance: measure `denoise_total_ms` on the phone for 24 MP; run only on the visible 100 percent region for the preview (within 2 s), and the whole image as a cancellable background job whose result is cached by (photo key, amount) and used by export; show estimated time.
- Size: M. Files: core/ml/Denoiser.kt, EditorSession.kt, Exporter.kt, DetailPanel. Risk: medium-high (export time and thermal, BK-177).

### BK-091 [P2] Raw-domain AI denoise on the Bayer data (Lightroom Denoise equivalent) instead of post-demosaic NAFNet
- Problem: NAFNet is applied on linear ProPhoto RGB after demosaic (grey DnCNN too smoothed). Lightroom's Denoise works on raw mosaic and creates a new DNG.
- Opportunity: train/choose a model on 4-plane packed Bayer (RGGB); no public permissive RAW denoiser is known to the author; likely requires training data and a licence review, so treat as research: Spike S: 2 days to evaluate options and decide (document in DECISIONS.md).
- Size: L (research first). Files: core/ml, raw_decode.cpp. Risk: high.
- Src: Adobe Lightroom Denoise listing in release notes (S4, 6 Oct 2026).

### BK-092 [P2] Sky replace-lite: sky mask presets (Sky darken, Sky blue shift, graduated look) and a "Select Sky" gradient blend
- Opportunity: sky mask exists (SegFormer ADE20K). One-tap presets built on it make landscape edits faster: Darken sky, Add colour, Dehaze sky only.
- Acceptance: three preset buttons in the sky mask tray that create the mask with preset adjustments; edge refinement from BK-076.
- Size: S. Files: MaskTray.kt, Presets. Risk: low.

### BK-093 [P2] Mask overlay modes: red overlay, white-on-black, black-on-white, and density control
- Problem: `uShowMask` tints the mask red with a fixed strength.
- Acceptance: overlay mode cycle, opacity slider, long-press on the mask to solo; no GL surface resize.
- Size: S. Files: main.frag, MaskUi.kt. Risk: low.

### BK-276 [P2] Persist the copied-edits clipboard and the last-edited look across process death
- Problem: `copied` and `lastEdited` are `MutableStateFlow`s in the ViewModel; Android may kill the process while Jai moves to the camera or Files, and the clipboard is gone.
- Acceptance: store the copied recipe JSON in prefs (it is small; mask layer keys need their files retained, so store with references that BK-153 garbage collection treats as in use); "Paste" remains enabled after a restart.
- Size: S. Files: LibraryViewModel.kt, Catalog.kt. Risk: low.

### BK-297 [P2] Spike: LiteRT CompiledModel with the Qualcomm NPU on the S24 Ultra for SAM, segmentation and denoise
- Opportunity: DECISIONS.md says QNN was not added because it needs a separate vendor runtime package. LiteRT's CompiledModel API now documents Qualcomm AI Engine Direct support, including the Snapdragon 8 Gen 3 (SM8650) used by the S24 Ultra, with on-device (JIT) compilation and zero-copy hardware buffers. The NPU could run the SAM encoder, SegFormer and NAFNet far faster and cooler than the GPU delegate.
- Acceptance: a one-day spike: a debug screen runs the SAM encoder with GPU delegate vs CompiledModel NPU on the phone and prints ms and success; if the NPU path works without extra packages (or with a packaged runtime under 30 MB), record numbers in docs/PERF.md and a decision in DECISIONS.md; otherwise record why not.
- Size: S (spike) / L (migration of TfModel to CompiledModel). Files: core/ml/TfModel.kt, core/ml/build.gradle.kts. Risk: high (API maturity, delegate packaging, 16 KB alignment BK-229).
- Src: Google AI Edge, LiteRT NPU / Qualcomm AI Engine Direct pages (checked 6 Oct 2026): https://developers.google.com/edge/litert/next/npu , https://developers.google.cn/edge/litert/next/qualcomm

### BK-311 [P2] Local point masks (tap to place an adaptive control point, like Snapseed Selective and Capture One local adjustments)
- Problem: masks are brush, linear, radial, colour range, luminance range and AI. There is no quick "tap here and adjust what looks similar nearby" tool, which is the fastest local edit on a phone.
- Acceptance: a Point mask type: tap places a point with a radius ring; the mask is a smooth falloff multiplied by colour/luminance similarity to the sampled pixel (reuse the colour range shader term); one slider set (Exposure, Contrast, Saturation, Structure/Texture); up to 4 points per mask component; golden scene "point on sky".
- Size: M. Files: EditRecipe.kt (new MaskType), params.h, main.frag, MaskTray.kt, MaskOverlay.kt. Risk: medium (params mirror test and recipe version flag).
- Src: Snapseed Selective tool descriptions via APKMirror changelogs (checked 6 Oct 2026).

### BK-316 [P2] Remove tool: also select the shadow or reflection of the chosen object
- Opportunity: Camera Raw's Remove tool "Detect Objects" now selects any shadow or reflection with the object (2026 releases). Rawline's remove asks the user to paint the whole region.
- Acceptance: after tapping an object (SAM), a "Include shadow" toggle expands the mask along the darker, similar-hue region adjacent to the object on the ground side (heuristic first: luminance drop connected to the object within 1.5 object heights; model later); preview overlay; user can brush to adjust.
- Size: M. Files: core/ml/AiMasks.kt, Healer.kt, RemoveFeature.kt. Risk: medium (heuristic quality).
- Src: Adobe Camera Raw release notes 2026 (checked 6 Oct 2026): https://helpx.adobe.com/au/camera-raw/using/whats-new/release-notes.html

### BK-391 [P2] Mask brush input pipeline: use every batched point, add a stabiliser slider and a predicted preview
- Problem: BrushLayer strokes record points from pointer events (ToolGestures.onMove receives one position per event); batched historical points are likely dropped, which makes fast strokes polygonal; there is no stabiliser (BK-084 mentions smoothing without detail).
- Acceptance: the brush consumes historical samples when available; a Stabiliser slider in the brush panel (0 to 100, default 15) with an EMA and a lazy-radius mode (see BK-373 for the algorithms), applied while recording so undo and the saved vector strokes keep the smoothed path; an optional predicted tip drawn only on the overlay; unit tests in MaskLogicTest with synthetic noisy lines (smoothed path deviates under 1.5 px and corners survive); same code shared with Studio (BK-373) to avoid two implementations.
- Size: M. Files: feature/masking/BrushLayer.kt, MaskLogic.kt, MaskTray.kt. Risk: low.
- Src: lazy brush and stabilisation descriptions (checked 6 Oct 2026): https://docs.toonboom.com/help/harmony-24/premium/drawing/about-stabilization.html

### BK-453 [P2] AI readiness at the start of editing, not when the mask tray opens (the spec target is 2 s after editing starts)
- Fact: `MaskingFeature.onEnter` calls `ai.prepare()` (SAM encoder) when the tray appears; the spec and PERF.md target "AI mask readiness under 2 s after editing starts, tap-to-select under 300 ms".
- Acceptance: when the editor reaches READY and the SAM pack is downloaded and the work policy allows (BK-177, thermal BK-176), `prepare()` runs in the background once per photo and geometry; opening the mask tray shows tap-to-select ready within 300 ms in the common case; cancelled when the editor closes; `ai_prepare_ms` and `ai_ready_after_edit_start_ms` timers; battery and heat cost measured (BK-178).
- Size: S-M. Files: feature/masking/MaskingFeature.kt, app/EditorHost.kt, core/ml/AiMasks.kt. Risk: low-medium.

### BK-461 [P2] [DONE] Colour range and luminance range masks compare in a different domain from the picker (audit AE-015)
- Status: Done in commit ee38b8b (colour and luminance range masks compare in the display domain the picker reads).
- Finding: the picker stores the display colour (after the base curve and OETF, 8-bit) while the shader compares `toGamma(c)` of the linear working value, so the exact picked colour sits about 0.22 per channel from itself at mid-grey and is only partly selected at the default range.
- Acceptance: the shader converts working to display (base curve plus OETF) for mask types 4 and 5, or `sample()` returns working-gamma values; a test: pick any patch of fixture (2) and the colour range mask at the default range selects that patch fully (alpha at least 0.95) and a patch of delta E above 25 not at all; the luminance bar labels match the domain used.
- Size: S-M. Files: shaders/main.frag, EditorSession.kt sample, MaskingFeature.kt. Risk: low.
- Src: audit-engine.md AE-015.

### BK-094 [P3] [PARTLY DONE] Invert/combine masks with a mask-math UI (Add, Subtract, Intersect already in model): make them visible and previewable per component
- Status: Already in the code: each mask part has a Segmented Add/Subtract/Intersect control in MaskTray. Left over: a small preview thumbnail per part and drag to reorder.
- Acceptance: each component shows its operation as an icon chip, with a mini preview alpha thumbnail; reorder components; tooltip help text (BK-195).
- Size: S-M. Files: MaskTray.kt. Risk: low.

### BK-095 [P3] Gradient masks: range sliders in the same gesture (luminance range inside a linear gradient), and a gradient angle snap
- Acceptance: linear/radial masks can be refined with a luminance or colour range (component intersect) with a one-tap "Refine with range"; angle snap to 0/45/90 with haptic.
- Size: S. Files: MaskLogic.kt, MaskTray.kt, MaskOverlay.kt. Risk: low.

### BK-317 [P3] Research: on-device super resolution (2x) for crops and high-ISO files
- Opportunity: Lightroom and Camera Raw offer Super Resolution (2x linear, 4x pixels). A phone-sized model (for example a compact ESRGAN/SwinIR variant) would let Jai enlarge a crop; quality and licence differ widely.
- Acceptance: a one-day spike recording candidate models, licence (must allow personal use, record in THIRD_PARTY.md), size, ms per 256 px tile on the GPU delegate, and a before/after on 5 real crops; decision in DECISIONS.md whether to build; if built, same tiled engine as the denoiser with a cache (BK-294 style) and an export option "Enhance 2x".
- Size: S (spike) / L. Files: core/ml, Exporter.kt. Risk: high (time per image, artefacts on fine texture).
- Src: Adobe Camera Raw release notes and a 2026 Super Resolution review (checked 6 Oct 2026).

### BK-318 [P3] Research: reflection removal for photos taken through glass
- Opportunity: Camera Raw (June 2026, v18.4) improved its Reflection Removal tool. Jai may photograph through windows when travelling; no open permissive model of known quality is on the list.
- Acceptance: a spike note in docs/DECISIONS.md: candidate models, licence, size, and a verdict (skip unless a small permissive model works on 5 sample photos).
- Size: S (spike). Files: docs. Risk: low.
- Src: Adobe Camera Raw release notes, June 2026 (checked 6 Oct 2026).

### BK-322 [P3] Record the model version that produced each AI mask, denoise and removal, and offer "Update AI edits"
- Opportunity: Camera Raw (June 2026) lets users view and update edits made with AI tools such as Super Resolution and Denoise when the tools improve. Rawline stores mask PNGs and heal patches without noting which model version made them.
- Acceptance: each bitmap mask component and heal op stores `model` (id and version from `Models`), shown in the mask info; when a model pack version changes, a "Refresh AI masks" action re-runs them for the current photo (keeping manual refinements as a separate layer); never automatic.
- Size: M. Files: EditRecipe.kt (optional field, version flag), AiMasks.kt, MaskTray.kt. Risk: low.
- Src: Adobe Camera Raw release notes, June 2026 (checked 6 Oct 2026).

### BK-336 [P3] Research: feed LiteRT from GPU buffers to skip the bitmap round trip for AI inputs
- Problem: AI inputs are made by `renderFrame` (GPU render, `glReadPixels` to a byte array, a software `Bitmap`), then scaled with `Bitmap.createScaledBitmap`, and converted to a float `ByteBuffer` on the CPU (`rgbBuffer`, `encodeLocked`): several copies and CPU loops per run (SAM encoder builds 1024 x 1024 x 3 floats pixel by pixel).
- Opportunity: LiteRT's CompiledModel documents zero-copy hardware buffers (S18), so a GL render target shared as an AHardwareBuffer could feed the encoder directly.
- Acceptance: a spike comparing `renderFrame` + CPU preprocessing time with a GPU-side resize/normalise pass on the phone; adopt only if the saving exceeds 100 ms for SAM or sky; recorded in docs/PERF.md.
- Size: S (spike) / L. Files: core/ml/AiMasks.kt, EditorSession.kt, engine.cpp. Risk: high.
- Src: Google AI Edge LiteRT NPU pages (S18, 6 Oct 2026).


---

# AREA E: LIBRARY WORKFLOW (ratings, flags, collections, search, filters, import, folders)

What exists: sources (camera roll via MediaStore, other albums, imported files, one SAF folder plus 8 recents), filter by minimum rating, flag, edited, camera, 4 sort orders, colour labels, multi-select, copy/paste/sync edits, batch export queue, backup zip, optional XMP sidecars. What is missing (checked by reading LibraryViewModel, Library.kt, Db.kt): text search, collections/albums, keywords, captions, stacks, duplicate handling, delete/reject-to-trash, date and lens filters, map/geotag, SD card import, "recently imported", compare/survey mode.

### BK-096 [P0] [PARTLY DONE] Import from SD card or USB-C reader: copy RW2/JPG into a dated folder with a progress queue, skip duplicates, verify, then eject prompt
- Status: Done on main: the engine (core/data/ingest: SHA-256 verify, .part then rename, resume by ledger, cancel, disk full, a card pulled mid file, card file time kept, speed class), the SAF card walk, a dataSync service and one Import from a card entry in the Add photos menu. Only host tested (26 tests); the SAF walk, the service and the menu are compiled, not run. Left for W17: the Preview only badge and editor text (D7), Settings toggle, USB attach.
- Problem: Jai shoots a Panasonic S5IIX; the real workflow is card to phone. Today the app can read a folder in place via SAF or list MediaStore; there is no ingest (no copy, no rename, no duplicate detection, no checksum).
- Why: this is the first thing every session starts with. Lightroom Mobile has "Add photos from a card". Without it Jai must use another file manager.
- Acceptance: Import screen detects a removable volume (`StorageManager`/`Intent.ACTION_MEDIA_MOUNTED` or the SAF tree of `DCIM/`), shows thumbnails of new files (embedded previews, nothing copied yet), selectable; destination folder pattern `Pictures/Rawline/{yyyy}/{yyyy-MM-dd}`; copy with `Files.copy`/streams with SHA-256 or size+time check; skips files already in the catalogue by (name, size, taken time); shows throughput; a foreground user-initiated transfer (BK-228 FGS type); an "Import done" summary with a count and "Eject card" hint.
- Size: L. Files: new feature/import module, core/data/DeviceScanner.kt, app wiring, manifest. Risk: medium (SAF speed on large cards, UHS-II throughput is not reachable through SAF; with all-files access use File paths for the card when `/storage/XXXX-XXXX` is readable).
- Src: Lightroom on Android has no direct card import (files must be copied first with a file manager, Adobe community threads, checked 6 Oct 2026), so a good card import is a clear advantage: https://helpx.adobe.com/ee/lightroom/mobile/add-and-capture-photos/add-and-import-photos/import-photos-from-card-or-cameras.html

### BK-097 [P0] Reject-to-trash and delete with undo (MediaStore trash and a 30 day bin)
- Problem: Flags exist (pick/reject) but no delete action; nothing removes rejected files, so a card dump of 800 photos stays 800.
- Acceptance: "Delete rejected" action with a count and size confirmation; uses `MediaStore.createTrashRequest` (API 30+) for device photos and DocumentsContract.deleteDocument for SAF; Rawline keeps an in-app Recently Deleted view mapping to the system trash; edits and metadata stay for 30 days so restore keeps ratings; never deletes without a confirm.
- Size: M. Files: LibraryViewModel.kt, Catalog.kt, DeviceScanner.kt, LibraryScreen.kt. Risk: medium (destructive action; test with a throwaway folder and show a clear count).

### BK-098 [P0] Search: text, camera, lens, ISO, aperture, focal length, date range, rating, label, edited, file type, with saved searches
- Problem: only 5 coarse filters and a camera dropdown; with tens of thousands of device photos Jai can't find "the 85 mm portraits from March".
- Acceptance: a search bar over the grid that parses structured tokens (`iso>3200 lens:50 date:2026-03 *4`) plus free text over file name; Room indexes on takenAt, camera, lens, iso, focal; result within 150 ms on 20 000 rows; saved searches appear as smart collections (BK-100).
- Size: M. Files: Library.kt (LibraryFilter), Db.kt (indexes + queries), LibraryScreen.kt, LibraryViewModel.kt. Risk: low-medium (DB migration, BK-146).

### BK-099 [P1] Natural language search over the library (on-device, no cloud)
- Opportunity: Lightroom added natural language search in August 2026 (S4). On-device equivalent: a small image-text embedding model (MobileCLIP, check licence) computed in the background on the 320 px thumbnails, stored in Room, queried with a text embedding.
- Why: finding "dog on a beach" in 40 000 photos.
- Acceptance: opt-in toggle with a model download and clear disk/battery cost (BK-177); search "sunset" returns a ranked grid within 500 ms; index runs only while charging; embeddings stored per photo key; no network use after the model download.
- Size: L. Files: core/ml (new embeddings), Db.kt, LibraryScreen.kt. Risk: high (model licence, battery, accuracy). 
- Src: Adobe Lightroom mobile release notes, natural language search, Aug 2026 (S4, 6 Oct 2026).

### BK-100 [P1] Collections (albums) and smart collections
- Problem: no way to group photos across folders; DB has no collection table.
- Acceptance: tables `collections(id,name,createdAt)` and `collection_items(collectionId, photoKey)`; add selected to collection; smart collection from a saved filter; collections listed in the source picker; deleting a collection never deletes photos; included in backup zip.
- Size: M. Files: Db.kt (+migration), Catalog.kt, LibraryViewModel.kt, LibraryScreen.kt, backup. Risk: medium (DB migration and backup format version bump).

### BK-101 [P1] Keywords, captions and copyright metadata in the catalogue, written to XMP when enabled
- Problem: XMP writes rating, label and six develop fields only (Xmp.build). No dc:subject keywords, no title/caption.
- Acceptance: keyword chips on the info panel and a batch "Add keywords" for selection; autocompletion from existing keywords; XMP writes `dc:subject`, `dc:title`, `dc:description`; exports can include them (BK-134).
- Size: M. Files: Db.kt, Catalog.kt Xmp, Info panel in LoupeScreen.kt. Risk: low.

### BK-102 [P1] Date, lens, focal length and ISO filters, plus sort by capture time, ISO, file size
- Problem: filters are rating, flag, edited, camera only; sort is Newest/Oldest/Name/Rating.
- Acceptance: filter sheet gets Date range, Lens, Focal range, ISO range, File type (RAW/JPG/HEIC), With edits; sort adds ISO, Size, Last edited; filter chips show active filters and counts; unit tests in LibraryFilter tests.
- Size: S-M. Files: Library.kt, LibraryScreen.kt, ModelTest.kt. Risk: low.

### BK-103 [P1] [PARTLY DONE] Group by day with sticky date headers and a fast scrubber with date labels
- Status: Already in the code: the grid has date header rows (`GridRow.Head`, grouped by the sort order) with counts. Left over: sticky headers and a fast scrubber with date labels.
- Problem: the grid is one flat list; a Scrollbar.kt exists but no dates.
- Acceptance: sticky headers (`Today`, `Yesterday`, `Sat 4 Oct`) using `LazyVerticalGrid` full-span items; scrubber bubble shows month/year; section header shows count and select-all-for-day.
- Size: M. Files: LibraryScreen.kt, Scrollbar.kt. Risk: low-medium (selection interplay).

### BK-104 [P1] Cull mode: full-screen one-at-a-time with pick/reject on swipe, rating on number tap, and auto-advance
- Problem: culling 500 photos by opening each in the loupe and tapping flag takes too many taps.
- Acceptance: Cull mode from the grid: swipe up = pick, swipe down = reject (configurable), tap 1-5 = rating, auto-advance with prefetch; shows progress "123 / 500" and a "remaining" filter; undo last action; haptics (BK-058).
- Size: M. Files: LoupeScreen.kt, LibraryViewModel.kt. Risk: low.

### BK-105 [P1] Survey and compare: view 2-6 selected photos side by side with synced zoom
- Acceptance: Select 2-4 photos, tap Compare; tapping zooms all together; pick winner; works from the cull mode; each uses the 2048 px preview cache.
- Size: M. Files: new feature/loupe/CompareScreen.kt. Risk: low.

### BK-106 [P1] Burst and bracket stacks, auto-grouped by capture time and exposure
- Problem: an S5IIX burst of 20 RW2 is 20 grid tiles.
- Acceptance: auto-stack photos within 2 s with the same camera; stack shows top pick and count; expand in place; rating/flag applies to top by default; stored as stack id in Photo rows (not file moves); setting to turn off.
- Size: M-L. Files: Db.kt, Indexer.kt, LibraryScreen.kt. Risk: medium.

### BK-107 [P1] RAW+JPEG pairs: show as one tile, with a toggle to treat the JPEG as a sidecar or as its own photo
- Problem: S5IIX in RAW+JPEG mode produces `P1055415.RW2` and `.JPG`; both appear as separate tiles; editing the raw does not touch the JPG but duplicates clutter.
- Acceptance: pairing by base name and directory; a badge "RAW+J"; filter "RAW only/JPEG only"; export of the pair outputs one file; rating/flag applies to both.
- Size: M. Files: Indexer.kt, DeviceScanner.kt, Library.kt, LibraryScreen.kt. Risk: low-medium.

### BK-108 [P1] Recents and favourites: "Recently edited", "Recently imported", and quick access to Picks
- Acceptance: virtual sources `recent:edited`, `recent:imported`, `picks` visible in the source picker without scanning; backed by `edits.updatedAt` and an `importedAt` column.
- Size: S-M. Files: LibraryViewModel.kt, Db.kt. Risk: low.

### BK-109 [P1] [BLOCKED] Library-side handling of identity changes: "edits relinked" notices and a manual relink screen
- Status: Depends on the BK-141 decision. If BK-291 is built, scope this entry down to the relink notice only.
- Problem: when a file is renamed, moved to another folder or re-copied from the card, the key `name|size|modified` changes and the edit appears lost. The data fix is BK-141; the library also needs a visible, recoverable experience for the cases the automatic match cannot decide.
- Acceptance: after a scan, a one-line banner "14 edits relinked, 3 need your help"; a Relink screen shows the orphaned edit with its last thumbnail beside candidate photos (same size or same capture time), Jai taps the match; unmatched edits stay in the catalogue (never deleted) and are listed under Settings > Storage.
- Size: M. Files: LibraryScreen.kt, Catalog.kt, new RelinkScreen. Risk: low. Depends on BK-141.

### BK-277 [P1] Drag-to-select across grid tiles (swipe over tiles to select a range) and select-all / invert / select-day
- Problem: selection is long press then taps (LibraryScreen); selecting 80 photos from a shoot takes 80 taps.
- Acceptance: long press then drag across tiles selects (and deselects when starting on a selected tile) with auto-scroll at the edges; a selection action bar with Select all, Invert, "Select to here" (range from the last tapped tile), and day headers select the day (BK-103); haptic tick for each new tile; selection survives rotation (BK-120).
- Size: M. Files: LibraryScreen.kt, core/ui/Gestures.kt. Risk: low-medium.

### BK-310 [P1] Cull assistant: blur, exposure and eyes-open flags to find the keepers quickly
- Opportunity: Lightroom's Assisted Culling (early access from Oct 2025, updated Apr 2026) scores eyes open, eye focus, subject focus, clean-up candidates (blur, misfire, exposure) and groups similar shots into stacks. Rawline can do the cheap, private part on the phone: a sharpness score (variance of the Laplacian on the 320 px thumbnail or better the embedded preview), an exposure score (clipped highlights and shadows from the preview histogram), and optionally a small face and eye model later.
- Why: Jai shoots bursts with a fast camera; culling 400 photos is the longest part of a session and the phone is his only tool.
- Acceptance: indexer stores `sharpness`, `clipHi`, `clipLo` per photo (computed from the decoded preview, adds under 5 ms per file); Cull mode (BK-104) and the filter sheet offer "Soft", "Blown", "Dark" and a "Suggested rejects" view with explanations, never auto-rejecting; stacks by time and similarity (BK-106) show the sharpest as the cover; thresholds tuned on 200 of Jai's photos with a precision table in docs; eyes-open via a face-landmark model is a separate spike (licence and size recorded).
- Size: M (scores) / L (eyes). Files: core/data/Indexer.kt, Db.kt, Library.kt, LibraryScreen.kt. Risk: medium (false positives with intentional blur; keep it advisory).
- Src: Adobe community, Assisted Culling early access threads and Imagen summary (checked 6 Oct 2026): https://community.adobe.com/t5/lightroom-classic-discussions/early-access-assisted-culling-lrclassic/td-p/15519069

### BK-338 [P1] Culling 800 photos: "next undecided" jump, a visible progress count, and resume where I stopped
- Scenario: Jai imports a card and culls. In the loupe he has to open the star bar (a toggle) to rate, tap Pick or Reject, then swipe. Nothing says how far through he is, and after a pause (a phone call, the camera, a restart) he scrolls to find where he stopped. Flag filters exist (`FlagFilter.NONE` is "Unflagged") but changing filters inside the loupe drops his place.
- Acceptance: the loupe top bar shows "312 decided of 800" (decided = flagged or rated) with a thin progress line; a "Next undecided" button (and a swipe-up-and-hold shortcut if wanted) jumps to the next unflagged photo in the current source order; the position is saved per source and reopened by "Continue culling" on the grid header; works with the filtered list; unit test of the next-undecided search with a fake list; stays under one frame to compute for 20 000 photos (index scan on the in-memory list).
- Size: S-M. Files: feature/loupe/LoupeScreen.kt, app/LibraryViewModel.kt, Library.kt. Risk: low. Complements BK-104.

### BK-339 [P1] Rejected photos hidden from the grid by default, with a "Rejects (n)" chip to bring them back
- Scenario: after rejecting 300 of 800 shots the grid still shows all of them, so the view never gets cleaner and Jai sees the same blinks again when he edits.
- Acceptance: `LibraryFilter` gets `hideRejects` (default true, stored in prefs); the grid and the loupe order skip rejected photos unless the filter explicitly asks for Rejects or Any flag; a chip "Rejects (312)" toggles them; the count of hidden photos is visible; a rejected photo opened from the chip shows a clear "Rejected" mark; unit tests in ModelTest for each flag filter with hideRejects on and off.
- Size: S. Files: core/model/Library.kt, feature/library/LibraryScreen.kt, app/LibraryViewModel.kt. Risk: low (default behaviour change: say so in What's New, BK-199).

### BK-474 [P1] Imported RAWs must keep the card file's modified time, or `Photo.keyOf` (`name|size|modified`) makes every imported copy a new photo and breaks sync with edits made on the original
- Finding: `Photo.keyOf` includes the file's mtime. A copy that gets "now" as mtime has a different key than the card file, and the edit history (stored by key) cannot match across a re-import.
- Acceptance: CopyEngine sets the copy's mtime to the card's mtime (done and tested in W15, test `copiesVerifiesAndKeepsMtime`); a second ledger keyed on name, size, mtime/2 s stops re-copies. Phone check: import, delete from the phone only, import again, the photo returns with its edit.
- Size: S. Files: core/data/ingest. Risk: low. FAT stores mtime with 2 s resolution, handled in the ledger key.
- Src: W15-card-import.md D3, D4; Photo.kt line 37.

### BK-475 [P1] A DNG that LibRaw opens but cannot decode shows no error: `unpack` returns -2 and the user sees a blank or grey frame
- Finding: measured with a host LibRaw 0.22.2 built like the app: JPEG XL (52546) and VC-5 (9) DNGs open but `unpack` returns UNSUPPORTED_FORMAT; lossy DNG (34892) fails at open. Nothing in the app tells the user why.
- Acceptance: classify at scan time by reading the TIFF Compression tag (`DngProber`, tested on synthetic files in W15), badge "Preview only" in the grid and loupe, editor opens the embedded preview read-only with a one-line reason; never a silent grey frame. Copy report prints the compression number per DNG.
- Size: M. Files: core/data/ingest, core/native raw_decode error path, feature/library badge. Risk: low.
- Src: W15-card-import.md section 3.

### BK-476 [P1] Samsung Expert RAW spike: find out what compression the S24 Ultra writes before promising DNG support
- Finding: unknown. Expectation: linear DNG, possibly JPEG XL compressed. Not tested on a real file.
- Acceptance: three real Expert RAW files (day, night, 50 MP) copied to the phone, the Copy report lists their compression numbers and SUPPORTED or PREVIEW_ONLY; the result is pasted back and the decision table in W15 section 3 picks the work (platform JXL decode first, libjxl second).
- Size: S (Jai 5 minutes, then a worker). Files: none until the numbers exist. Risk: scope (a JXL decoder is 1 to 4 days).
- Src: W15-card-import.md section 3.

### BK-497 [P1] [DESIGNED] The grid reorders itself while indexing finishes, because the sort key changes from file time to EXIF capture time (found by reading DeviceScanner and Indexer)
- Status: W29-library-first-impression.md: OrderGate, DefaultView, WhatsNew and the rawOnly filter compiled and tested on the host (23 core/model tests pass); ViewModel, scanner and screen edits specified, not compiled. Not merged.
- Finding: `DeviceScanner` inserts a row with `takenAt = MediaStore DATE_TAKEN if above 0, else the file's modified time` (MediaStore usually has no DATE_TAKEN for RW2). `Library` sorts newest first on `takenAt`. `Indexer.markIndexed` later overwrites `takenAt` with the EXIF capture time. A card copied with a file manager (all files within one minute of copy time, in copy order) therefore first shows in copy order and then re-sorts into shooting order, one batch at a time, while Jai is scrolling or has a photo selected. W15 keeps the original mtime for its own imports, but copies made any other way do not.
- Why it matters to Jai: the first thing a photographer does after a shoot is scroll the grid; rows jumping under the thumb makes culling unreliable.
- Acceptance: for new RW2 rows read the capture time from the first TIFF IFD (a 64 KB head read, no decode) in the scan itself, for the first screenful at once and the rest in the first indexing pass before the rows are inserted into the visible list; keep the visible order stable while the user is scrolling (apply a re-sort only when the list is idle for 1 s or on pull to refresh); a host test with 300 rows whose file times are in reverse shooting order checks the list order never changes after the first display; Copy report line `grid_resort_count`.
- Size: M. Files: core/data DeviceScanner.kt, Indexer.kt, LibraryViewModel.kt, tests. Risk: low.
- Src: DeviceScanner.kt lines 25 to 50, Indexer.kt markIndexed, core/model Library.kt sort.

### BK-498 [P1] [DESIGNED] First run shows everything on the phone (`device:*`), so the S24 Ultra's thousands of JPEG and HEIC photos, screenshots and chat images bury the RAW files
- Status: W29-library-first-impression.md: OrderGate, DefaultView, WhatsNew and the rawOnly filter compiled and tested on the host (23 core/model tests pass); ViewModel, scanner and screen edits specified, not compiled. Not merged.
- Finding: `LibraryViewModel.init` sets `source = "device:*"` when no source is saved, and `scanDevice` lists every image row plus every file named like a RAW. For a phone in daily use that is tens of thousands of rows, newest first; the RW2 files from last weekend sit below that day's screenshots. BK-450 covers the empty and "RAW files hidden" cases but not the default view when everything is allowed.
- Why it matters to Jai: Rawline is a RAW editor; its first screen should be RAW photos.
- Acceptance: the default filter on first run is "RAW photos" (a chip at the top of the grid, one tap to "All photos"), chosen when at least one RAW row exists after the first scan and otherwise "All photos" with the BK-450 hint; the choice is remembered; JPEG and HEIC stay reachable and editable (BK-275); the number of rows inserted at first scan is unchanged (this is a filter, not a different scan); Robolectric test of the default with a fake scanner returning 5000 images and 12 RAW.
- Size: S. Files: LibraryViewModel.kt, feature/library filter chips, tests. Risk: low.
- Src: LibraryViewModel.kt init (line 127 onwards), DeviceScanner.kt.

### BK-110 [P2] Duplicate finder (same content in two folders) with a merge action
- Acceptance: after BK-141, a screen listing duplicate groups; keep one, merge ratings and edits; never auto-delete; skip RAW+JPEG pairs.
- Size: M. Files: new feature/library/Duplicates.kt, Db.kt. Risk: low.

### BK-111 [P2] Folder tree browsing for SAF roots (not only one folder at a time)
- Problem: addFolder stores one tree plus eight recents (prefs StringSet, unordered); the scan flattens subfolders into one list; no tree view and `labelOf` shows the last path segment.
- Acceptance: folder tree with counts, expand/collapse, "Include subfolders" toggle per root; ordering is stable (recents is a Set, so order is lost: use a list); rename display name; remove source (forget the permission).
- Size: M. Files: LibraryViewModel.kt, LibraryScreen.kt, Db.kt (folder table). Risk: low.

### BK-112 [P2] Watch folders and a card-inserted shortcut: auto-scan when a removable volume is mounted
- Acceptance: ContentObserver (exists for MediaStore images) extended to the Files table and a `MEDIA_MOUNTED` receiver; a notification "Card found: 312 new photos. Import?" respecting a Settings toggle.
- Size: S-M. Files: LibraryViewModel.kt, manifest. Risk: low.

### BK-113 [P2] Share sheet integration: open in Rawline from other apps (VIEW/SEND/PICK intents) and share a photo straight to Rawline
- Problem: manifest has only the launcher intent filter; "Open with Rawline" from Files or the Samsung Gallery is impossible; no `ACTION_VIEW` for image/x-panasonic-rw2 or `image/*`.
- Acceptance: intent filters for VIEW and SEND with `image/*`, `image/x-panasonic-rw2`, `image/x-adobe-dng`; opens in a single-photo loupe using the content URI with a temporary read grant; "Edit" works without importing (edits keyed by BK-141 identity).
- Size: M. Files: AndroidManifest.xml, MainActivity.kt. Risk: low-medium.

### BK-114 [P2] Photo picker integration for the partial-access case (READ_MEDIA_VISUAL_USER_SELECTED)
- Problem: the manifest asks for READ_MEDIA_IMAGES and `MANAGE_EXTERNAL_STORAGE`; on Android 14+ the user can choose "Select photos" which grants partial access, and the app should handle it and offer to widen it.
- Acceptance: handle `READ_MEDIA_VISUAL_USER_SELECTED` (declare it), detect partial access and show a banner "Only 12 photos shared. Allow all?" with a button; test with the system dialog on the phone.
- Size: S-M. Files: AndroidManifest.xml, LibraryViewModel.kt (hasMediaPermission), LibraryScreen.kt. Risk: low.
- Src: Android partial photo access, Android 14 docs (S7, 6 Oct 2026).

### BK-115 [P2] Replace MANAGE_EXTERNAL_STORAGE where possible (keep it as the documented sideload exception)
- Problem: all files access is needed to list RW2 not classed as images by MediaStore, but it is broad and Google Play would reject it without a permitted use case. Not relevant to Play (sideload only) but it widens the attack surface and Samsung may nag about it.
- Acceptance: document the reason in docs/DECISIONS.md; an alternative path using `ACTION_OPEN_DOCUMENT_TREE` on `DCIM` as the primary route; the app functions with a clear degraded mode (device list shows only MediaStore images) when all files access is off.
- Size: S. Files: docs/DECISIONS.md, LibraryViewModel.kt. Risk: low.
- Src: Android storage guidance and Play all-files policy (S7, 6 Oct 2026).

### BK-116 [P2] Geotag and map: show location from EXIF, filter by place, and write GPS to exports only if allowed
- Problem: ACCESS_MEDIA_LOCATION is requested but location is not shown or used; no privacy toggle for stripping it from exports (export "Copyright only" mode exists).
- Acceptance: location shown in info panel with a small static map or "open in Maps" intent (no embedded SDK, avoids tracking); filter "Has location"; export option "Remove location" independent of other metadata.
- Size: S-M. Files: LoupeScreen.kt info, PreviewDecoder.readExifOnly, ExportRunner.writeExif. Risk: low.

### BK-117 [P2] Rating/flag keyboard and gesture shortcuts in the loupe and grid
- Acceptance: loupe: double tap with two fingers = pick, volume keys optional for next/prev (setting), number keys for ratings when a keyboard is attached; grid long press selects then bottom bar offers rate/flag/label.
- Size: S. Files: LoupeScreen.kt, LibraryScreen.kt. Risk: low.

### BK-118 [P2] Batch operations: auto-tone, apply preset, paste with scope, reset edits, and apply lens/profile to many photos with a progress queue
- Problem: copy/paste/sync exist; "apply preset to selection" and "auto settings for selection" do not; large batches run on the viewModelScope sequentially without progress.
- Acceptance: batch bar actions for Preset, Auto, Reset, Convert to B&W, Rotate; a cancellable progress strip; undo for the batch (BK-051 style): one entry reverts all.
- Size: M. Files: LibraryViewModel.kt, Catalog.kt, LibraryScreen.kt. Risk: low-medium.

### BK-119 [P2] Edits indicator quality: small badge for edited, a different one for "has masks/AI", and a thumbnail that shows the edit
- Problem: grid thumbnails show the embedded camera JPEG, not the edit; the edited flag is a boolean.
- Acceptance: optional "show edited thumbnails": after leaving the editor, render a 320 px thumbnail with the recipe (GL pass from the cached preview) and store it as the tile image; toggle in Settings; a badge variant for masks/heals.
- Size: M. Files: EditorHost.kt, ThumbStore.kt. Risk: low-medium (storage, GPU).

### BK-120 [P2] [DONE] Persist per-source scroll position, selection and sort across rotation and process death
- Status: Done in commit eec8960: filter, sort, column count and the top photo survive a swipe away (libraryFilter, columns, libraryTop).
- Problem: AUDIT item 4: rotation resets selection; `source` and `folder` are in prefs but scroll position, filters, columns (`mutableIntStateOf(5)` not saved) are not.
- Acceptance: `rememberSaveable` and a `SavedStateHandle` in LibraryViewModel; columns saved in prefs; grid returns to the same photo after viewing a photo and after the process is killed.
- Size: S. Files: LibraryScreen.kt, LibraryViewModel.kt. Risk: low.

### BK-278 [P2] Show real indexing progress with an estimated time and let the user pause it
- Problem: `IndexProgress(total, done, running)` exists; the header text and ETA are unverified; indexing 1 000 RW2 is spec'd at under 60 s and a first-time 20 000 photo library may take many minutes with the screen on.
- Acceptance: "Preparing thumbnails: 312 of 1 000, about 40 s left" with a Pause button; indexing prioritises the tiles currently visible (visible-first queue) over newest-first; the grid never shows blank grey for long (BK-250).
- Size: S-M. Files: Indexer.kt, LibraryScreen.kt. Risk: low.

### BK-279 [P2] Use File APIs for SAF folders when all-files access is granted (much faster listing and reads than `DocumentsContract` per directory)
- Problem: `Indexer.walk` runs a `ContentResolver.query` per directory and builds a document uri per file; for a card with 10 000 files, SAF overhead (binder + provider) dominates; XMP sidecars are read by opening a stream per file.
- Acceptance: when the tree maps to a real path readable by the app (`/storage/XXXX-XXXX/DCIM/...`), use `File.listFiles` and `FileChannel` for the scan, previews and XMP reads, keeping SAF only as a fallback; `scan_list_ms` improves at least 5x on a card with 5 000 files; behaviour identical in tests with a fake file tree.
- Size: M. Files: Indexer.kt, PreviewDecoder.kt, Catalog.kt Xmp. Risk: medium (path mapping and permission rules differ by volume).

### BK-329 [P2] Camera-shake risk flag from shutter speed, focal length and the stabiliser state
- Opportunity: with the shutter time (already indexed), focal length (indexed; adjust by crop factor if needed), the S5IIX's Dual I.S. state and the number of stops of stabilisation, a rule such as "slower than 1/(2 x focal length) with IS off" or "slower than 1/(focal length / 4) with IS on" predicts motion blur well enough to rank the culling queue.
- Acceptance: a `shakeRisk` score (0 to 2) computed at index time; shown in Cull mode and as a filter "May be shaky"; thresholds adjustable in Settings; feeds the cull assistant (BK-310) together with the measured sharpness; the stabiliser state comes from BK-325 when available (otherwise assumed on).
- Size: S. Files: core/data/Indexer.kt, Db.kt, Library.kt. Risk: low.

### BK-334 [P2] Removable volume handling: show the card as its own source, mark offline photos, and handle eject
- Problem: `DeviceScanner` lists `MediaStore.Files.getContentUri("external")`, which includes all volumes, but the grouping is by bucket name only, so a card appears as a folder named by its directory (for example `100_PANA`) and disappears from the list when removed (rows are pruned, see BK-145). The edit and rating records stay in the meta tables, but the grid shows nothing and Jai cannot tell the card is simply out.
- Acceptance: sources show the volume label (`MediaStore.getExternalVolumeNames`, `StorageVolume.getDescription`) as a parent line ("SD card: 100_PANA"); when a volume is unmounted its rows stay greyed as offline with the last thumbnails, instead of vanishing; `ACTION_MEDIA_EJECT`/`MOUNTED` update the list at once; edits and exports to a missing source fail with a clear message.
- Size: M. Files: DeviceScanner.kt, Db.kt (volume column), LibraryViewModel.kt, LibraryScreen.kt. Risk: medium. Works with BK-096 and BK-160.

### BK-344 [P2] Ingest extras that working photographers expect: rename pattern, copyright template, second backup copy and an import report
- Opportunity: Photo Mechanic's core selling points are ingesting with rename variables, an IPTC/metadata template and a backup destination in one step; Lightroom on Android cannot import from a card at all (it needs the files copied first with a file manager, Adobe community, checked 6 Oct 2026), so a good card import is a real advantage.
- Acceptance (adds to BK-096): rename pattern at import (`{yyyy}{MM}{dd}_{seq3}` and `{camera}`); an optional copyright/creator text written to the XMP sidecar (not touching the RAW) and used as the export default; an optional second destination (a folder on a USB drive or another SAF tree) written in the same pass with its own verification; an import report (counts, bytes, speed, duplicates skipped, errors) saved as a text file next to the batch and shown on completion.
- Size: M. Files: new feature/import, Catalog.kt Xmp. Risk: low-medium.
- Src: Photo Mechanic ingest descriptions (Expert Photography review and tutorials, checked 6 Oct 2026): https://expertphotography.com/photo-mechanic-workflow/ ; Adobe, Lightroom mobile card import (iOS only): https://helpx.adobe.com/ee/lightroom/mobile/add-and-capture-photos/add-and-import-photos/import-photos-from-card-or-cameras.html

### BK-345 [P2] Import selection helpers: "new since last import", by date or time range, and by day header
- Scenario: Jai puts the same card back after a break; the card still holds yesterday's shots. He wants only the new ones without ticking each.
- Acceptance: the import screen preselects files newer than the last import time for that volume (stored per volume id); chips for "Today", "Last 3 days", "Custom range"; day headers select the day; shows total size and an estimated copy time from measured speed (BK-255); the selection is kept if the card is removed and reinserted.
- Size: S-M. Files: feature/import. Risk: low.

### BK-352 [P2] [DONE] Undo for the last rating, flag, label or batch change (snackbar)
- Status: Done in commit e9cd055 (UndoRules and UndoEntry: one undo for the last rating, flag or label change, grouped restore writes, tests). Left over: undo for batch deletes and reset edits is BK-202.
- Scenario: a stray tap rejects a keeper in the middle of a cull; the only fix is to find it and toggle.
- Acceptance: every rate/flag/label action shows a 4 s "Undo" snackbar (loupe and grid) restoring the previous values for the affected photos (kept in memory as old values); the meta rows use their `updatedAt` so an undo also writes the old timestamp back; unit test with a fake catalog.
- Size: S-M. Files: app/LibraryViewModel.kt, LoupeScreen.kt, LibraryScreen.kt. Risk: low. Related to BK-202.

### BK-431 [P2] USB-C card reader specifics on the S24 Ultra (it has no SD slot): exFAT, throughput, bus power and safe removal
- Facts: the S24 Ultra has USB 3.2 Gen 2 over USB-C and no card slot (GSMArena and carrier spec sheets, checked 6 Oct 2026); S5IIX cards above 32 GB are exFAT, which Android reads natively; since Android 6 external storage is reached through the Storage Access Framework or, with all files access, a `/storage/XXXX-XXXX` path, and reads go through the FUSE layer.
- Acceptance (adds to BK-096 and BK-255): the import screen shows the reader as a volume with its file system and free space; a measured MB/s for the first 100 MB (shown, recorded) and a warning when a slow reader or a USB 2 speed is detected; battery drop during import is recorded (reader bus power draws from the phone) with a "plug in power" suggestion below 30 percent (BK-354); a safe-removal prompt after the last file is verified ("You can remove the card now") and handling of a surprise removal (BK-364); card with an unsupported file system shows a clear message, not an empty list.
- Size: S-M. Files: feature/import, DeviceReport. Risk: low.

### BK-457 [P2] Android's persisted URI permission cap: warn and recycle (audit AU-024)
- Fact: apps may hold a limited number of persisted URI grants (the limit is in the low hundreds); importing many single files with `takePersistableUriPermission` can silently stop persisting and old photos become unreadable after a restart (audit-ui.md AU-024).
- Acceptance: the import path counts persisted grants (`contentResolver.persistedUriPermissions.size`), warns near the cap, prefers folder trees to single files, releases grants for sources the user removed (BK-111), and a photo whose grant is gone shows "Access lost, choose it again" rather than a blank tile; tests with a fake resolver.
- Size: S-M. Files: core/data/DeviceScanner.kt, LibraryViewModel.kt. Risk: low.
- Src: audit-ui.md AU-024.

### BK-121 [P3] Albums for export results: show exported files under "Exports" with a link back to the source raw
- Acceptance: Export queue done items link to the output (MediaStore uri exists) and an "Open source" button; an "Exports" virtual source.
- Size: S. Files: QueueScreen.kt, ExportRunner.kt. Risk: low.

### BK-122 [P3] Ratings and flags written back to the camera-style standard: XMP `xmp:Rating`, `xmp:Label`, `photoshop:` urgency; and read Lightroom sidecars fully
- Problem: only Rating and Label read; Lightroom `crs:` develop settings are not interpreted (BK-050 covers import).
- Acceptance: also write `xmp:Rating = -1` for reject (Lightroom convention), read pick flag from `xmpDM`/`crs` where present, keep unknown XML intact when updating an existing sidecar (merge, not overwrite) using a real XML parser instead of regex (Xmp.parse uses Regex).
- Size: M. Files: Catalog.kt (Xmp). Risk: low-medium (never destroy another editor's sidecar: existing rule stays).

### BK-123 [P3] Library statistics and a "year in review": photos per month, favourite lens/focal length, most used preset
- Acceptance: a Stats screen from Settings using local queries only; no network.
- Size: S-M. Files: new feature/library/Stats.kt. Risk: low.

### BK-124 [P3] Multi-library or profiles (work vs personal) with separate catalogues
- Acceptance: switch database file; low priority because Jai is one user.
- Size: L. Files: RawlineApplication.kt Graph. Risk: medium. Defer.

### BK-125 [P3] [MERGED] Cloud-free sync between Jai's devices via Syncthing-style folder export of the catalogue (backup zip on a schedule to a chosen SAF folder)
- Status: Merged into BK-142 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-142.
- Acceptance: scheduled automatic backup to a user-chosen folder (see BK-142), so Jai can pick it up on another phone; no account or cloud code in the app.
- Size: M. Files: Catalog.kt, WorkManager (new dependency). Risk: low.

### BK-280 [P3] Rotate or correct orientation of a photo in the grid when EXIF orientation is wrong (rotate 90 without editing)
- Acceptance: a quick action stored as a recipe geometry override (rotate90) so no file changes; reflected in the thumbnail and loupe.
- Size: S. Files: LibraryScreen.kt, Catalog.kt. Risk: low.

### BK-321 [P3] [MERGED] Wireless and USB tethered import from the S5IIX
- Status: Merged into BK-255 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-255.
- Opportunity: Capture One Mobile (3.2.5, March 2026) consolidated wireless Lumix tethering on iPad. A phone-side equivalent is a "live import" mode that watches the camera's transfer folder or an incoming PTP/IP session and shows new frames at once for review. The camera's own Wi-Fi transfer is already visible through the MediaStore watcher (BK-256).
- Acceptance: spike: record what the S5IIX offers (Lumix Lab image transfer, USB tethering, PTP/IP) and what Android exposes without root; decide between "watch folder" (small) and a PTP stack (large); write the decision in DECISIONS.md; no implementation without Jai's choice.
- Size: S (spike). Files: docs. Risk: low.
- Src: L'art de la photo, Capture One Mobile 3.2.5 wireless Lumix tethering (April 2026): https://lartdelaphoto.fr/capture-one-mobile-3-2-5-wireless-lumix-avril-2026/

### BK-335 [P3] Use the system photo picker for JPEG/HEIC import and keep the document picker for RAW; check the embedded picker
- Facts: Android 14 added Selected Photos Access, and Google recommends the photo picker for privacy and a consistent experience (Android Developers, checked 6 Oct 2026). The picker lists MediaStore images; whether it lists `image/x-panasonic-rw2` depends on the vendor's MediaStore classification, which is exactly why the app needs all-files access today.
- Acceptance: a spike on the phone: does `PickVisualMedia`/`PickMultipleVisualMedia` show RW2 files? If yes, offer it as the default Import (no permission) and keep documents as the fallback; if no, keep the document picker and record the finding in DECISIONS.md. Verify on developer.android.com whether an embedded photo picker is available for the targeted API level before designing around it.
- Size: S (spike). Files: LibraryScreen.kt, MainActivity.kt, docs/DECISIONS.md. Risk: low.
- Src: Android Developers, partial photo and video access (S7, 6 Oct 2026).

### BK-351 [P3] Correct camera clock errors: shift capture time for a whole selection
- Scenario: two bodies or a camera with the clock not set to local time produce a wrong sort order and day headers.
- Acceptance: "Adjust capture time" for a selection (plus or minus hours and minutes, or set to match a reference photo) stored as an offset in the catalogue (originals untouched), applied in sort/group/search and written to XMP when enabled (`exif:DateTimeOriginal` offset in the sidecar only).
- Size: M. Files: Db.kt, Library.kt, Catalog.kt. Risk: low-medium.


---

# AREA F: EXPORT (formats, sizes, watermark, metadata)

What exists: JPEG (quality 40-100), PNG, 16-bit TIFF (own writer); long edge 0/4096/2048/custom; output sharpening for screen/print; sRGB or Display P3; metadata All/Copyright only/None; name pattern `{name} {date} {n} {rating} {camera}`; SAF destination or Pictures/Rawline via MediaStore with IS_PENDING; Room-backed queue run by a foreground service; Share export to a cache file.

### BK-126 [P0] Export size and speed: measure 24 MP JPEG under 3 s and fix the known slow spots
- Problem: Exporter does a per-pixel Kotlin loop converting half floats to 16-bit for TIFF (`Half.toFloat` per channel across 24 MP = 72 M calls), allocates a full `ByteBuffer.allocateDirect(tw*th*4)` for JPEG/PNG, creates a new GL context + engine + raw decode for every job, and the JPEG is encoded by `Bitmap.compress` (libjpeg-turbo, single thread) after the whole render.
- Acceptance: native conversion of half to uint16 and to 8-bit in the engine (readback already in C++), tile rows streamed to the JPEG encoder (see BK-127), engine reused across a batch (BK-018); `export_render_ms` and `export_total_ms` p50 under 3000 ms for 24 MP on the phone, TIFF within 6 s; PERF.md updated.
- Size: M. Files: core/render/Exporter.kt, jni_engine.cpp, ExportRunner.kt. Risk: medium.

### BK-127 [P1] Stream-encode JPEG in tiles so memory stays bounded (needed for 96 MP and 50 MP files)
- Problem: render holds the entire output as an RGBA direct buffer plus a Bitmap copy (`copyPixelsFromBuffer`): 24 MP = 96 MB x 2; 96 MP = 384 MB x 2. 
- Opportunity: use libjpeg-turbo (bundled in AOSP, but not exposed) or a small own encoder via `libjpeg-turbo` NDK build (BSD) writing scanlines per tile row; or `ImageWriter`/`HeifWriter` for HEIC. Keeps peak memory near one tile row.
- Acceptance: export of a synthetic 100 MP image peaks under 300 MB RSS; output JPEG visually identical to the Bitmap.compress path at the same quality (SSIM above 0.999); progressive JPEG option.
- Size: L. Files: new core/native jpeg_encode.cpp, Exporter.kt, CMake. Risk: high (new native dependency; licence record in THIRD_PARTY.md).

### BK-128 [P1] Export presets: named bundles (Instagram 1350, Web 2048, Full JPEG, Print TIFF, Archive 16-bit) with one-tap export
- Problem: the sheet shows every option each time; settings are kept as one global JSON in prefs.
- Acceptance: create/rename/delete presets, default preset per context (Share, Save, Batch), a long-press on the Export button shows the preset list; `ExportSettings` gains `name`; presets included in backup.
- Size: M. Files: ExportSheet.kt, Exporter.kt (ExportSettings), LibraryViewModel.kt. Risk: low.

### BK-129 [P1] More size modes: by percentage, by megapixels, by exact width/height with aspect lock, and "max file size in MB" with auto quality
- Problem: only long edge in pixels.
- Acceptance: tabs Long edge / Dimensions / Percent / Megapixels / Max file size; binary-search the JPEG quality to hit a size within 5 percent (re-encode only, render once); preview of resulting pixels and estimated size.
- Size: M. Files: Exporter.kt, ExportSheet.kt. Risk: low.

### BK-130 [P1] Watermark and signature overlay (text and image), position, opacity, and size relative to the image
- Problem: no watermark option (grep: none).
- Acceptance: text (font from system, copyright symbol), PNG logo import, anchor (9 positions), margin, opacity, size percent, shadow; rendered in the final pass in linear light so it looks the same at 2048 and 24 MP; per-preset (BK-128); live preview in the sheet.
- Size: M. Files: out.frag (overlay sampler), Exporter.kt, ExportSheet.kt. Risk: low-medium.

### BK-131 [P1] HEIC export (10-bit, Display P3) via HeifWriter, and AVIF via a bundled encoder if the licence allows
- Problem: AVIF is unavailable in the platform (DECISIONS), HEIC is not offered either, but the S24U and Samsung Gallery display HEIC fine and files are about half the size of JPEG at the same quality.
- Opportunity: `androidx.heifwriter` (supports 10-bit and AVIF in 1.1 alpha per the release page) for HEIC at 8/10 bit; AVIF via libavif + aom/dav1d NDK build if size allows (adds several MB and takes long CI time; evaluate rav1e/SVT-AV1 licences BSD-2).
- Acceptance: HEIC 10-bit P3 export opens in Samsung Gallery and Google Photos with correct colour; file size at least 35 percent smaller than JPEG at equal SSIM; AVIF decision recorded in DECISIONS.md.
- Size: M (HEIC) / L (AVIF). Files: Exporter.kt, ExportRunner.kt, app/build.gradle.kts. Risk: medium (hardware encoder availability; heifwriter at alpha).
- Src: androidx.heifwriter release notes (S12, 6 Oct 2026).

### BK-132 [P1] Ultra HDR JPEG export (JPEG_R with gain map) for HDR-edited photos
- Problem: only SDR outputs. The S24 Ultra, Google Photos and Instagram display Ultra HDR; Android 14+ supports the format and `Bitmap.hasGainMap()`; Lightroom exports HDR.
- Opportunity: render both an SDR base and an HDR rendition (BK-040), compute the gain map and write JPEG_R using libultrahdr (BSD/Apache, Google).
- Acceptance: export of a high-contrast photo displays the highlights brighter than SDR white in Google Photos on the S24U while looking identical on an SDR display; fall back to a normal JPEG when HDR is off.
- Size: L. Files: Exporter.kt, new native libultrahdr build, out.frag. Risk: high (new native dependency; format conformance).
- Src: Android Ultra HDR docs (S2, 6 Oct 2026); libultrahdr project.

### BK-133 [P1] [PARTLY DONE] Colour space embedding: confirm the ICC profile is written for P3 and add Adobe RGB / Rec 2020 and "preserve ProPhoto" TIFF
- Status: Commit ee38b8b embeds an sRGB or Display P3 ICC profile in the 16 bit TIFF (tag 34675). Left over: JPEG and HEIC embedding checks, Adobe RGB, Rec 2020, preserve ProPhoto.
- Problem: Bitmap with a ColorSpace compresses with an embedded ICC (API 26+) for JPEG/PNG; TIFF writer emits no ICC tag (the Tiff16Writer writes dimensions, bits, strips, resolution and optional copyright only) so a ProPhoto/sRGB 16-bit TIFF opens with no profile and apps assume sRGB.
- Acceptance: TIFF tag 34675 with an ICC for sRGB, Display P3, ProPhoto, Adobe RGB; verify with `exiftool`/ImageMagick in a unit test on the file bytes; add Adobe RGB and ProPhoto 16-bit option for print workflows.
- Size: M. Files: Exporter.kt Tiff16Writer, ColorSpaces.kt, ExportSheet.kt. Risk: low.
- Note: AUDIT item 9 (fifth pass) confirms TIFF export ignores Display P3 (no ICC tag).

### BK-134 [P1] Metadata export: carry GPS (optional), orientation, lens make/serial, white balance, flash, metering, plus XMP and IPTC
- Problem: writeExif sets Make, Model, Lens model, ISO, shutter, aperture, focal length, DateTimeOriginal, Software, Copyright, Orientation only. No GPS, no ExposureBias, no MeteringMode, no WhiteBalance, no LensMake, no XMP/IPTC, no PNG/TIFF metadata.
- Why: sharing sites and print labs read these; losing GPS may be wanted but should be a choice.
- Acceptance: copy the full EXIF from the source (RW2 maker-note excluded) into the output with an allow-list; separate toggles Location, Camera serial, Lens info, Keywords/Title (BK-101), Copyright; a "Strip all" mode remains; PNG iTXt and TIFF EXIF IFD support.
- Size: M. Files: ExportRunner.kt writeExif, Exporter.kt. Risk: low.

### BK-281 [P1] Export preview of the result: estimated size, pixel dimensions and a 100 percent crop preview before exporting
- Problem: the export sheet asks for numbers but never shows the outcome (final pixels for a crop, estimated file size, output sharpening effect).
- Acceptance: the sheet shows "5184 x 3456, about 6.8 MB" updated live; a button "Preview 100 percent" renders a 600 x 400 crop of the final file (including output sharpening and colour space) in 1 s; the dimensions account for the crop (`engineOutputSize`).
- Size: M. Files: ExportSheet.kt, Exporter.kt. Risk: low.

### BK-341 [P1] Remember what has been exported: an "Exported" badge, a "Not exported yet" filter and a per-photo export history
- Scenario: after exporting 40 photos and sharing 5 more over a week, Jai cannot tell which picks are still waiting, and re-exporting asks nothing: a second export of the same photo creates another file or a numbered copy (name collision handling is not defined, BK-135).
- Acceptance: each finished job writes `exports(photoKey, outputUri, presetName, at)` (the Room `export_jobs` table already has the data; keep finished jobs for 90 days and index by photoKey); grid shows a small badge for exported photos and the info panel lists the last 5 exports with a button to open each; filter "Not exported" and "Edited since export" (compare `edits.updatedAt` with the last export time); exporting an already-exported photo with the same preset asks Replace, Add copy or Skip once for the batch; tests with a fake DAO.
- Size: M. Files: core/data/Db.kt, app/ExportRunner.kt, feature/library/LibraryScreen.kt, Library.kt. Risk: low.

### BK-342 [P1] Share five photos in one go from the library selection, with a share preset
- Scenario: "Share 5": Jai selects five photos and wants one tap to send them to a client chat at 2048 px. Today `exportForShare` renders one photo to a cache file and shares it; there is no multi-photo share path (and the selection bar offers Export, not Share).
- Acceptance: a Share action in the selection bar renders each selected photo with the Share preset (default 2048 px long edge, JPEG 90, sRGB, metadata minimal) into a per-share cache folder with a progress strip and cancel, then opens `ACTION_SEND_MULTIPLE` with FileProvider URIs; each file is deleted after an hour (exists) and on the next launch; a render of 5 photos does not block the UI (service or a coroutine on IO with the GL context rules); a "Share original files" alternative sends the RAW or JPEG URIs untouched; test of the URI list builder.
- Size: M. Files: app/ExportRunner.kt, app/LibraryViewModel.kt, feature/library/LibraryScreen.kt. Risk: low-medium. Extends BK-138.

### BK-359 [P1] Export destination lost or changed mid-queue: revalidate, fall back and tell Jai once
- Scenario: Jai chose a folder on a USB drive or SD card for exports; during a 40-photo batch the drive is removed or the folder permission is revoked. `openTarget` then returns null (or `createDocument` throws), so each of the remaining jobs fails one by one with "No place to save" (or an exception text) and the queue ends with 39 failures.
- Acceptance: at queue start and before each job check the persisted URI permission (`ContentResolver.persistedUriPermissions`) and that the tree is reachable; on failure pause the queue (not fail the jobs), notify once "Export folder is not available", and offer: Choose another folder, Save to Pictures/Rawline, or Wait; jobs keep status waiting; tests with a fake resolver.
- Size: S-M. Files: ExportRunner.kt, ExportService.kt, QueueScreen.kt. Risk: low.

### BK-135 [P2] [PARTLY DONE] Export file naming: tokens (`{lens}`, `{iso}`, `{width}`, `{seq3}`, `{yyyy}`), preview, collision handling (append -1 or ask)
- Status: Commit 3cd7bb9: `ExportNaming` (pure, tested) sanitises illegal characters and caps the length. Left over: more tokens, live preview, collision policy.
- Problem: the pattern help lists 5 tokens; unknown behaviour on a name collision (MediaStore may rename; SAF `createDocument` appends numbers).
- Acceptance: a live preview of the first three names; collision policy Replace/Skip/Rename; invalid filename character sanitising covered by unit tests.
- Size: S. Files: ExportRunner.kt fileName, ExportSheet.kt, new test. Risk: low.

### BK-136 [P2] Export queue improvements: reorder, pause/resume, per-job retry with the reason, and a "Show in Files/Gallery" action
- Problem: the queue supports cancel and retry; no pause, no reorder; result shows a status number.
- Acceptance: Pause all/Resume, drag to reorder, tap done item to open in Gallery via the output uri, error text per failed job; export notification shows thumbnail of the current photo.
- Size: S-M. Files: QueueScreen.kt, ExportDao, ExportService.kt. Risk: low.

### BK-137 [P2] [DONE] Handle the Android 15+ dataSync foreground service time limit (6 hours per 24 h) with onTimeout
- Status: Done in commit 42b80f0 (ExportService.onTimeout and onDestroy put the running job back to waiting; mediaProcessing type on Android 15+, dataSync before).
- Problem: ExportService runs as `foregroundServiceType="dataSync"` with targetSdk 37; the system calls `onTimeout` after 6 hours in 24 and throws if the service does not stop in a few seconds. A giant batch (for example 2000 photos at 5 s each = 2.8 h, fine, but 5000 would exceed it).
- Why: crash in the middle of a long overnight export.
- Acceptance: implement `onTimeout(startId, fgsType)` to stop and mark remaining jobs "Paused (system limit)" with a Resume button; consider the `mediaProcessing` type (also 6 h) which better describes the work; document in DECISIONS.md.
- Size: S. Files: ExportService.kt, AndroidManifest.xml. Risk: low.
- Src: Android 15 behaviour changes, foreground service timeouts (S1, 6 Oct 2026).

### BK-138 [P2] Share: share multiple photos, share as original raw, and "Share to..." quick targets with recent export preset
- Problem: share exports a single photo to cache (exportForShare) and cleans after one hour.
- Acceptance: multi-select share via `ACTION_SEND_MULTIPLE` with a progress strip; "Share original" sends the file URI; the share preset remembered; shared files removed on the next launch.
- Size: S-M. Files: ExportRunner.kt, MainActivity.kt. Risk: low.

### BK-139 [P2] Contact sheet / collage / PDF proof export
- Opportunity: export a PDF contact sheet (4x6 thumbnails with names and ratings) for client proofing; use `PdfDocument` (no dependency).
- Acceptance: selection to PDF with selectable columns, caption fields; under 5 s for 24 photos.
- Size: M. Files: new feature/export/ContactSheet.kt. Risk: low.

### BK-282 [P2] Export sheet matches the Lr UI (it uses Material dialog and text fields) and is usable one-handed as a bottom sheet
- Problem: UI_SPEC bans the Material look; `ExportSheet.kt` imports Material `AlertDialog` and `OutlinedTextField` (its buttons are already the Lr versions), and a long list of options in a centred dialog is hard to scroll one-handed.
- Acceptance: a bottom sheet in Lr style (preset chips first, "More options" expandable), 48 dp rows, big Export button above the navigation bar; screenshot tests (BK-208).
- Size: M. Files: feature/export/ExportSheet.kt. Risk: low.

### BK-315 [P2] Export the same selection with several presets in one operation (web size plus archive size)
- Opportunity: darktable 5.2 added a multiple-export section to export a selection with several presets in one go. Jai often needs a full-size JPEG and a 2048 px share copy of the same picks.
- Acceptance: the export sheet (BK-128 presets) allows ticking several presets; one render pass produces all sizes from the same decode (render once at the largest, downscale per target with the correct output sharpening each); queue shows one job with N outputs; file names get a preset suffix.
- Size: M. Files: ExportRunner.kt, Exporter.kt, ExportSheet.kt. Risk: low.
- Src: darktable 5.2 release coverage (checked 6 Oct 2026): https://discuss.pixls.us/t/question-before-updating-from-5-2-to-5-4/55321

### BK-343 [P2] After an export finishes: open in Gallery, share the results, or undo the export
- Scenario: the notification says "Export finished, 40 photos saved" but not where, and tapping it does not open the results.
- Acceptance: the finished notification and the queue screen offer "Open folder" (the MediaStore collection or SAF folder), "Share these 40" and "Delete these exports" (with confirmation; only files this app created in this batch, tracked by `outputUri`); the notification tap opens the queue screen.
- Size: S-M. Files: ExportService.kt, QueueScreen.kt, ExportRunner.kt. Risk: low.

### BK-499 [P2] Exported JPEGs carry no time zone or sub-second tags, so a gallery that sorts by capture time can place them minutes or hours off (verify on the phone)
- Finding: `ExportRunner.writeExif` writes DateTimeOriginal as local time text in the phone's current time zone from `takenAt`, but no OffsetTimeOriginal and no SubSecTimeOriginal. When the phone is in a different zone from where the shoot happened, or `takenAt` is an epoch converted from the file's modified time, the exported time can differ from the camera's. Two frames in the same second (burst) tie, so their order in a gallery is undefined. Not verified in Samsung Gallery; the expectation comes from the Exif 2.31 definition of those tags.
- Acceptance: read OffsetTimeOriginal and SubSecTimeOriginal from the RW2 with the capture time (BK-497) and write them to the export; if the camera did not record an offset write none rather than the phone's; test with two bursts in one second and a time zone change; phone check: export a burst, open in Samsung Gallery, confirm the order.
- Size: S. Files: ExportRunner.kt, core/data Indexer.kt (EXIF reader), tests. Risk: low.
- Src: ExportRunner.kt writeExif.

### BK-140 [P3] Export styling: border/frame and canvas padding (Instagram square with white border)
- Acceptance: border width percent and colour, padded to a target aspect; in the final pass.
- Size: S-M. Files: out.frag, ExportSheet.kt. Risk: low.

### BK-283 [P3] Export a "processed DNG" (linear 16-bit, demosaiced) for round-tripping to desktop editors
- Opportunity: Lightroom can export edited photos as DNG. A linear DNG keeps the dynamic range of an edit for Photoshop or other tools. LibRaw cannot write DNG, so this needs an own writer (TIFF/EP with LinearRaw, similar to `Tiff16Writer`).
- Acceptance: writes a valid LinearRaw DNG 1.4 with ColorMatrix for ProPhoto, as-shot neutral and the rendered (not edited) base; opens in Lightroom and darktable (Jai tests on a PC friend's machine only if available; otherwise validate with `dng_validate` in the sandbox).
- Size: L. Files: Exporter.kt (new DngWriter). Risk: medium-high.


---

# AREA G: RELIABILITY, DATA AND MIGRATION

Facts: Room db version 3, `exportSchema = false`, one explicit migration 2 to 3, `fallbackToDestructiveMigrationFrom(true, 1)`; edits/meta/snapshots/presets keyed by `name|size|modified`; CrashStore captures only Java uncaught exceptions to `last_crash.txt`; PerfLog is in-memory; backup is a zip of JSON plus mask/heal files with no checksum or encryption; `allowBackup=false`.

### BK-141 [P0] [DECLINED for now] Stable photo identity and a migration from `name|size|modified` keys (AUDIT open items 1 and 2)
- Status: docs/DECISIONS.md (6 Oct 2026): the key stays `name|size|modified`, reviewed in the fourth audit pass. A lighter alternative that respects that decision is BK-291. Re-open only if Jai overrides the decision.
- Problem: edit key collisions for identical files in different folders, edits orphaned by a touched file or copy, and re-indexing dropping rows (`stale` list deleted on modified/size change then reinserted with a new row id: thumbnails keyed by id are lost too).
- Why: Jai's edits are the thing of value in the catalogue. A card re-import or Samsung "restore" changing the modified time would silently drop them.
- Acceptance: new `contentId` column computed from the first 64 KB hash + size + EXIF DateTimeOriginal/SubSec + body serial where available; edits/meta/snapshots re-keyed in a migration with a pre-migration backup written to `filesDir/backups/`; fallback "relink" screen for orphaned edits ("12 edits not matched; match by name?"); identical files in two folders get separate keys by path+contentId; unit tests with a fake DB for rename, copy, duplicate, touched mtime.
- Size: L. Files: Photo.kt, Db.kt, Catalog.kt, Indexer.kt, DeviceScanner.kt, ThumbStore.kt, backup format. Risk: high. Depends on BK-147 migration tests first.

### BK-142 [P0] [DONE] Automatic backups of the catalogue (edits, meta, presets, snapshots) on every N edits and daily, kept as the last 7 rotating files
- Status: Done on main (AutoBackup.kt, app/backup/): three targets, verified run order, rotation to 7, daily and after-25-changes triggers on JobScheduler instead of WorkManager (decision in docs/DECISIONS.md), Settings section. SAF, MediaStore and the job are only statically verified; the reinstall check is Jai's. The first launch offer waits for W05.
- Problem: backup is a manual button (`Back up edits`); a crash or reinstall without a manual backup loses everything (also `allowBackup=false` so Android's own backup does not run). Since this is a sideload that gets reinstalled often (every release), a stale install with a wrong signing key means uninstall, which deletes the whole catalogue (see BK-143).
- Acceptance: a `WorkManager` periodic job (plus after 25 saved edits) writes `Documents/Rawline/backups/rawline-YYYYMMDD.zip` through a SAF folder chosen once (or MediaStore Documents collection with no permission) with a 7-file rotation; Settings shows last backup time and size; restore tested from the zip (BK-144).
- Size: M. Files: Catalog.kt writeBackup, new BackupWorker.kt, app/build.gradle.kts (work-runtime), Settings. Risk: low-medium (a new dependency; keep the tiny `androidx.work`).

### BK-143 [P0] [PARTLY DONE] Survive uninstall: keep a copy of the catalogue outside the app's private storage
- Status: Backups sit in Documents/Rawline/backups with All files access, or in a chosen folder, and Restore from a backup lists them; the first launch offer waits for W05; MediaStore survival after a reinstall is open (phone check).
- Problem: Room db and mask/heal PNGs live in app-private storage; uninstall or "Clear data" deletes them. The README says build flow may need the signing key to match; with debug-signed fallback, updates may not install over the top, which forces an uninstall.
- Acceptance: BK-142 backup target is outside app data by default (MediaStore `Documents/Rawline`), restore offered automatically on first launch if a backup is found, with a count and date.
- Size: S-M (on top of BK-142). Files: RawlineApplication.kt, Settings. Risk: low.

### BK-144 [P0] [DONE] Make restore safe: transactional, streaming, versioned, newer-wins per item
- Status: Done in commit 2ef67a7 (restore is one transaction, streamed with size caps, newer wins including ratings via meta updatedAt, Room 3 to 4). Left over: dry-run preview and integrity manifest, see BK-303.
- Problem: AUDIT open item 1: restore overwrites newer ratings, reads whole zip entries into memory (`z.readBytes()`), is not transactional, and `putMeta(metas)` replaces ratings unconditionally.
- Acceptance: a Room `withTransaction` for all table writes; per-key `updatedAt` on meta (currently MetaEntity has no timestamp: add one) with newer-wins; stream zip entries to temp files with a size cap; a pre-restore auto backup; summary "Restored 312 edits, 1,204 ratings, skipped 12 newer"; version check refuses format > supported with a clear message; unit tests with a corrupted zip.
- Size: M. Files: Catalog.kt, Db.kt (MetaEntity.updatedAt + migration), tests. Risk: medium.

### BK-145 [P0] [DONE] DeviceScanner/Indexer must never prune on a partial listing (AUDIT open item 3), and add a downgrade/rollback path for Room
- Status: Done: Room 4 to 3 downgrade (2ef67a7) and the device scan prune guard in 3cd7bb9 (`ScanPrune`: a listing under half of what is known is only believed when two scans in a row agree; an empty listing never prunes; tested in CatalogLogicTest). Left over: the SAF folder scan in Indexer still prunes when `complete && docs.isNotEmpty()`.
- Problem: an empty listing is guarded (`rows.isEmpty()`) but a partial one (permission partially revoked, SD card half mounted, MediaStore still scanning) prunes every row not seen, deleting ratings state from `photos` (meta table keeps ratings, but `edited` and thumbs go). There is no `fallbackToDestructiveMigrationOnDowngrade` handling, so installing an older APK over a newer DB crashes at start (a real case: Jai sideloads an older release).
- Acceptance: prune only rows missing for 3 consecutive complete scans (`missingCount` column) and never when the count drops over 30 percent in one scan without a confirmation; a downgrade opens read-only or exports the DB and starts fresh with a banner rather than crashing; unit tests with injected listings.
- Size: M. Files: DeviceScanner.kt, Indexer.kt, Db.kt. Risk: medium.

### BK-146 [P0] [DONE] Room: export schemas, write all migrations explicitly, and test them with MigrationTestHelper
- Status: Done in commit 2ef67a7 (schema export on, v3 and v4 JSON committed, host-side MigrationTest, explicit 4 to 3 downgrade, destructive wipe only from version 1). Left over: a CI check that schema JSON changes come with a migration, data-preserving tests (BK-305).
- Problem: `exportSchema = false`, one hand written migration 2 to 3, and destructive fallback from versions 1 (and `true` = drop all tables). Future columns (BK-098, BK-100, BK-141) will multiply the risk.
- Acceptance: `exportSchema = true` with `room.schemaLocation`, committed JSON schemas for v3 onward, `MigrationTestHelper` tests that migrate v3 to each new version with sample data, a CI step failing if schema JSON changes without a migration; remove destructive fallback for versions >= 3.
- Size: M. Files: core/data/build.gradle.kts, Db.kt, new androidTest or Robolectric tests, CI. Risk: low. Hard prerequisite for every other schema entry.
- Src: Room migrations best practice (S13, 6 Oct 2026).

### BK-147 [P0] [PARTLY DONE] Migration and backup round-trip test harness that runs on the JVM (Robolectric) in CI
- Status: Commits 2ef67a7 and 3cd7bb9: host-side MigrationTest, BackupReaderTest, RecipeReadTest, CatalogLogicTest (prune, reapply, save actions) and PhotoKeyTest exist. Left over: scanner with a fake resolver, XMP, Robolectric DAO tests, data-preserving migrations (BK-305).
- Problem: UI/library/data modules have no tests (AUDIT item 6 says nothing for library, loupe, core/ui). Backup, scanner and migrations are the riskiest code.
- Acceptance: `core/data` tests with Robolectric + in-memory Room: backup then restore into an empty DB equals original; scanner with a fake `ContentResolver` for full/partial/empty listings; XMP parse/write round trips; runs in `testDebugUnitTest`.
- Size: M. Files: core/data/build.gradle.kts (robolectric dependency), new tests. Risk: low.

### BK-148 [P1] [DONE] Capture native crashes and ANRs: use ApplicationExitInfo on next start and attach to the Copy report
- Status: Done in commit 15fb461 (core/cache ExitReasons reads ApplicationExitInfo, native tombstone hints, ANR trace head; shown in the report under "How the app last ended").
- Problem: CrashStore only captures Java exceptions. A native crash in LibRaw, the engine or the GPU driver (SIGSEGV) leaves no `last_crash.txt`; the audit says "no C++ exception escapes JNI" but signals are not C++ exceptions.
- Acceptance: on launch read `ActivityManager.getHistoricalProcessExitReasons` (API 30+), record the last 3 reasons (`REASON_CRASH_NATIVE`, `REASON_ANR`, `REASON_LOW_MEMORY`, `REASON_SIGNALED`) and for native crashes the tombstone summary from `getTraceInputStream()` (API 31+, minSdk is 31 so always available); append to Copy report; show "Last session ended in a native crash" banner once.
- Size: S-M. Files: PerfLog.kt (CrashStore), RawlineApplication.kt, Settings. Risk: low.
- Src: Android NDK debugging docs, tombstones via ApplicationExitInfo (S14, 6 Oct 2026).

### BK-149 [P1] [PARTLY DONE] Breadcrumbs: last 50 user and engine events in the crash report
- Status: Commit 15fb461: PerfLog keeps 100 errors and 40 timestamped events (memory trims, service stops). Left over: user action and screen breadcrumbs.
- Problem: a crash report shows only a stack; knowing "opened photo X, moved Exposure, applied preset Y, GPU delegate fallback" shortens diagnosis.
- Acceptance: a ring buffer of breadcrumbs (screen changes, recipe change kinds, model runs, memory trims, thermal changes) persisted on every crash and included in the report; no photo names unless the user turns on "include file names" (privacy BK-232).
- Size: S. Files: PerfLog.kt, call sites. Risk: low.

### BK-150 [P1] [DONE] Persist PerfLog samples across restarts and rotate files
- Status: Done in commit 15fb461 (PerfLog writes session.txt after errors and on stop, previous.txt kept, report shows both).
- Problem: `samples` are in memory only; after a crash the timing report is empty.
- Acceptance: flush to `filesDir/perf.json` every 30 s and on pause; keep the last 3 sessions; report shows "this session" and "previous session".
- Size: S. Files: PerfLog.kt. Risk: low.

### BK-151 [P1] [DONE] Autosave the editor recipe on a debounce, not only on exit
- Status: Already in the code (found in the second read): EditorHost saves 400 ms after the last change and flushes on pause and stop; leaving waits for the write.
- Problem: edits are saved when leaving (the Audit mentions a failed save blocking Back). A process kill while editing (low memory, thermal) loses the session's changes.
- Acceptance: debounce 1.5 s after the last change plus onStop; a "Recovered unsaved edit" toast if the app is restarted into the editor; saved atomically (write then replace); unit test with the fake catalog.
- Size: S-M. Files: EditorHost.kt, EditorState.kt, Catalog.kt. Risk: low-medium.

### BK-152 [P1] [PARTLY DONE] Atomic writes for mask layers, heal patches and thumbnails (write temp, fsync, rename)
- Status: Commit 5ca42a0: `ThumbStore.save` never throws and writes atomically (AU-056, AU-057). Left over: mask and heal stores and the XMP sidecar write.
- Problem: MaskStore/PatchStore/ThumbStore write files directly; a kill while writing leaves a truncated PNG referenced by a recipe, which could crash decode or show a corrupted mask.
- Acceptance: a shared `AtomicFile`-based writer; on load a corrupt file is treated as missing (the mask component is dimmed with a "missing layer" badge) not a crash; unit tests with truncated bytes.
- Size: S. Files: core/cache/MaskStore.kt, PatchStore.kt, ThumbStore.kt. Risk: low.

### BK-153 [P1] Garbage collect orphaned mask layers, heal patches and thumbnails
- Problem: layers are deleted? Unknown; recipes may reference keys while old files accumulate after paste (each pasted brush gets its own key) and undo history.
- Acceptance: a nightly/idle sweep deleting files not referenced by any edit, snapshot or preset; size shown in Settings storage screen (BK-186); sweep is safe with a 24 h grace period.
- Size: S-M. Files: MaskStore.kt, PatchStore.kt, Catalog.kt. Risk: low-medium (never delete a referenced file; unit test).
- Note: AUDIT item 9 (fifth pass) confirms thumbnails and orphaned mask or heal files are never swept.

### BK-154 [P1] [PARTLY DONE] Recipe schema versioning with forward-compatible reading and a "newer than this app" lock
- Status: Commit 7e0272f: RecipeRead (Missing, Ok, Unreadable) so an unreadable or newer edit is never exported unedited, pasted over or deleted. Left over: explicit versioned migrations and a read-only open for newer recipes.
- Problem: `schemaVersion = 1` is hard-coded and `lensv` is an ad hoc flag; reading an unknown newer recipe succeeds with defaults dropped, and the audit fix only prevents deleting an unreadable edit.
- Acceptance: each recipe has a version; the reader migrates old versions explicitly in a `RecipeMigrations` table; unknown future versions open read-only with a message; unit tests with fixtures per version stored in test resources (golden recipes).
- Size: M. Files: EditRecipe.kt, ModelTest.kt, test resources. Risk: low.

### BK-155 [P1] Preserve unknown JSON fields when round-tripping recipes (older app reading newer file)
- Acceptance: unknown keys kept in a `extras` JSONObject and written back so a downgrade then upgrade does not lose fields.
- Size: S. Files: EditRecipe.kt. Risk: low.

### BK-156 [P1] [PARTLY DONE] GPU context loss and driver failure recovery in the editor (not only the heal/mask layer re-upload)
- Status: Commit 3ad30b3: a restored context abandons the dead engine without GL calls, stale GL errors are drained, a failed upload keeps the source. Left over: driver failure notice flow and the layer re-upload test.
- Problem: the engine re-uploads layers after a context restore; general behaviours (surface destroyed during Samsung multi-window, screen off/on, `eglCreateContext` failing on low memory) are not covered by tests.
- Acceptance: a debug menu "Simulate context loss"; editor recovers within 1 s with the recipe intact and no black frame; a fallback "Preview unavailable, tap to retry" state rather than a crash; `GPU init failed` strings shown to the user.
- Size: M. Files: EditorSession.kt, EditorScreen.kt, engine.cpp. Risk: medium.

### BK-157 [P1] Low storage handling: check free space before export, model download, import and cache writes
- Problem: AUDIT notes a failed JPEG encode on full disk was reported as success (fixed); no pre-check or message.
- Acceptance: `StatFs` check with required estimate (24 MP TIFF16 about 145 MB) and a clear message; the cache purges itself first when below 1 GB.
- Size: S. Files: ExportRunner.kt, ModelStore.kt, import. Risk: low.

### BK-158 [P1] Process death restoration: reopen the same photo and tool after the system kills the app
- Problem: only `source` and `folder` are in prefs; editor state uses some `rememberSaveable`.
- Acceptance: a saved-state record (current photo id, route, tool) restores navigation on relaunch after process death within 30 minutes; verify by `adb shell am kill` (or developer option "Don't keep activities").
- Size: M. Files: MainActivity.kt, EditorHost.kt, navigation. Risk: low-medium.

### BK-291 [P1] Lighter identity fix after the BK-141 decision: content fingerprint as a secondary lookup, never replacing the legacy key
- Problem: the fourth audit pass reviewed the `name|size|modified` key and left it (docs/DECISIONS.md, 6 Oct 2026) because a full re-key would need a legacy fallback and break old backups. The two weaknesses remain: a changed modified time (copying a card to a new folder with a tool that does not keep times, a Samsung restore, cloud sync) orphans the edit, and identical copies share one edit.
- Opportunity: keep `Photo.keyOf` as is and add only a second column `fingerprint` (first and last 64 KB plus size, SHA-1 or xxHash, computed lazily when a photo is first opened or edited, not at index time) plus an `edit_alias` table `(fingerprint, key)` written whenever an edit is saved. When a scan finds a photo with no edit under its key but its fingerprint matches an alias, link the edit (copy the row under the new key, keep the old one). No re-keying and no change to the backup format.
- Why: the weakness is invisible until it bites (an edited photo opens clean after a copy). This protects work without the migration cost the decision rejected.
- Acceptance: unit test: edit a photo, change its modified time in the fake scanner, rescan: the edit is found; two identical files in different folders keep separate edits after the first is edited (alias stored with path hash); backup/restore unchanged (aliases optional in the zip); one extra 128 KB read on first open only (measure under `fingerprint_ms`).
- Size: M. Files: Db.kt (+migration, schema JSON), Catalog.kt, Photo.kt, EditorHost.kt. Risk: low-medium. Supersedes the plan in BK-141 (which stays DECLINED unless Jai overrides the decision).

### BK-303 [P1] [DONE] Restore preview ("dry run") and a backup integrity manifest
- Status: Manifest with SHA-256 per entry (format 2), verify before publish and before restore, restore preview, damaged file refused, format 1 still restores.
- Problem: after the audit hardening, restore is transactional and newer-wins, but it still runs straight after picking the file with the message "Restored N edits". `version.json` is written but not checked against a supported range, and entries have no checksums, so a damaged zip may be partly read before a JSON error is hit (JSON is validated; PNG bodies are only header-checked).
- Acceptance: the zip gains `manifest.json` (counts per table, per-entry SHA-256, app version, creation time); restore first shows "This backup has 312 edits, 1 204 ratings, 18 presets, from 4 Oct. 41 edits and 230 ratings are newer on this phone and will be kept. Restore the rest?" with Cancel; checksum mismatch aborts before any write; older backups without a manifest still restore with the existing rules; tests in BackupReaderTest.
- Size: M. Files: core/data/BackupFormat.kt, Catalog.kt, LibraryViewModel.kt, MainActivity.kt. Risk: low.

### BK-308 [P1] [PARTLY DONE] Catalogue work runs on the main dispatcher: XMP writes, edit pastes and ratings can block the UI
- Status: Commit 5ca42a0: rate, flag, label, copy, paste, sync and queueing run on the IO dispatcher and the XMP step runs on IO inside Catalog (with a notice for photos that cannot have a sidecar, AU-063). Left over: `saveMeta` still queries per photo; XMP writes are not yet one queued worker with a single summary.
- Problem: `LibraryViewModel.rate/flag/label/pasteEdits/syncEdits` use `viewModelScope.launch { ... }` (Main). Room suspend calls are main-safe, but the rest is not: `Catalog.saveMeta` calls `Xmp.write` (ContentResolver reads and writes of sidecars, blocking) per photo when XMP is on, and does `edits.metaFor` plus `edits.get` per photo; `pasteEdits` parses and re-serialises every target recipe JSON (`EditRecipe.fromJson`, `RecipeMerge.paste`, `toJson`) on the main thread. Rating 400 photos with sidecars on, or pasting onto 200, can stall the UI or trigger an ANR.
- Acceptance: these functions run in `Dispatchers.Default`/`IO` (`withContext` inside Catalog, with a `// main-safe` rule), XMP writes are queued to one background worker with a progress count and failures collected (one summary toast), `saveMeta` batches the `metaFor` lookups; StrictMode (BK-162) shows no violations; rating 400 photos with XMP on keeps frames under 25 ms on the phone.
- Size: S-M. Files: core/data/Catalog.kt, app/LibraryViewModel.kt. Risk: low.

### BK-353 [P1] Disk full: handle SQLite and cache write failures without losing the session
- Scenario: the phone fills during a shoot (video clips, a full import). Rawline writes edits to Room, thumbnails and mask layers to files, and export outputs. Nothing catches `SQLiteFullException` or `SQLiteDiskIOException` (grep found no handling); a failed save in the editor is caught in the Back handler only; thumbnail and mask writes use plain streams.
- Acceptance: a `StorageGuard` wraps Room writes in the catalogue and meta paths, thumbnail and layer stores; on a full disk it keeps the unsaved recipe in memory, shows a sticky banner ("Storage full: edits are held in memory. Free some space"), offers a "Free space" screen with sizes (BK-186) and a Retry; the recipe is written when space returns; never crashes; unit test with a fake DAO throwing; manual test by filling the phone with a large file.
- Size: M. Files: new core/data/StorageGuard.kt, Catalog.kt, EditorHost.kt, ThumbStore.kt, MaskStore.kt. Risk: low-medium. Complements BK-157.

### BK-355 [P1] Corrupt, truncated or unsupported RW2: partial decode, preview-only editing and clear states
- Scenario: a card copied badly or pulled mid-write leaves a truncated RW2. In `raw_decode.cpp` any non-success from `unpack()` or `dcraw_process()` fails the whole decode ("unpack: ..."), so Edit shows an error and Jai gets nothing, even though LibRaw usually returns `LIBRAW_DATA_ERROR` for "corrupted data or unexpected EOF" with most rows already decoded (LibRaw docs and forum, checked 6 Oct 2026). The indexer marks unreadable files `indexed = 1, width = 0` and the grid shows a blank tile.
- Acceptance: treat `LIBRAW_DATA_ERROR` (and `UNEXPECTED_EOF`) from `unpack` as a partial result: continue to `dcraw_process`, return the image with a `partial = true` flag and the percentage of rows decoded (rows the decoder did not fill stay black or are filled by the embedded preview); the editor opens with a banner "This file is damaged: about 62 percent of the picture could be read"; if the raw cannot be decoded at all, open the embedded JPEG as a non-RAW edit (BK-275 path) with the same banner; the grid tile for an unreadable file shows a distinct "Damaged" badge and the embedded thumbnail when it can be read; the report records the LibRaw code; never retried in a loop (`indexed` marker plus reason column); export of a damaged file warns first.
- Size: M. Files: core/native/raw_decode.cpp, raw_decode.h, jni_engine.cpp, EditorSession.kt, Indexer.kt, LibraryScreen.kt. Risk: medium (partial images can contain garbage in the damaged rows: clamp and mask them).
- Src: LibRaw API docs and forum on `LIBRAW_DATA_ERROR` (checked 6 Oct 2026): https://www.libraw.org/node/2298

### BK-361 [P1] Upgrade from an old build: a fixture matrix and a first-launch-after-update check
- Scenario: Jai installs a new build over one from days or weeks ago. Things that must still work: Room versions 1 to 4 (wipe only from 1), recipes saved before `lensv` (the optics default flips to on), old mask and heal PNG naming, preferences keys (`export` JSON, `folders` set, `source`), model files (hash-pinned names), thumbnails keyed by row id, the export queue with jobs in any state, and backups from older zips.
- Acceptance: test fixtures (a DB file per version, recipe JSON per era, a backup zip per era, a prefs XML) checked into core/data test resources and run on the host: open, migrate, read; a small first-launch routine after a version change that runs `PRAGMA integrity_check`, counts rows before and after (stored in prefs) and shows a banner if something shrank; docs/RELEASE.md lists data format changes per release; version code monotonic (BK-242).
- Size: M. Files: core/data tests, RawlineApplication.kt, docs. Risk: low. Extends BK-305.

### BK-363 [P1] GPU out of memory while editing or exporting a large file: detect it and degrade instead of failing
- Scenario: a 44 MP or 96 MP file needs a 358 MB to 768 MB source texture plus mips (BK-324). `glTexStorage2D` can fail with `GL_OUT_OF_MEMORY` or the context can be lost; `engineSetSource` returns false and the editor shows "GPU upload failed".
- Acceptance: the engine checks `glGetError` after large allocations and returns a distinct code; the session retries at half size (or skips mips, or tile-streams the source) and shows "Opened at half size to fit the GPU"; export uses banded source upload; the report records the failing size and the fallback taken; a debug switch "Pretend GPU memory is 256 MB" for testing; golden harness test with a size limit.
- Size: M. Files: engine.cpp, jni_engine.cpp, EditorSession.kt, Exporter.kt. Risk: medium.

### BK-473 [P1] After a reinstall the new app may not be able to read backup zips that the old install wrote through MediaStore (verify on the phone)
- Finding: on Android 11 and later a file the app created in shared storage is owned by that app's UID; after uninstall and reinstall the new install is a different owner and cannot list or open it without a picker or the All-files permission. The old plan (MediaStore Documents only) therefore may not give "uninstall is no longer a loss". This is an expectation from the platform rules, not something measured on the S24 Ultra.
- Why it matters to Jai: the whole point of BK-142 is surviving a reinstall or a key change (BK-226).
- Acceptance: W06 target order is All-files folder first, then a SAF folder Jai chose (a persisted tree grant is lost on uninstall, but the files stay and the picker can reselect), then MediaStore as the weakest fallback and labelled so in Settings. Phone test: make a backup, uninstall, reinstall, open Restore: record which target worked on the S24U.
- Size: S for the test, in W06. Files: W06-auto-backups.md D1. Risk: medium (the app already holds All-files access, so the first target should work; confirm).
- Src: W06-auto-backups.md section 2.

### BK-159 [P2] [PARTLY DONE] Export job recovery after a crash: `resetRunning` exists; add resume of partial output cleanup and a retry limit
- Status: Commits 42b80f0 and dd68762: jobs left running are reset at app start, retry refuses a running job, a failed Gallery publish (IS_PENDING not cleared) fails the job and deletes the file, Share has its own cancel flag. Left over: sweep stray pending rows, retry limit.
- Acceptance: leftover `IS_PENDING` rows in MediaStore deleted on the next start; a job failing twice stays failed with its reason; tested with a fake runner.
- Size: S. Files: ExportRunner.kt, ExportService.kt. Risk: low.

### BK-160 [P2] Unexpected file changes: detect when the source RAW is modified, moved or deleted while an edit exists
- Acceptance: grid shows an "offline" badge for missing sources (SD card removed), edits remain, Edit opens with a "Source missing" message and offers relink; no data deleted.
- Size: M. Files: Db.kt, Indexer.kt, LibraryScreen.kt. Risk: low.

### BK-161 [P2] Thread-safety review of the engine call rule (all calls on the GL thread) with a debug assertion
- Problem: CLAUDE.md rule "every engine call must be on the GL thread"; violations show up as rare corruption.
- Acceptance: `Native.engine*` wrappers check the thread in debug builds and log in release; one unit/instrumented test enumerates entry points.
- Size: S. Files: core/native Native.kt, EditorSession.kt. Risk: low.

### BK-162 [P2] StrictMode in debug builds plus a "main thread I/O" budget report
- Acceptance: StrictMode thread and VM policies enabled in debug with `penaltyLog`, violations appended to PerfLog; zero violations on library open and loupe swipes (fix the ones found).
- Size: S. Files: RawlineApplication.kt. Risk: low.

### BK-163 [P2] Memory leak checks: LeakCanary in debug for Activity, Bitmap and native handle leaks
- Acceptance: LeakCanary debug-only dependency; a manual leak test script (open/close editor 20 times) with `native_handles_live` counter in the report that must return to 0.
- Size: S. Files: app/build.gradle.kts, Native.kt counters. Risk: low.

### BK-164 [P2] [PARTLY DONE] Defensive limits on untrusted input: enormous or malformed RW2/JPEG/XMP/zip
- Status: Commit 2ef67a7: backup zip entries are allow-listed, size capped and PNG-header checked. Left over: fuzzing the RW2 parser and EXIF reader.
- Problem: AUDIT fixed an IFD loop and JPEG length check; XMP parse (regex), zip restore and LibRaw on crafted files remain.
- Acceptance: a small fuzz corpus under tools/fuzz run in CI for the RW2 preview parser (libFuzzer build of rw2_preview.cpp), zip-slip prevention in `readBackup` (entry names are flattened with replace("/", "_"), keep and test), max entry sizes.
- Size: M. Files: tools/fuzz (new), rw2_preview.cpp, Catalog.kt. Risk: low.

### BK-284 [P2] Export queue survives process death without relying on app launch (WorkManager expedited)
- Problem: `ExportService` returns `START_NOT_STICKY`; after a kill the queue is recovered only by `recoverAfterStart()` when Jai next opens the app.
- Acceptance: queued jobs are rescheduled with an expedited `WorkManager` request that restarts the foreground service even if the app is closed; resume notification shows "Resuming 12 of 40"; limited by the system quota and degrades to the existing behaviour.
- Size: M. Files: ExportRunner.kt, ExportService.kt. Risk: low-medium.

### BK-304 [P2] Include app settings, export presets and model delegate choices in the backup
- Problem: the zip holds edits, snapshots, presets, meta and image layers. The export settings, XMP toggle, grid columns, recent folders and any future collections/search are in SharedPreferences or new tables and are lost with the data.
- Acceptance: `settings.json` (an allow-list of preference keys, no secrets, folder URIs excluded because the permissions do not survive) written and restored under newer-wins; new tables (BK-100 collections) added to the same format with a `formatVersion` bump and reader tests.
- Size: S-M. Files: Catalog.kt, BackupFormat.kt. Risk: low.

### BK-360 [P2] Permission revoked mid-session: define and test each case
- Cases: media permission revoked in system settings (Android kills the process, so restore after restart, BK-158); All files access turned off while an export of device photos is queued (reads start failing: the job should say "Access to files was turned off" and the queue should pause); SAF tree permission revoked for a source folder (the grid keeps cached rows but the previews fail: show the source as offline, BK-160); notification permission denied (service continues).
- Acceptance: a test table in docs/ROBUSTNESS.md with the steps Jai can do on the phone in Settings and the expected app behaviour; one fix per failing row; the Copy report prints the permission snapshot at report time (done) and at the time of each failure (`PerfLog.event`).
- Size: M. Files: ExportRunner.kt, Indexer.kt, LibraryViewModel.kt, docs. Risk: low.

### BK-364 [P2] Media removed during import or export: stop cleanly, remove partial files and resume
- Scenario: the SD card slips out of the reader or the USB drive is knocked during a copy or an export to that drive; writes fail with I/O errors; partial files may remain with a good-looking name.
- Acceptance: copy and export write to a temporary name (`.part` for SAF, `IS_PENDING` for MediaStore, exists) and rename only after verification; on an I/O error the job pauses with the reason "Storage was removed", partial files are deleted, and when the volume returns the batch resumes from the first unfinished file (import: by checking destination size and checksum); tests with a fake file system that throws after N bytes.
- Size: M. Files: new feature/import, ExportRunner.kt. Risk: medium.

### BK-464 [P2] RawPrefetch leaks a native decode when cancelled mid-flight (audit AE-024)
- Finding: a decode that finishes after `cancel()` or after the photo left the wanted set is stored into `ready` without a check and is never freed (about 100 MB each).
- Acceptance: the result is freed when its photo is no longer wanted or the prefetch was cancelled; a test with a fake decoder shows handle counts return to zero after rapid swipes; a `native_handles_live` counter in the report (BK-163).
- Size: S. Files: core/render/RawPrefetch.kt. Risk: low.
- Src: audit-engine.md AE-024.

### BK-471 [P2] A compiled Python file is committed (`tools/studio/__pycache__/studio_ref.cpython-311.pyc`) and nothing ignores `__pycache__`
- Finding: `git ls-files` lists the .pyc; it changes with every run of the Studio reference on a different Python and will show as a dirty file or a merge conflict.
- Why it matters to Jai: noise in every diff; a stale .pyc can hide a source change if timestamps line up.
- Acceptance: `git rm --cached` the file, add `__pycache__/` and `*.pyc` to .gitignore, CI step `git ls-files | grep -E '\.pyc$'` must print nothing.
- Size: S. Files: .gitignore, tools/studio. Risk: none.
- Src: found 6 Oct 2026 while reading the S1b tree.

### BK-165 [P3] Database integrity check and VACUUM on idle, and a "Repair catalogue" tool
- Acceptance: `PRAGMA integrity_check` weekly in a worker; on failure restore from the latest auto backup (BK-142); `wal_checkpoint(TRUNCATE)` before backup; Settings shows DB size.
- Size: S. Files: Db.kt, worker. Risk: low.

### BK-301 [P3] `Catalog.addSnapshot` finds its new id by reading the newest row; use the insert's row id
- Problem: it inserts then queries `edits.snapshots(key).firstOrNull()?.id`, which can return the wrong row when two snapshots share a millisecond (ordered by `createdAt DESC`).
- Acceptance: `@Insert suspend fun addSnapshot(...): Long` and return it; test with two inserts at the same clock value.
- Size: S. Files: Db.kt, Catalog.kt. Risk: low.

### BK-472 [P3] An untracked directory whose name contains a Markdown code fence sits next to `Blend.kt` in core/studio-model
- Finding: a stray directory (name starts with three backticks) created by a worker's shell; it is untracked, so `git add -A` would commit it, and Windows checkouts cannot create the path.
- Acceptance: delete it; add a CI check that no tracked or untracked path contains a backtick (`git ls-files -o --exclude-standard`).
- Size: S. Files: none in source. Risk: none.
- Src: noted in W20-studio-s1c.md section 6.


---

# AREA H: ACCESSIBILITY

Facts: slider semantics (range, value, set, reset) were added in the audit; several icon buttons have `contentDescription`; touch targets under 48 dp remain (AUDIT open item 4); UI_SPEC sets compact density (40 px main controls, 44 px icon targets); colour tokens #9A9A9A secondary on #1C1C1C, accent #437EE4.

### BK-166 [P1] Bring every interactive control to 48 dp minimum hit area without changing the visual size
- Problem: UI_SPEC compact density gives 40 dp buttons and 44 dp icon targets, and AUDIT item 4 lists targets under 48 dp. Material's own guidance is a 48 dp minimum, and WCAG 2.2 SC 2.5.8 allows 24 dp with spacing.
- Why: Jai edits one-handed on a large phone; small tiles near the edge are the common mis-tap. The UI rule "big touch targets" is also in the spec.
- Acceptance: a shared `Modifier.minTouch()` (visual size unchanged, hit area expanded to 48 dp using `minimumInteractiveComponentSize`-style padding) applied in core/ui controls; a debug "Show touch targets" overlay; an automated check with the Compose accessibility test `assertTouchHeightIsAtLeast(48.dp)` over library, loupe and editor screens.
- Size: M. Files: core/ui/Controls.kt, LrTheme.kt (LrDim), all feature screens. Risk: low-medium (adjacent hit areas overlap; check dock icon spacing).
- Src: Android Compose accessibility key steps (S9, 6 Oct 2026); WCAG 2.2 SC 2.5.8 (S9).

### BK-167 [P1] TalkBack pass on a real phone: grid tiles, loupe, editor, masking, export
- Problem: sliders are covered; grid tiles, photo canvas, dock tools, mask handles, and the crop workspace have no documented semantic labels or focus order. The audit lists TalkBack as NOT_RUN.
- Acceptance: each grid tile announces "Photo 12 of 340, 4 stars, picked, edited, taken 4 Oct 2026"; loupe announces position and offers actions (rate, flag, edit); editor tools are a labelled list with state ("Light, selected"); crop handles have keyboard/accessibility actions (nudge by 1 percent); a written checklist in docs/ACCESSIBILITY.md with Jai's TalkBack result for each screen.
- Size: M. Files: LibraryScreen.kt, LoupeScreen.kt, EditorChrome.kt, MaskOverlay.kt, GeometryPanel.kt. Risk: low.
- Src: S9, S17 (Compose semantics).

### BK-168 [P1] Custom accessibility actions on every direct manipulation (rating, flag, copy/paste, mask handles, curve points)
- Problem: sliders have custom actions; gestures like long press to select, pinch to change columns, swipe up for info and hold to see original have no alternative.
- Acceptance: `semantics { customActions = ... }` for tile (Select, Rate 1-5, Pick, Reject), loupe (Info, Zoom to 100 percent, Show original), curve point (Move up/down/left/right, Delete) and brush (Undo stroke); one-handed alternatives for pinch (columns +/- buttons).
- Size: M. Files: LibraryScreen.kt, LoupeScreen.kt, CurvePanel.kt, MaskTray.kt. Risk: low.
- Src: Compose semantics custom actions (S17, 6 Oct 2026).

### BK-169 [P1] Colour contrast audit of UI_SPEC tokens (accent text on charcoal falls under 4.5:1)
- Problem: calculating the tokens: #437EE4 on #1C1C1C is about 4.3:1 (below the 4.5:1 AA for normal text); #9A9A9A on #1C1C1C is about 6:1 (fine); #676767 disabled is about 3:1 (exempt for disabled, but check for non-disabled use).
- Acceptance: a unit test computing contrast ratios for every foreground/background pair declared in LrTheme and failing under 4.5:1 for text and 3:1 for icons/borders; accent text variant lightened (for example #5B93F0 or a lighter tint) for text only while keeping #437EE4 for fills (UI_SPEC says approximately; record in DECISIONS.md).
- Size: S. Files: core/ui/LrTheme.kt, new test. Risk: low (visual change is small).

### BK-170 [P2] Dynamic font size: test at 200 percent and Display size Largest; avoid clipped sliders and truncated labels
- Problem: compact layouts with fixed heights (46 dp slider block, 64 dp rail) will clip at large font scale; UI_SPEC prefers dense.
- Acceptance: screenshot tests (Paparazzi/Roborazzi) at fontScale 1.0, 1.3, 2.0 for the library header, tray, export sheet and settings; text uses `sp` everywhere and rows use `heightIn(min=)` not fixed height; editor falls back to a scrollable tray at scale above 1.3.
- Size: M. Files: core/ui/Controls.kt, LrTheme.kt (LrDim), feature screens. Risk: medium.

### BK-171 [P2] Respect "Remove animations" and animator duration scale
- Acceptance: LrMotion reads `Settings.Global.ANIMATOR_DURATION_SCALE` and `ACCESSIBILITY_...` (via `LocalReduceMotion`-like helper) and sets all tweens to 0 when disabled; no animation-dependent state.
- Size: S. Files: core/ui/LrTheme.kt (LrMotion). Risk: low.

### BK-172 [P2] Do not rely on colour alone: colour labels, flags, mask overlays and clipping warnings need shapes or text
- Problem: colour labels (red/yellow/green/blue/purple) and the red mask overlay are colour only; ~8 percent of males have red-green deficiency.
- Acceptance: labels show a letter or pattern in an optional accessibility mode (Settings), flag icons are distinct shapes (flag and X), mask overlay has a hatch option, clipping markers use different patterns (BK-054).
- Size: S-M. Files: LibraryScreen.kt (LabelColors), MaskUi.kt, main.frag. Risk: low.

### BK-173 [P2] Focus order, visible focus and hardware keyboard navigation
- Acceptance: tab traversal through top bar, tools, sliders; visible focus ring in the Lr style (UI_SPEC says thin low contrast borders; use the accent); Enter/Space activate; arrow keys adjust sliders (step) and Shift for 10x.
- Size: S-M. Files: core/ui/Controls.kt. Risk: low.

### BK-174 [P2] Describe the histogram and image statistics for screen readers (text summary)
- Acceptance: the histogram announces "Shadows 5 percent, midtones 70 percent, highlights 25 percent, 0.3 percent clipped"; a long press reads the pixel value under the finger in loupe at 100 percent.
- Size: S. Files: core/ui/Controls.kt, EditorSession histogram. Risk: low.

### BK-175 [P3] One-handed reachability: option to move top-bar actions to the bottom and make tray height adjustable
- Problem: the S24 Ultra is 79 mm wide and 162 mm tall; top bar actions (undo, share, more) are at the far edge.
- Acceptance: Settings "Reachability mode" moving the top bar controls into a bottom bar in the loupe and editor; the tray remains bottom anchored (it is, per UI_SPEC).
- Size: M. Files: EditorChrome.kt, LoupeScreen.kt. Risk: low.


---

# AREA I: BATTERY, THERMAL AND STORAGE

Context: S24U Snapdragon 8 Gen 3 reaches roughly 45 degrees C in sustained load in reviews and can lose a third of GPU performance over 15 minutes of load (Notebookcheck/NextPit reports, checked 6 Oct 2026). Rawline runs GPU edit, AI inference and foreground exports; no thermal API use, no `onTrimMemory`, no battery awareness exists (grep: none).

### BK-176 [P1] Thermal-aware work: listen to `PowerManager.addThermalStatusListener` and back off prefetch, AI warmup and export concurrency
- Problem: no thermal handling anywhere. The Thermal API exists since Android 11 and the NDK API since Android 12; minSdk is 31 so both are available.
- Acceptance: a `ThermalGovernor` with statuses mapped to policies (LIGHT: no change; MODERATE: halve prefetch, stop speculative AI; SEVERE: pause export and show "Cooling down"; CRITICAL: stop GPU work); `getThermalHeadroom(30)` used to predict export slowdowns; statuses and transitions recorded in the Copy report; unit tests with a fake source.
- Size: M. Files: new core/cache/ThermalGovernor.kt, RawPrefetch.kt, ExportRunner.kt, AiMasks.kt. Risk: low.
- Src: Android Thermal API / ADPF docs (S8, 6 Oct 2026).

### BK-177 [P1] AI work policy: speculative AI (warm SAM, background denoise, semantic index) only when charging or battery above 40 percent, never in Battery Saver
- Acceptance: a central `WorkPolicy.canRunBackground(kind)` reading `BatteryManager`, `PowerManager.isPowerSaveMode`, charging state, and thermal status; user override per task; all background AI/index tasks call it.
- Size: S-M. Files: new core/cache/WorkPolicy.kt, Indexer.kt, AiMasks.kt. Risk: low.

### BK-181 [P1] 30 minute soak test mode (scripted) with leak and thermal readout
- Problem: PERF.md "Checks that need the phone" includes a 30 minute soak test with no tool to run it.
- Acceptance: Settings (debug) "Soak test": loops open-loupe/swipe/edit/slider/export for 30 minutes, logs RSS, native handles live, GL memory estimate, frame p95 every minute and thermal status; fails loudly on growth above a threshold; included in the speed test (BK-001).
- Size: M. Files: new app/SoakTest.kt, PerfLog.kt. Risk: low.

### BK-186 [P1] [PARTLY DONE] Storage screen: sizes of thumbnails, previews, masks, heals, models, backups, exports; clear and cap each
- Status: Commit 5ca42a0: the thumbnail disk cache is capped at 300 MB, least recently used first (CacheTrim). Left over: the Settings storage screen and caps for the other caches.
- Problem: cache dir contents grow (thumbs per id, share dir, export-tmp), mask/heal PNGs accumulate, models take up to 0.7 GB, and nothing shows it; Android may also clear `cacheDir` unexpectedly.
- Acceptance: Settings > Storage with a stacked bar and per-category rows; "Clear thumbnails/previews", "Delete models", "Remove orphaned layers" (BK-153); an LRU cap (default 2 GB cache) enforced at start and after index.
- Size: M. Files: feature/settings, core/cache/*, ModelStore.kt. Risk: low.

### BK-324 [P1] Memory budget by megapixel count: write the table, then pick modes automatically (24 MP, 44 MP, 96 MP)
- Problem: the app has been sized for the 24.2 MP S5IIX only. The numbers below come from the code paths read (raw_decode.cpp, Exporter.kt, EditorSession) and need confirming with `native_heap` in the Copy report (BK-180) on the phone.
  - 24.2 MP (6000 x 4000): full decode buffer from LibRaw (ushort x4 = 8 bytes per pixel) 194 MB plus the half-float copy `out.half` 194 MB = about 390 MB peak on the CPU; GPU source texture RGBA16F 194 MB plus mip chain (+33 percent) about 259 MB. Half-size (3000 x 2000): 48 MB + 48 MB = 96 MB per prefetched photo (RawPrefetch holds 3). JPEG export buffers: 96 MB `ByteBuffer` + 96 MB `Bitmap` copy.
  - 44.3 MP (for example Lumix S1R II, 8192 x 5464 class): full 358 MB + 358 MB = about 716 MB CPU peak; GPU 358 MB + mips 477 MB; half-size 90 MB x 2 = 180 MB per prefetch; export buffers 179 MB x 2.
  - 96 MP (S5IIX High Resolution mode, 12000 x 8000): full 768 MB + 768 MB = about 1.5 GB CPU peak; GPU 768 MB + mips 1.0 GB; half-size 192 MB x 2 = 384 MB per prefetch; export buffers 384 MB x 2 = 768 MB.
  - The S24 Ultra has 12 GB RAM and a 16384 px texture limit on Adreno (verify in the report, BK-221), so 96 MP fits a texture but the peak can trigger the low-memory killer while other apps are cached.
- Acceptance: the table (with measured native and GPU memory per size) in docs/PERF.md; a `MemoryPlan` that takes pixel count and `ActivityManager.MemoryInfo.availMem` and chooses: prefetch count (3, 1 or 0), whether to keep a half-size base only, whether the full decode streams in strips, export band height; used by RawPrefetch, EditorSession.ensureFull and Exporter; a unit test with the three sizes; never allocate a buffer the plan says is unaffordable (show "This photo is very large: opening at half size").
- Size: M. Files: new core/render/MemoryPlan.kt, RawPrefetch.kt, EditorSession.kt, Exporter.kt, docs/PERF.md. Risk: low-medium. Builds on BK-013, BK-039 and BK-253.

### BK-354 [P1] Low battery policy: warn before and pause long jobs, and cheapen the editor
- Scenario: late in a shoot the battery is at 12 percent and Jai queues 40 exports. The export runs at full GPU load and the phone may die mid-batch; nothing in the app checks battery (grep shows no battery code outside the new report).
- Acceptance: `WorkPolicy` (BK-177) with thresholds: below 20 percent not charging, the export dialog warns with an estimate of the cost and offers "Export anyway" or "Wait until charging"; below 8 percent the queue pauses itself with a notification and resumes on charge; the editor offers "Battery saver editing" (BK-178); import (copy only) is allowed to continue; thresholds in Settings; unit tests with a fake battery source.
- Size: S-M. Files: ExportRunner.kt, ExportService.kt, ExportSheet.kt, WorkPolicy. Risk: low.

### BK-178 [P2] HDR and 120 Hz editing mode battery cost: measure and expose a Battery saver mode
- Acceptance: Settings "Battery saver editing" caps editor to 60 Hz, 1/2 res preview during drag, no speculative decode; measure `dumpsys batterystats` mAh for a 10 minute edit session in both modes (documented in PERF.md).
- Size: S. Files: Settings, EditorScreen.kt. Risk: low.

### BK-179 [P2] Run the indexer and thumbnail sweep as constrained background work
- Problem: indexing 1000 files runs in the app scope with a semaphore of 4 and no battery or Doze constraints; it is lost if the process dies mid-scan (the pending rows resume next scan, fine).
- Acceptance: `WorkManager` job (expedited when the user pressed Rescan, otherwise constrained to battery not low), resumable (pending = `indexed = 0`), progress shown in the library header.
- Size: M. Files: Indexer.kt, new IndexWorker.kt. Risk: low.

### BK-180 [P2] [PARTLY DONE] Battery and thermal state in the Copy report
- Status: Commit 15fb461: DeviceReport prints thermal status, battery level and temperature, battery saver, memory, storage, permissions. Left over: display refresh rate and HDR capability.
- Acceptance: report prints battery level and temperature at report time, `thermalStatus` history for the session, `isPowerSaveMode`, `hasGainMap`/colour mode in use, refresh rate; helps interpret slow timings.
- Size: S. Files: PerfLog.kt. Risk: low.

### BK-182 [P2] Partial wake lock only while exporting, and release on every exit path
- Acceptance: `PARTIAL_WAKE_LOCK` with a timeout held by ExportService while a job runs (Doze can pause the thread otherwise on long exports with the screen off); lock count in the report; test for release on cancel/failure.
- Size: S. Files: ExportService.kt. Risk: low.

### BK-183 [P2] Verify the GL view renders only when dirty and the editor stops all frames when the screen is idle or app is paused
- Acceptance: a counter `frames_idle` stays 0 over 60 s of no input; GL thread paused in `onPause`; no continuous `Choreographer` loop (FrameMonitor stops).
- Size: S. Files: EditorScreen.kt GL view, FrameMonitor.kt. Risk: low.

### BK-184 [P2] Cap and monitor native memory (RawPrefetch handles, mask layers, overlay) in the report
- Acceptance: counters for native bytes allocated by decode/engine, shown in the report next to heap; `RawPrefetch` capacity adapts to `ActivityManager.MemoryInfo.availMem` and `lowMemory`.
- Size: S. Files: RawPrefetch.kt, Native.kt, PerfLog.kt. Risk: low.

### BK-187 [P2] [MERGED] Bound the RAM disk-like caches: PreviewCache on disk for the last 200 screen-size previews
- Status: Merged into BK-008 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-008.
- Problem: PreviewCache is memory only (1/4 heap); a cold swipe costs a full embedded JPEG decode.
- Acceptance: optional disk cache of decoded 2048 px WebP/JPEG (or the raw JPEG slice is already small: measure first; the parse takes 5-12 microseconds, decode dominates); implement only if `tier2_decode_ms` > 40 ms; evaluated via BK-001 data.
- Size: S-M. Files: PreviewCache.kt. Risk: low.

### BK-188 [P2] Clean share and export temp files on startup and after use
- Acceptance: `export-tmp` cleared at queue start (exists), `share` cleaned after 1 hour (exists): add cleanup on app start and a hard cap (200 MB); tests in ExportTest for cleanup logic.
- Size: S. Files: ExportRunner.kt. Risk: low.

### BK-189 [P2] Show model cost before download and keep only what Jai uses (delete rarely used packs)
- Acceptance: each "Use AI X" prompt states size and time on mobile/Wi-Fi; a "Free up space" suggestion if a pack has not been used for 60 days.
- Size: S. Files: ModelStore.kt, MaskTray.kt. Risk: low.

### BK-190 [P2] [MERGED] Pause long jobs under thermal SEVERE and resume automatically
- Status: Merged into BK-176 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-176.
- Acceptance: export queue and AI denoise batch pause with a visible reason in the notification and queue screen; resume when status < MODERATE for 60 s.
- Size: S. Files: ExportRunner.kt, ThermalGovernor. Risk: low. Depends on BK-176.

### BK-332 [P2] Characterise sustained performance on the S24 Ultra and let the export and edit tile sizes adapt
- Facts: reviews measure the Snapdragon 8 Gen 3 reaching about 45 degrees C in sustained gaming load with roughly a third of GPU performance lost over 15 minutes (Notebookcheck and NextPit reports, checked 6 Oct 2026). Rawline's GPU edit, AI inference and long exports are sustained loads.
- Acceptance: the soak test (BK-181) logs, every minute, slider frame p95, export throughput (megapixels per second) and thermal headroom (`getThermalHeadroom(30)`); results table in docs/PERF.md for 5, 10 and 20 minutes; the Exporter reduces concurrency or inserts short pauses when headroom drops below 0.8 (BK-176); `PowerManager.isSustainedPerformanceModeSupported()` recorded and, if supported, tried during long exports with the measured effect.
- Size: M. Files: PerfLog/SoakTest, ThermalGovernor, ExportRunner.kt. Risk: low.
- Src: Android thermal API and ADPF docs (S8, 6 Oct 2026).

### BK-347 [P2] Samsung battery optimisation: detect "sleeping" or restricted state and guide Jai to exempt Rawline
- Facts: One UI puts apps it thinks idle into Sleeping or Deep sleeping and restricts background battery use; the standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent behaves oddly on Samsung (developer forum and user guides, checked 6 Oct 2026). A long export or import left in the background can be stopped.
- Acceptance: before a long background job (export over 10 photos, import, model download) check `ActivityManager.isBackgroundRestricted()` and `PowerManager.isIgnoringBatteryOptimizations`; if restricted, show once a plain explanation with a button to the app's battery settings and the steps to set Unrestricted and add Rawline to Never sleeping apps (tap-by-tap text for the S24 Ultra, One UI version recorded); the Copy report prints both flags; the foreground service notification stays visible so the user can see the job is alive.
- Size: S-M. Files: ExportService.kt, Settings, ReportBuilder.kt. Risk: low.
- Src: Samsung developer forum thread on never sleeping apps and the guide at docs.sportstracklive.com (checked 6 Oct 2026): https://forum.developer.samsung.com/t/optimize-battery-usage-vs-never-sleeping-apps/16047

### BK-434 [P2] Samsung memory and process behaviour: RAM Plus, background process limits and how Rawline is killed
- Facts: the S24 Ultra has 12 GB RAM and Samsung's RAM Plus uses storage as virtual memory (setting), and One UI limits background processes and puts unused apps to sleep (BK-347). Native memory from RAW decodes (BK-324: 390 MB peak at 24 MP) counts against the process like any other; Android's low memory killer ends the app when it is in the background.
- Acceptance: the Copy report records `ActivityManager.getRunningAppProcesses` importance and `MemoryInfo` at report time plus the previous exit reason (done in ExitReasons, 15fb461) and counts `REASON_LOW_MEMORY` exits across sessions; the memory plan (BK-324) reads available memory rather than total; a note in the help copy that RAM Plus does not help GPU or native allocations; no assumption about swap.
- Size: S. Files: DeviceReport.kt, MemoryPlan. Risk: low.

### BK-185 [P3] Reduce background wakeups: ContentObserver debounce and rescans only when the app is visible
- Problem: the MediaStore observer rescans 1.5 s after any change even while Rawline is in the background.
- Acceptance: observer registered `onStart`, removed `onStop`; one catch-up scan on resume.
- Size: S. Files: LibraryViewModel.kt. Risk: low.


---

# AREA J: ONBOARDING, IN-APP HELP AND SETTINGS

Facts: Settings is a single scrolling column (version, built, LibRaw, debug overlay switch, XMP switch, backup/restore buttons, Copy report, a gesture paragraph, last crash). It uses Material typography (`MaterialTheme.typography.headlineMedium`) while UI_SPEC forbids a Material look. No first-run flow. All strings are hard-coded in Kotlin (no strings.xml).

### BK-191 [P1] First-run onboarding in four screens: what it is, permissions explained, pick where photos live, three gestures
- Problem: first launch drops into the library and a permission prompt (READ_MEDIA_IMAGES) then an all-files intent; Jai (and any future tester) is not told why.
- Acceptance: a skippable flow: (1) Welcome, (2) "Photos" permission with the reason, "All files" with the reason (RW2 files are not shown by MediaStore otherwise) and the skip consequence, (3) choose Camera roll or a folder or card, (4) three gestures with tiny looping demos; shown once, re-openable from Settings > Help; no account, no network.
- Size: M. Files: new feature/onboarding, MainActivity.kt, LibraryViewModel.kt. Risk: low.

### BK-192 [P1] [PARTLY DONE] Permission rationale and recovery states in the library (denied, partial, all-files off)
- Status: Commit 5ca42a0: `MediaAccess` state machine (Open settings once Android stops asking, re-check on resume, All files access counts as access). Left over: partial access (Android 14 selected photos) and the copy for each state.
- Acceptance: each state has a clear panel with one button (Allow, Open settings, Choose photos) and wording in Australian English; app stays usable in degraded mode; tested by toggling the permission on the phone and confirmed via the Copy report line `permissions: media=granted partial=no allfiles=no`.
- Size: S-M. Files: LibraryScreen.kt (EmptyState), LibraryViewModel.kt. Risk: low.

### BK-193 [P1] Help centre inside the app: one-page cheat sheet per screen with a "?" button
- Problem: gestures are described in one paragraph in Settings.
- Acceptance: a "?" in library, loupe, editor, masking, remove and export opens a half sheet with 5-8 short items and small illustrations; search across help; works offline; text lives in resources (BK-203).
- Size: M. Files: new core/ui/HelpSheet.kt, each screen. Risk: low.

### BK-196 [P1] Rebuild Settings in the Lr style as grouped sections with search
- Problem: Settings uses default Material typography and one long column; as entries multiply (storage, models, export presets, XMP, haptics, backups) it will be unmanageable.
- Acceptance: sections General, Library, Editing, AI models, Export, Storage, Backup, Accessibility, Help, About, Developer; a search field; rows follow UI_SPEC (40 px menu rows, dividers, no cards); Developer section hides the debug overlay, speed test, soak test and Copy report behind a 7 tap on Version.
- Size: M. Files: feature/settings/SettingsScreen.kt, app wiring. Risk: low.

### BK-200 [P1] Human readable errors with a "Copy details" action
- Problem: errors surface as exception messages such as "GPU init failed: ..." and PerfLog entries.
- Acceptance: an error vocabulary (what happened, what to try) for the 12 most likely failures (file missing, no space, GPU init failed, model download failed, unreadable edit, permission denied, etc.); every error toast/dialog has "Copy details" that puts the report on the clipboard.
- Size: M. Files: new core/ui/Errors.kt, call sites. Risk: low.

### BK-350 [P1] First-time user friction audit: write the five-minute path and fix every dead end
- Scenario: a new user (Jai on a fresh install, or a friend) opens the app. Findings from the code read: two system permission dialogs appear at once (photos and notifications, BK-285); RW2 files do not show until All files access is granted, which is a banner with "Allow" rather than an explanation; the AI tools start a 14 to 430 MB download without a size or Wi-Fi warning (BK-073); the long-press-to-select, pinch-for-columns, hold-for-original and double-tap-reset gestures are described only in Settings; there is no sample photo; "Edit", "Export" and "Share" have no explanation of what is destructive (nothing is); the grid shows camera-roll JPEGs and phone photos mixed with RAW.
- Acceptance: a written script in docs/ONBOARDING.md of the first 5 minutes (open, allow, see RAW, open one, swipe, edit exposure, undo, export) with each step's expected screen; the fixes land as the entries they reference (BK-191, 192, 194, 285, 073, 349, 204); Jai (or a friend with no briefing) runs the script on the phone and every step is tick-passed or has a new entry; time to first export under 5 minutes is recorded.
- Size: M. Files: docs, several UI files. Risk: low.

### BK-392 [P1] [DESIGNED] First-run onboarding copy deck: exact words for every screen (Australian English, no em dashes)
- Status: W27-onboarding-help.md: exact copy plus 32 passing host tests (rules, flow, resources); Compose screens specified, not compiled. Not merged.
- Acceptance: these strings (final wording agreed with Jai, kept in `strings.xml`, BK-203) ship with the onboarding flow (BK-191):
  1. Welcome. Title: "Edit your RAW photos on your phone". Body: "Rawline opens your Panasonic RW2 files fast and edits them without ever changing the originals." Button: "Get started".
  2. Photos. Title: "Let Rawline see your photos". Body: "Rawline needs to see your photos to show them. Nothing leaves your phone." Button: "Allow photos". Link: "Why do you need this?" opens: "Android lists JPEG and HEIC photos by itself, but it often hides RAW files. Allow all files access on the next screen to see your RW2 files."
  3. RAW files. Title: "Show your RAW files". Body: "Turn on All files access so your RW2 files appear next to your other photos." Buttons: "Open settings", "Skip for now". After skipping: "You can turn this on later in Settings. Until then RAW files may be missing."
  4. Where from. Title: "Where are your photos?" Choices: "Camera roll", "Choose a folder or SD card", "Import files". Footnote: "Rawline reads your photos where they are. It does not copy them unless you import from a card."
  5. Three gestures. Title: "Three things to try". Lines: "Touch and hold a photo to select it." / "In the editor, hold the photo to see the original." / "Double tap a slider name to reset it." Button: "Open my photos".
  6. Notifications (shown at the first export, BK-285). Title: "Keep exports running". Body: "Rawline shows a notification while it exports so Android keeps it running. You can turn it off any time." Buttons: "Allow", "Not now".
- Also: every screen has a Skip, no screen needs a network, each screen fits at font scale 1.3 (BK-170), copy checked by the text rules test (BK-203, no em dashes, Australian spellings), and the whole flow is reachable again from Settings > Help.
- Size: S (copy) with BK-191 for the build. Files: new strings.xml entries, feature/onboarding. Risk: low.

### BK-393 [P1] [DESIGNED] In-app help copy for each screen (the text for the "?" sheets, BK-193)
- Status: W27-onboarding-help.md: exact copy plus 32 passing host tests (rules, flow, resources); Compose screens specified, not compiled. Not merged.
- Acceptance: short items, plain words, sentence case, each under 140 characters, shipped as resources:
  - Library: "Touch and hold a photo to select it. Then tap more photos." / "Pinch to change how many photos fit across." / "Use the filter button to show picks, ratings or edited photos." / "Rejected photos are hidden. Tap Rejects to see them." / "Pick or reject while culling: open a photo and use the flag buttons."
  - Viewer: "Swipe left or right to move between photos." / "Double tap to zoom. Pinch for more." / "Swipe up for details about the shot." / "Tap Edit to open the photo in the editor."
  - Editor: "Drag a slider to change it. Double tap its name to reset." / "Tap the number to type an exact value." / "Hold the photo to see the original." / "Undo and Redo are at the top. Your edits save by themselves." / "Tap the photo to hide the panel."
  - Masks: "A mask changes only part of the photo. Add one, then set what it does." / "Subtract takes an area away. Intersect keeps only where two masks overlap." / "AI tools download a model the first time. Use Wi-Fi if you can."
  - Remove: "Paint over what you want gone. Heal and Clone copy from nearby. Remove uses AI."
  - Export: "Export makes a new file. Your original is never changed." / "Choose a size and format, then tap Export. You can leave the app while it runs." / "Find your photos in Pictures/Rawline, or in the folder you chose."
- Also a "Report a problem" item that opens Share report (BK-205). Searchable (BK-193). Written in Australian English and checked by the text rules test.
- Size: S (copy) plus BK-193 for the UI. Files: strings.xml, core/ui/HelpSheet. Risk: low.

### BK-447 [P1] First-run reviewer scorecard: reproduce what a Lightroom or Snapseed reviewer would do in the first ten minutes and score it each release
- What a demanding reviewer would criticise today (from the code read and the three audit documents): (1) a cold start that shows two system permission dialogs back to back and then an empty grid labelled "Nothing here yet" while the library is still indexing (AU-004, AU-022); (2) RW2 files missing until All files access is granted, explained only by a banner; (3) no onboarding, no help, no sample photo; (4) toasts that were invisible (AU-001, fixed in 5ca42a0) and a long first scan with no progress (AU-023); (5) Material dialogs and filled blue buttons that break the locked UI_SPEC (AU-055), no launcher icon (AUDIT item 9); (6) no import from a card, no delete, no search, no collections; (7) the base look judged against Lightroom's: a clipped sky that never reaches white (AE-001); (8) export: one dialog with nine option groups, no presets, no Share for several photos; (9) no visible histogram by default and the Auto tools hidden in a menu; (10) the 61 MB APK and update by hand from the browser; (11) timings claimed in the spec but none shown.
- Acceptance: `docs/FIRST_RUN.md` holds a 10 minute script (install, open, allow, see RAW, open one, swipe, edit exposure, undo, mask a sky, export, share) with the metrics recorded per release: taps to first RAW on screen, seconds to first photo visible, taps to first edit, seconds from Edit to first frame, taps to export; a screenshot at each step stored by Jai or a tester; each of the 11 items above is linked to its fixing entry (BK-191, 192, 285, 349, 392, 428, 282, 096, 097, 098, 438, 128, 342, 448, 240, 001) with a status; the score is recomputed before each release announcement.
- Size: S. Files: docs/FIRST_RUN.md. Risk: low.

### BK-450 [P1] First launch with a big library: honest progress, a skeleton grid and a "RAW files hidden" hint instead of "Nothing here yet"
- Findings (AU-022, AU-023, AU-060): the empty state appears while indexing; adding a big folder shows nothing until the whole tree is listed; the table is re-read about four times a second while indexing and grid rows are built on the main thread.
- Acceptance: while the first scan runs the grid shows a skeleton and "Finding photos: 1 240 so far" (count from the cursor while listing, then "Preparing thumbnails 312 of 1 000" with the visible-first queue, BK-278); the empty state only appears after a finished scan with zero rows and then explains why (no permission, RAW hidden, empty folder) with one action; the re-read throttle is 1 per second during indexing and grid rows are built off the main thread (GridRows.kt exists in the working tree); tested with a fake 20 000 photo listing.
- Size: M. Files: feature/library/LibraryScreen.kt, GridRows.kt, app/LibraryViewModel.kt, core/data/Indexer.kt. Risk: low-medium.
- Src: audit-ui.md AU-022, AU-023, AU-060.

### BK-194 [P2] Coach marks shown once for the non-obvious gestures (double tap resets a slider, tap number to type, hold to see original, swipe up for info)
- Acceptance: each coach mark appears the first time the relevant control is used, can be dismissed forever, and all can be re-enabled in Settings > Help.
- Size: S-M. Files: core/ui/Controls.kt, EditorScreen.kt, LoupeScreen.kt. Risk: low.

### BK-195 [P2] Tooltips and long-press labels for every icon-only control, with the same text used for TalkBack
- Problem: icon-only tools (mask ops, crop utilities) rely on icon recognition.
- Acceptance: a shared `LrTooltip` on long press showing the content description; one source of truth for label strings.
- Size: S. Files: core/ui/Controls.kt, LrIcons. Risk: low.

### BK-197 [P2] More settings Jai will want: default sort, columns, default profile, default export preset, haptics, grid aspect (square vs original), show filenames, rating overlay
- Acceptance: each stored in prefs and read by the relevant screen; defaults documented; a "Reset settings" button; tests for the defaults map.
- Size: S-M. Files: feature/settings, LibraryScreen.kt. Risk: low.

### BK-198 [P2] Viewing background and chrome options: black/dark grey/mid grey, hide UI in loupe, clean view (tap to hide)
- Acceptance: three canvas colours (editing against mid grey is a real colour-judgement aid), tap-to-hide chrome in the loupe, edge-to-edge consistent; saved per session.
- Size: S. Files: LoupeScreen.kt, EditorScreen.kt, LrTheme.kt. Risk: low (UI_SPEC says pure black canvas; this is opt-in).

### BK-199 [P2] [PARTLY DONE] "What's new" screen after each update, from the release notes
- Status: Commit 3cd7bb9: each release now lists the commits since the previous release and carries rawline.apk.sha256. Left over: the in-app What's New screen and a Needs phone test list.
- Problem: Jai installs from GitHub Releases and has to know what changed; release body is `Build N, commit abc`.
- Acceptance: CI writes a CHANGES.md section from commit subjects (conventional prefixes) into the release notes and into an asset bundled in the APK (`assets/whatsnew.txt`); the app shows it once per version and in Settings > About.
- Size: S-M. Files: .github/workflows/build.yml, app assets, MainActivity.kt. Risk: low.

### BK-201 [P2] Empty-state and loading-state review across screens
- Acceptance: each list/grid has distinct states (loading skeleton, empty with an action, error with retry); no spinner longer than 400 ms without text; screenshot tests (BK-208).
- Size: S-M. Files: core/ui/EmptyState, LibraryScreen.kt, QueueScreen.kt. Risk: low.

### BK-202 [P2] Undo for destructive library actions with a snackbar (reset edits, delete preset, clear queue, delete snapshots)
- Acceptance: a 5 s "Undo" snackbar for each; soft delete in the DB until the snackbar expires.
- Size: S-M. Files: LibraryViewModel.kt, PresetsPanel.kt, QueueScreen.kt. Risk: low.

### BK-203 [P2] [PARTLY DONE] Move strings to resources (`strings.xml`), plurals, and Australian English wording; keep em dashes out
- Status: Commit 5ca42a0: plural and Australian date helpers with tests (AU-026, AU-027). Left over: strings.xml move and the text rules test.
- Problem: no resources; strings are scattered in Compose code, which blocks consistent wording checks, translations and pluralisation ("1 photos").
- Acceptance: all user-facing strings in `res/values/strings.xml` with plurals; a lint check or unit test that scans for em dashes (the project rule) and American spellings (colour, grey, centre, organise) in resources and docs.
- Size: M. Files: every feature module. Risk: low (mechanical).

### BK-285 [P2] [DONE] Ask for notification permission when the first export starts, not at launch together with the photo permission
- Status: Done in commit eec8960: the first export notification question is a tested NotificationRule with the one line reason shown first.
- Problem: `MainActivity` launches the media permission request and `POST_NOTIFICATIONS` request in the same `LaunchedEffect` on first launch (Android 13+), stacking two system dialogs before Jai has seen the app.
- Acceptance: notifications are requested the first time an export is queued with a one-line reason; declining does not stop exports (the foreground service still runs; the notification is hidden by the system).
- Size: S. Files: MainActivity.kt. Risk: low.

### BK-346 [P2] [PARTLY DONE] Keep the screen awake while culling, importing and exporting (setting, default on during those actions)
- Status: `KeepAwake.kt` (hold counter, test) is committed in core/ui as of 71918bf but no screen calls it yet. Left over: wiring in the loupe, import and queue screens and the Settings toggle.
- Problem: no `keepScreenOn` anywhere (checked). A long import or a cull session pauses when the screen times out, and an export that runs in the foreground screen may be slowed by Doze when the screen goes off.
- Acceptance: `view.keepScreenOn = true` in the loupe while the photo is on screen, on the import progress screen, and on the queue screen while jobs run, released on leaving; Settings > Display toggle "Keep screen on while working" (default on); not applied in the grid.
- Size: S. Files: feature/loupe/LoupeScreen.kt, feature/export/QueueScreen.kt, new import screen, Settings. Risk: low.

### BK-349 [P2] A "Saved" indicator and a one-time note that editing never touches the original
- Problem: the editor has no Save button (edits autosave after 400 ms and on leaving, which is good), but a first-time user expects one and may fear that edits vanish; the same user does not know that the RAW is never changed and where exports go.
- Acceptance: a small check icon in the editor top bar fades in for 1 s after each autosave and has a long-press note "Saved. Your original is never changed. Export makes a new file in Pictures/Rawline"; first-run flag shows the same note once in the editor.
- Size: S. Files: EditorHost.kt, EditorScreen.kt. Risk: low.

### BK-394 [P2] [DESIGNED] Plain-English tool glossary shown on long press (with a one-line effect and a "try this" tip)
- Status: W27-onboarding-help.md: exact copy plus 32 passing host tests (rules, flow, resources); Compose screens specified, not compiled. Not merged.
- Acceptance: each adjustment label has a long-press explanation in the same Lr-style popup as tooltips (BK-195), for example: "Exposure: overall brightness. Move right to brighten." / "Highlights: only the brightest parts. Pull left to bring back sky detail." / "Shadows: only the darkest parts. Move right to lift them." / "Whites and Blacks: set the brightest and darkest points." / "Texture: fine detail such as skin and bark." / "Clarity: contrast in the middle tones. Adds punch." / "Dehaze: cuts through haze and mist, or adds it." / "Vibrance: boosts dull colours and protects strong ones." / "Saturation: all colours at once." / "Grain: film-like noise for a classic look." Texts in resources; a settings switch "Show explanations"; first use shows it once.
- Size: S-M. Files: core/ui/Controls.kt, strings.xml. Risk: low.

### BK-395 [P2] [DESIGNED] Error and empty state copy list (the 14 messages Jai will actually see)
- Status: W27-onboarding-help.md: exact copy plus 32 passing host tests (rules, flow, resources); Compose screens specified, not compiled. Not merged.
- Acceptance: one table in docs/COPY.md and strings.xml, each with what happened, what to try, and a Copy details action (BK-200). Drafts: "No photos yet. Allow access to see your camera roll." / "RAW files are missing. Turn on All files access." / "This photo could not be opened. The file may be damaged." (BK-355) / "Not enough space. Free about 300 MB and try again." (BK-157) / "Export folder not available. Choose another folder or save to Pictures/Rawline." (BK-359) / "This edit was made by a newer version of Rawline, so it was left alone." / "The AI model could not be downloaded. Check your connection and try again." / "AI tools are running slower on your phone's processor right now." (BK-296) / "Your phone is warm. Rawline paused to cool down." (BK-176) / "Battery is low. Export paused until you charge." (BK-354) / "The backup is damaged and was not restored." (BK-303) / "Nothing new to import." / "The card was removed. Put it back to continue." (BK-364) / "This file is very large, so it opened at half size." (BK-324).
- Size: S. Files: docs/COPY.md, strings.xml. Risk: low.

### BK-396 [P2] [DESIGNED] Copy style guide and an automatic check (Australian English, no em dashes, sentence case, short verbs)
- Status: W27-onboarding-help.md: exact copy plus 32 passing host tests (rules, flow, resources); Compose screens specified, not compiled. Not merged.
- Acceptance: docs/COPY.md states: Australian spelling (colour, grey, centre, organise, favourite, licence as a noun, practise as a verb), no em dashes (use a full stop or a comma), sentence case for titles and buttons, buttons start with a verb ("Allow photos", "Export"), no jargon without a glossary entry (BK-394), numbers with units, no exclamation marks, no model or vendor names except where needed (Panasonic, Samsung); the text rules test (BK-203) enforces the mechanical parts over strings.xml, docs and release notes; a review checklist for new strings in the PR template.
- Size: S. Files: docs/COPY.md, core/model test. Risk: low.

### BK-397 [P2] Gesture tips that appear when they would help, then stay out of the way
- Acceptance: a `TipController` shows each tip at most twice, only after the relevant behaviour (a user who taps a slider number ten times gets "Double tap the name to reset"; a user who opens ten photos in a row gets "Swipe left or right to move between photos"; after the first long press selection "Tap Export to save them as new files"); dismissed forever on a tap; all tips can be turned off or reset in Settings > Help; tip text in resources (BK-393 style); tests with a fake clock and counters.
- Size: S-M. Files: core/ui TipController, feature screens. Risk: low.

### BK-428 [P2] A launcher icon and app name branding (the audit lists "no launcher icon")
- Fact: docs/AUDIT.md item 9 lists no launcher icon. An app without one shows a default icon in the launcher, the app switcher and the install flow.
- Acceptance: an adaptive icon (foreground, background, monochrome layer for themed icons on Android 13+), own artwork (no Adobe imagery, per the SPEC rule), a round and a themed variant, tested in the Samsung launcher and Settings > Apps; notification small icon replaces `android.R.drawable.stat_sys_download_done` placeholders in ExportService; Studio mode uses the same icon.
- Size: S-M. Files: app/src/main/res, manifest, ExportService.kt. Risk: low.

### BK-449 [P2] UI_SPEC conformance pass: the drift list from the UI audit (Material dialogs, input heights, overlay opacity, tab bar height, buttons)
- Source: audit-ui.md AU-055 lists: Material `AlertDialog` (8 dp corners, wide) in ExportSheet and the paste dialog where the spec says 6 dp and width at most 320 dp; Material `OutlinedTextField` (56 dp, floating label) where the spec says 40 dp and 4 dp radius; nine export option groups in a dialog where the spec wants a full subview; tab bar 48 dp versus 44; the save overlay at 72 percent black where the spec says 35 to 45 percent canvas visible; filled blue buttons in Settings where the spec wants secondary outlined buttons; a text "Back" instead of a 44 dp back icon.
- Acceptance: one pass fixes each item or records a reasoned exception in DECISIONS.md (for example 48 dp targets beat the spec's 44 dp, BK-166); a screenshot test per screen at font scale 1.0 and 1.3 (BK-208); the export flow becomes a bottom sheet or subview (BK-282).
- Size: M. Files: feature/export, feature/settings, core/ui, EditorHost.kt. Risk: low.
- Src: audit-ui.md AU-055.

### BK-204 [P3] Sample photo for first run and for demos (public domain RAW/DNG small file)
- Acceptance: bundled 2 MB CC0 DNG or JPEG so someone with an empty phone can try the editor; licence recorded in THIRD_PARTY.md; excluded from the library after the first run.
- Size: S. Files: app assets, onboarding. Risk: low.

### BK-205 [P3] Feedback path: "Send report" via share sheet with the report text and optionally the last crash and a screenshot
- Acceptance: one tap composes a share with the report as text (Jai pastes into the chat already, this removes a step); screenshot with consent.
- Size: S. Files: Settings. Risk: low.

### BK-456 [P3] Reconcile docs/SPEC.md with the locked UI spec and the decisions that changed it
- Facts: SPEC.md says "neutral mid-dark grey, one accent" and "milestones strict order" with M1 to M9; DECISIONS.md says the UI follows UI_SPEC.md (black canvas, #1C1C1C surfaces) and several SPEC lines were changed by decisions (half-size base, no lensfun library, no AVIF).
- Acceptance: SPEC.md keeps the original wording but each changed line gets a "Changed: see DECISIONS.md, <date>" suffix, and the UI paragraph points to UI_SPEC.md; no behaviour changes; the gap table (BK-451) is linked from the top of SPEC.md.
- Size: S. Files: docs/SPEC.md. Risk: low.


---

# AREA K: TESTING AND CI

Facts: CI = golden job (Mesa llvmpipe renders vs 15 reference PNGs, model link check, RW2 preview parser on one downloaded sample file) then build job (unit tests, signed release APK, release publish). No lint, actions pinned by tag not hash, no instrumented tests, no tests for library/loupe/core:ui, golden harness has no heal path (AUDIT).

### BK-206 [P1] [PARTLY DONE] Add Android lint and Kotlin static analysis (detekt or ktlint) to CI (AUDIT item 5)
- Status: Commit 3cd7bb9 added the separate lintDebug job; commit 2061987 fixed the one lint error in all modules and the job is no longer allowed to fail. Left over: detekt or ktlint and a baseline for new issues (BK-206 task in DISPATCH.md).
- Acceptance: `./gradlew lintDebug` and `detekt` as a CI job with a baseline for existing issues, failing on new ones; lint covers `NewApi`, `MissingPermission`, `HardcodedText`, `Overdraw`; report uploaded as an artifact.
- Size: S-M. Files: build.gradle.kts files, .github/workflows/build.yml. Risk: low (baseline needed).

### BK-207 [P1] [PARTLY DONE] Pin GitHub Actions to commit SHAs and enable Dependabot for actions and Gradle (AUDIT item 5)
- Status: Commit 3cd7bb9: Dependabot for github-actions (weekly) and all actions are on exact version tags. Left over: pin to commit SHAs and add the gradle ecosystem.
- Acceptance: each `uses:` pinned to a 40 char SHA with a version comment; `.github/dependabot.yml` for `github-actions` and `gradle` weekly; no auto-merge.
- Size: S. Files: .github/workflows/build.yml, new dependabot.yml. Risk: low.

### BK-208 [P1] Screenshot tests (Paparazzi or Roborazzi) for library, loupe chrome, editor panels, export sheet, settings at 3 font scales and dark theme
- Problem: UI_SPEC is locked and detailed but nothing verifies it; AUDIT item 6 says nothing for library, loupe or core/ui.
- Acceptance: JVM screenshot tests with golden PNGs for 20 key states; a CI job comparing within a tolerance; the reference images updated by a `--update` script like the shader goldens; failures upload diffs.
- Size: M. Files: new test source sets in feature/* and core/ui, CI. Risk: medium (screenshot library versions vs AGP 9.4.1 and Kotlin 2.4.20: check compatibility before adopting).

### BK-209 [P1] Compose UI behaviour tests (Robolectric `createComposeRule`) for selection, filter, rating, copy/paste flows
- Acceptance: tests for long press select, rating shortcuts, filter chips, paste scope dialog, back handling; `LibraryScreen` accepts fakes; coverage on those screens above 50 percent lines.
- Size: M. Files: feature/library tests, feature/loupe tests. Risk: low-medium.

### BK-210 [P1] Gradle Managed Devices emulator smoke test in CI: launch, grant permissions, import a sample, open loupe, open editor, move a slider, export
- Problem: nothing runs the real app in CI; the real GPU path is only Mesa in the golden harness.
- Acceptance: a nightly (not per push, to save minutes) CI job using an API 34/35 x86_64 emulator with software GLES, running 5 instrumented tests; failure uploads logcat and a screenshot.
- Size: M-L. Files: app/src/androidTest, build.gradle.kts, CI. Risk: medium (emulator flakiness; native lib is arm64-only: add an x86_64 ABI for debug test builds or use an arm64 emulator image on a runner that supports it).

### BK-211 [P1] Expand the golden shader suite to every feature and enforce coverage
- Problem: 8 scenes (and `layers2`, rotation/lens fits): heal path missing (AUDIT), no per-feature scenes for dehaze, grading, mixer, vignette, grain, NR, sharpen, lens CA, colour/luminance masks.
- Acceptance: a scene per slider group with small, medium and extreme values; a script `tools/golden/coverage.py` reporting which uniform slots (params.h) are exercised by any scene and failing below 90 percent; heal scene added.
- Size: M. Files: tools/golden/golden.cpp, run-golden.sh, ref/. Risk: low.

### BK-212 [P1] Golden tests for tiled export versus untiled preview (seam detection)
- Acceptance: render a 5000 x 3000 synthetic image both as one pass and as 2048 tiles; max difference under 1 level (8-bit) and no seam at tile borders (the local analysis layers are meant to avoid cross-tile blur: verify, including NR/sharpen margins).
- Size: S-M. Files: tools/golden. Risk: low.

### BK-213 [P1] Parity test between CPU-side helpers and shaders (curves, crop fit, tone) with property-based inputs
- Acceptance: CurveMath/BaseCurve/Geo evaluated on 10 000 random inputs and compared with a C++ reference in the golden harness (like fitcrop.expected) within tolerance; generators seeded for repeatability.
- Size: M. Files: tools/golden, core/render tests. Risk: low.

### BK-421 [P1] RAW quality benchmark suite: objective, repeatable numbers for demosaic, noise, colour, sharpness, highlights and lens correction
- Problem: the backlog proposes many quality changes (BK-022 to BK-035) but nothing can say whether a change helped. Today the only "benchmark" is three same-scene S5IIX files and eyeballing.
- Acceptance: `tools/quality/` with scripts that run on the sandbox (x86 Mesa or host reference) and print one table per run: (1) demosaic: synthetic Bayer mosaics made from public full-colour images, CPSNR and CIEDE2000 against the originals plus zipper and false colour counts, for the current AHD and any candidate (RCD, LMMSE, IGV); (2) noise: standard deviation in flat patches and slanted-edge MTF50 at ISO 800, 3200, 12800 so the trade-off is a curve, not a feeling; (3) colour: delta E 2000 of a 24 patch chart render against reference values and against the camera's embedded JPEG; (4) sharpening: MTF50 gain and overshoot percentage; (5) highlight recovery: error of the recovered area against an exposure bracket reference; (6) lens: residual straight-line error in percent at the corner; results stored per commit in docs/QUALITY.md so regressions show; thresholds per metric (pass/fail) agreed with Jai.
- Size: M-L. Files: tools/quality, docs/QUALITY.md, CI (non-blocking). Risk: low.
- Src: RawPedia demosaicing guidance and comparison tables (RawTherapee, checked 6 Oct 2026): https://rawpedia.rawtherapee.com/Demosaicing ; AIM 2025 and SPIE papers on PSNR, SSIM and MTF based evaluation of RAW denoising (checked 6 Oct 2026): https://www.scitepress.org/Papers/2010/28312/

### BK-458 [P1] One tracker: map the three audit documents (AE, AQ, AU ids) to backlog ids so nothing is lost or done twice
- Facts: the folder holds audit-engine.md (AE-001 to AE-055), audit-quality.md (AQ-001 to AQ-038) and audit-ui.md (AU-001 to AU-069); commits already cite AU ids (5ca42a0). This backlog overlaps them heavily but has its own ids.
- Acceptance: a table in this file (below the milestone plan) maps each audit finding that has a counterpart to its BK id with a status; findings with no counterpart become BK entries (BK-459 to BK-469 for the engine ones) or are listed as "audit only"; when a commit cites an AU, AE or AQ id the status is updated here; the rule is written once: audit ids are authoritative for the finding text, BK ids for scheduling.
- Size: S. Files: BACKLOG.md. Risk: low.

### BK-479 [P1] [DONE] Studio stroke commit works on one bounding box, so a thin diagonal line costs as much as a full-canvas fill (review of S1b)
- Status: Merged in 1fed051 (tiled commit, banded native readback, GL lifecycle with a paused state); checked in review-w13.md, host tests pass.
- Finding: `Dirty.rect` returns one box around all stamps. On commit the session reads the whole box back from the GPU, bakes it per pixel on the CPU (an `Rgba` object per pixel in `Brush.bake`), cuts before and after copies and pushes the box to the GPU. A corner to corner line on a 12 MP layer is a 12 MP box: about 96 MB of history for one stroke (the 200 MB cap holds two), and the model thread is busy while the next stroke starts.
- Why it matters to Jai: after a long stroke the next one starts late, and undo history shrinks to a handful of steps.
- Acceptance: dirty tiles (W26 `TileGrid.keysFor` per stamp), delta per touched tile, bake only tiles that had coverage, bake without per-pixel objects; host test with a diagonal stroke on 4000 x 3000 asserts commit time and `deltaBytes` stay proportional to painted area; Copy report `studio_commit_ms`.
- Size: M. Files: core/studio-model Brush.kt, History.kt, StudioSession.commitStroke. Risk: medium (history format).
- Src: review-s1b.md F1.

### BK-480 [P1] [DONE] Studio stroke readback allocates 16 bytes per pixel of the stroke box natively (192 MB at 12 MP)
- Status: Merged in 1fed051 (tiled commit, banded native readback, GL lifecycle with a paused state); checked in review-w13.md, host tests pass.
- Finding: `Compositor::readStroke` builds `std::vector<float>(w*h*4)` and copies channel 0; Kotlin adds `FloatArray(w*h)`. A big stroke can fail with bad_alloc, which is caught and shown as "Could not finish that stroke", and the stroke is lost.
- Acceptance: read and bake in bands of 256 rows with one reusable band buffer, no box sized float array; golden with a 4096 x 3072 box passes; peak RSS recorded.
- Size: S-M. Files: core/native studio_compositor.cpp, jni_studio.cpp, StudioSession. Risk: low.
- Src: review-s1b.md F2.

### BK-481 [P1] [PARTLY DONE] S Pen: a palm that lands first wins and the pen is ignored (InputRouter)
- Status: Pen over palm merged in 1fed051. Left over: a missed hover exit leaves fingers off for good (BK-510, W32 patch tested).
- Finding: in state STROKING a stylus DOWN is dropped (`if (e.kind == STYLUS) return out`), so a resting palm that started a stroke keeps drawing while the pen does nothing. The documented rule is that a stylus seen means fingers paint nothing.
- Acceptance: stylus DOWN while a finger stroke runs cancels it (no history entry) and starts the pen stroke; pen hover (Compose `PointerType.Stylus` enter) sets a "pen near" flag that ignores finger DOWN until 600 ms after exit; router tests with a fake clock; phone test writing with the hand resting on the glass.
- Size: S. Files: core/studio-model InputRouter.kt and tests, the canvas pointer handler. Risk: low.
- Src: review-s1b.md F3.

### BK-488 [P1] [DECIDED] Studio blend space: decide gamma or linear per document before the S3 blend modes are written (numbers from a host calculation)
- Status: PM decision 6 Oct 2026: Studio blends in gamma encoded display space by default (matches Photoshop and what Jai expects); the linear option is stored per document (BlendSpace) and is off. Remaining work: both spaces in the reference and every S3 golden, the new project switch, and the note in docs/STUDIO_STATUS.md and DECISIONS.md (text in DISPATCH.md).
- Finding: the document type already carries `BlendSpace` GAMMA or LINEAR, but nothing says which is the default and the two give visibly different pictures. 50 percent black over white: gamma blend gives 128, linear gives 188 (display values). A soft brush edge at coverage 0.25, 0.5, 0.75 over white gives 191, 128, 64 in gamma and 225, 188, 137 in linear, so a soft brush looks much lighter and thinner in linear. Multiply of two mid greys is close either way (64 versus 60). Photoshop's default is gamma (what the PSD reader of a designer expects); linear is physically right for glows, blurs and gradients.
- Why it matters to Jai: whichever is chosen decides how every brush, opacity slider and blend mode looks, and a later switch changes every saved project.
- Acceptance: the default is gamma (Photoshop compatible) with a per-document switch to linear stored in project.json (already `BlendSpace`); the Python and Kotlin references take the space as a parameter and every S3 golden runs in both; a note in the new project dialog ("Blend in linear light" off by default); brushes and gradients stay correct in both (stamp coverage is in the blend space the document uses); Jai compares a soft brush and a 50 percent layer on the phone before the default is frozen.
- Size: M. Files: core/studio-model Blend.kt, ReferenceCompositor.kt, studio shaders, goldens. Risk: high if decided late (changes saved projects).
- Src: host calculation 6 Oct 2026 (sRGB transfer), Document.kt BlendSpace.

### BK-489 [P1] Studio blend modes: the W3C formulas are not what Photoshop does for several modes, and the golden is only as independent as its reference
- Finding: S1 has Normal, Multiply, Screen, where W3C and Photoshop agree. For the other 21 modes (Soft Light, Hard Light, Vivid Light, Linear Light, Pin Light, Hard Mix, Divide, Subtract, Darker and Lighter Color, Dissolve, the four non separable modes) the W3C compositing spec covers only 16 and its Soft Light differs from Photoshop's. Colour Dodge and Colour Burn divide by zero at the ends. The S1a reference is written by the same author as the shader, so a shared misreading passes the golden.
- Acceptance: a table in docs per mode saying which definition is used (W3C or Photoshop) and why, with the division by zero rules; a second independent reference (a short Python file written from the specification text, not from the Kotlin) for each mode; 8 bit PSD import and export compatibility is a stated non-goal unless chosen otherwise; Dissolve uses a seeded hash so it is repeatable; every mode tested on opaque and half transparent backdrops (the S1a rule).
- Size: M. Files: Blend.kt, studio_composite.frag, tools/studio. Risk: medium.
- Src: STUDIO_SPEC.md 2.3 and milestone S3; W3C Compositing and Blending Level 1.

### BK-490 [P1] Studio transform quality: bilinear resampling aliases when shrinking and destroys fine detail when a layer is moved or rotated several times
- Finding: host calculation with a 1 D proxy: bilinear sampling of a 1 pixel stripe pattern at 0.37 scale swings the output by 0.97 where an area average swings 0.27 (aliasing, not a smooth reduction). Detail with a 3 px period keeps 43 percent of its contrast after one half pixel bilinear resample and 3 percent after five; a 6 px period keeps 21 percent after ten resamples (worst case of fractional shifts, real rotations vary). The S1b compositor samples layers with the GPU's bilinear and the spec lets a layer be moved and scaled non-destructively until committed, but a commit after several transforms resamples again.
- Acceptance: scaling down uses mipmaps plus bilinear (trilinear) at view time; a transform commit resamples once from the original pixels using the combined matrix with a Lanczos 3 or bicubic kernel in a tile shader (never from a previous commit); a test chart (stripes, a slanted edge) through scale 0.37, rotate 7 degrees, rotate back, with MTF50 and an edge ringing limit (overshoot under 5 percent) as acceptance; the live preview may be bilinear but a final view at 100 percent uses the commit kernel.
- Size: M-L. Files: studio compositor, transform tool (S6), goldens. Risk: medium.
- Src: host calculation 6 Oct 2026; STUDIO_SPEC.md transform section.

### BK-491 [P1] Studio group isolation and pass-through need an offscreen target per nesting level, which the 600 MB memory guard does not count
- Finding: the guard estimates layer textures, the ping-pong pair, the resolve target and the stroke buffer. An isolated group renders into its own RGBA16F target: 24 MP at 8 bytes is 192 MB per level, so three nested groups on a 24 MP canvas add about 576 MB on top. S3 exit asks for a 20 layer 24 MP scene.
- Acceptance: the guard counts group targets (depth times canvas size times 8); a nesting limit (for example 3) with a message; groups render in 1024 px strips like Flatten when the target would not fit; pass-through groups need no target; a host test that the estimate rises with depth; Copy report prints `studio_group_targets_mb`.
- Size: M. Files: MemoryGuard.kt, compositor, Flatten.kt. Risk: medium.
- Src: STUDIO_SPEC.md S3; MemoryGuard.kt.

### BK-492 [P1] Studio adjustment layers must not apply Develop's baseline look twice, and must say how out of gamut values are held in an 8 bit layer
- Finding: Develop adds a baseline (saturation +10, texture +25, clarity +10, sharpen 45) to every raw (`Baseline` object) and a base tone curve; an adjustment layer built from the same shader would add them to a photo that Develop already rendered. The S5 exit says parity with Develop within 1 level, which is only meaningful for a stated recipe. Separately, a Studio layer stores 8 bit straight alpha in the document space, so an adjustment that pushes colour outside the document gamut or below one level is clamped and gradients band.
- Acceptance: adjustment passes run with `useBaseline=false` and no base curve (the render params already support it for heal patches); a parity golden compares a Studio adjustment layer against Develop with the same recipe and baseline off, within 1 level; adjustment results stay in RGBA16F until a layer is rasterised (merge, flatten, export); on rasterise a blue noise dither of one level is applied before quantising (test: a 0 to 1 gradient through +1 EV and back has no step wider than one level); the choice is written in DECISIONS.md.
- Size: M. Files: studio adjustment pass, RenderParams, goldens. Risk: medium.
- Src: Baseline in RenderParams.kt; STUDIO_SPEC.md S5 and 2.17.

### BK-214 [P2] Run the C++ engine and parsers under AddressSanitizer and UBSan in the golden job
- Acceptance: golden build with `-fsanitize=address,undefined`; the RW2 parser and LibRaw decode tests also run under ASan; failures break CI. NaN/Inf and out-of-bounds in JNI bridges are the main historical issues in the audit.
- Size: S-M. Files: tools/golden/build.sh. Risk: low.

### BK-215 [P2] A libFuzzer harness for rw2_preview.cpp and the EXIF/TIFF IFD reader
- Acceptance: 10 minute fuzz run on each push to main (cached corpus), crash reproducers saved as artifacts; the parser has capped IFD visits already (AUDIT); the fuzz proves no other path hangs or reads out of bounds.
- Size: M. Files: tools/fuzz, CMake. Risk: low.

### BK-216 [P2] [PARTLY DONE] Reduce test-data fragility: mirror the RW2 samples into a GitHub Release asset with a pinned SHA-256
- Status: Commit 3cd7bb9: the sample RW2 is cached, checksummed and retried. Left over: mirror it as a Release asset and add a real S5IIX file.
- Problem: CI downloads a sample from raw.pixls.us; if the site is down or changes the file, CI goes red. Also only one file (a DC-S5M2X sample, close but not S5IIX).
- Acceptance: files stored as a Release asset of this repo (the sandbox can reach this repo), checksum pinned, fallback to the cache; at least one real S5IIX file if Jai can supply one (he owns the camera), recorded in testdata/README.
- Size: S. Files: .github/workflows/build.yml, tools/test-preview-parser.sh. Risk: low (licence: raw.pixls.us samples are CC0; Jai's own files are his).

### BK-217 [P2] [PARTLY DONE] Faster CI: ccache for LibRaw, Gradle build cache, split jobs in parallel, skip release build on pull requests
- Status: Commit 3cd7bb9: LibRaw build and sample cached, concurrency control, model and lint jobs split from the build. Left over: record job times and a time budget.
- Problem: native LibRaw and engine are rebuilt each run (FetchContent from libraw.org). Every push also builds a release APK even for branches.
- Acceptance: median CI time under 10 minutes (record the current number first); the release APK only on `main`, tests on branches; `ccache` with `actions/cache`; job summary lists timings.
- Size: S-M. Files: .github/workflows/build.yml, core/native/CMakeLists.txt. Risk: low.

### BK-218 [P2] APK size budget and dependency report on every build
- Acceptance: CI prints APK size and the top 10 contributors (arm64 libs, dex, assets, lensfun XML) and fails above a budget (set after the first measurement, then ratcheted); `dist/rawline.apk` in the repo also grows git history: stop committing APKs (see BK-227).
- Size: S. Files: CI. Risk: low.

### BK-219 [P2] Code coverage floor per module with JaCoCo and a coverage summary in the PR
- Acceptance: JaCoCo for `testDebugUnitTest`; initial report only; floors set per module after measuring (core/model, core/render, feature/masking first).
- Size: S-M. Files: build files, CI. Risk: low.

### BK-220 [P2] Extend the param mirror test to uniforms and shader slot names
- Problem: a test keeps `params.h` and `RenderParams.kt` equal; shader uniform names and block slot indices (`B(block, k)` texel indices) are not tied to the same source.
- Acceptance: a generated header from a single `params.json` for slots and texel layout used by C++, Kotlin and a GLSL `#define` include; test fails on any drift.
- Size: M. Files: core/native/cpp/engine/params.h, RenderParams.kt, shaders. Risk: medium.

### BK-221 [P2] [PARTLY DONE] Always log shader compile and link logs (and GPU renderer string) into the Copy report
- Status: Commit 15fb461: GlInfo prints renderer, vendor, GL version, EGL version, max texture size and float render target support. Left over: shader compile and link info logs.
- Problem: Mesa compiles like a strict ES 3.2 driver, but Adreno may warn or fail differently; the report lacks `GL_RENDERER`, `GL_VERSION`, extension list or compile warnings.
- Acceptance: the engine logs `GL_RENDERER`, `GL_VERSION`, `GL_MAX_TEXTURE_SIZE`, half-float renderability and every non-empty info log at init; included in the report.
- Size: S. Files: engine.cpp, jni_engine.cpp, PerfLog.kt. Risk: low.

### BK-222 [P2] Publish release only if the APK passes a contents check (permissions, minSdk, targetSdk, ABI, 16 KB alignment, signature)
- Acceptance: `aapt2 dump badging` and `apksigner verify --print-certs` in CI compared to an allow-list file; permission list diff fails the build unless the allow-list is updated in the same commit; `zipalign -c -P 16` check (BK-229).
- Size: S. Files: CI, new tools/apk-check.sh. Risk: low.

### BK-305 [P2] [PARTLY DONE] Data-preserving migration tests and DAO tests with rows (the current MigrationTest compares column shapes only)
- Status: Commit 71918bf added a confirm dialog before Restore backup and Clear thumbnails and Clear crash reports buttons in Settings, and a CatalogLogicTest for scan pruning. Left over: data-preserving migration and DAO tests with rows.
- Problem: `MigrationTest` builds each version from the schema JSON in in-memory SQLite and compares the columns after the migration SQL, which proves the shape but not that existing rows survive with sensible defaults (for example `meta.updatedAt` after 3 to 4, or the 4 to 3 downgrade keeping all rows).
- Acceptance: tests insert representative rows (photos with ratings, edits, snapshots, presets, export jobs) at version 3, run the migration, and assert row counts and values, including a downgrade round trip; add DAO query tests (`counts()`, `reapply` replacement from BK-261) on the same in-memory SQLite.
- Size: S-M. Files: core/data/src/test. Risk: low. Extends BK-147 (which is partly done: host-side migration and backup reader tests exist).

### BK-307 [P2] ML pre- and post-processing regression tests with fixture images and recorded outputs
- Problem: DECISIONS.md says each model was run on a PC against real photos to confirm input and output conventions, but nothing in CI re-checks them. The code has several hand-written conventions (SAM encoder input scaled by 1/255 only, SegFormer class 2 = sky from logits at 128 x 128, MediaPipe six-class softmax, NAFNet display-referred input) that a refactor could break silently. CI only checks that model links are alive.
- Acceptance: a host-side test (LiteRT or the Python TFLite interpreter in CI with cached models by hash, BK-216 style) running each model on 3 fixture images and comparing summary statistics of the result (sky fraction, person mask area, SAM IoU against a stored mask) within tolerance; the Kotlin pre/post-processing is exercised through the same fixtures with the interpreter faked.
- Size: M. Files: tools/models, core/ml tests, CI. Risk: medium (CI time and cached 700 MB of models: restrict to the small ones, keep denoise manual).

### BK-356 [P2] Corrupt file corpus for tests: truncated, zeroed, wrong extension, oversized IFD, huge dimensions
- Acceptance: a generator script produces from one good RW2 (or a synthetic file) 12 broken variants (truncated at 10, 50 and 99 percent; first 4 KB zeroed; an RW2 renamed `.jpg`; a JPEG renamed `.rw2`; IFD offset past EOF; an IFD loop; a preview length larger than the file; zero-byte file; width and height 65535); tests in `tools/test-preview-parser.sh` and a host test for the indexer and decode wrapper assert: no crash, no hang over 2 s, a defined error state, the right badge. Complements BK-164 and BK-215.
- Size: M. Files: tools/, core/native tests. Risk: low.

### BK-357 [P2] A synthetic 100 MP raw fixture and an end-to-end big file test under the memory plan
- Scenario: the S5IIX High Resolution mode makes a 96 MP RW2; Jai may also move to a 45 MP body; nobody has opened such a file in this app.
- Acceptance: `tools/gen-big-dng` writes a valid synthetic Bayer DNG at 12000 x 8000 (no real content needed) for the golden harness (x86 Mesa) and the phone; a debug "Open test file" action in Settings > Developer opens it; with BK-324 in place the expected result is half-size editing with a clear note, export in bands within the memory plan, and no crash; peak memory recorded in the report.
- Size: M. Files: tools/, Settings Developer. Risk: low.

### BK-424 [P2] A/B reference viewer for builds: render the same photo with a stored reference recipe and compare side by side or as a difference
- Opportunity: with BK-421 numbers and Jai's eyes, a debug tool that shows the current render, a stored reference render (from the previous release), and a heat map of differences makes look changes visible and reviewable on the phone.
- Acceptance: Settings > Developer "Reference compare": pick a photo, shows build N-1 output (stored PNG per photo and recipe from a golden set bundled in debug builds only) next to the current one with a difference overlay and a max delta number; not in release builds.
- Size: M. Files: feature/settings Developer, golden assets. Risk: low.

### BK-427 [P2] [PARTLY DONE] Fix the lint findings that are real bugs (API 33 call on minSdk 31, half-float API use) and make lint blocking for them
- Status: Commit 3ad30b3: nullOutputStream replaced, HalfFloat errors cleared (Kotlin half conversion). Left over: make lint blocking for these classes (BK-206).
- Facts from the fifth audit pass (docs/AUDIT.md): lint reports `OutputStream.nullOutputStream` in `ModelStore.kt` (API 33; the app's minSdk is 31, so a model download on Android 12 or 12L would crash; the S24 Ultra on Android 14 is not affected) and `HalfFloat` errors in core/render (12 spots).
- Acceptance: replace the API 33 call with a small own null `OutputStream`; review each `HalfFloat` finding (`android.util.Half` is API 26, the lint concern is probably about `Half` use on `Short`/`float` conversions, decide per case) and fix or suppress with a written reason; add `abortOnError` for `NewApi` and `HalfFloat` so these categories block while the rest stays advisory (BK-206).
- Size: S. Files: core/ml/ModelStore.kt, core/render/*, build files. Risk: low.

### BK-477 [P2] Studio lasso: the CPU reference rasteriser is far too slow for a 24 MP canvas; the shipped path must be a GPU stencil fill
- Finding: `Selection.lasso` is O(bounding box area x 16 samples x vertices); fine for 256 px golden scenes, minutes at 24 MP. Same for REPLACE clearing old bounds on the CPU.
- Acceptance: GPU even-odd fill (triangle fan, stencil invert) into the R8 selection plane, CPU version kept as the golden reference at test sizes only; lasso of 200 points on 24 MP updates in under 150 ms (target, unmeasured until a Copy report).
- Size: M. Files: core/render studio selection pass. Risk: medium.
- Src: W26-studio-s2.md section 6.

### BK-478 [P2] Backup change counter and WorkManager wiring: `androidx.work` is not a dependency yet and nothing counts saved edits
- Finding: the BackupPolicy needs a persistent count of saved edits (25 changes plus a 10 minute gap) and a daily worker; the app has neither.
- Acceptance: add `androidx.work:work-runtime-ktx` (check size and the 16 KB alignment of any native lib, there is none), `ChangeCounter` in SharedPreferences incremented from the edit save path, `BackupWorker` periodic 24 h with a charging-not-required constraint; instrumented check that it runs after reboot.
- Size: M. Files: app/build.gradle.kts, app/BackupWorker.kt, core/data. Risk: low.
- Src: W06-auto-backups.md D6, D7.

### BK-482 [P2] The Studio frame goes GPU to CPU to GPU every frame (18 MB readback and upload at 3120 x 1440)
- Finding: `StudioNative.render` reads the whole screen into a byte array and `Display.draw` uploads it again; JNI `GetByteArrayElements` may copy it twice more. Expected 20 to 40 ms per frame on Adreno; not measured.
- Acceptance: display pass inside the compositor drawing to the default framebuffer; until then a direct ByteBuffer and only the dirty rectangle while a stroke is live; `studio_frame_ms` and `studio_input_to_pixel_ms` in the Copy report before and after.
- Size: M. Files: core/native studio_compositor, StudioGl.kt. Risk: medium.
- Src: review-s1b.md F4, F5, F13.

### BK-483 [P2] [DONE] Studio GL lifecycle: jobs on a dead context, init failure hangs waiters, destroy blocks the main thread
- Status: Merged in 1fed051 (tiled commit, banded native readback, GL lifecycle with a paused state); checked in review-w13.md, host tests pass.
- Finding: (a) GLSurfaceView drains queued events before it recreates a lost context and `glReady` only turns false in `onSurfaceCreated`, so jobs can run with no current context; (b) a failed `StudioNative.init` leaves the pending queue stuck and `upload` waits 20 s; (c) `onDetachedFromWindow` waits up to 500 ms on the main thread and leaks the native compositor when the GL thread is busy.
- Acceptance: `glReady=false` on pause and until the context is valid; drop the queue on init failure so `start()` reports ERROR in under 1 s (fake executor test); destroy posted to the GL thread without waiting; a live native handle counter in the Copy report; phone test: lock the screen 30 s, unlock, draw.
- Size: S-M. Files: StudioGl.kt, StudioSession. Risk: medium (verify on device).
- Src: review-s1b.md F7, F8, F9.

### BK-484 [P2] [PARTLY DONE] Studio memory ceilings add up and the undo graveyard is never pruned against history
- Status: Graveyard pruned against history (1fed051). Left over: the 256 MB cap can still evict a layer whose delete is undoable (BK-512, review-w13.md R8).
- Finding: worst case heap at 12 MP: history 200 MB, graveyard 256 MB, active layer 48 MB, save snapshot 48 MB, unsaved layers; deleted layers stay in the graveyard after history has dropped their delete entry; the autosave copy of the active layer runs on the model thread right after a stroke.
- Acceptance: prune the graveyard to layer ids named by remaining history entries; debounce autosave to stroke end plus 1.5 s idle (5 s ceiling, flush on pause stays immediate); memory test with 10 layers of 12 MP, five deleted, undo, under a stated bound.
- Size: M. Files: StudioSession.kt, History.kt, RawlineApplication. Risk: low.
- Src: review-s1b.md F6, F10, F11.

### BK-487 [P2] [PARTLY DONE] Studio canvas Compose: rotation rebuilds the GL view and re-uploads every layer, a system cancel may commit a stroke, several touch targets are under 48 dp
- Status: Movable GL surface and 48 dp targets merged in 1fed051, system cancel fixed. Left over: whether a rotation still detaches the view is unproven (BK-508, counters and a detach rule in W32).
- Finding: Portrait and Landscape each place the `AndroidView` at a different call site, so rotating destroys the compositor and re-uploads all layers (about 0.5 GB for ten 12 MP layers) and loses zoom and pan; `canvasInput` treats cancelled pointers as up; the layer chips are 24 dp tall and the colour chips 40 dp.
- Acceptance: one `StudioGlView` kept across layouts (`movableContentOf`), view transform kept on rotation, `studio_texture_mb` unchanged after a rotation; a stroke cancelled by the system leaves no history entry (device test with the notification shade); every tap target at least 48 dp with TalkBack descriptions on the chips.
- Size: S-M. Files: feature/studio CanvasScreen.kt, CanvasInput.kt, LayersPanel.kt, StudioGl.kt. Risk: low.
- Src: review-s1b.md F16, F17, F18.

### BK-493 [P2] Studio text: antialiased edges in gamma space look bolder than in linear, and a raster that is re-made at 25 percent scale changes pops
- Finding: from BK-488's numbers, a half covered edge pixel of black text on white is 128 in a gamma blend and 188 in a linear blend, so the same font looks visibly heavier in gamma. Android's `StaticLayout` rasterises with its own gamma rules, and the spec re-rasterises text only when the scale changes by more than 25 percent, so a pinch can show a hard jump in weight and sharpness at the threshold. Straight versus premultiplied alpha at the edge also matters for coloured text on a coloured layer (fringes).
- Acceptance: text is rasterised as coverage only (a mask) and coloured and blended by the compositor, so it follows the document blend space; re-rasterise at the next power of two of the scale (hysteresis 10 percent) and cross-fade for one frame; a golden of black and white text on mid grey in both blend spaces; fonts bundled have licences in THIRD_PARTY.md, and emoji and right to left text are listed as supported or not.
- Size: M. Files: studio text layer (S6), compositor. Risk: medium.
- Src: host calculation 6 Oct 2026; STUDIO_SPEC.md text.

### BK-494 [P2] Studio feather and refine edge cost: a 250 px Gaussian on a 24 MP selection and a guided filter on the full composite are too slow to be live
- Finding: spec asks feather 0 to 250 px in under 150 ms at 24 MP and refine edge with a guided filter. A direct separable Gaussian of radius 250 is about 1500 taps per pixel per pass; a guided filter needs box filters on the composite at full resolution. Not measured; the numbers say these cannot be done naively.
- Acceptance: feather above 16 px runs on a 1/4 or 1/8 resolution copy with a Gaussian built from repeated box blurs (three passes) and is upsampled with a smooth kernel; refine edge runs at 1/4 resolution with the full resolution edge restored by a joint upsample; Copy report rows `studio_feather_ms` and `studio_refine_ms` at 24 MP; the quality check is the edge position error against the exact blur under 0.5 px at radius 20 and under 2 px at 250; the ants overlay never reads the full mask.
- Size: M. Files: studio selection shaders, core/ml GuidedFilter seam. Risk: medium.
- Src: STUDIO_SPEC.md 2.2 and S4; W26-studio-s2.md section 6.

### BK-495 [P2] Studio wand across tile borders: flood fill needs a scanline queue and a global visited bitmap, not recursion or per tile fills
- Finding: the wand selects the contiguous region within a tolerance; on tiles a region crosses borders, so the fill must be global. A recursive fill overflows the stack on a 24 MP flat patch, and a per tile fill leaves seams. A visited bitmap for 24 MP is 3 MB; an explicit queue of spans is bounded by the image height times a small constant.
- Acceptance: scanline flood fill with an explicit stack, run over decoded tiles on the model thread in the S4 form, with `sample all layers` and `contiguous` options; host tests on synthetic images (flat patch crossing four tiles, a one pixel wall, a diagonal leak that must not leak, tolerance 0 and 255); 24 MP flat patch completes without stack growth and within a stated time (measure on the phone).
- Size: S-M. Files: core/studio-model Wand.kt, tests. Risk: low.
- Src: STUDIO_SPEC.md S4.

### BK-496 [P2] Studio 8 bit layers band after repeated opacity and blend changes unless the compositor stays in float and dithers on commit
- Finding: layers are RGBA8 in the document space while the compositor ping-pongs in RGBA16F, which is right; but any operation that writes back to a layer (merge down, rasterise, flatten into a layer, filter commit) quantises to 8 bit again. Smooth gradients such as a sky lose a level at a time on each such step and show steps after three or four.
- Acceptance: quantise with a one level blue noise dither on every write back to a layer (the same dither as BK-492); a test that merges a 16 bit looking gradient five times and measures the largest step (must stay at one level); a note in the layer panel that Flatten keeps 16 bit until export when the export format is 16 bit TIFF (existing path).
- Size: S-M. Files: compositor commit paths, Flatten.kt. Risk: low.
- Src: STUDIO_SPEC.md 2.17 and the S3 merge and flatten operations.

### BK-223 [P3] Mutation testing sample on CropMath and CurveEdit (high value maths) with PIT/Stryker-like tooling
- Acceptance: a one-off run to find weak tests, not in CI; document findings.
- Size: S. Files: tests. Risk: low.

### BK-224 [P3] Adb monkey script and a "chaos" debug menu (kill process, low memory, deny permission, fill disk) for manual resilience testing
- Acceptance: `tools/chaos.sh` documented steps Jai can run from the phone alone (Settings > Developer buttons: simulate low memory, simulate GPU context loss, simulate full disk).
- Size: S-M. Files: Settings Developer section, tools/. Risk: low.

### BK-225 [P3] Weekly scheduled CI run against the latest stable toolchain (Gradle, AGP, Compose BOM) to see breakages early
- Acceptance: `schedule:` workflow building with updated versions in a throwaway branch; issue opened on failure.
- Size: S. Files: new workflow. Risk: low.

### BK-306 [P3] Test that AI masks never depend on the current edit
- Problem: `EditorSession.renderFrame` deliberately renders the frame with geometry only and the camera look (no edits) so that AI masks do not change when Jai changes exposure. That invariant is not covered by a test.
- Acceptance: a host or golden test renders `renderFrame` with two different recipes (same geometry) and asserts equal bytes; a comment-linked test name so the rule survives refactors.
- Size: S. Files: core/render tests, tools/golden. Risk: low.


---

# AREA L: SECURITY, PRIVACY, RELEASE AND UPDATE FLOW

Facts: release APK is signed with a keystore committed to the repo (`app/rawline-sideload.jks`, password in build.gradle.kts) unless Actions secrets exist; `dist/rawline.apk` (about 61 MB) is committed and the git history grows with each commit of it; permissions include INTERNET, READ_MEDIA_IMAGES, MANAGE_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION, POST_NOTIFICATIONS and a dataSync foreground service; `allowBackup=false`; FileProvider exposes only `cache/share/`.

### BK-226 [P0] Move release signing to a private key held only in Actions secrets (and plan the one-time reinstall safely)
- Problem: the sideload key and its passwords are public in the repo (AUDIT item 5). If the repo is or ever becomes public, anyone can build an APK that Android treats as an update to Jai's installed app, with access to his catalogue and storage. Even if private, the key is in every clone and CI log.
- Why: Jai's app holds all-files access. A forged update would own his photos.
- Acceptance: generate a new keystore on the phone-free sandbox, store `RAWLINE_KEYSTORE_B64` and passwords as Actions secrets (Jai taps the secrets in the GitHub mobile site; instructions tap-by-tap), CI fails on `main` if secrets are absent (no silent fallback to the public key), the committed key is deleted and noted as revoked in DECISIONS.md; because Android refuses an update signed with a different key, the migration requires an uninstall: first ship BK-142/BK-143 auto backups and a "Backup now" prompt, then release a transitional build; use APK Signature Scheme v3 key rotation (proof-of-rotation) so installs can update across the key change without uninstalling (supported on Android 9+; requires `apksigner rotate`).
- Size: M. Files: app/build.gradle.kts, .github/workflows/build.yml, docs/DECISIONS.md. Risk: high (lose install continuity if done wrong; test with a second package id first).

### BK-227 [P0] Stop committing release APKs to git
- Problem: `dist/rawline.apk` is 61.6 MB in the working tree and each update adds a new blob to history; GitHub rejects files over 100 MB and warns at 50 MB. The Release asset is the real distribution channel.
- Acceptance: remove `dist/` from the tree, add to `.gitignore`, publish via Releases only, keep `rawline.apk.sha256` in the release notes; history rewrite not required (document the clone size); sandbox can still fetch the APK from the Release for tests.
- Size: S. Files: dist/, .gitignore, README.md. Risk: low.

### BK-229 [P0] [PARTLY DONE] Verify 16 KB page size compatibility of every native library and fail CI otherwise
- Status: Checked 6 Oct 2026 on dist/rawline.apk (built 5 Oct): `zipalign -c -P 16 4` passes and every arm64-v8a library (libraw, librawline_jni, libc++_shared, both LiteRT libs, libandroidx.graphics.path) has LOAD segment alignment 0x4000. Left over: a CI step so it stays true (a quick win, see the list).
- Problem: apps with native code that target Android 15+ must support 16 KB pages for Google Play since 1 Nov 2025; NDK r28+ aligns by default. Rawline is sideloaded, but the S24U (and future Android 16/17 devices with 16 KB page kernels, including developer option) will refuse or crash with mis-aligned libraries, and prebuilt LiteRT/ML Kit libraries may be 4 KB aligned in older versions. `.android-sdk/ndk/28.2.13676358` is present, so own libs are probably fine; the third party AARs are not verified.
- Acceptance: CI step runs `zipalign -c -P 16 4 app-release.apk` or an ELF `p_align` checker over every `.so` in the APK; a table of libs and alignment in docs; upgrade LiteRT if it fails; test on a device in 16 KB developer mode if available.
- Size: S. Files: CI, tools/apk-check.sh. Risk: low.
- Src: Android Developers Blog, 16 KB page sizes, 2025 and Play requirement (S3, 6 Oct 2026).

### BK-429 [P0] Pre-release checklist and an automatic gate script (what can go wrong with a release, and how to check each item before telling Jai to install)
- Context: every push to main can publish a release that Jai installs over the app that holds his edits. These are the failure modes found by reading the workflow, Gradle files, manifest and Room setup on 6 Oct 2026, with the check for each.
  1. **CI not green, or the wrong build published.** The release job needs the golden job; lint and the model link check are non-blocking. Publishing is limited to the newest commit on main (an older run exits quietly), so if a second push lands during a run, the first run publishes nothing. Check: the Actions run for the head SHA is green and a release `v0.1.<run number>` exists whose notes show that SHA; never tell Jai about a build before that.
  2. **LibRaw download.** CMake fetches LibRaw from libraw.org with a pinned hash and one retry; an outage fails the build. Check: if it fails, re-run later; do not change the hash to make it pass.
  3. **Signing.** Without Actions secrets the committed sideload key signs (the notes say "Signed with Actions secrets: false"). If secrets are ever added, the signature changes and Android refuses the update ("App not installed"), which forces an uninstall and loses every edit unless backed up. Check: `apksigner verify --print-certs` shows the same SHA-256 certificate as the previous release; never add secrets without the BK-226 plan.
  4. **Version code.** `versionCode` is the run number, so it only grows while the workflow keeps its history. A local build with `RAWLINE_VERSION_CODE` larger than CI would block later updates, and a repo move or a recreated workflow would reset the run number. Android also refuses to install an older versionCode over a newer one, so a rollback cannot be done by installing the previous release; the only fix is a new build with a higher number (forward fix). Check: the new versionCode is greater than the installed build shown in Settings.
  5. **Re-run of a job.** A re-run keeps the run number and the tag, replaces the asset and rewrites the notes: the same version name with a different SHA-256. Check the notes SHA-256 against the file Jai downloaded.
  6. **Installing over the existing app.** Same package and same key are required; a changed `uses-feature` (OpenGL ES 3.2 is now required) or new permission can change what the installer offers; Play Protect may prompt for a sideload. Check: `aapt2 dump badging` against an allow-list (BK-222).
  7. **Database.** Room is at version 4 with schemas for 3 and 4 committed. Any new column or table needs the next schema JSON, an explicit migration and `MigrationTest`; wipe is only allowed from version 1; a downgrade 4 to 3 exists. Check: `core/data/schemas/.../<N>.json` exists for the declared version and `./gradlew testDebugUnitTest` passes.
  8. **R8 is off.** The release build is the same code path as debug, so there are no R8 surprises today. Do not switch `isMinifyEnabled` on by default without a phone run (AUDIT fifth pass: 61 MB to 37 MB when on).
  9. **Native libraries.** arm64 only is intended but three more ABIs ship (BK-323); every library must stay 16 KB aligned (`zipalign -c -P 16 4`; verified 6 Oct 2026 on the 5 Oct build).
  10. **Foreground service.** Exports run as `mediaProcessing` on Android 15 and later with the matching permission declared (present); any new foreground service type needs its permission and manifest type. Check on an Android 15 or 16 phone that a 3 photo export shows its notification and finishes.
  11. **Shaders.** Mesa accepts what Adreno may not: a shader edit needs `tools/golden/run-golden.sh` and Jai's first-run check; the Copy report prints the GL renderer and any compile problem.
  12. **Docs and stale files.** `dist/rawline.apk` is older than main (BK-227): tell Jai to use the Releases page only.
- Acceptance: `tools/prerelease-check.sh <sha>` (runs on the sandbox with `gh` and the SDK build tools) prints PASS/FAIL for: CI green for the SHA; release exists for the run number and its asset hash matches the notes; signing certificate fingerprint equals the previous release; versionCode above the previous release; `zipalign -c -P 16`; APK size within 10 percent of the previous (BK-218); `aapt2 dump badging` permission and uses-feature diff against `tools/apk-allowlist.txt`; ABIs present equals arm64-v8a (after BK-323); schema JSON present for the declared Room version; no `dist/` APK newer than the release; the notes mention "Signed with Actions secrets" as expected. `docs/RELEASE.md` holds the checklist, the message to send Jai (back up first: Settings > Back up edits; what to open; what to paste: the Copy report) and the forward-fix rule.
- Size: M. Files: new tools/prerelease-check.sh, tools/apk-allowlist.txt, docs/RELEASE.md. Risk: low.

### BK-228 [P1] [PARTLY DONE] Choose the right foreground service type and declare it correctly for each background job
- Status: Commit 42b80f0: export service uses mediaProcessing on Android 15+. Left over: user-initiated data transfer jobs for model downloads and card import.
- Problem: ExportService uses `dataSync` (6 hour limit, BK-137). Android has `mediaProcessing` (also 6 hours) for transcoding-like work, and user-initiated data transfer jobs for downloads and imports (`JobInfo.setUserInitiated`), which are not subject to the same limit.
- Acceptance: export uses `mediaProcessing` with `FOREGROUND_SERVICE_MEDIA_PROCESSING`; model download and card import use user-initiated data transfer jobs; each has a visible notification with Cancel; manifest permissions match; DECISIONS.md explains the choices.
- Size: S-M. Files: AndroidManifest.xml, ExportService.kt, ModelStore.kt. Risk: low.
- Src: Android 15 foreground service behaviour changes (S1, 6 Oct 2026).

### BK-230 [P1] Network security config: cleartext off, only the model hosts allowed, optional pinning
- Problem: INTERNET permission is used only for model downloads (qaihub S3, storage.googleapis.com) but nothing restricts other traffic; code paths could be added later by accident.
- Acceptance: `network_security_config.xml` denying cleartext and listing the two domains in a `domain-config`; a base-config that blocks everything else if feasible (Android cannot block by domain except via cleartext rules, so the practical protection is code-side: a single `Downloader` class with a host allow-list and a unit test that no other `HttpURLConnection` is used).
- Size: S. Files: app manifest, res/xml, core/ml/ModelStore.kt. Risk: low.

### BK-232 [P1] [PARTLY DONE] In-app privacy statement and a verified "no data leaves the phone" claim
- Status: Commit 15fb461: the report passes free text through ReportText.redact. Left over: in-app privacy statement, "Include file names" switch.
- Problem: the Copy report includes device info and file names in errors (`index ${row.name}`), and ML Kit subject segmentation runs through Google Play services (a Google component on a sideloaded app) which can contact Google for model delivery; THIRD_PARTY.md says "ML Kit terms".
- Acceptance: Settings > About > Privacy listing exactly what is stored (catalogue, edits, caches), what network access occurs (model download hosts, ML Kit model delivery if used), and what the report contains; report has a switch "Include file names" default off; a CI test greps the source for networking calls outside the Downloader; if ML Kit is dropped (BK-083) the statement is simpler.
- Size: S-M. Files: Settings, PerfLog.kt, docs. Risk: low.
- Note: AUDIT item 8 (fifth pass): Settings has no Clear button for the last crash and no note that reports contain photo file names.

### BK-234 [P1] Dependency and native library vulnerability monitoring, with LibRaw patch policy
- Problem: LibRaw 0.22.2 is pinned by hash from libraw.org; raw parsers have a history of memory safety bugs (decoders running on arbitrary files, for example from a card Jai did not shoot himself). LiteRT, ML Kit and Room versions are pinned in the catalog.
- Acceptance: a weekly CI job running OSV-scanner (Gradle lock) and listing LibRaw version versus latest on libraw.org (fail on newer security release); an update runbook in docs; a policy to run LibRaw only on files the user opens or indexes (already) and not auto-parse files from other apps without a prompt (BK-113).
- Size: S-M. Files: CI, docs. Risk: low.
- Src: LibRaw 0.22 release page (S11, 6 Oct 2026).

### BK-238 [P1] Prepare for Google's Android developer verification for sideloaded apps
- Problem: Google is requiring apps on certified devices to be registered to a verified developer: from 30 Sep 2026 in Brazil, Indonesia, Singapore and Thailand, with a global rollout planned for 2027; an advanced sideloading flow and limited-distribution accounts for students and hobbyists (no government ID, up to 20 devices) were announced. Jai is in Australia, so enforcement is not here yet, but it will reach him and the app is sideload only.
- Acceptance: a short DECISIONS.md entry with the plan: register a hobbyist / limited distribution account when it opens for Australia, record the package name `app.rawline` and signing certificate fingerprint to register (depends on BK-226: register the final key, not the throwaway one), test the install flow on the phone when available; keep the in-app update flow (BK-240) working with the advanced install flow.
- Size: S (planning) / M (execution when available). Files: docs/DECISIONS.md. Risk: medium (policy details are still evolving; re-check the Android developer verification page before acting).
- Src: Android Authority, sideloading changes timeline, and Help Net Security (S10, 6 Oct 2026).

### BK-239 [P1] Install integrity: show the app's signing fingerprint and build info in Settings > About
- Acceptance: SHA-256 of the signing cert (from `PackageManager.getPackageInfo` with `GET_SIGNING_CERTIFICATES`), build number, commit hash, build date; a check against the fingerprint recorded in the release notes so Jai can compare after installing.
- Size: S. Files: Settings, app BuildConfig (commit hash via CI env). Risk: low.

### BK-240 [P1] [PARTLY DONE] In-app update check and install (sideload update flow with SHA-256 verification)
- Status: Commit 3cd7bb9: the release carries rawline.apk.sha256 and its value in the notes, which is the verification half. Left over: the in-app check, download and PackageInstaller flow.
- Problem: Jai updates by opening GitHub Releases in the browser, downloading the APK and tapping install on the phone each time. An in-app flow would be one tap and show what changed.
- Acceptance: Settings > About "Check for update" (and an optional once-a-day check on launch over Wi-Fi) fetching the latest Release via the public API (this requires the repo to be reachable without a token; if private, use a read-only fine-grained token stored in the app, which is a security trade-off to document); compares `versionCode`; shows release notes (BK-199); downloads the APK to app storage, verifies SHA-256 from the release notes, and installs through `PackageInstaller` with `setRequestUpdateOwnership(true)` (Android 14+) so that updates do not prompt for confirmation each time after the first; needs `REQUEST_INSTALL_PACKAGES`.
- Size: M-L. Files: new app/Updater.kt, manifest, Settings. Risk: medium (security: only install from the expected certificate, which PackageInstaller enforces for updates; protect against a downgrade).

### BK-241 [P1] Release channels and rollback: keep the last 5 builds addressable and mark milestones
- Problem: tags cannot be pushed from the sandbox (proxy rejects them), the release tag is `v0.1.<run number>`, and milestones m1-m9 are not marked anywhere users can see; a bad build has no easy rollback instruction.
- Acceptance: CI marks a Release as pre-release unless the commit message contains `[release]`; release title includes the milestone name; the notes always contain "Previous good build: v0.1.N-1" with a link; Settings > About shows the milestone; `docs/RELEASE.md` describes the phone-only rollback steps.
- Size: S. Files: .github/workflows/build.yml, docs. Risk: low.

### BK-245 [P1] Licence compliance pass: SegFormer weights, ML Kit beta, lensfun CC BY-SA, LibRaw LGPL relink, and an in-app licences screen
- Problem: THIRD_PARTY.md says the sky model weights are "per NVIDIA SegFormer licence, personal use here". NVIDIA's SegFormer code/weights have been published under a non-commercial licence, which matters if the app is ever shared beyond Jai; lensfun data is CC BY-SA 3.0 (share-alike applies to the data file as shipped); LibRaw LGPL/CDDL needs the ability to replace the shared library, which a sideload APK technically allows but should be documented; ML Kit is a beta.
- Acceptance: a table of each item with licence text link, "personal use only" flag, and a note of what changes if Rawline is ever distributed; the screen Settings > About > Open source licences shows text for every component bundled or downloaded; a CI check that THIRD_PARTY.md mentions every dependency in the Gradle catalogue.
- Size: S-M. Files: THIRD_PARTY.md, Settings, CI. Risk: low.

### BK-231 [P2] Permissions audit: remove or justify each, add runtime flow for notifications
- Problem: ACCESS_MEDIA_LOCATION is declared (needed for reading GPS from MediaStore photos) but location is unused (BK-116); POST_NOTIFICATIONS needs a runtime request on Android 13+; MANAGE_EXTERNAL_STORAGE is broad (BK-115); FOREGROUND_SERVICE (generic) is only needed pre-14.
- Acceptance: each permission has a written reason in DECISIONS.md and a runtime path where required; unused ones removed; `READ_MEDIA_VISUAL_USER_SELECTED` added (BK-114).
- Size: S. Files: AndroidManifest.xml, docs. Risk: low.

### BK-233 [P2] Review FileProvider and exported components
- Problem: `file_paths.xml` exposes `cache/share/` only (good); the app adds VIEW/SEND filters in BK-113, which makes incoming URIs untrusted input.
- Acceptance: incoming URIs opened only with ContentResolver, size caps and MIME checks, no path traversal; share directory cleanup tests; `exported` attributes reviewed in CI via the manifest check (BK-222).
- Size: S. Files: manifest, MainActivity.kt. Risk: low.

### BK-235 [P2] Secret scanning and repo hygiene
- Acceptance: gitleaks (or GitHub secret scanning, enabled on the repo) and a CI check that fails if a `.jks`, `.keystore`, or key file is added (the current committed keystore is the exception to be removed by BK-226).
- Size: S. Files: CI. Risk: low.

### BK-236 [P2] Encrypt backups with a user passphrase (optional)
- Problem: the backup zip contains file names and edit settings and goes to a user-chosen location (cloud-synced folders possible).
- Acceptance: AES-256-GCM with a PBKDF2/scrypt key, optional toggle; restore prompts for the passphrase; test vectors in unit tests; unencrypted remains the default for simplicity.
- Size: M. Files: Catalog.kt writeBackup/readBackup. Risk: medium (lost passphrase = lost backup; show a clear warning).

### BK-242 [P2] Versioning: derive versionName and versionCode from the milestone and commit count, not just the run number
- Problem: versionCode = max(run number, 1); re-running a workflow keeps the number; a force-pushed history or a repo move would reset versionCode lower than an installed build and Android blocks the update.
- Acceptance: `versionCode = 1_000_000 + commit count on main` (monotonic), `versionName = 0.<milestone>.<commit count>`; unit test of the calculation script; documented in README.
- Size: S. Files: app/build.gradle.kts, CI. Risk: low-medium (must not decrease for an installed device: pick a base above the current value).

### BK-243 [P2] [MERGED] Release notes generation from conventional commit prefixes (feat, fix, perf, docs) grouped, with the phone's Copy report ask when a perf change is included
- Status: Merged into BK-199 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-199.
- Acceptance: CI uses `git log` since the last release to compose the notes; includes the "Needs phone test" list when commits touch shaders/native/speed.
- Size: S. Files: CI. Risk: low.

### BK-244 [P2] Smoke-check the release APK on the emulator before publishing (install, launch, no crash in 20 s)
- Acceptance: after BK-210, a `release` job step installs the final APK (x86_64 variant for CI) and runs a 20 s launch; publish only on success.
- Size: M. Files: CI. Risk: medium (ABI difference with the arm64-only build).

### BK-362 [P2] Install failure guidance: "App not installed", signature mismatch, downgrade, Play Protect prompts
- Scenario: the phone refuses an update (different signing key, lower version code, not enough space) and shows only "App not installed". Android also shows Play Protect "unknown app" prompts for sideloads.
- Acceptance: README and every release page have a three-line troubleshooting block: (1) take a backup first, (2) what each failure means (signature differs: uninstall needed; version lower: choose the newer build; storage), (3) what Play Protect asks and what to tap; the in-app updater (BK-240) pre-checks version code, free space and signature fingerprint (BK-239) and explains instead of failing.
- Size: S. Files: README.md, CI release notes, docs/RELEASE.md. Risk: low.

### BK-237 [P3] App lock (biometric) for the library
- Acceptance: optional BiometricPrompt on open and after 2 minutes in the background, using `androidx.biometric`; excludes export queue notifications content.
- Size: S-M. Files: MainActivity.kt, Settings. Risk: low.


---

# AREA M: PLATFORM, PANASONIC AND COMPOSE SPECIFICS

### BK-246 [P0] [DONE] Adopt Android 16 behaviours now: predictive back and edge-to-edge (targetSdk is 37)
- Status: Done in commit eec8960: predictive back on, guard tests (manifest rules, BackHandler inventory) and docs/PLATFORM.md.
- Problem: apps targeting 36 and up get predictive back animations by default and cannot opt out of edge-to-edge; `onBackPressed` is not called. The app uses `BackHandler` in places (LibraryScreen, LoupeScreen, EditorScreen, MaskTray, EditorHost) and a custom nav: the system back-to-home animation may preview the wrong destination or `BackHandler` ordering may break (for example crop X vs Back).
- Acceptance: `android:enableOnBackInvokedCallback="true"` explicit, every screen uses `PredictiveBackHandler` or `BackHandler` consistently, back from editor shows the loupe as the revealed screen, crop X vs Back precedence tested on the phone; insets verified on gesture and 3 button navigation, display cutout, and landscape.
- Size: M. Files: AndroidManifest.xml, MainActivity.kt, EditorHost.kt, LibraryScreen.kt, LoupeScreen.kt, EditorScreen.kt. Risk: medium.
- Src: Android 16 behaviour changes (S6, 6 Oct 2026).

### BK-252 [P1] Panasonic Photo Style and in-camera settings: read and show them, and offer a matching starting look
- Opportunity: the S5IIX stores its Photo Style (Standard, Vivid, Natural, L.Classic Neo, Cinelike D2, L.Monochrome ...) in the RW2 maker notes and applies it to the embedded JPEG; LibRaw reads some Panasonic maker notes. The baseline curve is calibrated to the camera's Standard look only (three files).
- Why: Jai compares the raw render with what he saw on the camera screen; if he shoots in L.Monochrome, the raw should open black and white.
- Acceptance: spike: print the Photo Style tag and white balance mode from several RW2 files (Jai supplies 5 files in different styles); if readable, show it in Info and map to profiles (BK-033), defaults: Standard to baseline, L.Monochrome to B&W profile.
- Size: M. Files: rw2_preview.cpp or raw_decode.cpp, Info panel, BaseCurve.kt. Risk: medium.

### BK-253 [P1] High-resolution (96 MP) and multi-shot RAW handling rules
- Problem: the same as BK-039 for memory; additionally the library should label them ("High Res") and avoid generating a full-size edit base by default.
- Acceptance: file detection by pixel dimensions from the RW2 header (12000 x 8000) at index time; badge in grid; Edit opens a 1/2 or 1/4 size base and states it; export streams in strips (BK-127).
- Size: M. Files: Indexer.kt, raw_decode.cpp, Library UI. Risk: medium.
- Src: B&H S5IIX product page, 96 MP mode (S16, 6 Oct 2026).

### BK-323 [P1] [PARTLY DONE] The release APK carries three unused ABIs: about 22.7 MB of native code, so every sideload update is a third bigger than needed
- Status: Commit e9cd055 added `ndk { abiFilters += "arm64-v8a" }` to the app. Left over: the CI step that fails if any other lib folder appears in the APK, and measuring the new size.
- Problem: measured on `dist/rawline.apk` (61.6 MB, built 5 Oct 2026): `lib/arm64-v8a` 9.6 MB, but also `lib/x86` 9.4 MB, `lib/x86_64` 9.0 MB and `lib/armeabi-v7a` 4.2 MB (the LiteRT and GPU delegate AARs and `libandroidx.graphics.path.so` ship all four ABIs). DECISIONS.md says only arm64-v8a is built, and `core/native/build.gradle.kts` has `abiFilters += "arm64-v8a"`, but an ABI filter in a library module does not filter libraries that arrive from dependency AARs; the app module has no filter. The dex is also 27.8 MB (three files, no R8, BK-004).
- Why: the S24 Ultra is arm64 only; Jai downloads the whole APK over the phone each build.
- Acceptance: `defaultConfig { ndk { abiFilters += "arm64-v8a" } }` in app/build.gradle.kts (and, if needed, `packaging { jniLibs { excludes += setOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") } }`); `unzip -l` of the release APK shows only `lib/arm64-v8a`; APK drops by about 22 MB uncompressed; the golden and unit jobs still pass (they do not use these libs); a CI line prints the APK size (BK-218). If an x86_64 emulator smoke test is added later (BK-210), keep the extra ABI in a debug-only build type.
- Size: S. Files: app/build.gradle.kts. Risk: low.

### BK-325 [P1] RW2 maker note reader: pull Photo Style, white balance mode, stabiliser mode, AF mode and point, ISO gain, HLG and Real Time LUT state into the catalogue
- Opportunity: the S5IIX writes its shooting settings into the RW2 maker notes (Panasonic IFD). LibRaw exposes parts (and `makernotes.panasonic`), and our own `rw2_preview.cpp` already walks the TIFF/IFD structure. Several backlog items need these values: Photo Style looks (BK-252), the focus point overlay (BK-254), camera-shake risk (BK-329), dual native ISO handling (BK-026), HLG and flat-profile detection (BK-327, BK-328) and Real Time LUT matching (BK-326).
- Why: one parser and one table feed many features, and the Info panel can finally show what the camera actually did.
- Acceptance: a spike of at most half a day lists, for 10 real S5IIX files (Jai supplies shots in different Photo Styles, IS on/off, HLG, with and without Real Time LUT), which tags are readable by LibRaw 0.22.2 and which need an own parser; then a `CameraSettings` model (nullable fields), stored at index time in new nullable Room columns or a side table (migration with schema JSON, BK-146), shown in the Info sheet under "Camera", and covered by a parser unit test using small hand-built IFD fixtures; unknown values show as "unknown", never guessed.
- Size: M. Files: core/native/rw2_preview.cpp or raw_decode.cpp, core/data/Db.kt, core/model/Photo.kt, LoupeScreen.kt Info. Risk: medium (vendor tags are undocumented and differ by firmware).
- Src: Panasonic LUMIX S manuals and Panasonic AU/SG feature pages for Photo Style, HLG Photo and Real Time LUT (checked 6 Oct 2026): https://www.panasonic.com/au/consumer/lumix-cameras-video-cameras/lumix-s-series-full-frame-cameras-learn/article/s1ii-photography.html

### BK-430 [P1] Samsung Expert RAW and phone-camera DNG files: decide support, test with LibRaw 0.22.2, and fail clearly
- Facts (Adobe and Samsung community threads, checked 6 Oct 2026): the S24 Ultra's Expert RAW writes DNG at 12, 24 and 50 MP; some of these use an unusual compression (reported as compression 52546, said to be JPEG XL, a DNG 1.7 feature), some users reported Lightroom and Snapseed failing on them. LibRaw 0.22 lists DNG 1.7 and JPEG XL support (S11), but only if the build was linked against libjxl; the FetchContent build here has not been checked. The camera roll scan lists every `.dng` as RAW (FileTypes), so Jai's own phone RAW photos appear in the grid.
- Acceptance: a spike with 3 real Expert RAW DNGs (12, 24, 50 MP) from Jai's S24 Ultra: does `findPreview`, the index, and `decodeRaw` work? record the LibRaw compile flags (`LIBRAW_USE_JPEGXL` or equivalent) in the Copy report; if JXL is not built in, decide: enable libjxl (licence, size, 16 KB alignment) or show "This Samsung DNG uses a format Rawline cannot decode yet" with the embedded preview still viewable; 50 MP DNG goes through the memory plan (BK-324: 50 MP at RGBA16F is about 400 MB); results in docs/DECISIONS.md.
- Size: S (spike) / M. Files: core/native CMake (LibRaw options), raw_decode.cpp, Indexer.kt. Risk: medium.
- Src: Adobe community threads on S24 Expert RAW compression and Samsung community threads (checked 6 Oct 2026): https://community.adobe.com/t5/camera-raw-discussions/samsung-s24-ultra-phones-don-t-do-raw-anymore-it-s-not-adobes-fault/m-p/14853506

### BK-451 [P1] SPEC.md versus what is built: the gap table (docs/SPEC_GAPS.md) with an owner for every row
- Method: docs/SPEC.md read line by line against the code and DECISIONS.md on 6 Oct 2026. Rows (status, backlog entry):
  1. Tier 3 zoom (embedded JPEG too small: LibRaw half-size in the background) is not in the loupe; zoom scales the 2048 px preview. Missing. BK-267.
  2. Edit base "LibRaw full linear 16-bit started on Edit press or 1.5 s dwell": built as a half-size base with the full decode on zoom and export; decided in DECISIONS.md. Differs by decision; preview/export parity risk BK-269.
  3. Prefetch next 3 and previous 2: the loupe prefetches 4 ahead and 3 behind (comment and code disagree, AU-036); "bitmap reuse" is not implemented (no `inBitmap`). Differs. BK-452.
  4. Pipeline order: WB before the camera matrix and in camera space: built as multipliers in the working space after the matrix, no lensfun library (data only). Differs by decision. BK-022, BK-443.
  5. Lensfun "LGPL shared": not linked, XML data only. Differs by decision. BK-034, BK-035, BK-466.
  6. Depth range mask: missing (no depth model). BK-080.
  7. AVIF export: not offered (no platform encoder). BK-131.
  8. AI "mask readiness under 2 s after editing starts": models warm when the mask tray opens, not at edit start (MaskingFeature.onEnter). Missing. BK-453.
  9. "Inline cancellable progress, no modal spinners": the editor shows a dimmed canvas with a spinner while saving after 150 ms. Differs. BK-454.
  10. UI "neutral mid-dark grey" (SPEC.md) versus black canvas (UI_SPEC.md, locked by DECISIONS.md): the documents disagree. BK-456.
  11. "Android 14+" (SPEC.md) versus minSdk 31: differs. BK-455.
  12. "Keystore as Actions secrets, never committed": a sideload keystore is committed. Violates. BK-226.
  13. "Tag m1..m9": the proxy rejects tag pushes. Differs by decision. BK-241.
  14. "Each milestone: timings in docs/PERF.md": nothing measured. Missing. BK-001.
  15. "Accessibility basics": sliders done, touch targets, tabs, chips and selection not. Partial. BK-166 to BK-168.
  16. "SAF + persisted URI": the persisted permission cap is not handled and only the last folder is rescanned (AU-024, AU-025). Partial. BK-457, BK-111.
  17. "Also JPG, HEIC, PNG, DNG": compressed DNG variants are compiled out and Samsung DNGs are untested (AE-040). Partial. BK-430, BK-468.
  18. "Camera HEIF" 10-bit HLG: decoded as 8-bit SDR. Partial. BK-275, BK-327.
  19. "Debug overlay with per-tier timings": the loupe overlay shows the last open line only; the report has the table. Mostly met.
  20. Meets: panels list, masks, AI tools (subject, sky, background, people, object, remove, heal, denoise), snapshots, XMP, backup, export queue and Share, tiled 2048 export, Copy report, crash handler, CI release.
- Acceptance: the table is committed as docs/SPEC_GAPS.md, each row has its BK id and a current status, rows are closed by evidence (a test or a report), and docs/SPEC.md gets a one-line pointer to it; reviewed again at every milestone.
- Size: S. Files: docs/SPEC_GAPS.md, docs/SPEC.md. Risk: low.

### BK-247 [P2] Android 17 readiness checklist: read the behaviour changes list when the next beta appears and record which affect Rawline
- Acceptance: a dated section in DECISIONS.md after reading developer.android.com behaviour changes for the next release; test on a beta device only if Jai has one (do not ask him to).
- Size: S. Files: docs. Risk: low.

### BK-248 [P2] [MERGED] Samsung One UI interplay: DeX, split screen, Edge panel, and screen recorder indicator behaviours
- Status: Merged into BK-069 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-069.
- Acceptance: app survives multi-window resize (surface recreated, state kept), `resizeableActivity` true, minimum size sensible; verify in Samsung split screen with the Gallery.
- Size: S-M. Files: manifest, EditorScreen.kt. Risk: low.

### BK-249 [P2] Compose performance review of the editor: stability report, deferred state reads, and recomposition counts
- Problem: slider drags update state that feeds many composables (panels, histogram, labels); strong skipping helps but state reads in composition rather than in layout/draw still recompose.
- Acceptance: Compose compiler metrics report committed to docs/PERF.md, `derivedStateOf` or lambda-based modifiers for fast-changing values, a recomposition counter in debug showing under 3 recompositions per slider tick for the visible panel; target frames within budget (BK-009).
- Size: M. Files: feature/editor/*, core/ui/Controls.kt. Risk: low-medium.
- Src: Compose performance guidance (S5, 6 Oct 2026).

### BK-250 [P2] Custom `LazyGridPrefetchStrategy` to warm thumbnails a screen ahead based on scroll velocity
- Opportunity: the grid asks `ThumbStore.obtain` per visible tile; at 120 fps fling, tiles appear empty. A prefetch strategy that schedules lines ahead (`scheduleLinePrefetch`) plus a thumbnail request queue with cancel-on-scroll (priority to visible tiles) removes blank tiles.
- Acceptance: fling through 2000 photos shows under 2 percent blank tiles after the first pass (counter `blank_tiles_ratio` in the report); `jank_frames` under 3 percent of frames.
- Size: M. Files: LibraryScreen.kt, ThumbStore.kt. Risk: low.
- Src: Compose LazyGridPrefetchStrategy reference (S15, 6 Oct 2026).
- Note: verified 6 Oct 2026 that the grid already passes `key = id` and `contentType`; the remaining gains are in BK-425.

### BK-251 [P2] Use hardware bitmaps for loupe previews where no CPU access is needed
- Problem: the preview decode path uses software decode (`software = true`) for thumbnails, and preview bitmaps go through an LruCache by allocation size; hardware bitmaps live in GPU memory and speed up drawing and reduce Java heap use.
- Acceptance: loupe previews decoded with `ImageDecoder` `ALLOCATOR_HARDWARE` and `setTargetSize` when not needed for analysis (histogram or AutoTools use CPU bitmaps, so those paths keep software); heap use drops measurably in the report; no crash when drawing hardware bitmaps into a Canvas that needs software.
- Size: S-M. Files: core/cache/PreviewDecoder.kt, PreviewCache.kt. Risk: low-medium.

### BK-254 [P2] Read the camera-embedded JPEG metadata fully (dual-pixel focus point, AF area, face boxes) and display the focus point on the loupe
- Opportunity: photographers check focus on the preview; the RW2 maker notes hold AF point coordinates; a "Show focus point" toggle overlays the AF box and 100 percent zoom jump to it.
- Acceptance: parse AF area from maker notes if present (spike with 5 files); overlay and "Zoom to focus" button; never shown when absent.
- Size: M. Files: rw2_preview.cpp, LoupeScreen.kt. Risk: medium (maker note decoding; skip if LibRaw does not expose it).

### BK-255 [P2] MTP/USB-C direct camera import (camera set to USB mass storage or PTP)
- Opportunity: with the S5IIX connected by USB-C, Android shows it through the Files/MTP document provider; the SAF tree is slow but works. A direct importer using `UsbManager` and MTP would be faster but is complex; first step is to test SAF throughput and document how Jai can use it today.
- Acceptance: a measurement of SAF copy speed from card reader and from camera (MB/s) in the Copy report via the import (BK-096) and a documented fallback; decision recorded about MTP.
- Size: S (measure) / L (MTP stack). Files: import module. Risk: medium.

### BK-290 [P2] Keep the app responsive when the phone is in Samsung's "Game" or performance profiles: request sustained performance and a CPU/GPU hint during interaction
- Opportunity: Android's Performance Hint Manager (ADPF `PerformanceHintManager`, API 31) lets the app declare target work durations so the scheduler picks the right cores for the GL and decode threads; this helps slider latency without burning battery all the time.
- Acceptance: a hint session for the GL render thread and decode pool with target 12 ms during drag and 0 when idle; `frame_render_ms` p95 compared with and without in PERF.md.
- Size: S-M. Files: EditorSession.kt, RawPrefetch.kt. Risk: low.
- Src: Android ADPF / Thermal API docs (S8, 6 Oct 2026).

### BK-331 [P2] Probe and log the GPU capabilities the proposed compute work needs (Adreno 750)
- Problem: BK-024 (demosaic), BK-053 (histogram), BK-266 (PBO upload) and BK-011 (pass caching) assume GLES 3.1 compute and a few limits. `GlInfo` now prints renderer, version, max texture and float render targets, but not compute and shared-memory limits or useful extensions.
- Acceptance: the report adds `GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS`, `GL_MAX_COMPUTE_SHARED_MEMORY_SIZE`, `GL_MAX_COMPUTE_WORK_GROUP_SIZE`, `GL_MAX_ARRAY_TEXTURE_LAYERS`, `GL_MAX_TEXTURE_SIZE`, `GL_MAX_COLOR_ATTACHMENTS`, and presence of `GL_EXT_shader_framebuffer_fetch`, `GL_EXT_texture_norm16`, `GL_EXT_color_buffer_float`, `GL_QCOM_*` tiling hints; a short table of the numbers for the S24 Ultra goes in docs/PERF.md so later designs use real limits.
- Size: S. Files: core/cache/GlInfo.kt. Risk: low.

### BK-426 [P2] [DONE] API 37 readiness checklist (edge-to-edge, predictive back, resizability) with tests on the S24 Ultra and a large-screen emulator
- Status: Done in commit eec8960: predictive back on, guard tests (manifest rules, BackHandler inventory) and docs/PLATFORM.md.
- Facts: targetSdk is already 37. Edge-to-edge cannot be opted out of (since API 36) and predictive back animations are on by default; Android 17 (API 37) removes the developer opt-out for orientation and resizability restrictions on large screens (sw 600 dp and wider), according to the Android Developers blog post "Prepare your app for the resizability and orientation changes in Android 17" (February 2026, checked 6 Oct 2026). The S24 Ultra is a phone and is not affected by the large-screen rule; the Studio spec says phone portrait and landscape only.
- Acceptance: the manifest has no `screenOrientation` lock and no `resizeableActivity=false` (verify and keep a test that fails if one is added); a one-time run on a large-screen emulator (tablet profile) to confirm that nothing crashes or becomes unusable (letterboxed is acceptable, crashes are not); the edge-to-edge insets are tested on gesture and three-button navigation; the predictive back list from BK-246 is ticked off; the checklist is stored in docs/PLATFORM.md and re-run when the next API level's behaviour changes are published.
- Size: S-M. Files: AndroidManifest.xml, docs, tests. Risk: low.
- Src: Android Developers Blog, resizability and orientation changes in Android 17: https://developer.android.com/blog/posts/prepare-your-app-for-the-resizability-and-orientation-changes-in-android-17

### BK-432 [P2] Report the One UI version and Android build so every timing and bug is tied to the right software
- Facts: the S24 Ultra shipped with Android 14 and One UI 6.1 and Samsung promises up to 7 major updates (spec sheets, checked 6 Oct 2026); behaviours that matter here (battery optimisation, Gallery, file access, foreground service limits on Android 15+) change per One UI release. `DeviceReport` prints manufacturer, model, Android release and SDK but not the One UI version or security patch.
- Acceptance: add `Build.VERSION.SECURITY_PATCH`, `Build.DISPLAY` (build id) and the One UI version (read `Build.VERSION` `SEM_PLATFORM_INT` by reflection on Samsung devices, formatted as major.minor; fall back to "unknown") plus the refresh rate and HDR capability (completes BK-180) to the Device section; unit test of the formatter with sample values.
- Size: S. Files: core/cache/DeviceReport.kt. Risk: low.

### BK-455 [P2] Decide minSdk: the spec says Android 14 and above, the build says 31
- Facts: SPEC.md says Android 14+; `minSdk = 31` (Android 12); Jai has one S24 Ultra. Supporting 31 to 33 adds branches (foreground service type, `nullOutputStream` was an API 33 call that lint caught, BK-427; photo picker and partial access differences; notification permission from 33) and test cases nobody runs.
- Acceptance: a decision in DECISIONS.md with the cost and benefit; if no other device matters, raise `minSdk` to 34 and delete the compatibility branches, lint baselines for NewApi and the 31 to 33 code paths (and update SPEC.md); if a friend's phone matters, keep 31 and add the API 31 emulator smoke test (BK-210).
- Size: S. Files: app/build.gradle.kts, docs. Risk: low (a one-way door for older phones: ask Jai).

### BK-468 [P2] Decoder robustness: read through the descriptor (not by path), include zlib for compressed DNG, map all LibRaw flips (audit AE-039, AE-040, AE-041)
- Findings: decode re-opens `/proc/self/fd/N`, which can fail for providers that hand out descriptors the app could not open by path; deflate-compressed and lossy-JPEG DNG variants are compiled out (no `USE_ZLIB`, no `USE_JPEG`), relevant to Samsung and other phone DNGs (BK-430); mirrored orientations are mapped to "none"; the RW2 preview parser fails when an IFD ends exactly at the end of the file.
- Acceptance: a `LibRaw_abstract_datastream` over the fd with `pread`; `USE_ZLIB` defined and zlib linked (16 KB aligned, licence line); all eight orientations handled in decode and shader (a golden for each); the parser reads the IFD entries and treats a missing next-IFD offset as 0; tests with the corrupt corpus (BK-356).
- Size: M. Files: core/native (raw_decode.cpp, jni_engine.cpp, CMakeLists.txt, rw2_preview.cpp, geometry.glsl). Risk: medium.
- Src: audit-engine.md AE-039, 040, 041.

### BK-256 [P3] Lumix Lab / Wi-Fi transfer awareness: watch a camera-written "Lumix" folder for newly transferred JPEG/RW2
- Opportunity: the camera can push images over Wi-Fi/Bluetooth to the phone (companion app drops files into Pictures/); the library already watches MediaStore; add a notification "3 new photos from camera".
- Acceptance: new files from folder names matching a user pattern trigger a toast or notification and appear at the top of the grid; no extra permissions.
- Size: S. Files: LibraryViewModel.kt. Risk: low.

### BK-257 [P3] App shortcuts and a Quick Settings tile ("Import from card", "Latest photo")
- Acceptance: static and dynamic shortcuts, long-press launcher icon actions open the import screen or the newest photo; tile optional.
- Size: S. Files: manifest, res/xml/shortcuts.xml, MainActivity.kt. Risk: low.

### BK-258 [P3] [MERGED] Wide gamut and HDR panel awareness: detect HDR and wide-gamut display capabilities and show them in the Copy report
- Status: Merged into BK-180 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-180.
- Acceptance: report prints `Display.isHdr`, `HdrCapabilities` max luminance, wide colour gamut support, refresh rates; used by BK-040 and BK-046.
- Size: S. Files: PerfLog.kt. Risk: low.

### BK-259 [P3] [MERGED] Reduce APK size: split models out of the base (already downloaded), strip LiteRT unused delegates, drop unused lens XML entries, ship one ABI (already arm64 only)
- Status: Merged into BK-323 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-323.
- Problem: the committed APK is about 61 MB; the only native deps are LibRaw, the engine and LiteRT (with GPU delegate) plus ML Kit client libs; download size affects each update over mobile data.
- Acceptance: a size breakdown (BK-218) then three experiments (R8 + resource shrink BK-004, `litert` without unused libs, compress lens XML) with before/after table in PERF.md; target under 40 MB.
- Size: M. Files: app/build.gradle.kts, core/ml/build.gradle.kts. Risk: low-medium.

### BK-260 [P3] Developer verification of timing claims in docs: a script that fails if docs/PERF.md contains a speed claim without a build number and a Copy report reference
- Problem: CLAUDE.md rule: never claim speed or look results without the phone's Copy report.
- Acceptance: `tools/check-claims.sh` run in CI scanning docs/*.md and release notes for "ms" or "fps" claims lacking the `[report: build N]` tag; fails the build otherwise.
- Size: S. Files: tools, CI. Risk: low.

### BK-337 [P3] New-camera readiness: a check that the pinned LibRaw supports the cameras Jai might add (for example a 45 MP Lumix S1R II)
- Problem: LibRaw is pinned (0.22.2); a new body may not be decodable or may need newer colour data, and the first sign would be "could not decode" in the field.
- Acceptance: a CI step lists the LibRaw camera list entries for the Jai-owned and candidate bodies (DC-S5M2X, DC-S1RM2, DC-S1M2) from `libraw_cameraList()` in the golden harness; BK-216 samples are used to prove decode; the LibRaw update runbook (BK-234) names how to bump the pin and rerun the golden and preview-parser tests.
- Size: S. Files: tools/golden, CI. Risk: low.


---

# AREA N: STUDIO (layer-based pixel editor, separate from Develop)

Status of the spec: docs/STUDIO_SPEC.md exists (commit 6d62008). Where an entry differs from it the spec wins and the entry is marked SPEC WINS; entries it already decides are marked SPEC COVERS with what is left over; the entries after BK-397 add what the spec misses and a test and risk entry for each milestone S1 to S10 (BK-411 to BK-420). See the traceability table after the milestone plan. The central risks are memory, undo cost, gesture conflicts, compositing correctness, schedule realism and keeping Develop unchanged.

### BK-365 [P0] [DONE] Reconcile every Studio requirement with the spec when docs/STUDIO_SPEC.md appears, and keep one traceability table
- Status: The spec now exists (docs/STUDIO_SPEC.md, commit 6d62008). The traceability table below maps its sections and risks to backlog ids; entries that differ from the spec are marked SPEC WINS.
- State on 6 Oct 2026: the file does not exist yet (searched the repo, all branches and worktrees). The Studio entries below are written from the brief (a separate mobile Photoshop-like section) and from how Procreate, Photoshop on iPad, Affinity and Photopea work. Anything that contradicts the spec loses.
- Acceptance: when the spec lands, a table `docs/STUDIO_TRACE.md` maps each spec requirement to a BK id, a test, and a milestone; requirements with no entry get one; entries the spec drops are marked DECLINED; the first Studio milestone is cut from the spec's own priority list, not from this backlog's order; goals with numbers (stroke latency, canvas size, layer count) are copied into docs/PERF.md as "not measured" rows like the Develop ones.
- Size: S. Files: docs. Risk: low (but skipping this is how two plans drift apart).

### BK-366 [P0] [SPEC COVERS] Tiled, sparse layer storage with an LRU memory budget and disk spill (the core data structure)
- Status: Spec 2.17, 3.3, R1: 256 px RGBA8 tiles, lossless WebP backing, 1.2 GB GPU cache with LRU, 2.2 GB hard cap; S1 has no tiles (12 MP cap). Left over: decode cost in the pan path and encode cost (BK-400), the eviction fuzz test (BK-412).
- Why it is the big one: the Develop engine holds one photo as one texture. A layer editor holds N full-canvas layers. At 4096 x 4096, an 8-bit RGBA layer is 64 MB and a half-float layer is 128 MB, so 20 layers are 1.3 GB or 2.6 GB before undo. Procreate and Krita solve this with tiles, so only touched and visible tiles live in memory. Procreate's Valkyrie engine is described as Metal based and up to 120 fps on supported iPads (80.lv and iMore coverage, checked 6 Oct 2026).
- Acceptance: a `TileStore` with 256 x 256 tiles (or 512 if measurement says so), empty tiles cost nothing, per-layer tile maps, a global byte budget from BK-367, LRU eviction of tiles not visible and not recently drawn to compressed files in a cache folder (lossless, fast codec), reload on demand without a visible stall (prefetch ring around the viewport); GPU side: tile textures in an atlas or array texture with a free list; a unit test: 100 random strokes on 30 layers at 8192 x 8192 stays under the budget and a full read back equals a reference; a fuzz test of evict/reload; documented tile and pixel formats.
- Size: L. Files: new studio/ module (core/studio). Risk: high (everything depends on it; get it measured early).
- Src: Procreate 5 and Valkyrie coverage (80.lv, iMore, checked 6 Oct 2026): https://80.lv/articles/procreate-5-a-new-graphics-engine-and-more/

### BK-367 [P0] [SPEC COVERS] Memory budget model for Studio: canvas size x layers x bit depth x undo, with hard caps and honest warnings
- Status: Spec 2.17 gives the budget (1.2 GB cache, 576 MB composite caches, 2.2 GB cap, 64 layers, 100 MP). Left over: the per-size table and "maximum layers at this size" message, selection channel memory, coexistence with Develop (BK-399).
- Acceptance: a table in docs/PERF.md (like BK-324 for Develop) for canvases 2048, 4096, 6000 x 4000 (a full photo), 8192 and 12000 x 8000 at 8-bit and 16-bit half float with 1, 10 and 30 full layers; the app computes the budget at New Canvas and shows the maximum layers ("About 24 full layers at this size"); caps enforced (the new-layer action says no instead of crashing); sparse tiles count only what is used; the budget uses `ActivityManager.MemoryInfo` and reserves room for the Develop editor if open; thermal and memory events logged to the Copy report.
- Size: M. Files: studio/MemoryPlan, docs/PERF.md. Risk: low-medium.

### BK-368 [P0] [SPEC COVERS] Undo and redo by copy-on-write tile diffs, with a memory cap that spills to disk
- Status: Spec 2.13 and R6: tile deltas (LZ4) on disk, 100 entries or 1.5 GB, content-addressed snapshots; S1 uses whole-layer snapshots (limit 10). Left over: the LZ4 dependency (BK-401), jump cost and disk-full behaviour (BK-406).
- Problem to avoid: snapshotting whole layers per stroke costs 64 MB or more per step; Procreate's 250 step undo (Procreate handbook, checked 6 Oct 2026) only works because it stores changed tiles.
- Acceptance: each stroke or operation records only the tiles it touched (before images, compressed); history depth is limited by bytes (default 512 MB, spill older steps to disk) and by count (default 250), shown to the user ("History: 143 steps"); undo/redo of layer structure operations (add, delete, reorder, merge, property change) are separate small commands in the same stack; property test: apply 500 random operations, undo all, the document hash equals the start; redo all equals the end; survives the process being killed between steps (journal, BK-369).
- Size: L. Files: studio/History. Risk: high.
- Src: Procreate gestures handbook (undo up to 250 actions): https://help.procreate.com/procreate/handbook/interface-gestures/gestures

### BK-398 [P0] [PARTLY DONE] The Studio plan's own estimates are not credible: split S1 into three releasable slices and re-plan with buffers
- Status: S1a done (3a1f250, cd53d6d, 6164874: model, blend maths, project JSON v1, GPU compositor, golden). S1b host parts merged (c6f8a39); GPU stroke, canvas UI and autosave thread in progress. S1c is specified in W20-studio-s1c.md.
- Spec fact: S1 is "about 4 to 6 hours, one worker" and includes three new modules, a GPU compositor with a golden scene, a project model with JSON and WebP save, Studio home, the canvas screen with layers and brush and eraser, S Pen pressure, autosave with atomic rename, flatten export, a new `ModeHost` behind a build flag, a second Room database and report timers. Later milestones are 5 to 7 days each (about 58 days in total). The Develop work in this repo (see AUDIT passes) shows that even small areas needed several review passes.
- Acceptance: S1 becomes S1a (model, blend reference, compositor, golden `studio_blend3`, host tests), S1b (canvas, brush, eraser, layers panel, autosave) and S1c (home, `ModeHost`, `studio.db`, flatten export, report timers), each with its own exit check and release; every later milestone gets a 1.5 times buffer for phone-only feedback loops (Jai tests on the phone and pastes a report, which costs days, not hours); the plan states the first phone measurement point (end of S1b) and what happens if the numbers miss (re-plan before S2).
- Size: S. Files: docs/STUDIO_SPEC.md (plan section). Risk: low. Spec wins on scope; this only challenges the schedule.

### BK-502 [P0] [DECIDED] The Develop | Studio switch ships in the CI release APK now, before the S1b fixes: decide who sees Studio first
- Status: PM decision 6 Oct 2026: the next release with Studio visible waits for W13 (S1b fixes plus BK-503) and S1c. No Studio visible release before those land.
- Finding: `.github/workflows/build.yml` builds the release with `-PstudioEnabled=true` and checks it with `check-studio-apk.sh ... true`. So the next release puts Studio in front of Jai with the known S1b problems open (review-s1b.md F1 to F3: a long stroke can fail with an out-of-memory message and lose the stroke, a resting palm beats the pen), and S1c's start guard only catches crashes before the first frame.
- Acceptance (recommended): the release carries the Studio code but the switch is hidden until Settings has a "Studio (preview)" switch turned on (default off, stored in the preferences, read by `ModeState` as the flag is today); a host test that with the switch off `startMode()` is Develop and `switchVisible` is false; the Copy report prints the setting. Alternative: change the workflow to `-PstudioEnabled=false` until W28 and W16 have merged and run PHONE-TEST-S1 again. Either way the PM decides before the next release.
- Size: S. Files: .github/workflows/build.yml or feature/settings and StudioEntry.kt, core/studio-model Mode.kt, tests. Risk: low.
- Src: build.yml lines 196 to 200; docs/STUDIO_STATUS.md S1c; review-s1b.md.

### BK-369 [P1] [SPEC COVERS] Document format and crash safety: a versioned package with atomic saves, autosave journal and recovery
- Status: Spec 2.15: a project directory (not a zip) with project.json, WebP tiles, journal, atomic rename, .new and .bak, recovery rules, schema migrations. Left over: encode cost at stroke end (BK-400), blob integrity (BK-405), project export earlier than S9 (BK-410).
- Acceptance: `.rawlinestudio` is a zip (or a folder with a manifest) holding `manifest.json` (schema version, canvas, layer tree, blend modes, colour space), one file per tile or per layer tile set, thumbnail and an optional develop recipe link; saved by writing to a temporary file and renaming; an autosave journal of tile diffs every N seconds so a killed process recovers to the last second ("Recovered your drawing"); unknown future versions open read-only with a message (as BK-154); import and export of OpenRaster (open spec, simple zip of PNG layers) for interchange; PSD is not promised (BK-384); included in the catalogue backup as an optional large item with a size warning (BK-304); host-side round trip and kill-mid-save tests.
- Size: L. Files: studio/format, Catalog backup. Risk: medium.

### BK-370 [P1] [SPEC COVERS] Compositing correctness: premultiplied alpha, blend modes, and the linear versus gamma decision, with golden tests
- Status: Spec 2.2 decides: GAMMA default with a LINEAR option per project, W3C formulas on straight alpha, 24 modes plus Dissolve, a host reference renderer and goldens. Left over: independent cross-check of the non-W3C modes (BK-413).
- Problem: Photoshop blends in gamma-encoded space by default, Procreate and most paint apps likewise, while the Develop engine works in linear ProPhoto. Mixing them gives different looks for Multiply, Overlay and soft brush edges. The choice affects every blend and every brush falloff.
- Acceptance: a decision recorded in docs/DECISIONS.md (recommended: layers stored 8-bit sRGB premultiplied, blends in gamma space to match user expectation from other apps, with an option for linear blending per document); the supported modes listed (Normal, Multiply, Screen, Overlay, Soft Light, Hard Light, Darken, Lighten, Colour Dodge, Colour Burn, Difference, Exclusion, Hue, Saturation, Colour, Luminosity, Add) using the W3C Compositing and Blending formulas; a golden scene per mode on Mesa (the existing golden harness) with a CPU reference; alpha edges tested for dark fringes (premultiplied handling); 16-bit path decided with BK-367.
- Size: M-L. Files: studio shaders, tools/golden. Risk: medium.

### BK-371 [P1] [SPEC COVERS] GPU compositor architecture that does not recompose the whole stack on every stroke
- Status: Spec 2.17 and 3.3: sandwich caches, viewport-only targets, proxies, frame graph. Left over: the real Adreno framebuffer-fetch path is not covered by CI (BK-402).
- Acceptance: cached flattened results below and above the active layer ("sandwich" cache) so a brush stroke redraws one layer and a small composite of the dirty rectangle; groups composite to their own cached texture; pan and zoom only transform the cached flattened image and re-render tiles at the new scale (no per-frame recomposition, 120 fps target on the S24 Ultra, BK-330); a dirty-rect tracker per tile; `composite_ms` per frame in the report; a stress scene with 30 layers, 4 blend modes and masks stays under 8 ms for a stroke update at 4096 x 4096.
- Size: L. Files: studio/render. Risk: high.

### BK-372 [P1] [SPEC COVERS] Brush engine v1: GPU stamping, spacing, pressure and tilt, flow accumulation, eraser, and our own brush tips
- Status: Spec 2.4 and 4.3: stamp engine, stroke accumulation tiles, pressure and tilt, clone, smudge, heal, dodge and burn. Left over: tests per milestone (BK-411, BK-417).
- Acceptance: stamps drawn into a stroke buffer (so opacity applies per stroke, flow per dab) then committed to the layer tiles; round, soft and textured tips generated by our own code (no Adobe or Procreate brush assets, per the SPEC rule of never copying Adobe assets); spacing, size and opacity jitter; pressure and tilt curves editable; eraser as a blend mode; smudge and blur are later; deterministic output for the same input (property test: replay a recorded stroke file twice, equal hash); size up to 2000 px with a performance warning; a brush preview.
- Size: L. Files: studio/brush, shaders. Risk: medium-high.

### BK-373 [P1] [SPEC WINS] Stroke input pipeline and smoothing: batched points, prediction, a stabiliser slider, and a low-latency path
- Status: Spec R7 skips front-buffered low-latency rendering in v1 (revisit in S10 only if input to pixel exceeds 40 ms) and uses historical points plus the Jetpack motion prediction library; the stabiliser is a lazy rope. This entry's front-buffer spike is therefore deferred. Left over: recorded-input tests and sharing the stabiliser with the Develop mask brush (BK-391).
- Facts: stabilisation methods in drawing apps are a moving average, an exponential moving average, and the "lazy radius" or pulled-string model where the brush follows the pointer on a virtual string (descriptions from the Lazy Brush library and Toon Boom docs, checked 6 Oct 2026). Android delivers batched historical points in a MotionEvent, and the AndroidX low-latency graphics library (front buffered rendering) and motion prediction library target exactly this problem, working with finger and stylus (Android Developers Medium post, alpha at the time; verify the current stable artefact versions).
- Acceptance: all `getHistoricalX/Y/Pressure` samples are consumed (never only the latest); a stabiliser slider 0 to 100 with two modes (smooth: EMA with distance-aware alpha; string: lazy radius) default 20; corners preserved by a velocity threshold; optional motion prediction (off for the final stroke, drawn only in a preview overlay); a spike on `GLFrontBufferedRenderer` from androidx.graphics (needs a `SurfaceView`, different from the GLSurfaceView used by Develop) with measured finger-to-pixel latency; target under 25 ms median on the S24 Ultra, recorded in docs/PERF.md; 120 Hz input handled (events at 240 Hz for S Pen).
- Size: M-L. Files: studio/input. Risk: medium (front buffer needs device checks).
- Src: Android Developers, low latency stylus and motion prediction libraries: https://medium.com/androiddevelopers/stylus-low-latency-d4a140a9c982 ; lazy brush description: https://dev.to/usapopopooon/i-built-a-library-to-reduce-hand-tremor-in-drawing-apps-33ng

### BK-374 [P1] [SPEC COVERS] Studio gesture map with conflict rules, palm rejection and Android edge dead zones
- Status: Spec 2.14, 3.4 and R17 define the gestures, timings (250 ms, 12 dp, 100 ms suppression) and a pure state machine with host tests. Not in the spec: a touch shortcut button and edge dead-zone rectangles (it leaves edge swipes to the system). Left over: traces from the real phone, Samsung edge panel check.
- Reference: Procreate: two-finger tap undoes (hold for rapid undo), three-finger swipe down opens copy and paste (handbook, checked 6 Oct 2026). Photoshop on iPad: pinch and two-finger drag to zoom and pan, two-finger tap to undo, three-finger tap to redo, and a Touch Shortcut button at the bottom left as a modifier (Adobe Learn, checked 6 Oct 2026).
- Acceptance: a written map in docs/STUDIO_GESTURES.md: one finger = current tool; two-finger pinch/pan/rotate; two-finger tap undo, hold repeats; three-finger tap redo; three-finger swipe down copy/paste menu; a movable touch shortcut button as a modifier (alt/eyedropper/straight line); a stylus draws while fingers pan; a state machine class tested with simulated MotionEvent sequences (down, move, second down, cancel) for every conflict (a late second finger must cancel the in-progress stroke and undo it, as Procreate does); edge dead zones of 24 dp so Android's back gesture and Samsung's edge panel do not fire mid-stroke (use `setSystemGestureExclusionRects` where allowed); palm rejection through `ACTION_CANCEL` and tool type; every gesture also has a button (BK-388) and can be turned off in Settings.
- Size: M. Files: studio/gestures, docs. Risk: medium (gesture conflicts are the top source of frustration).
- Src: Procreate gestures handbook: https://help.procreate.com/procreate/handbook/interface-gestures/gestures ; Adobe, Photoshop on iPad tutorial: https://www.adobe.com/learn/photoshop/web/learn-photoshop-ipad

### BK-375 [P1] [SPEC COVERS] Layers panel: compact and detailed views, GPU thumbnails, drag reorder, blend and opacity sheet, groups, clipping, alpha lock
- Status: Spec 2.1 and section 5: list rows with 40 dp thumbnail, 48 dp eye, drag handle, blend and opacity chip, grouped blend picker, cap of 64 layers. Compact versus detailed views are not in the spec. Left over: scroll performance with 64 rows and thumbnail caching.
- Reference: Photoshop on iPad offers a Compact view (a vertical strip of thumbnails) and a Detailed view with more information (Adobe Learn, checked 6 Oct 2026).
- Acceptance: both views; thumbnails rendered from the tile cache asynchronously and cached by layer revision; drag handles of 48 dp and an auto-scrolling reorder; a long press opens a sheet (opacity slider, blend mode, rename, duplicate, merge down, delete, clipping mask, alpha lock, mask add/remove); groups with collapse; hidden layers skip rendering; 100 layers scroll at 120 Hz (LazyColumn with stable keys and recycled thumbnails); TalkBack actions for move up/down and visibility; undo for all structural changes (BK-368).
- Size: M-L. Files: studio/ui. Risk: medium.

### BK-376 [P1] [PARTLY DESIGNED] Selections: shapes, lasso, magic wand on tiles, AI select, feather and expand, selection to mask
- Status: W26-studio-s2.md: rect, ellipse, lasso, byte algebra and sparse tiles with 11 passing host tests; GPU and UI are instructions. Not merged.
- Acceptance: rectangle, ellipse, freehand lasso, polygon lasso, and magic wand (tolerance, contiguous, sample merged) implemented as a flood fill over tiles (iterative, bounded memory, cancellable); AI subject/object select reusing MobileSAM and the guided-filter refine (BK-076, BK-298); feather, expand, contract, invert; a marching-ants overlay in a shader (animated off when the user asks for reduced motion, BK-171); selection stored as a single-channel tile layer and convertible to a layer mask; selection edits are undoable (BK-368); memory counted in the budget.
- Size: L. Files: studio/selection, core/ml. Risk: medium.

### BK-377 [P1] [SPEC WINS] Studio and Develop together: send an edited photo as a linked layer, update it from the edit, and flatten with the recipe
- Status: Spec 3.7 makes the hand-off one way: "Open in Studio" creates a new project with the photo as a smart object and a copy of the recipe; nothing flows back and there is no linked layer or "Update from Develop". The linked-update design in this entry is declined. Remaining value is in BK-407 (stale recipe copy).
- Why: Jai's content is RAW photos. The value of Studio is compositing and retouching finished photos, so the bridge matters more than most Studio features.
- Acceptance: "Edit in Studio" from the Develop editor renders the current recipe at the chosen size (up to the memory plan) in 16-bit and places it as a layer that remembers `(photo key, recipe revision)`; "Update from Develop" re-renders when the edit changed (keeping Studio layers above it, with a warning when the canvas size changes); a Studio document can be re-opened and the link still resolves, or shows "Source photo missing" (BK-160); export flattens to JPEG/PNG/TIFF16 through the same export queue and colour rules (BK-133); the original RAW is never modified; tests with fake renderers.
- Size: L. Files: app wiring, EditorHost.kt, studio/integration, Exporter.kt. Risk: medium-high.

### BK-378 [P1] [SPEC COVERS] Transform tool: move, scale, rotate, perspective and mesh warp with high quality resampling and non-destructive preview
- Status: Spec 2.5 (S8): free transform, perspective, warp (P2), Lanczos3 or bicubic at commit, bilinear while dragging.
- Acceptance: free transform with 48 dp handles and numeric entry; perspective and a 4 x 4 mesh warp; bicubic or Lanczos resampling in the GPU shader at commit (bilinear for the live preview); the preview is a transform of cached tiles, committing resamples once (no repeated degradation: transform history keeps the original tiles until commit); snap to canvas centre and angle detents with haptics (BK-058); undo as one step; golden tests of 90 degree rotations being lossless and 1 degree rotation within tolerance of a CPU reference.
- Size: L. Files: studio/transform, shaders. Risk: medium.

### BK-382 [P1] [SPEC COVERS] Studio test plan: what to test before the first line ships
- Status: Spec 3.9 covers most of the test strategy. This plan is now split into one test and risk entry per milestone, BK-411 to BK-420, which add stroke replay, kill-during-compaction, gesture traces and accessibility scripts.
- Acceptance (each item becomes a test or a manual script, tracked in docs/STUDIO_TESTS.md): golden compositing per blend mode and group (BK-370); brush determinism via stroke replay (BK-372, BK-381); undo/redo property test with random operations (BK-368); tile store fuzz of evict/reload and a memory ceiling test at 12000 x 8000 with 30 sparse layers (BK-366); document save/kill/recover test (BK-369); format round trip with older versions fixtures (like BK-361); gesture state machine tests with synthetic MotionEvents including cancel and late second finger (BK-374); selection flood fill at tile borders; filter seam tests; Develop link tests (BK-377); screenshot tests for the layers panel at three font scales (BK-208); TalkBack script for the layers panel and gestures alternatives (BK-388).
- Size: M (plan) then continuous. Files: docs, studio tests, tools/golden. Risk: low.

### BK-385 [P1] [SPEC COVERS] Do not stretch the Develop engine into a layer editor: define a separate Studio engine and GL context rules
- Status: Spec 3.1, 3.3, R5 and R16 decide separate modules, a separate GL context and JNI file, and shared shader includes with Develop goldens byte-identical. Left over: the CI flag matrix (BK-403) and memory coexistence (BK-399).
- Risk: the Develop engine has fixed limits by design (one source image, 8 masks, 16 bitmap layers resampled into 1024 x 1024 R8 array textures, a flat float parameter layout mirrored in params.h and RenderParams.kt, one GL thread, engine calls only on that thread). Reusing it for painting layers would break the golden tests, the params mirror test and the single-thread rule.
- Acceptance: Studio gets its own module (`core/studio`) and engine (own shaders, own tile textures) with a documented context policy: one GL thread per editor, Develop and Studio are not open at the same time (opening one releases the other's textures), the shared code is limited to colour transforms and the curve/adjustment shaders pulled in as source includes with golden coverage on both sides; params.h is untouched; a CI check that core/render does not depend on core/studio and the reverse (SPEC rule: core never depends on feature); APK size impact recorded (BK-218).
- Size: M. Files: new core/studio, settings.gradle.kts, CMake. Risk: medium.

### BK-399 [P1] Two modes alive at once: define what each mode releases when hidden (GL textures, native decodes, models) and prove the memory high-water mark
- Spec fact: `ModeHost` keeps a full `NavHost` for each mode alive with `rememberSaveable`; Studio and Develop each own their own GL context (R5), their own caches and, in Develop, up to 3 prefetched raw decodes of about 100 MB. R5 says the modes are never visible together, but nothing says the hidden mode frees its GPU and native memory. Studio's own budget is a 2.2 GB hard cap (2.17) on a 12 GB phone that also runs the system and other apps.
- Acceptance: on a mode switch the hidden mode releases GL textures and large native buffers (Develop: EditorSession, RawPrefetch handles; Studio: tile cache pages above a small resident set) and keeps only navigation state; the switch back rebuilds in under 1 s from caches or disk; a test (instrumented or host with fakes) switches 20 times and asserts native and GPU counters return to a baseline; the Copy report shows both modes' memory; `onTrimMemory` is honoured by both (BK-013).
- Size: M. Files: app ModeHost, EditorSession, StudioSession. Risk: medium.

### BK-400 [P1] Stroke-end cost: lossless WebP encoding of dirty tiles may not fit "autosave on stroke end"
- Spec fact: tiles are stored as lossless WebP and `project.json` is rewritten on stroke end; "a 20 layer 24 MP project saves incrementally in under 500 ms after one stroke". A long stroke can dirty dozens of 256 x 256 tiles; lossless WebP encoding is much slower than decoding (typically several milliseconds per tile even at low effort; measure, do not assume), so 50 tiles could take most of a second on a busy core and compete with the GL thread and the next stroke.
- Acceptance: a measurement of per-tile encode time at the effort levels available (platform `Bitmap.compress(WEBP_LOSSLESS)` on API 30+ or a bundled libwebp) on the phone; the journal (spec 2.15) stores raw or LZ4 tile deltas immediately (cheap), and WebP encoding happens in a low-priority background compaction that never blocks drawing and is bounded in rate; `studio_autosave_ms` and `studio_compact_ms` timers; a test that a kill between journal write and compaction recovers (BK-368).
- Size: M. Files: studio-render project IO. Risk: medium.

### BK-401 [P1] Studio dependency, licence and size checklist: LZ4, WebP codec, fonts, JNI additions, 16 KB alignment
- Facts: the spec uses LZ4 for history tiles (no LZ4 in the Android SDK; the options are a Java or native library, both need a licence line and, if native, 16 KB alignment), lossless WebP (platform encoder from API 30, or libwebp), eight bundled OFL fonts (several MB), and new C++ in the existing shared library. The APK is already 61.6 MB, or 37 MB with R8 on (AUDIT fifth pass), and 22.7 MB of that is unused ABIs (BK-323).
- Acceptance: a table in docs (component, licence, size, alignment check, who maintains it); THIRD_PARTY.md updated before each ships (the 8 font families with their OFL texts); CI prints APK size per milestone with a budget (BK-218); Studio must not push the APK past a limit chosen by Jai (suggest 60 MB with R8 and single ABI).
- Size: S. Files: docs, THIRD_PARTY.md, CI. Risk: low.

### BK-402 [P1] The real Adreno code path is not covered by CI: self-test both framebuffer-fetch and ping-pong paths on the phone
- Spec fact: the compositor uses `GL_EXT_shader_framebuffer_fetch` when present and ping-pong targets otherwise; Mesa llvmpipe (the golden harness) lacks the extension, so CI only ever tests the fallback. Adreno 750 probably has it, so the production path ships unverified.
- Acceptance: a debug action in Studio settings renders the golden scenes on the phone with each path forced, compares with the host reference renderer, and prints PASS/FAIL per scene and path in the Copy report (also records `GL_RENDERER`, BK-331); a shader define to force the path; failure falls back to ping-pong automatically and records why; run once at first Studio launch (quietly) and after app updates.
- Size: M. Files: studio-render, Settings Developer. Risk: low.

### BK-403 [P1] CI matrix for `STUDIO_ENABLED`: prove Develop is unchanged with the flag off and with it on
- Spec fact: Develop edits are limited to the mode switch and the optional menu item, behind a build-time flag; "Develop can be proven unchanged". Nothing says CI builds both configurations.
- Acceptance: CI builds and unit-tests with the flag off and on; the existing Develop golden set must be byte-identical in both (and after the S2 shader include refactor); a check that with the flag off no Studio class, activity, intent filter or native symbol is in the APK (`apkanalyzer` or `unzip -l` plus `nm`); the release build used by Jai has the flag on only when Jai agrees.
- Size: S-M. Files: .github/workflows/build.yml, app/build.gradle.kts. Risk: low.

### BK-404 [P1] Untrusted input in Studio: images, .cube LUTs, fonts, project zips, clipboard images
- Spec fact: Studio imports images from gallery, files and clipboard (HEIC, TIFF, DNG), user fonts (.ttf, .otf), .cube LUTs (17 to 65 grid), and re-imports project zips. Each is parsed by code that may crash or exhaust memory on a crafted or damaged file (a 65 grid LUT is 275 K entries; a TIFF or PNG can declare 60000 x 60000 pixels; the platform font loader has crashed on bad fonts).
- Acceptance: pre-checks before any decode (read dimensions through `BitmapFactory` bounds or `ImageDecoder` header, refuse over 100 MP total with the existing message); `.cube` parser with size, NaN, and domain checks and a fuzz corpus; fonts loaded in a guarded way (catch, never at startup, a bad font file does not break the document); project zip import reuses the Develop caps and allow-list (BackupReader style) with Studio names; clipboard images capped; every refusal shows a plain message (BK-395); tests for each.
- Size: M. Files: studio-render import, studio-model. Risk: low-medium.

### BK-410 [P1] Project export-all and backup should land early (spec has it in S9); uninstall and a key change currently lose Studio projects
- Spec fact: Studio projects are app-private, are not in the Develop backup (3.6), and "Export all projects" arrives in S9 (R10 accepts the risk). BK-226 (signing key change) and every sideload reinstall can force an uninstall.
- Acceptance: "Export project" (zip) ships in S2 with the format; "Export all projects" and a reminder before an update that requires uninstall ship no later than S4; the zip reuses the Develop restore caps; a backup size estimate and free space check (BK-157); optional scheduled backup of Studio to a chosen folder (BK-142 style) with a warning about size.
- Size: M. Files: studio-render project IO, Settings. Risk: low.

### BK-411 [P1] S1 test and risk entry (shell, model, layers, 3 blends, move and scale, brush and eraser, flatten export)
- Risks: (1) S1 has no tile store, one full-canvas RGBA8 texture per layer: a 12 MP layer is 48 MB, so 20 layers are about 960 MB and whole-layer undo snapshots (limit 10) add up to 480 MB more; set a layer cap for S1 (for example 10) and count snapshots in a budget. (2) Premultiplied versus straight alpha mistakes show as dark fringes: the host reference and the shader must agree on partial alpha. (3) The first GL context for Studio and the second Room database are new moving parts. (4) Brush pressure from S Pen is device behaviour. (5) Flatten export colour (sRGB or P3) must match on-screen.
- Tests: host: the three blend functions on hand vectors including partial alpha, opacity multiply, associativity of stacked Normal layers, project JSON round trip, layer op undo; golden `studio_blend3` compared with the host reference; kill the writer mid stroke (host fake) and reopen; Develop goldens identical with the flag off and on (BK-403); phone script: new project, photo layer, paint with Multiply, move, scale, close mid-stroke, reopen, export JPEG, paste the report (timers `studio_frame_ms`, `studio_stroke_stamp_ms`, texture MB).
- Size: M. Files: tests, docs/PERF.md. Risk: medium.

### BK-412 [P1] S2 test and risk entry (tiles, history, crash journal, gestures, shared shader includes, hand-off)
- Risks: refactoring Develop's `main.frag` into shared `adjust.glsl` includes can change Develop output (must be byte identical); tile cache correctness at 256 px borders; WebP decode time in the pan path; LZ4 licence and alignment (BK-401); journal corruption; gesture conflicts (accidental undo while painting); the JPEG/PNG hand-off keeps a recipe copy (BK-407).
- Tests: strokes that cross tile borders and corners compared with the host reference; tile cache eviction fuzz with an artificially tiny budget; history fuzz (100 random operations, undo all, byte equality, spec 2.13); kill-the-writer test over 200 simulated strokes (spec 2.15); gesture state machine over recorded event scripts including a late second finger and a stroke starting within 100 ms of a tap; Develop golden identical after the include refactor; phone: 24 MP canvas with 10 layers opens, memory timers, pan at 60 fps (report).
- Size: M. Files: tests, tools/golden. Risk: medium-high.

### BK-413 [P1] S3 test and risk entry (24 blend modes, groups, masks, clipping)
- Risks: non-W3C modes (Linear Burn, Linear Dodge, Vivid, Linear and Pin Light, Hard Mix, Subtract, Divide) are defined by the spec itself and verified only against our own reference, so a shared mistake would pass; group isolation versus pass-through; clipping runs sharing one base; masks in layer space under a transform; branchy shader cost with 24 modes; Hard Mix is unstable at thresholds.
- Tests: per mode algebraic properties (identity, commutativity where it holds, bounds, monotonicity) and the exact W3C vectors for the separable and non-separable sets; golden scene per mode over a gradient backdrop with partial alpha and a mask; depth 4 group nesting; a clipped run of 5 layers; masks with linked and unlinked transforms; a 20 layer 24 MP pan on the phone (report); cross-check the host reference against an independent implementation (for example the W3C reference code run offline) for the standard modes.
- Size: M. Files: tests, tools/golden. Risk: medium.

### BK-414 [P1] S4 test and risk entry (selections)
- Risks: magic wand flood fill over a 24 MP document must be iterative and tile aware (recursion would overflow the stack), and contiguity across tile borders is easy to get wrong; Gaussian feather up to 250 px is expensive; expand and contract on large masks; the rule that a selection limits destructive tools but never adjustment layers; saved channels add memory (8 x up to 100 MB at 100 MP).
- Tests: byte-exact pins of add, subtract, intersect, invert (spec 4.2); wand on synthetic images (spirals, one pixel gaps, tile border seams, tolerance 0 and 255); polygon winding with self-intersection; feather against a CPU Gaussian; expand and contract against a brute force morphology; marching ants contour stability when zooming; undo of every selection op; memory with 8 saved channels at 12 and 100 MP.
- Size: M. Files: tests. Risk: low-medium.

### BK-415 [P1] S5 test and risk entry (adjustments and colour tools)
- Risks: parity "within 1 level" with Develop depends on identical conversions between document space (sRGB or P3, 8-bit-ish encoding) and linear ProPhoto; each fused run converts in and out, and half-float round trips can lose a level in dark gradients; fusing adjacent adjustments must give the same result as running them separately; the `.cube` parser; the eyedropper must read the composite exactly; any new flat parameter block needs the C++/Kotlin mirror test.
- Tests: parity goldens of Light, Colour, Curve, Mixer and Grading against Develop at identical parameters; fused versus unfused equality; identity LUT within 1 level and a swap-channel LUT exactness; `.cube` fuzz (BK-404); eyedropper at 1 x 1, 3 x 3, 5 x 5 on a known chart image; adjustment layer with mask and clip; colour history only updating on commit (spec 2.11); phone: each adjustment under 4 ms per frame (report).
- Size: M. Files: tests, tools/golden. Risk: medium.

### BK-416 [P1] S6 test and risk entry (AI tools)
- Risks: the `ImageSource` seam changes Develop's `AiMasksImpl` and `Healer`, which the spec otherwise forbids touching (needs the Develop AI behaviour unchanged, BK-306 style tests); two GPU users at once (the LiteRT GPU delegate and the compositor context) can stall or fail on memory; models plus the 2.2 GB budget (LaMa about 170 MB, SAM, SegFormer); results must always land on a new layer or mask, never on current pixels; cancel must leave no orphan layers or tiles; AI on a 24 MP composite uses 1024 px frames and 512 px tiles.
- Tests: the seam with a fake `ImageSource` (Develop and Studio use the same unit tests for mask post-processing); model lifecycle tests extended (exist for release serialisation); cancel at each stage; memory with models loaded and a full document; latency from the report against the Develop targets (subject and sky under 2 s, tap select under 300 ms, remove under 10 s); a check that a blocked tool is absent (spec rule, no stubs).
- Size: M. Files: tests, core/ml. Risk: medium-high.

### BK-417 [P1] S7 test and risk entry (brush engine complete)
- Risks: smudge, clone and healing on the GPU are harder to keep deterministic than plain stamps; healing reuses CPU `Healer.clonePatch` logic; stabiliser feel cannot be judged without hands; palm rejection when the pen is in range but fingers paint; pen button and `TOOL_TYPE_ERASER` behaviour differs per device; motion prediction overshoot; dodge and burn range weighting; 120 Hz input at 240 Hz pen sampling.
- Tests: stamp positions and spacing deterministic for recorded input (host); stabiliser output for a recorded noisy line (deviation and corner preservation); recorded stroke replay on the phone (same pixels, timing in the report, BK-381); prediction never committed to the layer (only the overlay); pen event traces (hover, down, button, eraser end, cancel) through the router; clone offset and aligned mode math; dodge and burn weights against a CPU reference; finger-versus-pen routing matrix.
- Size: M. Files: tests. Risk: medium.

### BK-418 [P1] S8 test and risk entry (transform, text, shapes, gradients)
- Risks: Lanczos3 ringing and cost at 24 MP; homography precision at extreme corners; one-time resampling rule (commit once); text re-rasterisation at scale changes beyond 25 percent; user font import safety (BK-404); bundled font licences and APK size (BK-401); gradient banding without dither; smart object matrix versus pixel layer commit; RTL and emoji through the platform stack.
- Tests: homography maps the four corners exactly; scale 50 then 200 percent loses detail once (committed pairs); 90 degree rotations lossless; text save, reopen and export pixel parity with preview (spec 2.7); stroke and shadow parity; gradient 16 stops quantisation error at most 1 level (spec 2.8); fonts with missing glyphs; smart object round trip lossless (spec 2.5); numeric entry round trip.
- Size: M. Files: tests. Risk: medium.

### BK-419 [P1] S9 test and risk entry (filters, RAW smart objects, complete export)
- Risks: a RAW smart object decodes through LibRaw and the Develop engine (memory plan, BK-324, and the 96 MP case); filter halos at tile borders; liquify displacement field memory; PDF and OpenRaster correctness (a wrong `stack.xml` breaks other apps); large canvases through the export service (100 MP exports in bands); zip re-import caps; export naming collisions.
- Tests: filter seam tests at 256 px borders for blur, sharpen, noise, liquify; filter then undo byte-identical (spec 2.9); golden filter scenes; vignette and grain parity with Develop's Effects; TIFF16 header and row checks (existing); OpenRaster layout validated against the spec's file list and re-imported by our own importer; PDF page size and image DPI; a 100 MP canvas export under the memory plan on the phone; layer export file name collisions; export of a RAW smart object project with the source file removed (graceful).
- Size: M. Files: tests, tools/golden. Risk: medium.

### BK-420 [P1] S10 test and risk entry (polish, accessibility, performance pass)
- Risks: tuning needs real reports from the phone; TalkBack on a layers panel with drag reorder; a 30 minute soak combines GPU, memory and thermal limits; low-memory behaviour with the tile cache at 50 percent; UI_SPEC visual drift; left-hand mode layout.
- Tests: accessibility checklist run on the phone (spec 2.19) with a written result per item; soak script (BK-181) with the Studio loop (paint, undo, layer ops, save) and the thermal log (BK-332); `onTrimMemory` simulation; font scale 1.0, 1.3 and 2.0 screenshots (BK-208, BK-170); reduced motion honoured (BK-171); recorded-stroke performance regression (BK-381); PERF.md rows filled from reports; snapshot create under 100 ms and history thumbnails.
- Size: M. Files: tests, docs. Risk: low-medium.

### BK-503 [P1] [PARTLY DONE] Studio on a nearly full phone: the autosave retries every 5 seconds forever with a toast each time, and nothing checks free space (measured on the host)
- Status: Merged in b63e9df and 1fed051: free space rule, retry schedule 5, 10, 20, 40, 60 s with a stop after 10 tries, one message, NO_SPACE state, pre-checks, no half written first save, leave dialog. Left over (W32, review-w13.md R4 to R6): leaving waits for the save, a failed duplicate cleans up, the retry gap counts from the failure.
- Finding: with writes failing, a host test that advances the clock 200 times by 5.1 s (17 minutes) records 201 failed saves and 200 toasts ("Could not save. Will try again."), each retry re-encoding the changed layers (CPU and heat for nothing). `grep` finds no free space check anywhere in Studio (`usableSpace`, `StatFs`), so new project, duplicate and a photo import start without one, and the first failed save of a new project can leave a half written folder. Closing with the save state FAILED leaves the project unsaved with no warning.
- Why it matters to Jai: the first public sign of trouble on a phone with 20 GB of photos is a Studio that drains the battery and then loses an hour of painting.
- Acceptance: back off the retry (5, 10, 20, 40, then 60 s) and stop after 10 tries until something changes (new edit or space freed); one persistent line in the status strip ("Not saved: the phone is almost full") instead of a toast per try; free space checked before new project, duplicate, photo import and export (need the estimated project size plus 200 MB) with the message "Not enough space. Free about N MB and try again."; leaving with unsaved changes asks "Leave without saving? Your last changes are not saved."; a failed first save removes its half written folder; host tests with the fake file system (failWrites) for the retry count (at most 12 in 17 minutes), the single notice and the cleanup.
- Size: M. Files: StudioSession.kt, ProjectStore.kt, ProjectCatalog, CanvasScreen.kt, StudioRoot.kt, tests. Risk: low.
- Src: probe on the live StudioSession (docs/backlog/probes/FullDiskProbe.kt); StudioSession.onSaveFailed.

### BK-508 [P1] [DESIGNED] Studio rotation: `movableContentOf` probably still detaches the GL view, so a rotation rebuilds the compositor, and jobs posted in the gap wait out their timeout
- Status: W32-w13-followups.md: patch for the detach rule applies, counters and layout fix specified; not compiled.
- Finding: `CanvasScreen` says the GL view is moved between the portrait and landscape layouts without being detached. Moving an `AndroidView` between call sites removes and re-adds the child view, which fires `onDetachedFromWindow` and stops the GL thread and its context. `StudioGl.viewDetached` then does nothing (unless released), `glReady` stays true, and a job posted in the gap goes to a dead queue and never runs or drops (review-w13.md R1).
- Why it matters to Jai: a rotation with a big project re-uploads every layer (a visible pause) and a stroke or thumbnail started during it can hang for seconds; a second risk is a "child already has a parent" crash that I could not rule out without a device.
- Acceptance: `viewDetached` clears `glReady` when not released (patch in W32); counters `studio_gl_attach`, `studio_gl_detach`, `studio_gl_create` in the Copy report; phone: five rotations show create 1, or the surface is rebuilt at one call site and then they do; drawing right after each rotation works.
- Size: S for the patch and counters, M if the layout must be restructured. Files: StudioGl.kt, CanvasScreen.kt. Risk: low for the patch.
- Src: review-w13.md R1; CanvasScreen.kt, StudioGl.kt read 6 Oct 2026 at b5b06ac. Status: designed in W32 (patch applies, not compiled).

### BK-509 [P1] [DESIGNED] A Studio export that is running when the app goes to the background stalls for 60 seconds and then fails with a wrong message
- Status: W32-w13-followups.md section 5.2: keep awake and wait for the foreground, specified only; PM decided option (a) now, the service stays with S9.
- Finding: export strips are `gpuCall`s with a 60 s timeout; `onPause` sets `glReady = false` (correct for a context that may be lost) so the strips wait in `pending`; a phone call, notification or screen timeout in the middle of a 12 MP PNG export ends in "Export failed. The canvas may be too large for the memory that is free." and the partial file is discarded (review-w13.md R2).
- Why it matters to Jai: the first export of a big painting is the one he walks away from.
- Acceptance: keep the screen on while exporting; strips wait for the foreground (up to 10 minutes) and retry once; an honest message when the wait ran out; later, export on its own context in a service with S9 (BK-410). Phone: Home at 30 percent, back after 20 s: the export finishes.
- Size: M. Files: ExportSheet.kt, StudioExporter.kt, StudioGl.kt, StudioSession.kt. Risk: medium (GL thread timing). PM decision 6 Oct 2026: option (a) now, in W32; the service with its own context stays with S9.
- Src: review-w13.md R2; ExportSheet.kt, StudioSession.renderView read 6 Oct 2026. Status: designed in W32 section 5.2 (specified only).

### BK-379 [P2] [SPEC COVERS] Filters on tiles: halo handling, large radius blur cost, preview on a proxy, and cancel
- Status: Spec 2.9 (S9): the filter list, selection as a mix mask, previews at viewport resolution.
- Acceptance: filters (Gaussian blur, sharpen, noise, levels and curves as adjustment layers) run per tile with a halo of the kernel radius; large blurs use a separable or pyramid method; the preview runs on a half-size proxy then a full pass with progress and cancel; adjustment layers are non-destructive and reuse the Develop curve and colour code; golden tests across tile borders (no seams).
- Size: M-L. Files: studio/filters. Risk: medium.

### BK-380 [P2] [SPEC COVERS] Text and shape layers: decide the scope, then build the smallest useful version
- Status: Spec 2.7 (S8): platform text stack rasterised to tiles, about 8 bundled OFL fonts plus imported fonts. Left over: font licences and size (BK-401), untrusted fonts (BK-404).
- Acceptance: a scope note in the spec reconciliation (BK-365); if included: system fonts only (no bundled font without a licence record in THIRD_PARTY.md), editable text layers rasterised on demand, basic shapes with fill/stroke, snapping; text rendering at 8192 px canvases tested; accessibility of the text entry.
- Size: M-L. Files: studio/text. Risk: medium (font licensing and layout engines are a sink of time).

### BK-381 [P2] [SPEC COVERS] Studio performance budget and a repeatable measurement rig (stroke replay)
- Status: Spec 2.17 lists the Studio timers and a debug overlay. Not in the spec: the recorded-stroke replay rig, which stays here.
- Acceptance: targets in docs/PERF.md as "not measured" until the phone says otherwise: stroke latency, pan/zoom fps with 10 layers at 4096 px, composite ms, open document time, undo time, memory; a recorded-stroke file format (timestamps, positions, pressure) and a debug "Replay strokes" action so the same input produces the same timings and the same pixels on every build; numbers land in the Copy report under a Studio section; a thermal soak for a 20 minute painting session (BK-181, BK-332).
- Size: M. Files: studio/debug, PerfLog. Risk: low.

### BK-383 [P2] [SPEC COVERS] S Pen support: pressure, tilt, hover cursor, eraser end, button actions and palm rejection
- Status: Spec 2.4 covers pressure, tilt, orientation, hover distance, palm rejection and the pen button as eraser. Not in the spec: `TOOL_TYPE_ERASER` (the eraser end), a hover cursor ring, Samsung Air actions.
- Acceptance: `MotionEvent.TOOL_TYPE_STYLUS` and `TOOL_TYPE_ERASER` select pen versus eraser automatically; pressure and tilt feed the brush curves; `ACTION_HOVER_MOVE` shows the brush ring before touching; the barrel button (`BUTTON_STYLUS_PRIMARY`) is configurable (eyedropper, undo); touch input is ignored for drawing while the pen is in range (palm rejection) but still pans and zooms; Samsung Air actions are optional and never required; everything has a finger-only fallback; test with recorded stylus events.
- Size: M. Files: studio/input. Risk: low-medium (device specific behaviour: confirm on the phone).

### BK-384 [P2] [SPEC COVERS] Legal and naming for Studio: no Adobe or Procreate assets, trademarks or file format promises
- Status: Spec section 1 decides no PSD and no Photoshop compatibility, own fonts under OFL, and the name Studio. Left over: THIRD_PARTY.md lines before any font ships (BK-401).
- Acceptance: the section is called Studio (never "Photoshop"); own brush tips, icons and sample content; PSD import/export is a documented non-goal for v1 (huge spec, patent and fidelity risk) with OpenRaster, PNG, TIFF as interchange; any bundled font, brush or texture gets a licence line in THIRD_PARTY.md before it ships; a note that behaviours borrowed from other apps (gestures, panels) are conventions, not assets.
- Size: S. Files: docs, THIRD_PARTY.md. Risk: low.

### BK-386 [P2] [SPEC COVERS] Studio storage, autosave location and export through the existing queue
- Status: Spec 3.6 and R10: Room studio.db, app-private project directories, export through SAF or Share. Left over: export-all earlier than S9 (BK-410), low storage behaviour.
- Acceptance: documents live in app storage by default with an "Export document" to a chosen folder (the format is a single file); size and free-space warnings before opening a big canvas and on every autosave (BK-353 style); cache and tile spill files cleaned on close and by the storage screen (BK-186); exports of flattened results use the export queue, naming and metadata rules (BK-135, BK-134); a large-document backup option that does not bloat the catalogue backup (BK-304).
- Size: M. Files: studio/storage, ExportRunner.kt. Risk: low.

### BK-387 [P2] Thermal and battery rules for long painting sessions
- Acceptance: the ThermalGovernor (BK-176) lowers the composite resolution and caps frame rate at MODERATE, shows a quiet notice at SEVERE; idle detection stops all GPU work after 2 s without input (RENDERMODE_WHEN_DIRTY equivalent); battery saver halves the live-preview resolution; the report records Studio session length and thermal history; the soak test (BK-181) has a Studio script.
- Size: S-M. Files: studio/render, ThermalGovernor. Risk: low.

### BK-388 [P2] [SPEC COVERS] Studio accessibility: button alternatives for every gesture and screen reader support for layers
- Status: Spec 2.19 covers content descriptions, 48 dp, TalkBack custom actions for layers, button twins for gestures, font scale, reduced motion and left-hand mode.
- Acceptance: visible Undo and Redo buttons (gestures are shortcuts, BK-374), zoom +/- and reset, a tool list with labels and states, layer rows with custom actions (move, toggle visibility, opacity step), 48 dp targets (BK-166), high-contrast handle colours, no meaning in colour alone (BK-172), reduce-motion honoured for marching ants and animations (BK-171); a TalkBack pass script in docs/STUDIO_TESTS.md.
- Size: M. Files: studio/ui. Risk: low.

### BK-405 [P2] Content-addressed tile blobs: full hash, reference counting, integrity check on read, and an orphan sweep
- Spec fact: tile files are named `{sha1-12}.webp` in a shared `blobs/` folder and referenced from JSON; snapshots and history share tiles; "tiles are garbage collected only when no history entry or snapshot refers to them". A 12 hex character name is 48 bits of SHA-1, which is plenty for one project but is not a verified identity, and a truncated blob (killed write) would still be addressed by a good-looking name.
- Acceptance: verify the content hash (or a stored length plus CRC) when a blob is read; mismatch marks the tile "damaged" and shows a banner, never crashes (BK-355 style); reference counts rebuilt on open from `project.json`, history and snapshots; an orphan sweep after the 24 h grace period; writes use temp file plus rename; host tests for collision handling, truncated blobs and GC under a fuzzed history.
- Size: M. Files: studio-render project IO. Risk: low-medium.

### BK-406 [P2] History jumps: progress, cancel and disk-full behaviour
- Spec fact: tap on a history entry jumps; undo of one stroke targets under 50 ms, but a jump across 80 entries replays 80 tile deltas from disk, and the history cap is also bytes on disk (1.5 GB). When storage runs low (BK-353) the oldest entries are dropped or writes fail.
- Acceptance: a jump over more than 5 entries shows inline progress and can be cancelled (rolling back to the start state); jump cost recorded (`studio_history_jump_ms`); when disk space is below a threshold the history cap shrinks and a single note says so; a test with a fake disk that fails writes mid-stroke keeps the document consistent and the stroke is dropped, not half applied.
- Size: S-M. Files: studio-model history, studio-render. Risk: low.

### BK-407 [P2] Smart objects: a stored recipe copy goes stale, and a RAW smart object needs the Develop memory plan
- Spec fact: "Open in Studio" copies the recipe JSON into the project and nothing flows back; the RAW source is copied into the project (a 24 MP RW2 is about 30 MB, a 96 MP file about 120 MB); the smart object renders through LibRaw and the engine at the layer's scale, cached as tiles. Edits made later in Develop never reach the Studio copy, and a new LibRaw or colour pipeline version changes the render.
- Acceptance: the smart object records the pipeline version and shows "Rendered with an older version, update?" when the app's renderer changes (user-triggered, never silent); the project lists the copied source size and offers "Remove source copy and keep the pixels" for large files; rendering uses the memory plan (BK-324) and refuses to decode a 96 MP source at full size while the Studio tile cache is full; the one-way hand-off is explained once in the dialog (BK-349 style).
- Size: M. Files: studio-render smart object, feature/studio. Risk: medium.

### BK-408 [P2] Studio first-run onboarding and help copy (Australian English, no em dashes)
- Gap: the spec has accessibility and layout but no first-run flow or help for Studio, whose gestures are denser than Develop's.
- Acceptance: on first entry to Studio a three-step card flow, each skippable and reachable again from Studio settings. Copy: (1) "Studio is for layers and retouching. Develop is for your RAW edits. Switch at the top." (2) "Two fingers move and zoom the canvas. Tap with two fingers to undo, three to redo." (3) "Your work saves by itself. Export makes a new file." Help sheet items: "Paint with one finger or your S Pen. With the pen, your finger moves the canvas." / "Touch and hold to pick a colour." / "Layers: tap the eye to hide, drag the handle to reorder." / "Selections limit what a tool can change. Adjustment layers always apply to everything below." / "AI tools need a download the first time. Use Wi-Fi if you can." Tips appear when relevant (BK-397); all copy in strings.xml and covered by the text rules test (BK-203, BK-396).
- Size: S. Files: feature/studio, strings.xml. Risk: low.

### BK-409 [P2] Crash-loop safety for "opens in the mode you left" and for a project that crashes on open
- Risk: the spec remembers the last mode. If Studio crashes at startup or while opening a damaged project, the app would relaunch into the same crash and Develop (the working half) becomes unreachable.
- Acceptance: after two crashes within a minute of start in Studio mode, the next launch opens Develop with a note ("Studio closed unexpectedly. Opening Develop."); a project that was open at a crash opens next time in "safe mode" (no journal replay, no autosave until Jai confirms, offer Export project zip); counters stored in prefs and shown in the Copy report; tests with a fake crash counter.
- Size: S-M. Files: app ModeHost, studio storage. Risk: low.

### BK-500 [P2] `tools/golden/golden.cpp` is one long else-if chain that every colour and shader task edits at the same place, so two tasks always conflict (W22 and W23 did)
- Finding: each task adds scene keys next to `sharpen` or `look`; the W23 patch failed to apply after W22 on exactly that hunk and had to be rebased by hand (6 Oct 2026). The file is about 280 lines and grows with every task.
- Acceptance: replace the chain with a table of key, handler lines grouped in one block per topic (tone, colour, masks, geometry, look, studio) with a comment marking where a new topic goes, so independent tasks add independent blocks; scene output unchanged (the 19 scenes stay byte identical, checked with `cmp` before and after); no functional change.
- Size: S. Files: tools/golden/golden.cpp only. Risk: low; do it right after W22 and before W23 to remove the conflict.
- Src: W22 and W23 patch ordering test.

### BK-501 [P2] [DONE] 45 of 73 commits carry a model name in a Co-Authored-By trailer, against the CLAUDE.md rule "Do not name any model in commits, code or docs"
- Status: Done in commit 6bc276b: tools/commit-msg-check.sh, tools/check-commit-messages.sh and the CI job.
- Finding: `git log` shows 45 commits whose Co-Authored-By trailer names a model, all up to 0620e2a (before the Studio work); commits since then carry only the `Claude-Session` link. A worker's tool prompt can ask for a `Co-Authored-By` line naming a model; the project rule in CLAUDE.md takes precedence over that prompt.
- Acceptance: history is left alone (it is published); a CI step (and a local commit-msg hook in tools/) fails a commit whose message matches a list of AI model and vendor names kept in tools/ except the `Claude-Session` trailer; `final-check.sh` item 6 prints the count; DISPATCH.md tells workers to leave the model trailer out.
- Size: S. Files: .github/workflows/build.yml, tools/commit-msg-check.sh. Risk: low.
- Src: `git log` trailers, CLAUDE.md rules. Status: done in commit 6bc276b (tools/commit-msg-check.sh, tools/check-commit-messages.sh, the CI job).

### BK-504 [P2] [DESIGNED] After the app is killed in Studio it reopens in Studio on the project list, and the open project is not offered back
- Status: W31-studio-s1d.md: the rules compiled and tested on the host (124 studio-model tests); app, Compose and picker edits specified, not compiled. Not merged.
- Finding: `ModeState` restores the last mode, so a user who tried Studio once and was killed there opens Rawline in Studio every time until they switch; `StudioRoot`'s `open` is plain `remember`, so a process death returns to the project list, not the canvas, and nothing says which project was open.
- Acceptance: start in Develop unless the last session ended on the Studio home or canvas less than 30 minutes ago; the Studio home shows a "Continue <name>" card first when the last session ended with a project open (cleared by a normal Close); PHONE-TEST-S1 step 7 passes.
- Size: S. Files: Mode.kt, StudioRoot.kt, StudioHome.kt, tests. Risk: low.
- Src: Mode.kt startMode, StudioRoot.kt.

### BK-505 [P2] [DESIGNED] RAW files in Studio: the picker offers a Samsung Expert RAW DNG that Android decodes with its own colour, and RW2 is not offered; failures say only "Could not read that picture"
- Status: W31-studio-s1d.md: the rules compiled and tested on the host (124 studio-model tests); app, Compose and picker edits specified, not compiled. Not merged.
- Finding: `PhotoImport.decode` uses `ImageDecoder`, so a DNG goes through Android's raw rendering (not Rawline's pipeline) and looks different from the same file in Develop; an RW2 is not an image type to MediaStore, so the photo picker usually does not list it. The hand-off from Develop (W26) is the intended way.
- Acceptance: until the hand-off exists the home says in one line where RAW files come from ("RAW photos open from Develop"); a picked DNG shows a notice "Rendered by Android, so colours can differ from Develop" on the layer; the decode failure message names the cause (unsupported format, too large, damaged); PHONE-TEST-S1 step 5 records the real behaviour.
- Size: S. Files: StudioRoot.kt, PhotoImport.kt, StudioHome.kt. Risk: low.
- Src: PhotoImport.kt, StudioRoot.kt photo launcher.

### BK-510 [P2] [DESIGNED] A missed pen hover exit leaves finger drawing off until the pen is hovered again (proven on the host)
- Status: W32 patch: 3 router tests pass on the host. Not merged.
- Finding: `InputRouter.onHover(near = true)` set the "pen near" deadline to the end of time; only a hover exit clears it. A probe against the live router shows a finger DOWN an hour after a lost exit is ignored (review-w13.md R3).
- Acceptance: near hover keeps the rule for 2 s from the last hover event; an exit gives the 600 ms grace; three router tests (in W32).
- Size: S. Files: InputRouter.kt. Risk: low.
- Src: probe on the live router (scratchpad/w13rev/RouterProbe.kt). Status: designed in W32, tests pass on the host.

### BK-511 [P2] [DESIGNED] BK-503 leftovers: leaving does not wait for the save, a failed duplicate leaves a damaged copy, and the retry gap counts from the start of the save
- Status: W32 patch: 4 session tests pass on the host (164 tests in all); the CanvasScreen edit is not compiled. Not merged.
- Finding: `leave()` calls the non-blocking `flush()` and goes, so a save that fails because the phone filled up since the last good one is reported after the screen has gone (R4); `Catalog.duplicate` leaves its half copied folder behind when a write fails (R5, proven); the retry gap is measured from `lastSaveStart`, so a slow failing save uses its pause up (R6, proven).
- Acceptance: `flushAndWait(5 s)` and a stay-and-ask path; a failed duplicate removes its folder; `lastFailureAt` as the gap base; four session tests (in W32).
- Size: S. Files: StudioSession.kt, CanvasScreen.kt, Catalog.kt. Risk: low.
- Src: review-w13.md R4 to R6; probes on the live session. Status: designed in W32 (host parts tested, 164 tests; CanvasScreen edit not compiled).

### BK-506 [P3] [DESIGNED] The start guard counts an impatient swipe-away during the first frame as a failed Studio start
- Status: W31-studio-s1d.md: the rules compiled and tested on the host (124 studio-model tests); app, Compose and picker edits specified, not compiled. Not merged.
- Finding: `StartGuard` increments on every Studio start and resets only when the home has drawn two frames. Two quick app kills while the home loads (a slow cold start on a busy phone) flip the next start to Develop with the notice "Studio did not start twice".
- Acceptance: count a start as failed only if the process died from a crash (the existing `ApplicationExitInfo` reader reports the reason) or took over 10 s; test with a fake exit reason list.
- Size: S. Files: Mode.kt, RawlineApplication (ExitReasons), tests. Risk: low.
- Src: Mode.kt StartGuard.

### BK-507 [P3] [DESIGNED] Leaving a Studio project waits up to 3 seconds for a thumbnail the user did not ask for
- Status: W31-studio-s1d.md: the rules compiled and tested on the host (124 studio-model tests); app, Compose and picker edits specified, not compiled. Not merged.
- Finding: `CanvasScreen.leave` renders the project thumbnail and waits at most 3 s before closing, with a spinner. The limit is real but every Close can cost it on a big project.
- Acceptance: write the thumbnail from the autosave path (after a quiet 2 s) so Close is immediate; `studio_leave_ms` in the Copy report; phone: Close under 500 ms on a 12 MP project (measure, no claim before).
- Size: S. Files: CanvasScreen.kt, StudioSession.kt. Risk: low.
- Src: CanvasScreen.kt leave.

### BK-512 [P3] W13 review bundle: leave dialog layout at large fonts, graveyard cap evicting a restorable layer, native readback never compared on a device, export space check on the wrong volume
- Finding: R7 (three dialog buttons in one row), R8 (the 256 MB graveyard cap can evict a layer whose delete is still undoable, and the toast comes at the wrong moment), R9 (banded native readback is checked on the CPU side only), R10 (the free space check looks at app storage even when the export goes to a card or a document provider); see review-w13.md.
- Acceptance: vertical 48 dp button stack; graveyard evicts non-restorable layers first and the toast names the undo; an instrumented round trip stroke, commit, undo, redo on a 4000 x 3000 layer; the export check skips non-app targets.
- Size: S-M. Files: CanvasScreen.kt, StudioSession.kt, ExportSheet.kt, tests. Risk: low.
- Src: review-w13.md R7 to R10. Status: open, not in W32.


---

# SOURCES (all checked 6 Oct 2026 unless stated)

- S1 Android Developers, Behavior changes: apps targeting Android 15 or higher (foreground service dataSync and mediaProcessing 6 hour timeout, onTimeout): https://developer.android.com/about/versions/15/behavior-changes-15
- S2 Android Open Source Project, Ultra HDR (JPEG_R, Android 14) and Android Developers, Ultra HDR display (`Bitmap.hasGainMap`): https://source.android.com/docs/core/camera/ultra-hdr , https://developer.android.com/media/grow/ultra-hdr-display
- S3 Android Developers Blog, Transition to 16 KB page sizes (July 2025; Google Play requirement from 1 Nov 2025 for apps targeting Android 15+; NDK r28 aligns by default): https://android-developers.googleblog.com/2025/07/transition-to-16-kb-page-sizes-android-apps-games-android-studio.html
- S4 Adobe, Lightroom on mobile release notes (July and August 2026: natural language search, edge-refined masks, AI blemish removal, faster Select Subject, generative expand, Trending presets, large-screen layouts): https://helpx.adobe.com/lt/lightroom/mobile/whats-new/adobe-lightroom-on-mobile-release-notes.html
- S5 Android Developers, Baseline Profiles for Compose and Macrobenchmark (about 30 percent faster code execution from first launch): https://developer.android.com/develop/ui/compose/performance/baseline-profiles
- S6 Android Developers, Behavior changes: apps targeting Android 16 (edge-to-edge opt-out removed, predictive back by default): https://developer.android.com/about/versions/16/behavior-changes-16
- S7 Android Developers, Partial photo and video access (Android 14, READ_MEDIA_VISUAL_USER_SELECTED) and Google Play, All files access policy: https://developer.android.com/about/versions/14/changes/partial-photo-video-access , https://support.google.com/googleplay/android-developer/answer/10467955
- S8 Android Developers, Thermal API and ADPF (`getThermalHeadroom`, thermal status listener): https://developer.android.com/games/optimize/adpf/thermal
- S9 Android Developers, Key steps to improve Compose accessibility (48 dp minimum, semantics) and W3C WCAG 2.2 SC 2.5.8 Target Size (Minimum): https://developer.android.com/develop/ui/compose/accessibility/key-steps , https://www.w3.org/TR/WCAG22/#target-size-minimum
- S10 Android Authority, Android sideloading changes timeline and Help Net Security (developer verification: Android Developer Verifier from April 2026, limited distribution accounts up to 20 devices, enforcement from 30 Sep 2026 in Brazil, Indonesia, Singapore and Thailand, global in 2027): https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/ , https://www.helpnetsecurity.com/?p=375237
- S11 LibRaw, 0.22 release notes: https://www.libraw.org/node/2861
- S12 AndroidX heifwriter release notes (10-bit and AVIF encoding in 1.1 alpha): https://developer.android.com/jetpack/androidx/releases/heifwriter
- S13 Infinum Android handbook, Room migrations (export schemas, avoid destructive fallback, test migrations): https://infinum.com/handbook/android/common-android/room-migrations
- S14 Android NDK, Debug your project (tombstones through `ApplicationExitInfo.getTraceInputStream`, Android 12+): https://developer.android.com/ndk/guides/debug
- S15 Android Developers, LazyGridPrefetchStrategy reference: https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyGridPrefetchStrategy
- S16 B&H Photo, Panasonic Lumix S5 IIX product page (96 MP High Resolution mode of eight exposures, dual native ISO): https://www.bhphotovideo.com/c/product/1929575-REG/panasonic_panasonic_lumix_s5_iix.html
- S17 Android Developers, Compose semantics (custom actions): https://developer.android.com/jetpack/compose/semantics

- S18 Google AI Edge, LiteRT NPU and Qualcomm AI Engine Direct (CompiledModel API, Snapdragon 8 Gen 3 supported): https://developers.google.com/edge/litert/next/npu , https://developers.google.cn/edge/litert/next/qualcomm
- S19 Adobe, Camera Raw release notes 2026 (Detect Objects includes shadows and reflections, Reflection Removal, Super Resolution and Denoise update, AI mask Feather and Edge, autofocus point info): https://helpx.adobe.com/au/camera-raw/using/whats-new/release-notes.html
- S20 Adobe community, Assisted Culling early access (eyes open, eye focus, subject focus, clean up, stacks; updated April 2026): https://community.adobe.com/t5/lightroom-classic-discussions/early-access-assisted-culling-lrclassic/td-p/15519069
- S21 darktable 5.4 release (AgX tone mapper, Capture Sharpening in demosaic, workspaces, Dec 2025) and 5.2 (multiple export presets, snapshots compare): https://www.darktable.org/2025/12/darktable-5.4.0-released/
- S22 Adobe Help, Apply profiles in Lightroom mobile (Adaptive Color and Adaptive B&W with Amount): https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/apply-profiles.html
- S23 Snapseed release notes through APKMirror (looks shared by QR code, Stacks, Selective Structure control point): https://www.apkmirror.com/apk/google-inc/snapseed/
- S24 L'art de la photo, Capture One Mobile 3.2.5 wireless LUMIX tethering (April 2026): https://lartdelaphoto.fr/capture-one-mobile-3-2-5-wireless-lumix-avril-2026/

- S25 Samsung, Galaxy S24 series refresh rates (1 to 120 Hz) and Android Developers, Frame rate guide (`Surface.setFrameRate`, API 30): https://www.samsung.com/ae/support/mobile-devices/what-are-the-refresh-rates-and-ppi-for-the-new-s24-series , https://developer.android.com/media/optimize/performance/frame-rate
- S26 Panasonic LUMIX S feature pages (HEIF 10-bit with HLG Photo, Real Time LUT, Dual Native ISO, Photo Style limits in HDR (HLG)): https://www.panasonic.com/au/consumer/lumix-cameras-video-cameras/lumix-s-series-full-frame-cameras-learn/article/s1ii-photography.html
- S27 Own measurements, 6 Oct 2026, on dist/rawline.apk (zipalign check, readelf LOAD alignment, per-ABI library sizes) and own arithmetic for the memory table in BK-324 (to be confirmed with `native_heap` from the Copy report).

- S28 Photo Mechanic ingest (rename variables, IPTC template, backup destination): https://expertphotography.com/photo-mechanic-workflow/ ; Adobe Lightroom mobile card import (iOS only, Android needs files copied first; community threads): https://helpx.adobe.com/ee/lightroom/mobile/add-and-capture-photos/add-and-import-photos/import-photos-from-card-or-cameras.html
- S29 Samsung developer forum and user guides on One UI Sleeping and Deep sleeping apps and Never sleeping apps: https://forum.developer.samsung.com/t/optimize-battery-usage-vs-never-sleeping-apps/16047
- S30 LibRaw error handling (`LIBRAW_DATA_ERROR` for corrupted data or unexpected EOF): https://www.libraw.org/node/2298

- S31 Procreate handbook, gestures (two-finger tap undo, hold for rapid undo, 250 undo steps, three-finger swipe copy and paste menu) and Valkyrie engine coverage: https://help.procreate.com/procreate/handbook/interface-gestures/gestures , https://80.lv/articles/procreate-5-a-new-graphics-engine-and-more/
- S32 Adobe Learn, Photoshop on iPad (Touch Shortcut, compact and detailed layers views, two-finger tap undo, three-finger tap redo): https://www.adobe.com/learn/photoshop/web/learn-photoshop-ipad
- S33 Android Developers, low latency graphics and motion prediction libraries for stylus and touch: https://medium.com/androiddevelopers/stylus-low-latency-d4a140a9c982
- S34 Stroke stabilisation methods (Toon Boom stabilisation docs, Lazy Brush description): https://docs.toonboom.com/help/harmony-24/premium/drawing/about-stabilization.html , https://dev.to/usapopopooon/i-built-a-library-to-reduce-hand-tremor-in-drawing-apps-33ng

- S35 Android Developers Blog, resizability and orientation changes in Android 17 (API 37; opt-out removed for sw 600 dp and wider), February 2026: https://developer.android.com/blog/posts/prepare-your-app-for-the-resizability-and-orientation-changes-in-android-17
- S36 RawPedia (RawTherapee), Demosaicing: https://rawpedia.rawtherapee.com/Demosaicing ; SCITEPRESS and SPIE papers on objective evaluation of RAW denoising and MTF: https://www.scitepress.org/Papers/2010/28312/
- S37 docs/STUDIO_SPEC.md at commit 6d62008 (the Studio spec, including its W3C compositing, Android stylus and motion prediction sources).

- S38 GSMArena and carrier specification sheets for the Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Adreno 750, 12 GB RAM, UFS 4.0, USB 3.2 Gen 2, no SD slot, 6.8 inch 3120 x 1440 LTPO 1 to 120 Hz, 2600 nits): https://m.gsmarena.com/_galaxy_s24_ultra-12771.php
- S39 Adobe and Samsung community threads on Galaxy S24 Ultra Expert RAW DNG (12, 24, 50 MP; unusual DNG compression reported as JPEG XL; compatibility problems): https://community.adobe.com/t5/camera-raw-discussions/samsung-s24-ultra-phones-don-t-do-raw-anymore-it-s-not-adobes-fault/m-p/14853506

- S40 In-repo audit documents of 6 Oct 2026 by other reviewers: audit-engine.md (AE), audit-quality.md (AQ), audit-ui.md (AU), in the same scratchpad folder as this file; dcraw and LibRaw documentation of `-o 4` output space and the `DC-S5M2` matrix in `colordata.cpp` (read from the fetched LibRaw tree).

Not independently confirmed (marked "verify" in entries): darktable and RawTherapee highlight reconstruction methods, Snapseed gesture model, licences of any proposed depth or embedding model, MediaPipe versioned URLs, whether LibRaw exposes S5IIX Photo Style and AF area tags.

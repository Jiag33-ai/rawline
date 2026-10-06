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

## W16 Platform compliance and small fixes (Starts: right after W28; owns MainActivity)
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

## Studio first release gate (decision, no worker yet)
- BK-502 (P0): the CI release has the Studio switch on before the S1b fixes. Recommended: a Settings opt in (default off) or flag off until W28 and W16 merge. BK-503 (P1): full phone, endless save retries. Jai's baseline: PHONE-TEST-S1.md.

## W30 Commit message check (Starts: now; tools and one CI job)
- Full task file: docs/backlog/tasks/W30-commit-message-check.md. A commit-msg hook template, a range checker for CI (only commits after 0620e2a) and a test script; 31 checks pass in throw away repositories, including a real git commit through the hook, and three mutations of the checker made the tests fail.
- Entries: BK-501.
- Owns: tools/commit-msg-check.sh, tools/check-commit-messages.sh, tools/commit-message-base, tools/hooks/commit-msg, tools/test-commit-msg-check.sh, one new job in .github/workflows/build.yml.
- Exit check: CI job green on main and red on a throw away branch with a Co-Authored-By trailer.

## W28 Studio S1b fixes (task file name: W13-s1b-fixes.md; W13 is already Reject-to-trash) (Starts: now, before the S1b canvas ships)
- Full task file: docs/backlog/tasks/W13-s1b-fixes.md. Pure parts built and run: per tile stroke commit (history 10 MB instead of 96 MB on a 12 MP diagonal, bytes identical to the old bake), pen over palm with hover grace, graveyard pruning; 119 model tests pass. Native banded readback built and run through the Studio goldens (byte identical at five band sizes). Session, GL and Compose edits are exact but not compiled.
- Entries: BK-479, BK-480, BK-481, BK-483, BK-484, BK-487.
- Owns: core/studio-model (StrokeTiles, History, InputRouter), core/studio-render, core/native studio files, feature/studio.
- Exit check: CI green and golden green; Jai tries the pen with the hand on the glass, rotates the phone with five layers, and pastes the Copy report.

## Review of the merged Studio S1b session code (done, read only)
- docs/backlog/reviews/review-s1b.md: 19 findings including the Compose layer of 75cb498, BK-479 to BK-484 and BK-487 (stroke dirty region, banded readback, pen over palm, frame path, GL lifecycle, memory, rotation and touch targets). Fix before the S1b canvas ships: F1, F2, F3.

After these 25: baseline profile and macrobenchmarks (BK-002, BK-003, after W01 and W06), onboarding and help copy (BK-191, 192, 392, 393, 350, 394, after W17), BK-024 and BK-422 (GPU demosaic, after W21 has numbers), BK-021 and BK-445 (look refit and camera matrix from Jai's chart), BK-051/052 (undo and before/after), BK-460 and BK-466 (lens consistency), Studio S2 onward (spec milestones), then the rest of M2 to M6.

---


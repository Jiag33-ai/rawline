# Rawline quality audit (tests, CI, build, manifest, security, deps, crash/report)

Audited commit: HEAD b16994f. NOTE: the working tree has uncommitted work by another session touching
core/data (Catalog.kt, Db.kt, BackupFormat.kt, schemas/, build.gradle.kts) and core/model/Photo.kt. That work appears
to address open items 1 (backup), 2 (edit key) and Room schemas. Findings AQ-004/005/006/007 are marked
"in flight" and were judged against HEAD; re-check once that lands. No repo files edited, gradle not run.
Sizes: S = under 1 h, M = 1 to 4 h, L = over 4 h.

## Counts
P0: 0 | P1: 4 | P2: 19 | P3: 15 | total 38

## Findings

### AQ-001 P1 CI: external downloads can block every release
.github/workflows/build.yml:22-29. The `golden` job runs `tools/models/fetch.sh` (HEAD to S3 and storage.googleapis.com)
and curls a 38 MB RW2 from raw.pixls.us (volunteer-run host). `build` has `needs: golden` (line 31), so any 5xx, rate limit
or timeout on those hosts fails the job and no APK is published, even though no code changed. Also the same RW2 is fetched
again inside tools/golden/run-golden.sh:24 and libraw.org is hit twice (run-golden.sh:13 and CMake FetchContent).
Fix: split into a non-blocking `external-links` job (continue-on-error, scheduled nightly, not in `needs`); cache the RW2 and
libraw tarball with actions/cache keyed on their SHA-256; make the RW2 download retry (`curl --retry 5 --retry-all-errors`).
Size: S.

### AQ-002 P1 Signing: public keystore signs releases, switching to secrets later strands the install
app/build.gradle.kts:30-37 (password literal), app/rawline-sideload.jks committed, ci build.yml:57-77 falls back silently.
Scenario A: anyone who can read the repo can sign an APK with the same key; Android will accept it as an update over Jai's
install (user must tap install, but there is no signature barrier). Scenario B: the day RAWLINE_KEYSTORE_B64 is set, new
builds have a different signature, the phone refuses the update ("App not installed"), and the only path is uninstall,
which deletes the Room DB, edits, masks and heals (backup restore is the only recovery and it is imperfect, see AQ-004).
The spec (docs/SPEC.md "Build/deliver") says "never committed", DECISIONS.md overrides it; README.md still says CI builds a
debug APK without secrets (doc drift).
Fix: generate a private key once, store as secrets now, and make the build fail (not fall back) on main when secrets are
absent. Before rotating, ship a build that adds a one-tap "Back up edits" prompt. Size: S (plus one manual phone step).

### AQ-003 P1 Tests: nothing covers Room, Catalog, backup, DeviceScanner or Indexer
core/data has zero tests (no src/test dir, build.gradle.kts has no testImplementation). Catalog.saveRecipe guards,
readBackup/writeBackup, the 2->3 migration (Db.kt:193-197 hand written SQL that must match Room's expected schema exactly
or the app crashes on open for existing installs), reapply, Xmp.build/parse/write and scan pruning are all unexercised.
These hold user data. Fix: add Robolectric or androidTest in-memory Room tests (proposal in section "Missing tests").
Size: L.

### AQ-004 P1 Backup restore (in flight in working tree)
HEAD Catalog.kt:270-315: whole entries read with `z.readBytes()` (zip bomb or 400 MB heal PNG = OOM on a largeHeap app),
not transactional (edits applied, then a bad snapshots.json throws, leaving a half restore), `edits.putMeta(metas)` REPLACE
overwrites newer ratings with old backup values, `count` reports entries in the file not applied. Name handling is safe
(File(dir, name.substringAfter('/').replace("/","_")) cannot escape; ".." and "" resolve to existing dirs and are skipped).
The working tree adds BackupReader/BackupMerge and db.withTransaction; verify it bounds entry sizes and rejects bad JSON before
writing. Fix and tests: see Missing tests T3. Size: M (mostly done).

### AQ-005 P2 Room: no schema export or migration tests, downgrade crashes (in flight)
core/data/build.gradle.kts has no `ksp { arg("room.schemaLocation", ...) }` at HEAD, so `exportSchema = true` (Db.kt:185)
writes nothing and no MigrationTestHelper test is possible. Installing an older build over a newer one (for example a local
build, versionCode 1, see AQ-030) throws IllegalStateException on first DB access, in Application.onCreate
(RawlineApplication.kt:43) before CrashStore can show anything useful. `fallbackToDestructiveMigrationFrom(true, 1)`
(Db.kt:197) wipes edits for v1 installs. Working tree adds schemas/ and version 4; confirm `androidx.room:room-testing` and a
migration test land with it. Fix: schemaLocation, MigrationTestHelper test for every step, `fallbackToDestructiveMigrationOnDowngrade`
is NOT recommended (silent data loss): instead catch and show "this build is older than your data". Size: M.

### AQ-006 P2 Edit key collides for identical files (in flight)
core/model/.../Photo.kt:33-37 key `name|size|modified`. Two cards with IMG_0001.RW2 of equal size and mtime (burst copies, a
file copied to a second folder) share one edit, rating and flag; editing one changes the other and ExportRunner.write
(ExportRunner.kt:111) loads the wrong recipe. DeviceScanner/Indexer also key reapply on it. Working tree touches Photo.kt;
confirm the fix keeps old keys readable (a migration of edits.key is needed or every existing edit disappears).
Fix: add a content hash of first 64 KB plus size, with a one-off key migration plus test. Size: M.

### AQ-007 P2 DeviceScanner prunes on partial listing
core/data/.../DeviceScanner.kt:56. `rows.isEmpty()` is guarded, but a non-empty partial result (Android 14 "Selected photos"
grant, slow MediaStore, SD card mid-mount) deletes every missing row, losing indexed EXIF, thumbnails (orphaned) and
rating/edited flags until reapply (which only restores from meta/edits tables, so the previews, ids and export queue
references to old ids break: ExportRunner.kt:61 `byUri` returns null, job fails "Photo no longer in the library").
Fix: only prune rows when the new count is at least ~50% of known or after two consecutive agreeing scans; never prune while
`READ_MEDIA_VISUAL_USER_SELECTED` partial access is active. Size: S.

### AQ-008 P2 Export silently renders an unedited photo when the saved recipe cannot be read
app/.../ExportRunner.kt:111 `loadRecipe(p) ?: EditRecipe()`. Catalog.loadRecipe returns null for corrupt JSON or a recipe
written by a newer schema (Catalog.kt:220). The export then succeeds with no adjustments, status Done, user posts the wrong
image. Same pattern in LibraryViewModel.kt:197,203,208 (copy/paste/sync treat unreadable as default and paste over it).
Fix: distinguish "no recipe" from "unreadable"; fail the job with "Edit could not be read" for unreadable. Size: S.

### AQ-009 P2 Export cancel flag shared by queue and Share (reverse race of the audited fix)
ExportRunner.kt:31 single `@Volatile cancelled`; line 88 `exportForShare` sets it false. Scenario: user taps Cancel on the
running queue job, then immediately taps Share on another photo: the share resets the flag, the queue job keeps rendering
and is written as Done although the user cancelled. Also line 57 resets at job start, so a Cancel tapped between jobs is lost.
Fix: per-job cancellation (a CompletableJob or AtomicReference<Long> job id). Size: S.

### AQ-010 P2 Release is not minified and has no keep-rule story ready
app/build.gradle.kts:43 `isMinifyEnabled = false`; no proguard-rules.pro anywhere (git ls-files shows none). Current state is
safe for JNI/Room/org.json (nothing is stripped), but the APK carries unshrunk Compose, ML Kit and LiteRT, and nobody has
checked what happens on enabling R8. Facts for the day it is turned on: JNI names `Java_app_rawline_core_nativelib_Native_*`
(jni_bridge.cpp:15,25; jni_engine.cpp) require `-keep class app.rawline.core.nativelib.Native { native <methods>; }` (R8 renames
the class and the native lookup fails with UnsatisfiedLinkError at first engine call); Room 2.8 generates code via KSP and
needs no reflection rules; LiteRT and ML Kit ship consumer rules; org.json is the framework one at runtime; enum names are
stored as strings in ExportSettings.toJson (Exporter.kt:40) and EditRecipe JSON, so enums must be kept or names get obfuscated
and saved settings stop parsing. Also release stack traces in the report are readable only because nothing is renamed.
Fix: either leave off deliberately (record in DECISIONS.md) or add R8 + rules + a CI step that installs nothing but runs
`assembleRelease` and unit tests against minified classes. Size: M.

### AQ-011 P2 No lint, no static analysis
build.yml has no `lint`, detekt or ktlint step (matches AUDIT.md open item 5). AGP runs only lintVital on assembleRelease for the
app module, so library modules (all UI) are never linted: missing `contentDescription`, hard coded strings, ObsoleteSdkInt,
unsafe `READ_EXTERNAL_STORAGE` use, UnspecifiedRegisterReceiverFlag etc. go unseen. No `lint { }` block in any gradle file.
Fix: add `./gradlew lintDebug` (report-only first, then baseline) and upload the HTML as an artifact. Size: S to M.

### AQ-012 P2 Actions not pinned, several majors behind
build.yml:15,38 checkout@v4, 39 setup-java@v4, 43 cache@v4, 47 gradle/actions/setup-gradle@v4. Latest seen on GitHub release pages
today: setup-java v6.0.1 (9 Sep), cache v6.1.0 (26 Jun), gradle/actions v6.4.0 (28 Sep, note: needs an extra trusted key if
dependency verification is on), checkout v7.x (page date unreliable, treat as unverified). Tags are mutable: a compromised
action can read RAWLINE_KEYSTORE_PASSWORD in the signed build step (the job has `contents: write`).
Fix: pin each to a full commit SHA with a version comment and add Dependabot for `github-actions`. Size: S.

### AQ-013 P2 No concurrency control, duplicate triggers, floating runner
build.yml:2-5 `on: push` (all branches) plus `pull_request`: a PR branch builds twice. No `concurrency:` group, so two quick
pushes to main race: the slower, older run can finish last, its release becomes the newest "Latest" and its older code
is what Jai installs under the newest tag.
`runs-on: ubuntu-latest` (13, 32) floats to a new image; the apt package names on line 17-18 (`libgles2-mesa-dev`,
`libegl1-mesa-dev`) have been renamed across Ubuntu releases and will break the golden job without a code change.
Fix: `concurrency: { group: build-${{ github.ref }}, cancel-in-progress: true }` (but not for the publish step), restrict push
to main + tags, pin `ubuntu-24.04`. Size: S.

### AQ-014 P2 Release numbering and artefacts
build.yml:87 `TAG="v0.1.${{ github.run_number }}"`; app/build.gradle.kts:17-18 versionCode = run number, versionName 0.1.N.
(a) run_number counts PR and branch runs, so releases have gaps and the number says nothing about main. (b) `gh release create`
without `--latest=false/true` or `--prerelease`; notes have no changelog (Jai cannot see what changed on his phone).
(c) only the APK is attached: no SHA-256, no `mapping`, no native debug symbols (see AQ-029), so a field crash cannot be
symbolicated. (d) the re-run branch (line 90-92) `--clobber`s the asset of an existing release, i.e. different bytes under
the same versionCode/tag. (e) the two build steps duplicate (lines 65-82) and `assembleRelease` after `testDebugUnitTest`
rebuilds LibRaw natively a second time (two full arm64 compiles of LibRaw per run).
Fix: tag from a counter derived from `git rev-list --count`, attach `rawline.apk.sha256`, generate notes with
`--generate-notes`, merge duplicate steps with an env fallback. Size: S.

### AQ-015 P2 No caching of the LibRaw download or native build
CMakeLists.txt:8-12 FetchContent pulls libraw.org on every clean CI build; build.yml caches only `.android-sdk` (line 40-44) and
Gradle (setup-gradle). `.cxx` and `build/` are not cached. A libraw.org outage fails the whole build (single host, no mirror;
the CLAUDE.md gotcha explains why not GitHub). Fix: `actions/cache` for `core/native/.cxx` plus a download dir via
`FETCHCONTENT_SOURCE_DIR_LIBRAW_SRC`, key on the pinned hash. Size: S.

### AQ-016 P2 Golden sample RW2 is unverified and unpinned
tools/golden/run-golden.sh:24 and build.yml:27 download `P1055415.RW2` with no checksum. If raw.pixls.us replaces the file the
golden references silently mismatch (or worse, `--update` bakes in a different image). The model zips are hash pinned but the
test input is not. Fix: `echo "<sha256>  file" | sha256sum -c`. Size: S.

### AQ-017 P2 Model links: hashes never verified in CI, two sources of truth, People model unpinned
tools/models/fetch.sh:6-9 only does a HEAD and prints size; it never downloads and never compares `sha256` from
core/ml/.../ModelStore.kt:31-40. A typo in a pinned hash would only surface on the phone as "download does not match the
expected checksum" after up to 430 MB. models.json duplicates the URLs in Models.kt (no test ties them together, ModelsTest only
checks https and hash shape). PEOPLE (ModelStore.kt:33) uses `/latest/` with `sha256 = null`: Google can change the file and the
app will happily load a model whose input/output conventions differ (AUDIT item 8). The model-link CI step also blocks releases
(AQ-001). Fix: nightly job downloads the four small zips and checks hash; generate models.json from Models.kt or read it in a
test; pin a versioned URL for people or pin the hash and fail loudly when it moves. Size: M.

### AQ-018 P2 Model download: retries deterministic failures, no space or network checks
ModelStore.kt:66 `repeat(3)` catches every Exception, so a checksum mismatch or HTTP 404 re-downloads the full file three times
(up to 1.3 GB for denoise) with no backoff. No free-space check before a 430 MB write to filesDir, no metered-network guard, and
the blocking socket read is not cancellable (`withContext` cannot interrupt HttpURLConnection reads) while the Mutex is held, so
a second feature asking `ensure` waits behind a stalled download for up to the 30 s read timeout per read.
Fix: classify errors (retry only IOException/5xx), `StatFs` check against approxMb * 2, expose "Wi-Fi only" pref, use
`coroutineContext.ensureActive()` between buffer reads. Size: S.

### AQ-019 P2 TIFF16 ignores Display P3 (untagged)
Exporter.kt:74 sets output space P3 for any format and Tiff16Writer (lines 199-201) writes no ICC profile tag 34675. A P3 TIFF
opens in other software as sRGB with visibly undersaturated colour. JPEG/PNG are tagged via the Bitmap ColorSpace (line 152).
Fix: embed a Display P3 (or sRGB) ICC profile in the TIFF IFD, or force sRGB for TIFF in the UI; add a TIFF test that asserts tag
34675 present when P3. Size: M.

### AQ-020 P2 Crash report root cause can be cut off, no context saved with the crash
core/cache/.../PerfLog.kt:58 `e.stackTraceToString().take(6000)`: Kotlin prints the "Caused by" chain last, so a deep trace is cut
exactly where the root cause is. The file holds only thread name and trace: no timestamp, no versionName/build number, no
free memory, no current screen or photo, no breadcrumbs. Because the file is overwritten and never cleared, after an update
Jai cannot tell whether "Last crash" is from the current build or three builds ago (Settings shows it forever,
SettingsScreen.kt:82-86, and `remember { CrashStore.last }` MainActivity.kt:257 is read once per composition).
Fix: store a small JSON (time, version, build, thread, heap used/max, route, last PerfLog.lastOpen, last 10 errors), keep the first 3000 and last 3000 chars, rotate the last 3 crashes, add "Clear" and age display. Size: S.

### AQ-021 P2 Native crashes, ANRs and OOM kills are invisible to the report
Only a JVM uncaught-exception handler exists (PerfLog.kt:55-61); there is no use of `ApplicationExitInfo` (available at minSdk 31),
no `onTrimMemory`/`onLowMemory`, no StrictMode in debug. The riskiest code is native (LibRaw, GL engine, LiteRT GPU delegate):
a SIGSEGV or an LMK kill during a RAW decode leaves "Last crash: none" in Copy report, which is the only debug channel
(Jai is phone-only). Fix: on startup read `ActivityManager.getHistoricalProcessExitReasons(null,0,5)`, record reason, importance,
pss/rss and `traceInputStream` first 4 KB (tombstone head for native crashes) into the report; log `onTrimMemory` levels
into PerfLog. Size: M.

### AQ-022 P2 Copy report lacks most of what is needed to debug on a phone
PerfLog.kt:77-92 and MainActivity.kt:251-256 include: version/build/date, manufacturer+model, Android SDK, max heap, timings,
20 errors, last crash. Missing: report timestamp, build type, current heap used/native heap (`Debug.getNativeHeapAllocatedSize`),
`ActivityManager.MemoryInfo` (availMem, lowMemory), memoryClass/largeMemoryClass, thermal status
(`PowerManager.currentThermalStatus`) and battery saver (both change speed numbers on an S24), free storage, GL renderer/version
strings and extensions (the engine requires ES 3.2 and RGBA16F render targets; nothing records what the Adreno actually exposes),
which delegate each model chose (DECISIONS.md says "remembered per model" but it is not reported), ModelStore pack state and last
download error, permission state (media, all files, notifications), settings (XMP, overlay), ABI, LibRaw version (shown in
Settings but not in the report), export queue counts and last failure messages, app uptime, and error timestamps.
The extra line is mislabelled: "Photos in this source" prints `allPhotos.size` of the current source (MainActivity.kt:253) and
omits total library size and indexed vs failed count. Errors are capped at 20 and carry no timestamp or count.
Fix: extend `PerfLog.report` with the above (all cheap, no PII; do not add file paths or URIs). Size: M.

### AQ-023 P2 In-memory PerfLog is lost with the process; timings keys grow without bound
PerfLog.kt:55-56 samples and errors live only in RAM; after a crash or LMK kill the next report has empty "Timings" and
"Recent errors", exactly when they matter. Keys embed variable data: `device_scan_ms (n=${rows.size})`
(DeviceScanner.kt:60), `index_total_ms (n=...)` (Indexer.kt:187), `export_render_ms (${tw}x$th)` (Exporter.kt:155): every library
change makes a new key with one sample, the map grows and the report fills with single-sample "medians". `record` also does
`removeAt(0)` on an ArrayList (O(n), n=500). Fix: constant key names with n as a separate sample or tag, persist the last 100
errors to a ring file on write, flush a snapshot on onStop. Size: S.

### AQ-024 P3 No MANAGE_EXTERNAL_STORAGE justification path, no uses-feature for GLES 3.2
AndroidManifest.xml:6 MANAGE_EXTERNAL_STORAGE grants every file on the phone to find RW2s; fine for sideload but it is the biggest
privilege in the app and Play would reject it; it is used only to make MediaStore list non-image MIME RAW. No
`<uses-feature android:glEsVersion="0x00030002" android:required="true"/>` (manifest lines 1-35): a device without ES 3.2 installs
and fails at `engineInit` at runtime instead of at install. No `android:icon` and no mipmap resources (app/src/main/res has only
values and xml): the launcher shows the default green Android icon. Fix: add uses-feature; add an adaptive icon; document why the
all-files permission stays. Size: S.

### AQ-025 P3 Manifest: no dataExtractionRules, no predictive back, ExportService lacks onTimeout
`allowBackup="false"` (line 13) is correct and the app has its own zip backup, but there is no `dataExtractionRules` so device-to-device
transfer is also off with no explanation to the user. targetSdk 37: `enableOnBackInvokedCallback` not set (Compose BackHandler works,
but system back-preview animation is absent). ExportService declares `foregroundServiceType="dataSync"` (line 18) with no
`onTimeout(int,int)` override; since Android 15 dataSync services are time limited and the system calls onTimeout, and if the
service does not stop, the app crashes with a RemoteServiceException. A very large batch export on a phone can exceed it.
Fix: override `onTimeout` to stop the foreground state and re-queue; set the back callback attribute. Size: S.

### AQ-026 P3 Export service robustness
ExportService.kt:17-37: START_NOT_STICKY and a plain `thread`; if the process dies mid job (LMK on a large TIFF) the job row stays
status 1 "running" in the Queue UI until the next export starts (`resetRunning` only runs inside processQueue, ExportRunner.kt:51).
Notification channel is created on every start (harmless). `runBlockingActive()` uses runBlocking on the service thread twice per
loop. Cancel via PendingIntent when the service is not running starts it with no foreground call (fine on API 31+ because it is
`startService` from a PendingIntent, but it then leaks a service instance until process end because `stopSelf` is not called on
the cancel path, line 165). Fix: `resetRunning` in Application start, `stopSelf(startId)` on cancel path. Size: S.

### AQ-027 P3 Share files: deleted under the receiving app
ExportRunner.kt:86 `listFiles()?.forEach { it.delete() }` clears the whole share dir at the start of every share. If the user shares photo A to a slow app (upload) and shares photo B, A's file disappears mid read. FileProvider config is tight (file_paths.xml: only `cache-path share/`, `exported=false`, grants via FLAG_GRANT_READ_URI_PERMISSION; good). Fix: delete files older than 1 hour instead. Size: S.

### AQ-028 P3 Metadata errors swallowed
ExportRunner.kt:99-101 and 125 wrap EXIF writes in `runCatching` with no PerfLog entry; exported JPEGs without camera data, copyright or orientation normalisation fail silently, and ExportRunner.kt:102 does the same for IS_PENDING release (a failure there leaves the photo hidden from Gallery forever, which looks like "export did nothing"). Fix: log failures with PerfLog.error, and if the IS_PENDING update fails mark the job failed. Size: S.

### AQ-029 P3 Release has no native symbols, so field native crashes cannot be read
core/native/build.gradle.kts has no `ndk.debugSymbolLevel`, and release stripping removes symbols; CI uploads only the APK
(build.yml:98). Combined with AQ-021 a native fault produces no usable trace. Fix: `ndk { debugSymbolLevel = "SYMBOL_TABLE" }` or
upload `app/build/outputs/native-debug-symbols`. Size: S.

### AQ-030 P3 Build config hygiene
app/build.gradle.kts:20 `BUILD_DATE = LocalDate.now()` makes the output non-reproducible and invalidates the BuildConfig task output
(and Gradle build cache) at midnight; local builds get `versionCode 1` and `0.1.0` (lines 14-17) so a sideloaded local build can never
install over a CI build (downgrade) and vice versa. `GITHUB_RUN_NUMBER` is already an Actions default env (build.yml:35-36 redundant).
`compileSdk/targetSdk 37` in 15 files, versions duplicated per module (no convention plugin); ABI is arm64-v8a only
(core/native/build.gradle.kts:11) so emulators and x86 Chromebooks cannot run it (documented in DECISIONS.md).
No Gradle dependency verification (`gradle/verification-metadata.xml`) and no wrapper `distributionSha256Sum`
(gradle-wrapper.properties) so the Gradle zip and all Maven artefacts are trusted on first fetch. Fix: take the date from
`SOURCE_DATE_EPOCH`/commit date, add `distributionSha256Sum`, consider dependency verification. Size: S to M.

### AQ-031 P3 CI SDK bootstrap is unverified
tools/setup-sdk.sh:12-14 downloads commandlinetools zip from dl.google.com with no checksum, `yes | sdkmanager --licenses`
blanket accept, writes /tmp/clt.zip. Low risk (Google host) but this is the toolchain that signs the release.
Fix: compare to the published SHA-256. Size: S.

### AQ-032 P3 Thumbs and mask/heal files are never cleaned
ThumbStore.kt:19 files keyed by row id; Indexer.kt:136-141 deletes and re-inserts rows (new autoincrement id) when a file's size or
mtime changes, and DeviceScanner.kt:57 deletes vanished rows, leaving `<id>.jpg` orphans in cacheDir/thumbs (system may evict the
cache, but there is no limit of its own). MaskStore/PatchStore have `delete` and `all()` but nothing in app or features calls them
(grep: only MaskingFeature holds a MaskStore); orphaned PNGs accumulate in filesDir/masks and heals (one 1024x1024 mask is up to
1 MB) and are all included in every backup (Catalog.kt:264-265). Fix: sweep files not referenced by any recipe/row on startup,
LRU cap for thumbs. Size: M.

### AQ-033 P3 Share/XMP writes are not atomic and run per photo
Catalog.kt:238 for a selection of N photos, `Xmp.write` is called sequentially through SAF, each doing a read of the old sidecar plus
a "wt" write that truncates first (Catalog.kt:376): a kill during the write leaves a zero-byte sidecar that Lightroom treats as
no metadata. Also `metaFor` + `edits.get` run per photo (N+1 queries). Fix: write to a temp document and rename, batch the queries,
move off the caller coroutine's critical path. Size: M.

### AQ-034 P3 Direct buffer per export never freed explicitly
Exporter.kt:141 `ByteBuffer.allocateDirect(tw*th*4)` (96 MB for 24 MP) plus the Bitmap copy; direct memory is released only after GC, so
a batch of 20 full-size exports can hit `OutOfMemoryError: Direct buffer memory` on a phone even with largeHeap. ExportRunner catches
Throwable (line 75) so the job fails, but with an opaque message. Fix: render straight into the Bitmap row bands via copyPixelsFromBuffer
per tile, or reuse one buffer for the batch. Size: M.

### AQ-035 P3 Permission prompts repeat
MainActivity.kt:125-128 `LaunchedEffect(Unit)` asks for media access every composition start while not granted and
POST_NOTIFICATIONS every launch; after two denials Android silently ignores the request so the library stays empty with no explanation
beyond the button. Also READ_MEDIA_VISUAL_USER_SELECTED is not declared, so Android 14 offers all-or-nothing. Size: S.

### AQ-036 P3 Logging and privacy (verified clean, with two notes)
No `android.util.Log`, `println` or `printStackTrace` in any Kotlin; native uses LOGE/LOGI via liblog (jni_engine.cpp:41-48) with messages
only. Good: file paths are not logged. Notes: (a) PerfLog.error includes photo file NAMES (Indexer.kt:179, ExportRunner.kt:76,
PreviewCache.kt:183,193) which end up in the Copy report that Jai pastes into chat; names only, acceptable, but say so in the Settings
help text; (b) INTERNET permission exists only for model downloads (ModelStore) and ML Kit via Play services; there is no analytics,
no telemetry. ModelStore enforces https (line 88) and never writes by zip entry name (keyed lookup at line 115), so zip-slip is not
possible. Redirects go cross-host without re-checking an allowlist (line 93-96); acceptable with the hash pin except for People
(AQ-017). Intent handling: MainActivity is the only exported component and has only MAIN/LAUNCHER, no VIEW/SEND filters, so no
untrusted intent data is parsed. Size: none.

### AQ-037 P3 Dependency freshness (official sources, 6 Oct 2026)
Verified current: Room 2.8.5 (9 Sep 2026), core-ktx 1.19.1 (23 Sep), navigation 2.10.2 (23 Sep), lifecycle 2.11.0 (17 Jun),
activity 1.13.0 (11 Mar), exifinterface 1.4.2 (3 Dec 2025), Compose BOM 2026.09.00 (9 Sep, newest on Google Maven), KSP 2.3.12
(9 Sep), coroutines 1.11.0 (8 May), Kotlin 2.4.20 (7 Sep), Gradle 9.8.0 (24 Sep).
Behind or odd: LiteRT core is 1.4.2 while Maven shows 2.2.0 exists; litert-gpu stops at 1.4.2 so the pair is aligned at the newest GPU
artefact (moving core alone is risky: keep unless 2.x GPU ships; needs an on-phone check). ML Kit subject segmentation
16.0.0-beta1 is the only version ever published (a beta that needs Google Play services; no fallback path on a de-Googled phone, and
AiMasks.kt:47 calls it unconditionally for "Select subject"). `org.json:json:20250517` and kxml2 2.3.0 are test-only. Not confirmed:
AGP: the docs page I could read says 9.4.0 (needs Gradle 9.6, max API 37) while libs.versions.toml pins 9.4.1; I could not confirm
9.4.1 on the page, check with `./gradlew --version` on a machine with network. GitHub Actions versions are in AQ-012. No dependency is
stale enough to be a risk; set up Dependabot or Renovate to keep it so. Size: S.

### AQ-038 P3 Spec and docs drift
README.md:11 says CI builds a debug APK without secrets (it uses the committed key); docs/SPEC.md says "Keystore as Actions secrets, never
committed" and lists AVIF export (DECISIONS.md says not offered); docs/PERF.md says nothing measured; PerfLog timers named in PERF.md
(`ai_*_run_ms`, `heal_*_ms`) should be checked against actual `record(` calls. `.claude/worktrees/` is untracked but not in .gitignore
(only in .git/info/exclude) so a fresh clone on another machine could commit agent worktrees. Size: S.

## Test coverage map (150 @Test total, all JVM unit tests, no androidTest anywhere)
| Module | Tests | State |
| --- | --- | --- |
| core/model | 7 (ModelTest) | recipe JSON round trip; thin for EditRecipe versioning/unknown fields, RecipeMerge, LibraryFilter |
| core/render | 38 (Render, GeoFit, Export, LayerPack) | good for params layout, geo, TIFF header; no Exporter.render, no EditorSession, no LensProfiles parse failures, no RenderParams NaN/limits fuzz |
| core/ml | 8 | link/hash shape only; ModelStore.download, Healer, Denoiser, AiMasks untested |
| feature/editor | 66 (CropMath, CurveEdit, EditorTest) | good for math; ColourPanel, GeometryPanel, Panels, PresetsPanel, Resets, AutoTools, EditorState, history undo/redo mostly untested |
| feature/masking | 29 | MaskLogic only; BrushLayer, MaskTray, MaskingFeature persist/restore untested |
| app | 2 | BuildInfo only; ExportRunner, ExportService, LibraryViewModel, Graph wiring untested |
| core/data | 0 | Catalog, Db, migration, backup, Xmp, Indexer, DeviceScanner |
| core/cache | 0 | PerfLog, CrashStore, ThumbStore, PreviewCache, PreviewDecoder, Mask/PatchStore |
| core/ui | 0 | Controls (sliders with TalkBack semantics fixed in AUDIT), Gestures, theme tokens |
| feature/library, loupe, export, remove, settings | 0 | LibraryScreen selection/rotation, LoupeScreen gestures, QueueScreen, ExportSheet, RemoveFeature, SettingsScreen |
| core/native | 0 JVM; golden C++ harness (15 scenes) + preview parser script | JNI layer and LibRaw decode are only exercised on the host harness, never as the shipped JNI/.so; nothing runs on an emulator or device in CI |

## Missing tests: highest-value proposals (names and assertions)
Where Android classes are needed use Robolectric (in-memory Room, Context) or add one `androidTest` job on an emulator (arm64 images are slow; x86_64 cannot load the arm64-only .so, so JNI-free tests only).

T1 core/data `CatalogRecipeTest` (Robolectric, in-memory Room)
- `unreadableRecipeIsNotDeletedWhenEditorSavesDefault`: put EditEntity with json "{future}" then saveRecipe(default): row still present.
- `defaultRecipeDeletesRowAndClearsEditedFlag`; `saveRecipeSetsEditedFlag`.
- `loadRecipeReturnsNullForCorruptJson` plus (after AQ-008 fix) `loadRecipeDistinguishesMissingFromUnreadable`.

T2 core/data `RoomMigrationTest` (MigrationTestHelper with exported schemas)
- `migrate2To3CreatesExportJobsWithRoomExpectedSchema`, one per step to the current version, and `edits_ratings_presets_survive_every_migration`.
- `opening_a_newer_database_reports_a_clear_error_instead_of_crashing` (downgrade, AQ-005).

T3 core/data `BackupRoundTripTest`
- `backupThenRestoreOnEmptyDbRecreatesEditsSnapshotsPresetsMeta`.
- `olderBackupDoesNotOverwriteNewerEditRatingFlagLabel` (both edits and meta).
- `corruptSnapshotsJsonLeavesDatabaseUntouched` (transactional).
- `entryOverLimitIsRejectedWithoutOom`; `zipEntryNamesWithDotDotAbsoluteOrBackslashNeverWriteOutsideMaskDir`.
- `restoredCountEqualsEditsActuallyApplied`.

T4 core/data `ScanPruneTest` (fake ContentResolver)
- `emptyListingNeverPrunes`, `partialListingBelowThresholdNeverPrunes` (AQ-007), `modifiedFileKeepsRatingAndEditViaReapply`,
  `twoIdenticalNamedFilesGetDistinctKeys` (AQ-006).

T5 app `ExportRunnerTest` (Robolectric + fake Exporter seam; extract an interface)
- `unreadableRecipeFailsTheJobInsteadOfExportingUnedited` (AQ-008).
- `cancelOnQueueJobIsNotClearedByShare` (AQ-009), `cancelBetweenJobsCancelsNextJob`.
- `deletedPhotoMarksJobFailedAndContinues`; `metadataFailureIsLoggedToPerfLog`; `fileNameStripsSeparatorsAndKeepsExtension` (pattern "{name}/../x", very long names, empty pattern, `{n}` padding).
- `processQueueResetsRunningJobsLeftByAKilledProcess`.

T6 core/ml `ModelStoreDownloadTest` (local MockWebServer, plain JVM with temp dir)
- `checksumMismatchDeletesPartFilesAndDoesNotRetryThreeTimes` (AQ-018), `truncatedDownloadRejected`, `redirectToHttpRefused`,
  `redirectLoopStopsAtFive`, `zipEntryNotInMapIsIgnoredAndNeverWritten` (zip-slip guard), `modelsJsonUrlsEqualModelsKt` (AQ-017).

T7 core/cache `CrashStoreTest` and `PerfLogTest`
- `crashFileKeepsRootCauseOfDeepTrace` (AQ-020), `crashFileHasTimestampAndVersion`, `reportContainsDeviceMemoryThermalAndGlInfo` (AQ-022),
  `errorsRingBoundedAt20AndTimestamped`, `variableKeyNamesDoNotGrowTheMap` (AQ-023), `recordIsThreadSafe` (parallel stress).

T8 core/render `ExporterTest` (extend ExportTest)
- `displayP3TiffCarriesIccTag` (AQ-019), `tiffWithCopyrightNoneWhenMetadataNone`, `longEdgeNeverUpscales`.

T9 feature/library `LibrarySelectionTest` (Compose test or pure state holder)
- selection survives rotation (AUDIT item 4), pinch columns clamps 2..8, filter + sort is stable.

T10 CI-level: `tools/ci/check-manifest.sh` (grep-based) asserting: no new exported component except MainActivity; `allowBackup=false`;
no `usesCleartextTraffic`; the permission list equals an approved list.

## Top fixes by value
1 AQ-002 key strategy, 2 AQ-001 and AQ-015/016 CI flake removal, 3 AQ-003/005 data tests plus schema export,
4 AQ-008 export of unreadable recipe, 5 AQ-021/022/020/023 crash and report content, 6 AQ-013/012/014 workflow hardening.

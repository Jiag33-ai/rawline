import re,glob,os
base=os.path.dirname(os.path.abspath(__file__))
AREAS={'A':'SPEED (open, swipe, grid, edit start, slider latency)','B':'EDITING QUALITY AND COLOUR SCIENCE','C':'EDITOR UX AND WORKFLOW',
'D':'MASKING AND AI','E':'LIBRARY WORKFLOW (ratings, flags, collections, search, filters, import, folders)','F':'EXPORT (formats, sizes, watermark, metadata)',
'G':'RELIABILITY, DATA AND MIGRATION','H':'ACCESSIBILITY','I':'BATTERY, THERMAL AND STORAGE','J':'ONBOARDING, IN-APP HELP AND SETTINGS',
'K':'TESTING AND CI','L':'SECURITY, PRIVACY, RELEASE AND UPDATE FLOW','M':'PLATFORM, PANASONIC AND COMPOSE SPECIFICS','N':'STUDIO (layer-based pixel editor, separate from Develop)'}
SHORT={'A':'Speed','B':'Editing quality and colour','C':'Editor UX','D':'Masking and AI','E':'Library workflow','F':'Export','G':'Reliability and data','H':'Accessibility','I':'Battery, thermal, storage','J':'Onboarding, help, settings','K':'Testing and CI','L':'Security, privacy, release','M':'Platform and Panasonic','N':'Studio'}
intro={}; entries={k:[] for k in AREAS}
def parse(text, default=None):
    cur=default; marker=None
    blocks=re.split(r'(?m)^(?=### BK-|# AREA |%%AREA )',text)
    for b in blocks:
        if b.startswith('# AREA '):
            cur=b[7]; 
            body=b.split('\n',1)[1] if '\n' in b else ''
            pre=body.split('### BK-')[0]
            intro[cur]=pre.strip().strip('-').strip()
        elif b.startswith('%%AREA '):
            marker=b[7]
        elif b.startswith('### BK-'):
            a=marker or cur
            marker=None
            entries[a].append(b.rstrip().rstrip('-').rstrip()+'\n')
        else:
            pass
for f in sorted(glob.glob(base+'/parts/p*.md'), key=lambda x:int(re.findall(r'p(\d+)',x)[0])):
    parse(open(f).read())
# split text blocks for intro: when entries blocks include trailing "# AREA" they've been split already
rank={'P0':0,'P1':1,'P2':2,'P3':3}
def pr(e): return rank[re.match(r'### BK-\d+ \[(P\d)\]',e).group(1)]
def eid(e): return re.match(r'### (BK-\d+)',e).group(1)
def title(e): return re.match(r'### BK-\d+ \[P\d\] (.*)',e).group(1)
allm={}
for a in entries:
    entries[a].sort(key=pr)
    for e in entries[a]: allm[eid(e)]=(a,e)

STATUS={
'BK-144':('DONE','Done in commit 2ef67a7 (restore is one transaction, streamed with size caps, newer wins including ratings via meta updatedAt, Room 3 to 4). Left over: dry-run preview and integrity manifest, see BK-303.'),
'BK-146':('DONE','Done in commit 2ef67a7 (schema export on, v3 and v4 JSON committed, host-side MigrationTest, explicit 4 to 3 downgrade, destructive wipe only from version 1). Left over: a CI check that schema JSON changes come with a migration, data-preserving tests (BK-305).'),
'BK-137':('DONE','Done in commit 42b80f0 (ExportService.onTimeout and onDestroy put the running job back to waiting; mediaProcessing type on Android 15+, dataSync before).'),
'BK-151':('DONE','Already in the code (found in the second read): EditorHost saves 400 ms after the last change and flushes on pause and stop; leaving waits for the write.'),
'BK-086':('DONE','Already in the code: MaskingFeature.limitMessage says "A photo can have 8 masks. Delete one to add another." Left over: measure render cost per mask.'),
'BK-063':('DONE','Already in the code: PresetsPanel has a Strength slider that re-blends the look onto the edit as it was before the preset. Left over: partial apply (tone only, colour only).'),
'BK-148':('DONE','Done in commit 15fb461 (core/cache ExitReasons reads ApplicationExitInfo, native tombstone hints, ANR trace head; shown in the report under "How the app last ended").'),
'BK-150':('DONE','Done in commit 15fb461 (PerfLog writes session.txt after errors and on stop, previous.txt kept, report shows both).'),
'BK-221':('PARTLY DONE','Commit 15fb461: GlInfo prints renderer, vendor, GL version, EGL version, max texture size and float render target support. Left over: shader compile and link info logs.'),
'BK-149':('PARTLY DONE','Commit 15fb461: PerfLog keeps 100 errors and 40 timestamped events (memory trims, service stops). Left over: user action and screen breadcrumbs.'),
'BK-180':('PARTLY DONE','Commit 15fb461: DeviceReport prints thermal status, battery level and temperature, battery saver, memory, storage, permissions. Left over: display refresh rate and HDR capability.'),
'BK-013':('PARTLY DONE','Commit 15fb461: RawlineApplication.onTrimMemory and onLowMemory record events. Left over: actually shrinking caches and RawPrefetch, native byte counters.'),
'BK-232':('PARTLY DONE','Commit 15fb461: the report passes free text through ReportText.redact. Left over: in-app privacy statement, "Include file names" switch.'),
'BK-071':('PARTLY DONE','Commit 15fb461: the report lists each model pack state and its saved delegate. Left over: the phone timing table and pass/fail targets.'),
'BK-228':('PARTLY DONE','Commit 42b80f0: export service uses mediaProcessing on Android 15+. Left over: user-initiated data transfer jobs for model downloads and card import.'),
'BK-145':('PARTLY DONE','Commit 2ef67a7: explicit Room 4 to 3 downgrade that keeps all rows. Left over: pruning on a partial listing (AUDIT open item 2).'),
'BK-164':('PARTLY DONE','Commit 2ef67a7: backup zip entries are allow-listed, size capped and PNG-header checked. Left over: fuzzing the RW2 parser and EXIF reader.'),
'BK-147':('PARTLY DONE','Commit 2ef67a7: host-side MigrationTest and BackupReaderTest exist. Left over: scanner and XMP tests, data-preserving migration tests (BK-305).'),
'BK-159':('PARTLY DONE','Commit 42b80f0: jobs left running are reset at app start and retry refuses a running job. Left over: clean up stray IS_PENDING rows, retry limit.'),
'BK-051':('PARTLY DONE','Already in the code: Undo and Redo icons in the editor top bar, 200 step history with labels. Left over: one history for brush strokes (BK-300), gestures, long-press scrub.'),
'BK-055':('PARTLY DONE','Already in the code: PhotoMode.TARGET_MIXER (tap the photo to pick the mixer band). Left over: two-finger drag on the photo for any slider.'),
'BK-229':('PARTLY DONE','Checked 6 Oct 2026 on dist/rawline.apk (built 5 Oct): `zipalign -c -P 16 4` passes and every arm64-v8a library (libraw, librawline_jni, libc++_shared, both LiteRT libs, libandroidx.graphics.path) has LOAD segment alignment 0x4000. Left over: a CI step so it stays true (a quick win, see the list).'),
'BK-094':('PARTLY DONE','Already in the code: each mask part has a Segmented Add/Subtract/Intersect control in MaskTray. Left over: a small preview thumbnail per part and drag to reorder.'),
'BK-085':('PARTLY DONE','Already in the code: duplicateMask, rename, show/hide, invert per mask. Left over: save a parametric mask set as a reusable item.'),
'BK-154':('PARTLY DONE','Commit 7e0272f: RecipeRead (Missing, Ok, Unreadable) so an unreadable or newer edit is never exported unedited, pasted over or deleted. Left over: explicit versioned migrations and a read-only open for newer recipes.'),
'BK-020':('MERGED','Superseded by BK-330, which names the Android adaptive refresh APIs and the acceptance numbers.'),
'BK-125':('MERGED','Merged into BK-142 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-142.'),
'BK-321':('MERGED','Merged into BK-255 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-255.'),
'BK-258':('MERGED','Merged into BK-180 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-180.'),
'BK-039':('MERGED','Merged into BK-253 and BK-324 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-253 and BK-324.'),
'BK-259':('MERGED','Merged into BK-323 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-323.'),
'BK-190':('MERGED','Merged into BK-176 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-176.'),
'BK-243':('MERGED','Merged into BK-199 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-199.'),
'BK-248':('MERGED','Merged into BK-069 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-069.'),
'BK-187':('MERGED','Merged into BK-008 in the dedupe pass of 6 Oct 2026. Kept only for its acceptance details; schedule it with BK-008.'),
'BK-141':('DECLINED for now','docs/DECISIONS.md (6 Oct 2026): the key stays `name|size|modified`, reviewed in the fourth audit pass. A lighter alternative that respects that decision is BK-291. Re-open only if Jai overrides the decision.'),
'BK-109':('BLOCKED','Depends on the BK-141 decision. If BK-291 is built, scope this entry down to the relink notice only.'),
}

intro['N']="Status of the spec: docs/STUDIO_SPEC.md exists (commit 6d62008). Where an entry differs from it the spec wins and the entry is marked SPEC WINS; entries it already decides are marked SPEC COVERS with what is left over; the entries after BK-397 add what the spec misses and a test and risk entry for each milestone S1 to S10 (BK-411 to BK-420). See the traceability table after the milestone plan. The central risks are memory, undo cost, gesture conflicts, compositing correctness, schedule realism and keeping Develop unchanged."
STATUS['BK-145']=('DONE','Done: Room 4 to 3 downgrade (2ef67a7) and the device scan prune guard in 3cd7bb9 (`ScanPrune`: a listing under half of what is known is only believed when two scans in a row agree; an empty listing never prunes; tested in CatalogLogicTest). Left over: the SAF folder scan in Indexer still prunes when `complete && docs.isNotEmpty()`.')
STATUS['BK-206']=('PARTLY DONE','Commit 3cd7bb9: a separate, non-blocking `lintDebug` job with the HTML reports uploaded. Left over: a baseline, making it blocking for new issues, detekt or ktlint.')
STATUS['BK-207']=('PARTLY DONE','Commit 3cd7bb9: Dependabot for github-actions (weekly) and all actions are on exact version tags. Left over: pin to commit SHAs and add the gradle ecosystem.')
STATUS['BK-216']=('PARTLY DONE','Commit 3cd7bb9: the sample RW2 is cached, checksummed and retried. Left over: mirror it as a Release asset and add a real S5IIX file.')
STATUS['BK-217']=('PARTLY DONE','Commit 3cd7bb9: LibRaw build and sample cached, concurrency control, model and lint jobs split from the build. Left over: record job times and a time budget.')
STATUS['BK-004']=('PARTLY DONE','Commit 3cd7bb9: proguard-rules.pro written and `-PminifyRelease=true` builds with R8; off by default on purpose (docs/DECISIONS.md) until a phone run proves it. Left over: the phone run, then turn it on.')
STATUS['BK-135']=('PARTLY DONE','Commit 3cd7bb9: `ExportNaming` (pure, tested) sanitises illegal characters and caps the length. Left over: more tokens, live preview, collision policy.')
STATUS['BK-147']=('PARTLY DONE','Commits 2ef67a7 and 3cd7bb9: host-side MigrationTest, BackupReaderTest, RecipeReadTest, CatalogLogicTest (prune, reapply, save actions) and PhotoKeyTest exist. Left over: scanner with a fake resolver, XMP, Robolectric DAO tests, data-preserving migrations (BK-305).')
STATUS['BK-159']=('PARTLY DONE','Commit 42b80f0 resets running jobs and refuses retry of a running job. Working tree (not committed): a failed MediaStore publish (IS_PENDING not cleared) now fails the job and deletes the file, and Share has its own cancel flag. Left over: sweep stray pending rows, retry limit.')
STATUS['BK-199']=('PARTLY DONE','Commit 3cd7bb9: each release now lists the commits since the previous release and carries rawline.apk.sha256. Left over: the in-app What\'s New screen and a Needs phone test list.')
STATUS['BK-240']=('PARTLY DONE','Commit 3cd7bb9: the release carries rawline.apk.sha256 and its value in the notes, which is the verification half. Left over: the in-app check, download and PackageInstaller flow.')

SW={
'BK-365':('DONE','The spec now exists (docs/STUDIO_SPEC.md, commit 6d62008). The traceability table below maps its sections and risks to backlog ids; entries that differ from the spec are marked SPEC WINS.'),
'BK-366':('SPEC COVERS','Spec 2.17, 3.3, R1: 256 px RGBA8 tiles, lossless WebP backing, 1.2 GB GPU cache with LRU, 2.2 GB hard cap; S1 has no tiles (12 MP cap). Left over: decode cost in the pan path and encode cost (BK-400), the eviction fuzz test (BK-412).'),
'BK-367':('SPEC COVERS','Spec 2.17 gives the budget (1.2 GB cache, 576 MB composite caches, 2.2 GB cap, 64 layers, 100 MP). Left over: the per-size table and "maximum layers at this size" message, selection channel memory, coexistence with Develop (BK-399).'),
'BK-368':('SPEC COVERS','Spec 2.13 and R6: tile deltas (LZ4) on disk, 100 entries or 1.5 GB, content-addressed snapshots; S1 uses whole-layer snapshots (limit 10). Left over: the LZ4 dependency (BK-401), jump cost and disk-full behaviour (BK-406).'),
'BK-369':('SPEC COVERS','Spec 2.15: a project directory (not a zip) with project.json, WebP tiles, journal, atomic rename, .new and .bak, recovery rules, schema migrations. Left over: encode cost at stroke end (BK-400), blob integrity (BK-405), project export earlier than S9 (BK-410).'),
'BK-370':('SPEC COVERS','Spec 2.2 decides: GAMMA default with a LINEAR option per project, W3C formulas on straight alpha, 24 modes plus Dissolve, a host reference renderer and goldens. Left over: independent cross-check of the non-W3C modes (BK-413).'),
'BK-371':('SPEC COVERS','Spec 2.17 and 3.3: sandwich caches, viewport-only targets, proxies, frame graph. Left over: the real Adreno framebuffer-fetch path is not covered by CI (BK-402).'),
'BK-372':('SPEC COVERS','Spec 2.4 and 4.3: stamp engine, stroke accumulation tiles, pressure and tilt, clone, smudge, heal, dodge and burn. Left over: tests per milestone (BK-411, BK-417).'),
'BK-373':('SPEC WINS','Spec R7 skips front-buffered low-latency rendering in v1 (revisit in S10 only if input to pixel exceeds 40 ms) and uses historical points plus the Jetpack motion prediction library; the stabiliser is a lazy rope. This entry\'s front-buffer spike is therefore deferred. Left over: recorded-input tests and sharing the stabiliser with the Develop mask brush (BK-391).'),
'BK-374':('SPEC COVERS','Spec 2.14, 3.4 and R17 define the gestures, timings (250 ms, 12 dp, 100 ms suppression) and a pure state machine with host tests. Not in the spec: a touch shortcut button and edge dead-zone rectangles (it leaves edge swipes to the system). Left over: traces from the real phone, Samsung edge panel check.'),
'BK-375':('SPEC COVERS','Spec 2.1 and section 5: list rows with 40 dp thumbnail, 48 dp eye, drag handle, blend and opacity chip, grouped blend picker, cap of 64 layers. Compact versus detailed views are not in the spec. Left over: scroll performance with 64 rows and thumbnail caching.'),
'BK-376':('SPEC COVERS','Spec 2.3 (S4): all the tools, modifiers, refine edge and saved channels.'),
'BK-377':('SPEC WINS','Spec 3.7 makes the hand-off one way: "Open in Studio" creates a new project with the photo as a smart object and a copy of the recipe; nothing flows back and there is no linked layer or "Update from Develop". The linked-update design in this entry is declined. Remaining value is in BK-407 (stale recipe copy).'),
'BK-378':('SPEC COVERS','Spec 2.5 (S8): free transform, perspective, warp (P2), Lanczos3 or bicubic at commit, bilinear while dragging.'),
'BK-379':('SPEC COVERS','Spec 2.9 (S9): the filter list, selection as a mix mask, previews at viewport resolution.'),
'BK-380':('SPEC COVERS','Spec 2.7 (S8): platform text stack rasterised to tiles, about 8 bundled OFL fonts plus imported fonts. Left over: font licences and size (BK-401), untrusted fonts (BK-404).'),
'BK-381':('SPEC COVERS','Spec 2.17 lists the Studio timers and a debug overlay. Not in the spec: the recorded-stroke replay rig, which stays here.'),
'BK-382':('SPEC COVERS','Spec 3.9 covers most of the test strategy. This plan is now split into one test and risk entry per milestone, BK-411 to BK-420, which add stroke replay, kill-during-compaction, gesture traces and accessibility scripts.'),
'BK-383':('SPEC COVERS','Spec 2.4 covers pressure, tilt, orientation, hover distance, palm rejection and the pen button as eraser. Not in the spec: `TOOL_TYPE_ERASER` (the eraser end), a hover cursor ring, Samsung Air actions.'),
'BK-384':('SPEC COVERS','Spec section 1 decides no PSD and no Photoshop compatibility, own fonts under OFL, and the name Studio. Left over: THIRD_PARTY.md lines before any font ships (BK-401).'),
'BK-385':('SPEC COVERS','Spec 3.1, 3.3, R5 and R16 decide separate modules, a separate GL context and JNI file, and shared shader includes with Develop goldens byte-identical. Left over: the CI flag matrix (BK-403) and memory coexistence (BK-399).'),
'BK-386':('SPEC COVERS','Spec 3.6 and R10: Room studio.db, app-private project directories, export through SAF or Share. Left over: export-all earlier than S9 (BK-410), low storage behaviour.'),
'BK-388':('SPEC COVERS','Spec 2.19 covers content descriptions, 48 dp, TalkBack custom actions for layers, button twins for gestures, font scale, reduced motion and left-hand mode.'),
'BK-004':('PARTLY DONE','Commit 3cd7bb9 and the fifth audit pass: proguard-rules.pro written; `-PminifyRelease=true` builds and the APK drops from 61 MB to 37 MB (measured by the main session); minification stays off by default on purpose (docs/DECISIONS.md) until a phone run proves it. Left over: that phone run, then turn it on.'),
'BK-159':('PARTLY DONE','Commits 42b80f0 and dd68762: jobs left running are reset at app start, retry refuses a running job, a failed Gallery publish (IS_PENDING not cleared) fails the job and deletes the file, Share has its own cancel flag. Left over: sweep stray pending rows, retry limit.'),
'BK-103':('PARTLY DONE','Already in the code: the grid has date header rows (`GridRow.Head`, grouped by the sort order) with counts. Left over: sticky headers and a fast scrubber with date labels.'),
}
for k,v in SW.items(): STATUS[k]=v

STATUS['BK-308']=('PARTLY DONE','Commit 5ca42a0: rate, flag, label, copy, paste, sync and queueing run on the IO dispatcher and the XMP step runs on IO inside Catalog (with a notice for photos that cannot have a sidecar, AU-063). Left over: `saveMeta` still queries per photo; XMP writes are not yet one queued worker with a single summary.')
STATUS['BK-186']=('PARTLY DONE','Commit 5ca42a0: the thumbnail disk cache is capped at 300 MB, least recently used first (CacheTrim). Left over: the Settings storage screen and caps for the other caches.')
STATUS['BK-152']=('PARTLY DONE','Commit 5ca42a0: `ThumbStore.save` never throws and writes atomically (AU-056, AU-057). Left over: mask and heal stores and the XMP sidecar write.')
STATUS['BK-192']=('PARTLY DONE','Commit 5ca42a0: `MediaAccess` state machine (Open settings once Android stops asking, re-check on resume, All files access counts as access). Left over: partial access (Android 14 selected photos) and the copy for each state.')
STATUS['BK-285']=('DONE','Done in commit eec8960: the first export notification question is a tested NotificationRule with the one line reason shown first.')
STATUS['BK-203']=('PARTLY DONE','Commit 5ca42a0: plural and Australian date helpers with tests (AU-026, AU-027). Left over: strings.xml move and the text rules test.')

STATUS['BK-007']=('DONE','Done in commit 3ad453d (LibRaw built with OpenMP, -O3, static libomp, KMP_BLOCKTIME 0; host: 24 MP full decode 3.0 s to 1.6 to 1.9 s, pixel data identical). Left over: a phone timing from the Copy report.')
STATUS['BK-053']=('PARTLY DONE','Commit 3ad453d: histogram on its own small targets, at most every 100 ms with a trailing frame (HistogramGate), no second screen frame. Left over: the GPU reduction read back as about 3 KB.')
STATUS['BK-266']=('PARTLY DONE','Commits 3ad30b3 and 3ad453d: 8 MB strip upload, swap only on success, LibRaw block converted in place (24 MP peak RSS 420 to 242 MB on the host). Left over: persistent PBOs.')
STATUS['BK-295']=('PARTLY DONE','Commit 3ad30b3: tile results are written back one row late (two rows held), per tile scratch arrays reused, host test of the order. Left over: the per pixel colour conversion in Kotlin.')
STATUS['BK-427']=('PARTLY DONE','Commit 3ad30b3: nullOutputStream replaced, HalfFloat errors cleared (Kotlin half conversion). Left over: make lint blocking for these classes (BK-206).')
STATUS['BK-444']=('DONE','Done in commit ee38b8b (float32 intermediate and output, one quantisation in native code, 1024 px tiles, chunked writer; the golden counts distinct values and compares 16 bit with 8 bit).')
STATUS['BK-133']=('PARTLY DONE','Commit ee38b8b embeds an sRGB or Display P3 ICC profile in the 16 bit TIFF (tag 34675). Left over: JPEG and HEIC embedding checks, Adobe RGB, Rec 2020, preserve ProPhoto.')
STATUS['BK-156']=('PARTLY DONE','Commit 3ad30b3: a restored context abandons the dead engine without GL calls, stale GL errors are drained, a failed upload keeps the source. Left over: driver failure notice flow and the layer re-upload test.')
STATUS['BK-459']=('PARTLY DONE','Commit ee38b8b: negative dehaze, lens gain in the local analysis, mask baseline, grading tint luminance. Left over: HSL partition of unity (AE-013), clarity weight above 1.0 (AE-029), manual vignette centre (AE-016).')
STATUS['BK-460']=('PARTLY DONE','Commit ee38b8b: heal overlay before the vignetting gain (AE-005), Healer places strokes through the lens polynomial (AE-004). Left over: AI mask input frame with lens and optics (AE-017), heal overlay colour conversion for finished pictures (AE-026).')
STATUS['BK-461']=('DONE','Done in commit ee38b8b (colour and luminance range masks compare in the display domain the picker reads).')
STATUS['BK-467']=('DONE','Done in commit ee38b8b (floatToHalf clamps to the largest finite half; host test in tools/golden/halfs_test.cpp, run by run-golden.sh). The Kotlin twin is in 3ad30b3.')

STATUS['BK-398']=('PARTLY DONE','S1a done (3a1f250, cd53d6d, 6164874: model, blend maths, project JSON v1, GPU compositor, golden). S1b host parts merged (c6f8a39); GPU stroke, canvas UI and autosave thread in progress. S1c is specified in W20-studio-s1c.md.')
STATUS['BK-465']=('DECLINED','Closed 6 Oct 2026: the mip chain at 1:1 export is needed because the local analysis samples the source at a high mip level; dropping it would change every export (docs/DECISIONS.md, W09b-engine-residue.md section 0).')
STATUS['BK-142']=('DONE','Done on main (AutoBackup.kt, app/backup/): three targets, verified run order, rotation to 7, daily and after-25-changes triggers on JobScheduler instead of WorkManager (decision in docs/DECISIONS.md), Settings section. SAF, MediaStore and the job are only statically verified; the reinstall check is Jai\'s. The first launch offer waits for W05.')
STATUS['BK-143']=('PARTLY DONE','Backups sit in Documents/Rawline/backups with All files access, or in a chosen folder, and Restore from a backup lists them; the first launch offer waits for W05; MediaStore survival after a reinstall is open (phone check).')
STATUS['BK-303']=('DONE','Manifest with SHA-256 per entry (format 2), verify before publish and before restore, restore preview, damaged file refused, format 1 still restores.')
STATUS['BK-096']=('PARTLY DONE','Done on main: the engine (core/data/ingest: SHA-256 verify, .part then rename, resume by ledger, cancel, disk full, a card pulled mid file, card file time kept, speed class), the SAF card walk, a dataSync service and one Import from a card entry in the Add photos menu. Only host tested (26 tests); the SAF walk, the service and the menu are compiled, not run. Left for W17: the Preview only badge and editor text (D7), Settings toggle, USB attach.')
STATUS['BK-376']=('PARTLY DESIGNED','W26-studio-s2.md: rect, ellipse, lasso, byte algebra and sparse tiles with 11 passing host tests; GPU and UI are instructions. Not merged.')
STATUS['BK-459']=('PARTLY DONE','Commit ee38b8b merged dehaze sign, lens gain in the analysis, mask baseline, grading tint luminance, range masks in display terms, clarity clamp, manual vignette centre. Left: HSL partition, fine Texture radius and ProPhoto grading luma, now built and measured in W23-shader-correctness.md (look 2, not merged).')
for _k in ('BK-392','BK-393','BK-394','BK-395','BK-396'): STATUS[_k]=('DONE','Done in commits fb5d24f (copy rules test, docs/COPY.md, the copy deck, the flow machine) and 64006fd (welcome screens, help sheets, glossary, Settings > Help). Compiled and host tested; the screens were not run on a phone, so Jai\'s fresh install tap-through is still the check. BK-397 (tips) and Reset tips are left.')
STATUS['BK-488']=('DECIDED','PM decision 6 Oct 2026: Studio blends in gamma encoded display space by default (matches Photoshop and what Jai expects); the linear option is stored per document (BlendSpace) and is off. Remaining work: both spaces in the reference and every S3 golden, the new project switch, and the note in docs/STUDIO_STATUS.md and DECISIONS.md (text in DISPATCH.md).')
for _k in ('BK-246','BK-426'): STATUS[_k]=('DONE','Done in commit eec8960: predictive back on, guard tests (manifest rules, BackHandler inventory) and docs/PLATFORM.md.')
STATUS['BK-285']=('DONE','Done in commit eec8960: the first export notification question is a tested NotificationRule with the one line reason shown first.')
STATUS['BK-120']=('DONE','Done in commit eec8960: filter, sort, column count and the top photo survive a swipe away (libraryFilter, columns, libraryTop).')
for _k in ('BK-497','BK-498'): STATUS[_k]=('DESIGNED','W29-library-first-impression.md: OrderGate, DefaultView, WhatsNew and the rawOnly filter compiled and tested on the host (23 core/model tests pass); ViewModel, scanner and screen edits specified, not compiled. Not merged.')
STATUS['BK-501']=('DONE','Done in commit 6bc276b: tools/commit-msg-check.sh, tools/check-commit-messages.sh and the CI job.')
STATUS['BK-206']=('PARTLY DONE','Commit 3cd7bb9 added the separate lintDebug job; commit 2061987 fixed the one lint error in all modules and the job is no longer allowed to fail. Left over: detekt or ktlint and a baseline for new issues (BK-206 task in DISPATCH.md).')
STATUS['BK-503']=('PARTLY DONE','Merged in b63e9df and 1fed051: free space rule, retry schedule 5, 10, 20, 40, 60 s with a stop after 10 tries, one message, NO_SPACE state, pre-checks, no half written first save, leave dialog. Left over (W32, review-w13.md R4 to R6): leaving waits for the save, a failed duplicate cleans up, the retry gap counts from the failure.')
for _k in ('BK-479','BK-480','BK-483'): STATUS[_k]=('DONE','Merged in 1fed051 (tiled commit, banded native readback, GL lifecycle with a paused state); checked in review-w13.md, host tests pass.')
STATUS['BK-481']=('PARTLY DONE','Pen over palm merged in 1fed051. Left over: a missed hover exit leaves fingers off for good (BK-510, W32 patch tested).')
STATUS['BK-484']=('PARTLY DONE','Graveyard pruned against history (1fed051). Left over: the 256 MB cap can still evict a layer whose delete is undoable (BK-512, review-w13.md R8).')
STATUS['BK-487']=('PARTLY DONE','Movable GL surface and 48 dp targets merged in 1fed051, system cancel fixed. Left over: whether a rotation still detaches the view is unproven (BK-508, counters and a detach rule in W32).')
STATUS['BK-508']=('DESIGNED','W32-w13-followups.md: patch for the detach rule applies, counters and layout fix specified; not compiled.')
STATUS['BK-509']=('DESIGNED','W32-w13-followups.md section 5.2: keep awake and wait for the foreground, specified only; PM decided option (a) now, the service stays with S9.')
STATUS['BK-510']=('DESIGNED','W32 patch: 3 router tests pass on the host. Not merged.')
STATUS['BK-511']=('DESIGNED','W32 patch: 4 session tests pass on the host (164 tests in all); the CanvasScreen edit is not compiled. Not merged.')
for _k in ('BK-504','BK-505','BK-506','BK-507'): STATUS[_k]=('DESIGNED','W31-studio-s1d.md: the rules compiled and tested on the host (124 studio-model tests); app, Compose and picker edits specified, not compiled. Not merged.')
STATUS['BK-502']=('DECIDED','PM decision 6 Oct 2026: the next release with Studio visible waits for W13 (S1b fixes plus BK-503) and S1c. No Studio visible release before those land.')
STATUS['BK-470']=('DESIGNED','Fully specified and verified in W22-colour-contract.md (decode with adjust_maximum_thr 0, look version 1 reproduces the old factor exactly, look 2 passes fixture F8). Not merged yet.')
STATUS['BK-346']=('PARTLY DONE','`KeepAwake.kt` (hold counter, test) is committed in core/ui as of 71918bf but no screen calls it yet. Left over: wiring in the loupe, import and queue screens and the Settings toggle.')
STATUS['BK-352']=('DONE','Done in commit e9cd055 (UndoRules and UndoEntry: one undo for the last rating, flag or label change, grouped restore writes, tests). Left over: undo for batch deletes and reset edits is BK-202.')
STATUS['BK-323']=('PARTLY DONE','Commit e9cd055 added `ndk { abiFilters += "arm64-v8a" }` to the app. Left over: the CI step that fails if any other lib folder appears in the APK, and measuring the new size.')
STATUS['BK-305']=('PARTLY DONE','Commit 71918bf added a confirm dialog before Restore backup and Clear thumbnails and Clear crash reports buttons in Settings, and a CatalogLogicTest for scan pruning. Left over: data-preserving migration and DAO tests with rows.')
for k,(tag,txt) in STATUS.items():
    a,e=allm[k]
    lines=e.split('\n')
    lines[0]=re.sub(r'(### BK-\d+ \[P\d\]) ',r'\1 ['+tag+r'] ',lines[0],count=1)
    lines.insert(1,'- Status: '+txt)
    allm[k]=(a,'\n'.join(lines))
    entries[a]=[allm[k][1] if eid(x)==k else x for x in entries[a]]


ADD={'BK-024':['- Src: RawPedia (RawTherapee) demosaicing guidance: AMaZE best at low ISO, RCD close and better on round edges, LMMSE and IGV for noisy high ISO files, AHD old and inferior (checked 6 Oct 2026); see BK-422 for the choice by ISO: https://rawpedia.rawtherapee.com/Demosaicing'],
'BK-133':['- Note: AUDIT item 9 (fifth pass) confirms TIFF export ignores Display P3 (no ICC tag).'],
'BK-153':['- Note: AUDIT item 9 (fifth pass) confirms thumbnails and orphaned mask or heal files are never swept.'],
'BK-232':['- Note: AUDIT item 8 (fifth pass): Settings has no Clear button for the last crash and no note that reports contain photo file names.'],
'BK-250':['- Note: verified 6 Oct 2026 that the grid already passes `key = id` and `contentType`; the remaining gains are in BK-425.'],
'BK-084':['- Src: stabilisation methods (moving average, exponential moving average, lazy radius or pulled string) described by Toon Boom and the Lazy Brush library (checked 6 Oct 2026): https://docs.toonboom.com/help/harmony-24/premium/drawing/about-stabilization.html ; see BK-391 for the concrete input pipeline.'],
'BK-096':['- Src: Lightroom on Android has no direct card import (files must be copied first with a file manager, Adobe community threads, checked 6 Oct 2026), so a good card import is a clear advantage: https://helpx.adobe.com/ee/lightroom/mobile/add-and-capture-photos/add-and-import-photos/import-photos-from-card-or-cameras.html']}
for k,lines in ADD.items():
    a,e=allm[k]; e=e.rstrip('\n')+'\n'+'\n'.join(lines)+'\n'
    allm[k]=(a,e); entries[a]=[e if eid(x)==k else x for x in entries[a]]
ids=[eid(e) for a in entries for e in entries[a]]
assert len(ids)==len(set(ids)),'dup ids'
TOP=[
('BK-142','Automatic rotating catalogue backups outside the app: uninstall or a key change still wipes everything.'),
('BK-226','Private release signing key via Actions secrets with key rotation: the public key lets anyone forge an update.'),
('BK-001','First real phone timings and a one-tap speed test: all speed work is guesswork until measured.'),
('BK-096','Import from the SD card or camera over USB: the first step of every shoot, missing today.'),
('BK-291','Content fingerprint as a secondary lookup: protects edits from a changed modified time without the re-key the decision rejected.'),
('BK-308','Catalogue work (XMP writes, pastes, ratings) runs on the main thread: a real jank and ANR risk.'),
('BK-261','Stop the full-table reload and per-photo queries on every MediaStore change (exports trigger it too).'),
('BK-003','Baseline Profile (plus R8, BK-004): faster first launch after every sideloaded update.'),
('BK-009','Slider latency: coalesce renders, GPU histogram, no readback per tick.'),
('BK-292','Rotation rebuilds the editor: Undo history is lost and the raw is decoded again.'),
('BK-021','Validate and refit the base look on 15-20 scenes: it touches every photo, fitted on one scene today.'),
('BK-022','Camera-space white balance and the real S5IIX colour matrix: hue accuracy of skin and sky.'),
('BK-023','Highlight reconstruction beyond LibRaw blend: clipped skies and lights are common on the S5IIX.'),
('BK-024','GPU full-resolution demosaic: fast edit base, instant 100 percent zoom, better detail than AHD.'),
('BK-267','Loupe 100 percent zoom with real pixels (spec tier 3): focus checking while culling.'),
('BK-269','Preview versus export parity at 100 percent (binned half-size base): honest sharpening and NR decisions.'),
('BK-294','AI denoise slider re-decodes and re-runs the model on every change: make the amount a live GPU blend.'),
('BK-052','Split and side-by-side before/after, not only hold-to-see.'),
('BK-097','Reject-to-trash with a confirmation and undo: culling never frees space today.'),
('BK-098','Structured search (lens, ISO, date, rating) with saved searches.'),
('BK-104','Cull mode with swipe pick/reject and auto-advance (then BK-310 suggestions).'),
('BK-246','Android 16 predictive back and edge-to-edge compliance (targetSdk is 37).'),
('BK-145','No pruning on a partial listing (the downgrade half is done).'),
('BK-166','48 dp touch targets without changing the look.'),
('BK-323','Drop the three unused ABIs: about 22.7 MB less in every sideload update, one line in app/build.gradle.kts.'),
('BK-227','Stop committing the 61 MB APK to git; Releases are the channel.'),
('BK-240','In-app update check and one-tap install with SHA-256 verification.'),
('BK-303','Restore preview (dry run) and backup integrity manifest.'),
('BK-355','Corrupt or truncated RW2: partial decode and preview-only editing instead of a dead end.'),
('BK-338','Culling 800 photos: next undecided, progress count and resume.'),
]
assert len(TOP)==30 and all(t[0] in allm for t in TOP)

MS=[
('M1','Safety net, measurement and release hygiene','Highest value for effort: mostly S and M items that protect Jai\'s edits and make every later claim measurable.',
 'Exit check: an automatic backup exists outside the app; a release is signed with a private key and passes the APK check; the first full Copy report (speed test) is pasted and PERF.md has real rows; no database work on the main thread.',
 'Dependencies: BK-142 and BK-143 before BK-226 (key rotation needs a safe backup first); BK-001 before any claim in M2 and M3; BK-147 before more schema work.',
 ['BK-473','BK-001','BK-002','BK-071','BK-072','BK-142','BK-143','BK-145','BK-147','BK-152','BK-153','BK-154','BK-155','BK-157','BK-226','BK-227','BK-229','BK-261','BK-264','BK-291','BK-303','BK-308','BK-206','BK-207','BK-230','BK-234','BK-238','BK-239','BK-241','BK-149','BK-323','BK-353','BK-361','BK-429','BK-451','BK-458']),
('M2','Fast to open, fast to edit','High value (goal 1 in the spec). Needs the M1 speed test to choose what to do first; drop any item the numbers say is not a problem.',
 'Exit check: PERF.md shows phone numbers for every row; open under 150 ms, Edit first frame under 1.2 s, slider frame p95 under 16 ms, or a written reason and a new target.',
 'Dependencies: BK-001 (numbers); BK-003 and BK-004 together; BK-292 before BK-156; BK-176 and BK-177 before wider prefetch (BK-005, BK-008).',
 ['BK-003','BK-004','BK-005','BK-007','BK-008','BK-009','BK-010','BK-011','BK-012','BK-013','BK-053','BK-126','BK-156','BK-158','BK-176','BK-177','BK-181','BK-267','BK-292','BK-293','BK-354','BK-355','BK-358','BK-363','BK-389']),
('M3','Look and image quality','High value (goal 2) but the riskiest: every item changes how photos look, so each is behind a recipe flag and needs Jai to judge on the phone.',
 'Exit check: golden scenes cover each stage; Jai approves side by side comparisons against the camera JPEG and desktop output on 15 files; export parity at 100 percent verified.',
 'Dependencies: BK-021 before BK-022, BK-033, BK-287; BK-024 before BK-025, BK-027 and the 96 MP work (BK-253, BK-127); BK-211 to BK-213 alongside; BK-294 and BK-295 before more AI denoise work.',
 ['BK-021','BK-022','BK-023','BK-024','BK-006','BK-025','BK-026','BK-027','BK-028','BK-029','BK-030','BK-031','BK-032','BK-033','BK-034','BK-035','BK-287','BK-252','BK-253','BK-269','BK-127','BK-275','BK-294','BK-295','BK-211','BK-212','BK-213','BK-324','BK-325','BK-421','BK-422','BK-423','BK-430','BK-470','BK-435','BK-436','BK-437','BK-438','BK-439','BK-440','BK-441','BK-442','BK-443','BK-445','BK-459']),
('M4','Editor and AI daily workflow','Medium to high value: the everyday feel of editing and trust in the AI tools.',
 'Exit check: before/after split, clip overlay and fine slider control in daily use; AI tools show states, refine edges and have known phone timings; no second undo system.',
 'Dependencies: BK-053 (GPU histogram) before BK-054; BK-296 before BK-075; BK-076 before BK-078 and BK-084 (shared refine step); BK-077 with BK-078 (layer sizes).',
 ['BK-051','BK-052','BK-054','BK-055','BK-056','BK-057','BK-058','BK-059','BK-060','BK-061','BK-073','BK-074','BK-075','BK-076','BK-077','BK-078','BK-079','BK-082','BK-083','BK-084','BK-296','BK-298','BK-200','BK-340','BK-460']),
('M5','Library and card import','High value for the real workflow (shoot, import, cull, find). Large items (BK-096, BK-106) can ship in two steps.',
 'Exit check: Jai imports a card, culls with swipes, deletes rejects, and finds a photo by lens and date without leaving Rawline.',
 'Dependencies: BK-263 and BK-098 before BK-099; BK-096 before BK-112 and BK-256; BK-100 needs the Room migration test (BK-305); BK-097 before the cull "delete rejects" step; BK-310 after BK-104.',
 ['BK-497','BK-498','BK-474','BK-475','BK-476','BK-096','BK-097','BK-098','BK-099','BK-100','BK-101','BK-102','BK-103','BK-104','BK-105','BK-106','BK-107','BK-108','BK-109','BK-277','BK-310','BK-186','BK-191','BK-192','BK-338','BK-339','BK-350','BK-392','BK-393','BK-447','BK-450']),
('M6','Export, accessibility, polish and the stretch items','Medium value each, but they finish the product: export that matches Jai\'s needs, accessibility, updates, tests and the large optional features.',
 'Exit check: export presets with preview, Ultra HDR/HEIC decision made, 48 dp targets and a TalkBack pass done, in-app update works, screenshot and emulator tests green.',
 'Dependencies: BK-040 before BK-132; BK-128 before BK-315 and BK-130; BK-166 before BK-208 screenshot baselines; BK-226 before BK-240 and BK-238 registration.',
 ['BK-128','BK-129','BK-130','BK-131','BK-132','BK-133','BK-134','BK-281','BK-166','BK-167','BK-168','BK-169','BK-193','BK-196','BK-208','BK-209','BK-210','BK-240','BK-245','BK-246','BK-232','BK-228','BK-080','BK-081','BK-341','BK-342','BK-359']),
('M7','Studio (separate product line, scheduled after M1 to M3; the spec\'s own milestones S1 to S10 set the inner order)','Studio is a new product inside the app, not a feature of Develop, so its effort is large and its inner order is the spec\'s S1 to S10 (docs/STUDIO_SPEC.md). Backlog effort figures for M7 count only the review, test and risk entries added here, not the spec\'s own roughly 58 days of build work, and the spec\'s S1 estimate of 4 to 6 hours is not credible (BK-398).',
 'Exit check (first Studio release): open a 6000 x 4000 photo from Develop as a layer, paint on 10 layers at 60 fps or better, undo 100 steps, save, kill the app, recover, and export through the queue, with the Studio test plan (BK-382) green.',
 'Dependencies: BK-365 first; BK-366 and BK-367 before everything; BK-368 and BK-369 before the brush ships; BK-385 before any shared shader code; BK-374 before the layers panel gestures; BK-372 and BK-373 together.',
 ['BK-502','BK-503','BK-508','BK-509','BK-479','BK-480','BK-481','BK-488','BK-489','BK-490','BK-491','BK-492','BK-365','BK-366','BK-367','BK-368','BK-369','BK-370','BK-371','BK-372','BK-373','BK-374','BK-375','BK-376','BK-377','BK-378','BK-382','BK-385','BK-398','BK-399','BK-400','BK-401','BK-402','BK-403','BK-404','BK-410','BK-411','BK-412','BK-413','BK-414','BK-415','BK-416','BK-417','BK-418','BK-419','BK-420']),
]
done_ids=[k for k,(t,_) in STATUS.items() if t.startswith('DONE')]
assigned=[i for m in MS for i in m[5]]
assert len(assigned)==len(set(assigned)), [i for i in assigned if assigned.count(i)>1]
need=[eid(e) for a in entries for e in entries[a] if pr(e)<=1 and eid(e) not in done_ids and eid(e) != 'BK-141']
miss=[i for i in need if i not in assigned]
extra=[i for i in assigned if i not in allm]
assert not miss and not extra, (miss, extra)
DAYS={'S':1,'S-M':2,'M':3,'M-L':5,'L':8}
def clean(t): return re.sub(r'^\[[^\]]+\] ','',t).replace('|','/')
def size(e): return re.search(r'- Size: ([A-Z](?:-[A-Z])?)',e).group(1)
ms_out=['\n## Milestone plan (all P0 and P1 entries, seven ordered milestones (M7 is the separate Studio line))\n\n','Effort units are rough working days (S 1, S-M 2, M 3, M-L 5, L 8) for one person with the sandbox build loop; calendar time depends on how many sessions work in parallel and they ignore waiting for Jai\'s phone tests, which dominate M2 and M3. "Value" is my judgement for Jai, not a measurement. Items marked partly done keep only their left-over scope. Done entries are listed under the plan.\n\n']
ms_out.append('| Milestone | Entries | Effort (days) | Value | Value per effort |\n| --- | --- | --- | --- | --- |\n')
vals={'M7':('High if wanted','Large; set by the spec'),'M1':('Very high','Highest'),'M2':('High','High'),'M3':('High (risky)','Medium'),'M4':('Medium-high','Medium'),'M5':('High for workflow','Medium-high'),'M6':('Medium','Lower'),}
rows=[]
for code,name,goal,exit_,deps,ids_ in MS:
    d=sum(DAYS[size(allm[i][1])] for i in ids_)
    rows.append((code,name,len(ids_),d))
    ms_out.append(f"| {code} {name} | {len(ids_)} | {d} | {vals[code][0]} | {vals[code][1]} |\n")
ms_out.append('\n')
for code,name,goal,exit_,deps,ids_ in MS:
    ms_out.append(f"### {code}: {name}\n\n{goal}\n\n{exit_}\n\n{deps}\n\n| ID | P | Size | Status | Title |\n| --- | --- | --- | --- | --- |\n")
    for i in ids_:
        a,e=allm[i]
        st=re.match(r'### BK-\d+ \[P\d\] \[([^\]]+)\]',e)
        ms_out.append(f"| {i} | P{pr(e)} | {size(e)} | {st.group(1) if st else ''} | {clean(title(e))} |\n")
    ms_out.append('\n')
ms_out.append('### Already done (kept in the backlog, marked in each entry)\n\n')
for i in done_ids:
    a,e=allm[i]; ms_out.append(f"- {i} {clean(title(e))}: {STATUS[i][0]}\n")
ms_out.append('\nParked with a reason: BK-141 (declined in DECISIONS.md, see BK-291), BK-109 (blocked on it). P2 and P3 entries are not scheduled; pull them in when they unblock a P0/P1 item or when a milestone finishes early.\n')
MS_TEXT=''.join(ms_out)

QW=[
('BK-323','20 min','app/build.gradle.kts','In `defaultConfig` add `ndk { abiFilters += "arm64-v8a" }` (the filter in core/native does not reach dependency AARs). Optional: `packaging { jniLibs { excludes += setOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") } }`.','`unzip -l app-release.apk | grep lib/` lists only arm64-v8a; APK about 22 MB smaller uncompressed.'),
('BK-308','45 min','app/src/main/kotlin/app/rawline/LibraryViewModel.kt (lines 192-194, 197, 205, 210, 232)','Change `viewModelScope.launch {` to `viewModelScope.launch(Dispatchers.IO) {` for rate, flag, label, copyEdits, pasteEdits, syncEdits and enqueueExport. `applyToTargets` loops over every target parsing JSON and writing XMP; it must not run on Main. `message.value` is a StateFlow so setting it from IO is safe.','Rate 400 selected photos with XMP sidecars on: no frame over 50 ms in the grid (Copy report `grid jank_frames`).'),
('BK-427','20 min','core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt (`download`, the checksum drain line)','Replace `java.io.OutputStream.nullOutputStream()` (API 33; minSdk is 31) with a tiny own stream: `object : java.io.OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }`. Found by lint in the fifth audit pass; it would crash a model download on Android 12 and 12L.','Lint no longer reports NewApi for ModelStore; a unit test drains a zip through `download`-like code.'),
('BK-120','1 h','feature/library/src/main/kotlin/app/rawline/feature/library/LibraryScreen.kt (lines 120 and 122)','`var columns by rememberSaveable { mutableIntStateOf(5) }` and wrap `selected` in `rememberSaveable(saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })) { mutableStateOf(setOf<Long>()) }`. Fixes AUDIT open item "rotation resets library selection".','Select 5 photos, rotate the phone: selection and column count stay.'),
('BK-339','90 min','core/model/src/main/kotlin/app/rawline/core/model/Library.kt (`LibraryFilter.apply`), feature/library/.../LibraryScreen.kt (filter chips), core/model/src/test/.../ModelTest.kt','Add `hideRejects: Boolean = true` to `LibraryFilter`; in `apply` skip `flag == -1` unless `flag` is `REJECT` or hideRejects is false; add a "Rejects" chip that sets it; add tests for each `FlagFilter` with the new default. Mention the new default in What\'s New.','Reject a photo: it leaves the grid; the Rejects chip brings it back.'),
('BK-285','45 min','app/src/main/kotlin/app/rawline/MainActivity.kt (line 135)','Remove the `notifPermission.launch(POST_NOTIFICATIONS)` from the first-launch `LaunchedEffect`; call it once (flag in prefs) from the code path that queues the first export (`vm.enqueueExport`), before the service starts.','Fresh install shows only the photos prompt at launch; the notification prompt appears on the first export.'),
('BK-346','30 min','feature/loupe/.../LoupeScreen.kt (top of `LoupeScreen`) and feature/export/.../QueueScreen.kt','`val view = LocalView.current; DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }` in the loupe; the same in the queue screen while any job is running. Setting toggle later.','The screen stays on while swiping photos for 5 minutes with a 30 second timeout.'),
('BK-229','30 min','.github/workflows/build.yml (after "Build signed release APK" and "Build release APK with the committed sideload key")','Add a step: `source tools/env.sh && "$ANDROID_HOME/build-tools/37.0.0/zipalign" -c -P 16 4 rawline.apk` (it passes on the 5 Oct APK). Add `for f in lib/arm64-v8a/*.so` readelf check later.','CI fails if a library is not 16 KB aligned.'),
('BK-218','15 min','.github/workflows/build.yml (same step)','Add `ls -l rawline.apk` and `unzip -lv rawline.apk | sort -k1 -n -r | head -10` so the APK size and top contents are in every run log.','APK size visible in the job log; use it to confirm BK-323.'),
('BK-264','1 h','app/src/main/kotlin/app/rawline/RawlineApplication.kt (class Graph)','Make `modelStore`, `rawPrefetch`, `previews`, `maskStore`, `patchStore` and `thumbs` `by lazy` (catalog and indexer reference some of them: keep their order). ModelStore does file checks for five packs in its constructor.','Startup trace: `Application.onCreate` under 20 ms; app still opens the library.'),
('BK-296','90 min','core/ml/src/main/kotlin/app/rawline/core/ml/TfModel.kt (`create()`)','Store `accel_ver_$name` = app `longVersionCode` next to the saved delegate; when the version differs (or after 7 days) ignore a saved `CPU` and try the GPU first again. Keep recording the error text.','After an update a model that failed on GPU once is tried on GPU again (report shows `ai_<name>_GPU_first_ms`).'),
('BK-157','1 h','app/src/main/kotlin/app/rawline/ExportRunner.kt (top of `exportOne`)','Before rendering: if `context.cacheDir.usableSpace` is under 300 MB (or 2 x the estimated output) throw `IllegalStateException("Not enough free space: about 300 MB needed")`; the job then shows a clear message in the queue.','Fill the phone storage in a test: the job fails fast with the message.'),
('BK-166','45 min','feature/loupe/src/main/kotlin/app/rawline/feature/loupe/LoupeScreen.kt (`BarIcon` and the star `Box`), feature/masking/.../MaskTray.kt (line 141)','Change `Modifier.size(44.dp)` to `48.dp` (icon sizes stay 22 and 24 dp). Phone check that the bottom bar still fits six items on a 1440 px wide screen.','Hit areas are 48 dp; no layout shift beyond 4 dp per icon.'),
('BK-169','30 min','core/ui/src/main/kotlin/app/rawline/core/ui/LrTheme.kt (line 38) and five call sites','Add `val AccentText = Color(0xFF6C9CF0)` (about 6.2:1 on #1C1C1C) and use it instead of `Lr.Accent` where it colours text: QueueScreen.kt lines 50 and 80, LibraryScreen.kt line 186, Controls.kt line 282, GeometryPanel.kt line 151. Fills and icons keep #437EE4.','Contrast of accent text over Surface1 is at least 4.5:1 (add the unit test from BK-169 if time allows).'),
('BK-227','20 min','repo root: `dist/`, `.gitignore`, README.md','`git rm --cached dist/rawline.apk dist/rawline.apk.sha256`, add `dist/` to .gitignore, and say in README that the APK comes from Releases. First confirm nobody downloads `dist/` directly (commit 0c6441b added it deliberately).','`git ls-files dist` is empty; clone size stops growing by 60 MB per APK commit.'),
('BK-299','1 h','core/data/src/main/kotlin/app/rawline/core/data/Db.kt (PhotoDao) and app/src/main/kotlin/app/rawline/ReportBuilder.kt','Add `@Query("SELECT width, height, COUNT(*) AS n FROM photos WHERE isRaw = 1 AND width > 0 GROUP BY width, height ORDER BY n DESC LIMIT 5") suspend fun previewSizes()` (no schema change) and print it in the "Library" section as `embedded preview sizes: 6000x4000 (n=412)`.','The next Copy report states whether the S5IIX embeds a full-size JPEG (decides BK-267 and BK-333).'),
('BK-058','45 min','core/ui/src/main/kotlin/app/rawline/core/ui/Controls.kt (`RawSlider`, `doReset`)','Get `LocalHapticFeedback.current` and call `performHapticFeedback(HapticFeedbackType.Confirm)` inside `doReset`; later add `SegmentTick` when the value crosses the default during a drag.','Double tap a slider label: a short confirm haptic.'),
('BK-241','20 min','.github/workflows/build.yml ("Publish release", variable `NOTE`)','Append ` Previous build: v0.1.$(( ${{ github.run_number }} - 1 )).` and the milestone name from a `MILESTONE` file; makes the rollback target visible in the release page.','New release notes name the previous build.'),
('BK-301','20 min','core/data/src/main/kotlin/app/rawline/core/data/Db.kt (EditDao.addSnapshot) and Catalog.kt (`addSnapshot`)','`@Insert suspend fun addSnapshot(s: SnapshotEntity): Long` and `return edits.addSnapshot(...)` instead of re-reading the newest row.','Two snapshots in the same millisecond get two ids (unit test).'),
('BK-205','90 min','app/src/main/kotlin/app/rawline/MainActivity.kt (where `onCopyReport` builds the report) and feature/settings/SettingsScreen.kt','Add a "Share report" button that sends the same text with `Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), null)`; pass an `onShareReport` lambda to SettingsScreen.','Share sheet opens with the full report text.'),
('BK-203','1 h','new core/model/src/test/kotlin/app/rawline/core/model/TextRulesTest.kt','Walk `docs/*.md` and every `src/main/**/*.kt` under the repo root (use `File("../..")` from the module dir) and fail on the em dash character (U+2014) and on a short list of American spellings (colour is correct; flag `color ` in prose strings only, not code identifiers).','`./gradlew testDebugUnitTest` fails when a string or doc breaks the project writing rule.'),
('BK-072','1 h','core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt (`Models.PEOPLE`), tools/models/models.json','Download the file once, record its SHA-256 in `sha256 = ...` and keep the `latest` URL; the download is then rejected if upstream changes it (and CI `fetch.sh` should compare the hash and fail loudly). Needs network access to storage.googleapis.com from the sandbox, which may be blocked; if so Jai pastes the hash from a PC-free tool or the CI log prints it.','Download of the People pack matches the pinned hash; a changed file fails with "does not match the expected checksum".'),
]
QW_TEXT=['\n## Quick wins under 2 hours each (ranked by value for Jai)\n\nEach is a small, low-risk change with the exact file and edit. Times are for a person who knows the code, without phone testing. Most only need the unit tests and one look on the phone. Ranking: size of the benefit to Jai first (download size, jank, lost state), then how many later items it unblocks.\n\n']
QW_TEXT.append('| # | ID | Time | File | Change | Check |\n| --- | --- | --- | --- | --- | --- |\n')
for n,(i,t,f,c,v) in enumerate(QW,1):
    assert i in allm, i
    QW_TEXT.append(f"| {n} | {i} | {t} | {f.replace('|','/')} | {c.replace('|','/')} | {v.replace('|','/')} |\n")
QW_TEXT.append('\nTotal about 17 hours for all 22. Do items 1 to 6 first (about 4.5 hours): they fix the biggest download, the main-thread risk, two visible annoyances and keep alignment honest.\n\n')
QW_TEXT=''.join(QW_TEXT)

TRACE_TEXT="""
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

"""

XREF_TEXT="""
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
"""
out=[]
out.append('''# Rawline improvement backlog

Compiled 6 Oct 2026 by the backlog thinker. Read-only audit of docs/SPEC.md, DECISIONS.md, AUDIT.md, PERF.md, UI_SPEC.md and the source at commit b16994f. Nothing here has been run on the phone, so every speed or look claim is a hypothesis until a Copy report says otherwise (project rule).

Conventions: IDs are permanent (BK-001 to BK-512). Priority P0 (do first: data loss, security, measurement, or blocks other work), P1 (high value), P2 (worth doing), P3 (nice to have). Size S (under a day), M (a few days), L (a week or more). Entries inside each area are ranked P0 to P3 (IDs are therefore not in order). "Src" lines cite the source and the date it was checked; sources are listed at the end. Australian English, no em dashes.

''')
cnt={a:{p:0 for p in rank} for a in AREAS}
for a in entries:
    for e in entries[a]: cnt[a]['P%d'%pr(e)]+=1
tot=sum(len(v) for v in entries.values())
out.append(open(base+'/parts/next25.md').read())
out.append('## Counts\n\n| Area | P0 | P1 | P2 | P3 | Total |\n| --- | --- | --- | --- | --- | --- |\n')
for a in AREAS:
    c=cnt[a]; out.append(f"| {a}. {SHORT[a]} | {c['P0']} | {c['P1']} | {c['P2']} | {c['P3']} | {sum(c.values())} |\n")
tp=[sum(cnt[a][p] for a in AREAS) for p in rank]
out.append(f"| **All** | {tp[0]} | {tp[1]} | {tp[2]} | {tp[3]} | {tot} |\n\n")
nd=sum(1 for k,(t,_) in STATUS.items() if t=='DONE'); npd=sum(1 for k,(t,_) in STATUS.items() if t=='PARTLY DONE'); nm=sum(1 for k,(t,_) in STATUS.items() if t=='MERGED'); nsc=sum(1 for k,(t,_) in STATUS.items() if t=='SPEC COVERS'); nsw=sum(1 for k,(t,_) in STATUS.items() if t=='SPEC WINS')
out.append(f"Status of the {tot} entries: {nd} DONE, {npd} PARTLY DONE, {nm} MERGED into another entry (dedupe pass, 6 Oct 2026), {nsc} SPEC COVERS and {nsw} SPEC WINS (Studio entries settled by docs/STUDIO_SPEC.md), 1 DECLINED and 1 BLOCKED; the rest are open. Merged and done entries are kept for their history and acceptance details.\n\n")
out.append('## Top 30 overall (ranked)\n\nOrder weighs risk of losing Jai\'s work first, then measurement, then speed and look, then workflow. Items marked with a dependency must follow it.\n\n| # | ID | Area | Title | Why now |\n| --- | --- | --- | --- | --- |\n')
for i,(k,why) in enumerate(TOP,1):
    a,e=allm[k]
    out.append(f"| {i} | {k} | {a} | {title(e).replace('|','/')} | {why} |\n")
out.append('''
See the milestone plan below for the order of attack and dependencies. Already done (BK-144, BK-146, BK-137, BK-148, BK-150 and others) are marked DONE in their entries and left out of this list.

''')
out.append(QW_TEXT)
out.append(MS_TEXT)
out.append(TRACE_TEXT)
out.append(XREF_TEXT)
out.append('## Contents\n\n')
for a in AREAS:
    out.append(f"- Area {a}: {AREAS[a]} ({sum(cnt[a].values())} entries)\n")
out.append('\n')
for a in AREAS:
    out.append(f"\n---\n\n# AREA {a}: {AREAS[a]}\n\n")
    t=intro.get(a,'').strip()
    if t: out.append(t+'\n\n')
    for e in entries[a]: out.append(e+'\n')
out.append('''
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
''')
open(os.path.join(base,'..','BACKLOG.md'),'w').write(''.join(out))
print(tot, tp)

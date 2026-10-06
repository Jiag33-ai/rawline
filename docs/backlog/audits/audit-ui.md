# Rawline UI audit (read only, static)

Scope: feature/library, feature/loupe, feature/settings, feature/export, app/ (MainActivity, nav, ExportService, ExportRunner, ExportUi, EditorHost, LibraryViewModel, Graph), core/ui, core/cache, core/data.
Method: full read of every file in scope, plus docs/UI_SPEC.md and docs/AUDIT.md. Nothing was built or run. Anything about look, speed or TalkBack behaviour is a static-read finding and needs the phone's Copy report or a hands-on check before it is called fixed (CLAUDE.md rule). No em dashes or en dashes were found in any user-facing string in scope.

Severity: P0 crash on a normal path or data loss. P1 broken feature, ANR or crash risk, major dead end. P2 notable usability, accessibility or performance problem. P3 polish or spec drift.
Size: S under 1 hour, M 1 to 3 hours, L more than 3 hours.

Totals: P0 0, P1 4, P2 38, P3 27 (69 findings).

Items already on docs/AUDIT.md "Still open" are marked (known) where they overlap.

---------------------------------------------------------------------------
## A. Navigation, permissions, lifecycle

### AU-001 P1 Every toast is drawn underneath the screen, so no confirmation is ever visible
- Where: app/.../MainActivity.kt:141-143 (ToastHost is the first child of the Box, NavHost the second), screens with opaque backgrounds at LibraryScreen.kt:136, LoupeScreen.kt:109, QueueScreen.kt:44.
- Scenario: select photos, tap "Add to export queue" (or rate, paste edits, copy edits, import, backup). The message is set, `toast` becomes non-null, but the toast composable is drawn before the NavHost, and Library, Loupe and Queue paint a full-size black background over it. Only Settings (no background) can show it. The user gets no sign that anything happened, which is worst for the export enqueue (hidden background work) and bulk paste.
- Fix: move `ToastHost` after the `SharedTransitionLayout` in the Box so it draws on top; keep it above the selection bar by adding bottom padding when selecting.
- Size: S. Confirm on the phone (static read of z-order).

### AU-002 P1 Media permission dead end after two denials
- Where: LibraryScreen.kt:234-239 ("Allow access" calls onRequestPermission), MainActivity.kt:111,189, LibraryViewModel.kt:129-132.
- Scenario: the user taps Don't allow twice (or picks "Select photos" on Android 14, which leaves READ_MEDIA_IMAGES ungranted because the partial-access permission is not declared). From then on `RequestPermission.launch` returns "denied" instantly with no dialog. The only visible action ("Allow access") does nothing. The only way out is Import files.
- Fix: after a denied result, use `shouldShowRequestPermissionRationale` (or track a denied flag) and switch the button to "Open settings" (ACTION_APPLICATION_DETAILS_SETTINGS). Mention the all files route as the alternative.
- Size: S.

### AU-003 P2 Permission is not re-checked on resume
- Where: LibraryViewModel.kt:115-118 (`onResume` only rechecks all files), 62 (permissionGranted is set once and only by the launcher result).
- Scenario: the user follows AU-002 to system Settings and grants Photos access, then returns. `permissionGranted` is still false, so the prompt stays and no scan runs until the app is killed and reopened. Also, a user who granted All files access but not Photos permission is held on the prompt even though MediaStore would answer (isExternalStorageManager grants it).
- Fix: in `onResume` recompute `hasMediaPermission()`, call `onPermission(...)`, and treat `hasAllFiles()` as sufficient for starting the device watch.
- Size: S.

### AU-004 P2 Notification permission is launched at the same instant as the media permission; both re-fire on every Activity creation
- Where: MainActivity.kt:125-128.
- Scenario: two `RequestPermission` launchers fire in one effect. Android allows one permission request at a time and answers the second with an empty result, so the notification dialog is dropped on first run. The effect is keyed on Unit, so it runs again on every rotation and cold start: a denied media permission re-prompts after each rotation, and POST_NOTIFICATIONS is re-requested until the user grants or denies twice. Without it the export foreground notification (and its Cancel action) is hidden on Android 13+.
- Fix: request media first, then request notifications from its result callback (or the first time an export is queued, with a one line reason). Guard with a `rememberSaveable` flag so recreation does not re-ask.
- Size: S.

### AU-005 P2 Library UI state is not saved: column count, selection and filter bar reset
- Where: LibraryScreen.kt:120 (`columns`), 122 (`selected`), 123 (`showFilters`), all plain `remember`.
- Scenario: pinch to 6 columns, open a photo, come back (or switch to Queue and back): the grid is back at 5 columns because the destination leaves composition. Rotating mid-selection drops the selection (known, AUDIT #4). Filter bar closes on every return even though the filter is still active.
- Fix: `rememberSaveable` for columns, selected (as a long list saver) and showFilters; or hoist columns to LibraryViewModel and persist it in prefs.
- Size: S.

### AU-006 P2 Process death or cold restore: the viewer pops straight back, the editor shows blank
- Where: LoupeScreen.kt:91, MainActivity.kt:228-243, LibraryViewModel.kt:84-93 (StateFlow starts as an empty list).
- Scenario: Android kills the process while the user is in `loupe/12` or `edit/<id>`. Navigation restores the route before the DB flow has emitted. `photos` is empty, so LoupeScreen immediately calls `onBack()` and the user lands in the library at the top. The editor route renders nothing until `allPhotos` emits.
- Fix: delay the empty check until the first real emission (expose a `loaded` flag from the VM), and show a spinner on the edit route while the photo is null.
- Size: S.

### AU-007 P2 Double tap opens two viewers or two editors
- Where: MainActivity.kt:180 (`nav.navigate("loupe/$i")`), 217 (`nav.navigate("edit/${p.id}")`), no `launchSingleTop`.
- Scenario: a fast double tap on a thumbnail (or on Edit) pushes two identical entries. Back must be pressed twice, and a double Edit starts two EditorSessions (two GL engines, two full RAW decodes) on the same photo, a real memory risk on a phone.
- Fix: `launchSingleTop = true` on both, plus a short click debounce on Edit.
- Size: S.

### AU-008 P2 Back from the editor lands on a different photo than the one just edited
- Where: MainActivity.kt:133-137 (`openEditor` replaces only the edit entry), 213-217.
- Scenario: open photo 10 in the viewer, tap Edit, swipe in the editor to photo 13, press Back. The stack is photos, loupe/10, edit/13, so Back shows photo 10 again (pager state is saved per entry) instead of 13. The user loses their place, and in a long list may not notice they edited 3 other photos.
- Fix: when the editor swaps photos, also update the loupe entry's saved index (savedStateHandle result), or pop to the library and navigate loupe/newIndex then edit.
- Size: M.

### AU-009 P2 Editor swipe jumps to the first photo when the edited photo has left the filtered list
- Where: MainActivity.kt:134-135 and 230-233 (`indexOfFirst` returns -1 against the filtered `photos`, while the editor looks the photo up in `allPhotos` first).
- Scenario: filter is "Unedited". The user edits photo X; once the first save lands, X no longer matches the filter, so `i = -1`. Swiping to the next photo gives `photos[0]` (the top of the grid, far from X); swiping back does nothing. Neighbour prefetch also warms the wrong photos (photos[0], photos[1]).
- Fix: compute neighbours from the list the editor was opened from (capture the ordered id list at navigation time), or fall back to `allPhotos` order when `i < 0`.
- Size: M.

### AU-010 P2 Rating or flagging in the viewer with a library filter active removes the photo from under the user
- Where: LoupeScreen.kt:117 (pager is built from the live filtered list), MainActivity.kt:218.
- Scenario: filter is "Rating 4+" or "Picks". In the viewer the user taps Pick to unpick (or drops stars to 0): the photo leaves `photos`, the pager content shifts, and the next photo slides in with no explanation. Rating in sequence (cull workflow) becomes unreliable.
- Fix: freeze the id list when the viewer opens (snapshot the filtered list in the nav entry's VM or `remember`), and only update per-photo fields in place.
- Size: M.

### AU-011 P2 Returning from the viewer does not scroll the grid to the photo last seen
- Where: LibraryScreen.kt:121 (gridState only), MainActivity.kt:176-181 (index is passed one way only).
- Scenario: open photo 20 and swipe to photo 140; Back returns the grid at the old scroll position, shared-element return falls back to a slide because the thumbnail is off screen (spec 10.2 expects a shrink to the thumbnail). The user has to find their place again.
- Fix: on pop, scroll the grid to the last viewed id (save it in the VM when the pager settles) when it is not visible.
- Size: M.

### AU-012 P2 Info sheet state survives when the controls are hidden, and Back then does nothing visible
- Where: LoupeScreen.kt:103 (BackHandler enabled when `info || stars`), 134-158 (InfoSheet only drawn inside `chrome && p != null`), 109-116 (swipe up sets `info = true` regardless of chrome).
- Scenario: tap the photo to hide the bars, swipe up: `info` flips true but nothing appears. The next Back press is consumed to clear `info` (nothing changes on screen), so the user feels Back is broken. Showing the bars later pops the sheet open unexpectedly.
- Fix: draw InfoSheet outside the chrome condition, or clear `info` when chrome hides and ignore the swipe while hidden.
- Size: S.

### AU-013 P2 Viewer has no loading or failure state
- Where: LoupeScreen.kt:181-189 (`produceState`), 242-249.
- Scenario: (a) a photo with no thumbnail yet and a slow preview: black page, no spinner (spec 12.1 wants a 28 dp spinner). (b) preview decode fails (corrupt file, unplugged card): `value` stays null, the 320 px thumbnail is upscaled to full screen forever, no message and no retry. (c) PreviewCache.load swallows a cancelled decode and returns null (PreviewCache.kt:41-45) so a quick swipe can leave a page permanently on the thumbnail.
- Fix: track a tri-state (loading, ready, failed), show LocalLoader while loading and "Could not read this photo" with a Retry on failure; re-key the produceState on a retry counter.
- Size: M.

### AU-014 P2 Rotating the phone in the editor throws away the undo history
- Where: EditorHost.kt:48-50 (`remember(photo.id)` state, session, denoiser), AndroidManifest.xml (no configChanges, no orientation lock).
- Scenario: edit for ten minutes, rotate to landscape to check the photo: Activity is recreated, the pending debounce is flushed (good) but EditorState history, undo/redo, crop/mask mode and the GL engine are rebuilt from the saved recipe. Undo steps and any unsaved snapshot names are gone; reload is slow.
- Fix: either handle `orientation|screenSize` in configChanges for MainActivity (Compose adapts), or hoist EditorState to a ViewModel scoped to the nav entry.
- Size: M (configChanges is S but needs a phone check on insets).

### AU-015 P3 Editor route is blank until the recipe and preview load, with no spinner
- Where: EditorHost.kt:59-61, 98 (`val st = state ?: return`), 106-107 (`masking ?: return`, `remove ?: return`).
- Scenario: when the preview is not cached, `previews.load(photo)` (a full decode) runs before the recipe is even read, and nothing is composed meanwhile. Opening a not-yet-viewed RAW shows an empty black screen for hundreds of ms, then everything at once. Spec 12.1 asks for the cached thumbnail immediately, or a spinner.
- Fix: render the placeholder (thumbnail via `graph.thumbs.peek`) plus LocalLoader while `state` is null; load recipe first, preview in parallel.
- Size: S.

### AU-016 P3 Cold start flashes grey, not black
- Where: app/src/main/res/values/themes.xml:3 (`windowBackground` #2A2A2A).
- Scenario: launch the app: a #2A2A2A window shows before Compose draws the black canvas. Spec 12.1: "Do not flash a white page", canvas is #000000.
- Fix: set `windowBackground` to #000000 (and the splash background).
- Size: S.

### AU-017 P2 Status bar and gesture bar icons turn dark when the phone is in system light mode
- Where: MainActivity.kt:77 (`enableEdgeToEdge()` with defaults; no SystemBarStyle anywhere in the repo).
- Scenario: default `SystemBarStyle.auto` follows the system theme. With the phone in light mode the clock, battery and nav buttons are drawn dark on the app's black canvas and are nearly invisible. Spec section 3: "Status-bar content: light".
- Fix: `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))`.
- Size: S.

---------------------------------------------------------------------------
## B. Library (feature/library)

### AU-018 P2 "0 selected" ghost selection mode
- Where: LibraryScreen.kt:131 (`selecting` from the id set), 133 (`sel` filtered from `photos`), 141.
- Scenario: filter "Rating 3+", long press a photo, tap "No stars" (or any action that moves it out of the filter). The photo leaves `photos`, but its id stays in `selected`. The header reads "0 selected", the selection bar stays up, every chip applies to an empty list. Same after a background scan removes a photo.
- Fix: derive `selecting` from `sel.isNotEmpty()` and prune `selected` against `photos` in a `LaunchedEffect(photos)`.
- Size: S.

### AU-019 P2 Selection state and long press are invisible to TalkBack
- Where: LibraryScreen.kt:214-217, 301 (semantics carry name, stars and "edited" only).
- Scenario: a TalkBack or switch user cannot tell which tiles are selected, cannot learn that long press is the way to start selecting (no `onLongClickLabel`), and gets no "selected" announcement after toggling.
- Fix: add `selected = isSel` and `Role.Checkbox`-style state semantics, `onLongClickLabel = "Select"`, and a visible "Select" entry in the overflow so selection is discoverable.
- Size: S.

### AU-020 P2 Paste dialog can overflow a short screen and hide its buttons
- Where: LibraryScreen.kt:283-291 (11 rows of 40 dp in a Column with no scroll inside AlertDialog).
- Scenario: in landscape or on a small phone the rows total ~440 dp plus title and buttons; Paste and Cancel fall off the bottom. Back still dismisses, but Paste is unreachable. Also the label text is not tappable (only the 16 dp box toggles) so the touch target is the 40 dp box alone.
- Fix: `verticalScroll(rememberScrollState())` on the Column; make the whole row toggleable.
- Size: S.

### AU-021 P2 No way out of an empty filtered library except hunting for the filter controls
- Where: LibraryScreen.kt:192-194 (EmptyState with no `action`), 180 (filter bar is a separate toggle).
- Scenario: filter to "Rejects", nothing matches: "No photos match / Change or clear the filter to see more." The filter bar may be closed, and there is no Clear button; the user must reopen it and reset up to four groups chip by chip. The two chip groups on the second row also read "Any flag" and "All" side by side with no group labels (LibraryScreen.kt:249-252).
- Fix: add a "Clear filters" action to the empty state and an overflow "Clear filters" next to the filter icon; add small labels (Flag, Edited) to the chip rows.
- Size: S.

### AU-022 P2 First launch and large imports show "Nothing here yet" while work is in progress
- Where: LibraryScreen.kt:192-194, LibraryViewModel.kt:101-111, DeviceScanner.kt:21-62.
- Scenario: right after granting access, the MediaStore scan runs for a second or more on a big camera roll. The grid area says "Nothing here yet / Tap + to import photos or a folder", which is wrong and suggests the app found nothing. `progress.running` only exists once rows are inserted.
- Fix: add a `scanning` StateFlow around `scanDevice`/`scan`, show LocalLoader plus "Reading your photos" instead of the empty state while it is true and the list is empty.
- Size: S.

### AU-023 P2 Adding a big folder shows nothing, with no progress, until the whole tree is listed
- Where: core/data/Indexer.kt:40 (`walk` collects every Doc first), 54-55 (rows inserted after), 126-148 (one query per directory, single thread). The class comment says rows are added "straight away".
- Scenario: pick an SD card folder with 20,000 files in nested directories: listing takes a minute or more; the grid stays empty (see AU-022) with no progress and no cancel. Also `rescanFolder` runs on every launch with the same cost.
- Fix: insert rows per directory as they are found, publish `IndexProgress` during the walk, and allow cancel (scope the scan job in the VM).
- Size: M.

### AU-024 P2 Imported files silently hit Android's persisted permission cap
- Where: core/data/DeviceScanner.kt:70 (`takePersistableUriPermission` inside runCatching), LibraryScreen add menu "Import from files".
- Scenario: import hundreds of RAWs in several batches. Android keeps at most 512 persisted URI grants (128 on older builds) and evicts the oldest. Evicted files stop opening after a restart and the failure is swallowed. The photo rows remain, with blank thumbnails and failing previews.
- Fix: detect the cap (count `contentResolver.persistedUriPermissions`), warn the user, and prefer the folder route or file paths via All files access for bulk work.
- Size: M.

### AU-025 P2 No way to remove a source, and only the last folder is ever rescanned
- Where: LibraryViewModel.kt:61, 168-177 (`recentFolders` is written but never read by UI), 109 (only `prefs "folder"` is rescanned at start), 69-81 (sources are built from DB rows).
- Scenario: add three folders; the source menu lists all three forever. There is no Remove or Refresh. At next launch only the most recently added folder is rescanned, so changes in the other two (new shots, deleted files) never show. A revoked folder permission leaves a dead entry.
- Fix: rescan all saved folders (bounded concurrency), add "Refresh" and "Remove from library" in the source menu (delete rows only; never touch files).
- Size: M.

### AU-026 P3 Date headers use US order, not Australian
- Where: LibraryScreen.kt:351 (`"MMMM d, yyyy"`, Locale.ENGLISH). Loupe info uses a different pattern at LoupeScreen.kt:262.
- Scenario: headings read "October 3, 2026". House style is Australian English; expected "3 October 2026". The two screens also disagree.
- Fix: `"d MMMM yyyy"` with `Locale("en", "AU")` in both places.
- Size: S.

### AU-027 P3 "1 photos" and friends
- Where: LibraryScreen.kt:150, LibraryViewModel.kt:161, 204, 210, ExportService.kt:45 ("$ok photos saved"), MainActivity toast strings.
- Scenario: one photo reads "1 photos", "Pasted onto 1 photos", "1 photos saved".
- Fix: a small `plural(n, "photo")` helper (core/ui) used everywhere.
- Size: S.

### AU-028 P3 Thumbnail badges: unreadable on bright photos, collide at 6 columns, no failure glyph
- Where: LibraryScreen.kt:306-312 (stars and flag icons are bare white, no scrim), 309 (colour dot at end = 24 dp), 312.
- Scenario: white star text and flag on a sky or snow shot disappear; spec 7.21 wants dark translucent badges. At 6 columns on a 360 dp phone a tile is about 58 dp wide: five stars (about 48 dp) collide with the label dot at x 27 to 36. A tile whose thumbnail fails stays a flat grey square forever with no hint.
- Fix: put stars, flag and edited icons on a 2 dp radius #B3000000 chip like the RAW badge; shift the dot to the top; show a small broken-image glyph when `obtain` returns null.
- Size: S.

### AU-029 P3 Thumbnail size can only be changed by pinching; column count is not width aware
- Where: LibraryScreen.kt:197-200 (`GridCells.Fixed`), 321-341, 172-175 (sort menu has no check mark).
- Scenario: TalkBack or one handed users cannot change density. In landscape, 5 columns make tiles huge; spec 7.21 says 4 to 6 depending on width. The sort menu does not show which order is active.
- Fix: `GridCells.Adaptive(minSize)` driven by the pinch value, an overflow option for density, and a check icon on the current sort.
- Size: M.

### AU-030 P3 "Import from files" jumps to the Imported source even when nothing was added
- Where: LibraryViewModel.kt:158-165, MainActivity.kt:187 (picker uses `*/*`).
- Scenario: the user picks files that are duplicates or not photos: toast "Nothing new to import" (if visible, see AU-001) and the view switches to an empty "Imported files" source. The message cannot tell duplicates from unsupported types.
- Fix: only switch source when `n > 0`; report "N skipped (not photos)" separately.
- Size: S.

### AU-031 P3 Bulk actions: ambiguous "Sync from first", no undo, "Add to export queue" styled as selected
- Where: LibraryScreen.kt:264, 273, 265-270.
- Scenario: "Sync from first" uses the topmost selected tile in grid order, not the first one tapped, with no explanation. Stars, flags, labels and Paste apply instantly to hundreds of photos with no undo or confirmation. The first chip is drawn selected (#303030) although it is an action.
- Fix: rename to "Sync from top photo" (or let the user pick the source), add an Undo action to the confirmation toast, render the export chip as a normal action.
- Size: M.

### AU-032 P3 Source picker: no menu semantics, long album names clipped
- Where: LibraryScreen.kt:144 (clickable Row with no role or state), 157 (menu item text next to a count, row fixed at 40 dp).
- Scenario: TalkBack reads "Camera roll, 1204 photos" with no hint that it opens a menu; a long album name wraps to two lines and is clipped in the 40 dp row.
- Fix: `Role.DropdownList` plus `stateDescription`; single line with ellipsis and right aligned count.
- Size: S.

---------------------------------------------------------------------------
## C. Viewer (feature/loupe)

### AU-033 P3 Zoomed pan swallows the swipe to the next photo; pan limits ignore letterboxing
- Where: LoupeScreen.kt:224-235.
- Scenario: when zoomed, one finger always pans and is consumed, so the pager cannot take over even at the image edge; the user must double tap back to fit first. Limits use the full view size, so a wide photo can be dragged until its edge is well inside the screen.
- Fix: compute bounds from the fitted image rect, and let overscroll at an edge fall through to the pager.
- Size: M.

### AU-034 P3 Histogram is built on the main thread and never updates
- Where: LoupeScreen.kt:255 (`remember(preview)` runs a hardware-bitmap readback and scale on the UI thread), 157 (`previews.peek` is not state).
- Scenario: opening Info on a 2048 px preview copies about 12 MB and scales it during composition (a dropped frame or two). If the preview was not loaded yet, the histogram stays blank until something else recomposes.
- Fix: compute in `produceState` on Dispatchers.Default, keyed on the page's loaded preview.
- Size: S.

### AU-035 P3 Info sheet content gaps
- Where: LoupeScreen.kt:258 (width and height shown even when 0, size `/1024/1024` gives "0 MB" under 1 MB), 261 (empty EXIF line when not indexed), 263 (`fillMaxWidth().size(width = 280.dp)` fixed), 264.
- Scenario: a not yet indexed RAW shows "0 x 0 px  0 MB" and a blank line; on a 320 dp screen the histogram is clipped. The only close affordance is a swipe (no button, not reachable by TalkBack except via the Info toggle).
- Fix: hide unknown values, show KB under 1 MB, use `fillMaxWidth()` only, add a close icon.
- Size: S.

### AU-036 P3 Prefetch window comment is wrong, and the window is memory heavy
- Where: LoupeScreen.kt:95-96 (comment says three ahead and two behind, code is four ahead and three behind), 117 (`beyondViewportPageCount = 2`), 104-107 (every photo dwelled on for 1.5 s starts a full RAW decode).
- Scenario: eight 2048 px previews (about 12 MB each) plus five live pages sit in a cache capped at maxMemory/4; browsing a card of RAWs also starts full-resolution decodes for each photo paused on, competing with the editor's own memory.
- Fix: match comment and code, cap RawPrefetch to the current photo only until the user taps Edit, test peak memory on the phone.
- Size: S.

### AU-037 P3 "Add to export queue" uses the Share icon
- Where: LoupeScreen.kt:147.
- Scenario: the share glyph suggests the system share sheet; the tap silently queues an export (and its toast is hidden, AU-001). The user expects a share sheet and finds nothing.
- Fix: use the DOWNLOAD or QUEUE icon, and keep Share for the actual share action.
- Size: S.

### AU-038 P2 Viewer bar: star buttons unlabeled, toggles do not announce state
- Where: LoupeScreen.kt:137-139 (the five star Boxes have no semantics), 143-147, 163-167.
- Scenario: TalkBack reads the star row as unlabeled buttons; Pick and Reject read "Pick" whether on or off.
- Fix: `contentDescription = "$i stars"` plus selected state; add `stateDescription` ("on"/"off") to BarIcon.
- Size: S.

---------------------------------------------------------------------------
## D. Export queue and service

### AU-039 P2 Share gives no progress and fails silently
- Where: app/.../ExportUi.kt:38-47.
- Scenario: tap Share: the dialog closes at once, then a full size render (possibly with AI denoise) runs for seconds with no indicator. If it throws or returns null the error is only logged; nothing happens, and the user taps Share again. The coroutine also holds the Activity `context`, so a rotation during the render leaks the old Activity until it ends.
- Fix: keep the dialog open with a spinner (or show a toast/Snackbar), report failure ("Could not prepare the photo"), use `applicationContext` for the chooser.
- Size: M.

### AU-040 P2 A job left in "running" after the app or service dies cannot be cleared
- Where: QueueScreen.kt:77-82 (status 1 offers only Stop), MainActivity.kt:201 (Stop calls `cancelCurrent()` which only sets a flag), core/data/Db.kt:80 (`resetRunning` is called only when a new service run begins).
- Scenario: the process is killed mid export. Next launch, the queue shows "Saving 40%" and a badge of 1 on the Queue tab forever. Stop does nothing (nothing is running), Remove is not offered, Clear finished skips it. It only changes when another export is queued (and then re-exports that stale job).
- Fix: on app start call `resetRunning()` when no service run is active; offer Remove/Retry for status 1 when `ExportService` is not running.
- Size: S.

### AU-041 P2 Failed exports are silent when the app is in the background
- Where: ExportService.kt:44-45 (completion notification only when `ok > 0`), 55-60 (no contentIntent on either notification).
- Scenario: a 50 photo batch fails (full disk, revoked folder): no notification at all. A partial failure says "40 photos saved" with no mention of the 10 that failed. Tapping either notification does nothing instead of opening the Queue.
- Fix: always post a finish notification ("38 saved, 12 failed"), add a content intent to the Queue tab, and say "1 photo" correctly (AU-027).
- Size: S.

### AU-042 P2 Queue rows are dead ends, in reverse order, and Settings scope is unclear
- Where: QueueScreen.kt:73-82 (status 2 is just "Saved"), core/data/Db.kt:71 (`ORDER BY id DESC`) vs `nextWaiting ... ASC` at 73, QueueScreen.kt:50, ExportUi.kt:26-27.
- Scenario: after export finishes the user wonders where the files went: no path, no Open, no Share (outputUri is stored but unused). The list shows the newest job at the top while jobs run oldest first, so the running row is at the bottom. The queue's "Settings" button changes only future jobs (settings are copied into each job at enqueue), which is not said anywhere.
- Fix: show "Saved to Pictures/Rawline" (or folder name) and open on tap, sort active jobs oldest first, add one line under Settings: "Applies to photos you add next".
- Size: M.

### AU-043 P2 Export destination cannot be reset, and the label overflows
- Where: feature/export/ExportSheet.kt:92, app/.../ExportUi.kt:28-33.
- Scenario: once a custom folder is picked there is no control to return to Pictures/Rawline; the only way is clearing app data. The default label "Pictures/Rawline (tap to choose a folder)" sits in a 40 dp fixed-height button inside a dialog about 280 dp wide and clips. Custom long edge below 64 is silently ignored (ExportSheet.kt:69).
- Fix: add a "Use Pictures/Rawline" text button when `destination != null`, shorten the label, show an inline error for values under 64.
- Size: S.

### AU-044 P3 Raw exception text reaches the user; `{n}` restarts every service run
- Where: ExportRunner.kt:77 (`e.message ?: simpleName` stored and shown in the Queue), 62; 151 (`{n}` is the job index within one `processQueue` run); LibraryViewModel.kt:224-229.
- Scenario: a revoked folder shows text like "Failed to create document" or a class name in red; restore errors show JSON parser text. `{n}` gives 001 again for the next batch, so a pattern such as `{name}_{n}` can collide with earlier output.
- Fix: map known failures to plain sentences ("The save folder is no longer available") and keep detail in the log; use a persistent counter or job id for `{n}`.
- Size: S.

### AU-045 P3 Service lifecycle loose ends
- Where: ExportService.kt:19 (ACTION_CANCEL returns without stopSelf when nothing is running), 46 (`stopSelf(startId)` of the first command only), no `onTimeout` override (targetSdk 37; data sync services have a time limit from Android 15).
- Scenario: a stale Cancel tap starts an idle service instance that lingers; a second start command means `stopSelf(oldId)` does not stop it; a very long queue could hit the foreground timeout with no handler.
- Fix: `stopSelf()` on the cancel path when idle, track the latest startId, implement `onTimeout` to stop cleanly and leave jobs waiting.
- Size: S.

### AU-046 P3 Share render and queue render can run at once
- Where: ExportRunner.kt:85-92 (Share runs on `Dispatchers.IO` separately from the service thread, `cancelled` is shared and reset at 88).
- Scenario: two full resolution engines and two decodes at the same time double peak memory; a Stop tap on the queue that lands just before a Share can be erased by the Share's reset of `cancelled`.
- Fix: serialise renders with one mutex; give each render its own cancel token.
- Size: M.

### AU-047 P3 Fallback for the All files settings page can crash
- Where: MainActivity.kt:190 (`runCatching { startActivity(app page) }.onFailure { startActivity(general page) }`).
- Scenario: on a build where neither settings activity exists the second `startActivity` throws ActivityNotFoundException outside any guard.
- Fix: wrap the fallback too and toast "Open Settings, Apps, Special access, All files access".
- Size: S.

---------------------------------------------------------------------------
## E. core/ui, accessibility, spec drift

### AU-048 P2 Many touch targets are under 48 dp (known, AUDIT #4)
- Where: core/ui/Controls.kt:291 (ChipButton min height 34 dp, used for every filter, selection, export chip), 383 (LrTextButton 40 dp), 340 (LrIconButton 44 dp), 398 (LrCheckbox 40 dp); LoupeScreen.kt:148 (Edit 40 dp), 137/164 (44 dp); LrDim.touch (48) exists and TouchChip (Controls.kt:516) already uses the right pattern.
- Scenario: rating chips in the selection bar and filter rows are 34 dp tall and sit 6 dp apart; mis-taps on "Reject" instead of "Pick" are likely with one thumb. Queue Cancel/Remove/Retry buttons are 40 dp and adjacent.
- Fix: give ChipButton and LrTextButton the TouchChip approach (48 dp hit box around a 34 dp visual), 48 dp for icon button and checkbox boxes. Spec says 44 minimum, AUDIT says 48; pick 48.
- Size: M (layout shifts, check on the phone).

### AU-049 P2 Fixed heights clip text when the system font is larger
- Where: LibraryScreen.kt:138 (header fixed at 48 dp with 16 sp title plus 11 sp subtitle), QueueScreen.kt:45, MainActivity.kt:269 (bottom nav 56 dp), Controls.kt:275, 357, 383 (buttons 40 dp).
- Scenario: at font scale 1.5 the library header needs about 55 dp, so the title and "N photos" subtitle overlap or clip; at 2.0 the bottom nav labels clip. Text is sp, boxes are dp.
- Fix: `heightIn(min = ...)` instead of `height(...)` for bars and buttons; test at 1.3 and 2.0.
- Size: M.

### AU-050 P2 Semantics gaps: toggles, checkboxes, tabs, chips, nav items
- Where: Controls.kt:320 (LrToggle sets contentDescription to "On"/"Off", replacing any name), 398 (LrCheckbox: no role, no state), 258 and 520 (LrTabs, TouchChip use `tapOrDoubleTap`, which is `pointerInput` only and exposes no click action), 291 (ChipButton: no selected state), 504-505 (PanelHeader says "Double tap to reset" but exposes no action), MainActivity.kt:282 (NavItem: no selected state, badge count not spoken).
- Scenario: in Settings TalkBack says "On, double tap to activate" with no setting name; filter chips do not say which one is selected; tabs and TouchChips cannot be operated through Switch Access or Voice Access because they offer no click action; the Queue badge ("3") is not announced. (Whether TalkBack falls back to a touch at the node's centre needs a check on the phone.)
- Fix: use `toggleable`/`selectable` with proper roles, `onClick` semantics inside `tapOrDoubleTap` (`semantics { onClick { ... } }`), `stateDescription` for chips and nav, count in the nav description.
- Size: M.

### AU-051 P2 Typing a slider value is awkward and can silently fail
- Where: Controls.kt:218-236, 224-232.
- Scenario: (a) `KeyboardType.Decimal` has no minus key on many keyboards, so negative values (exposure, shadows, temperature) cannot be typed (check on your keyboard). (b) For sliders with a `format` (Temperature shows "5500 K", Panels.kt:258) the field is pre-filled with text such as "5500 K"; `toFloatOrNull()` fails on edit and "Set" closes with no change and no error. (c) The field is not focused, so the keyboard does not open until a second tap; the keyboard's Done key does nothing (no ImeAction or keyboard action). (d) A decimal comma keyboard would also fail to parse.
- Fix: pre-fill with the raw numeric value, parse leniently (strip units, accept comma), `FocusRequester` on open, `ImeAction.Done` that confirms, show an inline error under the field, offer a +/- toggle for negatives.
- Size: M.

### AU-052 P3 The default ValueFeedback is process wide and leaks stale text
- Where: Controls.kt:98 (`compositionLocalOf { ValueFeedback() }`), 103 (the clearing effect lives only inside the pill), ExportSheet.kt:60 (Quality slider in a dialog with no pill).
- Scenario: drag Quality in the export dialog: `text` is set on the shared default and never cleared. Any later screen that reads the default shows or retains "Quality: 92". Also `feedback.held || true` (Controls.kt:105) is dead logic.
- Fix: make the default throw/inert, or provide a `ValueFeedback` in the dialog; remove the `|| true`.
- Size: S.

### AU-053 P3 `tapOrDoubleTap` counts a long press as a tap
- Where: core/ui/Gestures.kt:30-34.
- Scenario: `waitForUpOrCancellation()` has no long-press timeout; hold a tab or chip for two seconds and release: it activates (and a quick second touch within 300 ms fires the double action). Standard Android lets a long hold cancel the tap.
- Fix: enforce `viewConfiguration.longPressTimeoutMillis` and ignore the release after it.
- Size: S.

### AU-054 P3 Contrast: selected chip state and white on the accent
- Where: Controls.kt:289-295 (selected is #303030 on #1C1C1C, about 1.3:1, plus a 13% white border), LrButton/PrimaryButton (white 14 sp on #437EE4 is about 3.9:1).
- Scenario: in the filter bar the chosen rating or flag is barely distinguishable, especially in sunlight; the filter icon is the only strong cue. The accent button text is under 4.5:1 for 14 sp medium. Both colours are locked by the spec, so this is a flag for a decision, not a quick edit.
- Fix: add a non-colour cue (check icon or a stronger border) to selected chips; consider a darker button fill or bold text.
- Size: S to M (design decision).

### AU-055 P3 Spec drift list
- Where: ExportSheet.kt:51 and LibraryScreen.kt:281 (Material AlertDialog: 8 dp corners, wide; spec 13.3 says 6 dp, width min(320, viewport - 32)), ExportSheet.kt:69, 87, 89 (Material OutlinedTextField is 56 dp tall with floating label; spec 7.22: 40 dp, 4 dp radius, not 52 px style), ExportSheet.kt (nine option groups in a dialog; spec 10.4/13.5 wants a full subview for a complex export flow), LrDim.tabBar 48 dp vs spec 44, EditorHost.kt:131 (overlay 72% black leaves 28% of the canvas; spec 12.3 says 35 to 45% visible), LrDim.touch 48 vs spec 44, SettingsScreen.kt:67-72 (filled blue buttons; spec asks for secondary outlined buttons outside primary actions), SettingsScreen.kt:45 (text "Back" instead of a 44 dp back icon).
- Fix: one pass to align modal, input and save overlay tokens; decide whether export becomes a subview.
- Size: M.

---------------------------------------------------------------------------
## F. core/cache, core/data, app wiring

### AU-056 P1 A disk write error while scrolling the grid crashes the app
- Where: core/cache/ThumbStore.kt:45-48 (`make` is inside `runCatching`, but `save(p.id, b)` is not), 60-62 (`FileOutputStream` can throw IOException), LibraryScreen.kt:300 (`produceState` calls `obtain`).
- Scenario: the cache partition fills (large exports, low storage). `save` throws inside the tile's produceState coroutine, which propagates to the composition scope and kills the process on the next thumbnail. Indexer.kt:93 already guards this; the grid path does not.
- Fix: wrap `save` in `runCatching` (keep the decoded bitmap in the LRU anyway) and add `catch (e: OutOfMemoryError)` around decode.
- Size: S.

### AU-057 P2 Thumbnail files are written non-atomically
- Where: ThumbStore.kt:24-33, 60-62; the indexer (Indexer.kt:117) and tile requests (ThumbStore.kt:43-47) can both write `thumbs/<id>.jpg`.
- Scenario: the indexer and a visible tile process the same RAW at the same time; one reads the half written file (decodes partially, grey bottom) and `lru.put` caches it for the session. A kill mid write leaves a truncated file that exists, so `load` returns it forever.
- Fix: write to `<id>.tmp` then `renameTo`; treat `decodeFile == null` as missing and delete; serialise per id.
- Size: S.

### AU-058 P2 Thumbnail disk cache is unbounded and includes thumbnails of the camera roll
- Where: ThumbStore.kt:44-48 (every obtained thumbnail is saved, including `loadThumbnail` results for ordinary images), no trimming anywhere.
- Scenario: a 40,000 photo library at about 25 KB each is around 1 GB in cacheDir; deleted photos' thumbnails are never removed; Settings has no "Clear cache" or storage figure. Android only clears cache under storage pressure.
- Fix: do not persist system-provided thumbnails (the system already caches them), cap the directory (LRU by last modified, for example 300 MB), prune ids missing from the DB, add Settings "Clear thumbnails".
- Size: M.

### AU-059 P2 The export job query is re-created on every recomposition of the root
- Where: MainActivity.kt:104 (`graph.db.exports().observe().collectAsStateWithLifecycle(emptyList())` is not remembered).
- Scenario: `observe()` returns a new Flow each time, so collection restarts and re-queries the table whenever `RawlineRoot` recomposes (route change, toast change, every job progress write, which happens every 400 ms during an export). Each progress write therefore costs a second query and the root recomposes with it.
- Fix: `val jobsFlow = remember { graph.db.exports().observe() }` (or expose it from the ViewModel as a StateFlow).
- Size: S.

### AU-060 P2 Whole table is re-read about four times a second while indexing, and the grid rows are built on the main thread
- Where: LibraryViewModel.kt:84-90 (`SELECT *` for all device rows on every table change, throttled only by `delay(250)`), LibraryScreen.kt:196 and 349-364 (`gridRows` runs in composition: `Instant` to local date for every photo, two passes, all allocations).
- Scenario: first index of a 20,000 photo camera roll: each `markIndexed` invalidates the table, so about 20,000 rows are re-read, mapped, filtered, sorted and then regrouped on the UI thread 4 times a second. Scrolling stutters during the first minutes, battery climbs. The same happens on every rating tap.
- Fix: compute `gridRows` in the VM flow on Dispatchers.Default (combine with filter), publish indexing updates less often (every N rows or 1 s), move to a Paging/id-window query for very large libraries, store a precomputed `dayKey` column.
- Size: L (M for the throttle and off-main move alone).

### AU-061 P2 Every scan runs one query per photo, and our own exports trigger a rescan
- Where: core/data/Catalog.kt:119-133 (`reapply`: loads all photos and calls `edits.get(k)` once per photo, line 127), DeviceScanner.kt:59, LibraryViewModel.kt:138-141 (ContentObserver fires on every MediaStore change including Rawline's own IS_PENDING inserts).
- Scenario: with 50,000 photos, each device rescan issues about 50,000 point queries (seconds to tens of seconds of DB time). Each exported photo causes several observer callbacks; `scanJob?.cancel()` restarts the debounce each time, so a 100 photo export keeps rescanning and cancelling (also cancelling a running index mid-way).
- Fix: one `SELECT key FROM edits` into a set, one IN-chunked meta query; ignore observer events for URIs under Pictures/Rawline or while the export service runs; debounce longer after bursts.
- Size: M.

### AU-062 P1 Rating, flag and label writes can run blocking XMP file I/O on the main thread
- Where: LibraryViewModel.kt:191-193 (`viewModelScope.launch { catalog.setRating(...) }` uses Dispatchers.Main), core/data/Catalog.kt:39 (loop calling `Xmp.write`: `openInputStream`, `openOutputStream`, `createDocument` per photo).
- Scenario: XMP sidecars switched on, user selects 300 photos in a SAF folder and taps a star rating. Each photo does several binder calls to the document provider on the UI thread, so the app freezes (ANR risk on slow SD cards).
- Fix: `viewModelScope.launch(Dispatchers.IO)` for rate/flag/label (and `withContext(IO)` inside `saveMeta`), update the UI state first.
- Size: S.

### AU-063 P2 "Write XMP sidecars" does nothing for the camera roll and imported files
- Where: Catalog.kt:165-182 (`DocumentsContract.getDocumentId(doc)` on a MediaStore URI throws, caught silently; `Uri.parse(p.folderUri)` for "device:Camera" is not a tree), SettingsScreen.kt:62 ("Saves ratings next to your photos so they survive outside the app").
- Scenario: the default library source is the camera roll, so for most photos the switch silently writes nothing while the copy promises it does.
- Fix: say "folders you added" in the description, or write sidecars through All files access paths for device photos.
- Size: S (copy) or M (feature).

### AU-064 P2 FrameMonitor can keep posting frame callbacks forever
- Where: LibraryScreen.kt:128-130 (`LaunchedEffect(gridState.isScrollInProgress)`, no cleanup), core/cache/FrameMonitor.kt:23-36 (callback re-posts itself every frame while `running`).
- Scenario: flick the grid and tap a bottom nav tab while it is still flinging; the screen leaves composition, the effect is cancelled without calling `stop`, and `running` stays true. The Choreographer then wakes the app every vsync (60 to 120 times a second) in the background of other screens until the library is scrolled again. Battery and thermals.
- Fix: `DisposableEffect(Unit) { onDispose { FrameMonitor.stop("grid") } }` or stop it in a `finally`; consider stopping automatically when the app is paused.
- Size: S.

### AU-065 P3 In-flight decodes cannot be cancelled and can crowd out the page the user is on
- Where: core/cache/PreviewCache.kt:23, 33, 66-72, ThumbStore.kt:41-48.
- Scenario: `prefetch` cancels stale `async` jobs, but `decode` and thumbnail `make` are blocking calls, so a started decode runs to the end. Three slots (`limitedParallelism(3)`) can all be busy with photos the user already swiped past, delaying the current page's decode by one to two RAW parse and decode times.
- Fix: check `isActive` between read and decode steps, or order the queue by recency; at least give the current page a dedicated slot.
- Size: M.

### AU-066 P3 One failed index is permanent
- Where: core/data/Db.kt:133 (`markFailed` sets `indexed = 1`), Indexer.kt:93, 116.
- Scenario: a transient failure (file busy, card momentarily unavailable, low memory) marks the RAW as indexed with no EXIF, no preview offset and no dimensions; it never retries, so it sorts by file date and shows blank metadata in the viewer even after the file is fine.
- Fix: store a `failedAt` and retry after a day or on app update; show "Could not read metadata" in the info sheet.
- Size: S.

### AU-067 P3 Backup and restore feedback is wrong in small ways
- Where: LibraryViewModel.kt:222-230 (`openOutputStream(uri)?.use` returns null for a null stream yet reports "Backup saved"), core/data/Catalog.kt:89-90 (count adds every entry in the zip, not those applied, so "Restored 120 edits" can mean 0 applied), SettingsScreen.kt:70 and MainActivity.kt:129-131 (the message stays in the VM; opening Settings later shows a stale "Backup saved"). Restore has no confirmation. (Overwrite of newer ratings: known, AUDIT #1.)
- Fix: treat a null stream as failure, return the applied count, clear `message` after display and on leaving Settings, confirm restore with a one line dialog.
- Size: S.

### AU-068 P3 Settings screen polish
- Where: SettingsScreen.kt:43 (`safeDrawingPadding()` inside a Box that already has `statusBarsPadding()` and above a bottom bar with `navigationBarsPadding()`: extra bottom space), 82-86 (last crash shown in red forever, no Clear), 77-79 (gesture help is the only place long press to select is mentioned), 49-51 (no storage or cache information; see AU-058).
- Scenario: stale crash text keeps showing after it was reported; the user has no way to clear it. Bottom of the scroll has a gap equal to the nav bar height.
- Fix: add "Clear crash report", drop the duplicate bottom inset, show cache size with a Clear button.
- Size: S.

### AU-069 P3 Toast timing and duration
- Where: MainActivity.kt:106 (timer keyed on the text), 291-304; spec section 14 asks errors to stay longer.
- Scenario: a second identical toast ("Added to export queue" twice in 2 s) does not restart the timer, so the second one vanishes early; error toasts ("Backup failed: ...") use the same 2.5 s as success.
- Fix: key the effect on a counter, use 5 s or manual dismiss for errors, cap at three stacked.
- Size: S.

---------------------------------------------------------------------------
## Cross checks done with no defect found
- BackHandler priority in library and viewer is correct (selection and info close before navigating).
- Export service restart race and IS_PENDING handling (fixed in the earlier pass) read correctly.
- Edit save on pause/stop is flushed before rotation (EditorHost.kt:80-92); only the undo history is lost (AU-014).
- No em dashes or en dashes in any user-facing string in the audited modules; Australian spellings (Colour) are used in the strings read.
- Library grid keys (`h<label>` strings, Long ids) cannot collide while the list is date sorted.

## Not verified (needs the phone)
- AU-001 toast z-order, AU-017 bar icon colour in light mode, AU-049 clipping at 1.5x font, AU-050 TalkBack behaviour for pointerInput-only controls, AU-051 minus key on the phone's keyboard, AU-060 and AU-064 real cost. Use the phone's Copy report for any speed claim.

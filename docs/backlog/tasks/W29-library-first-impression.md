# W29 The library's first impression: a grid that does not move, and RAW photos first

Status at writing: main c270b1e (S1c merged). Entries: BK-497 (stable grid order), BK-498 (default RAW photos view with an obvious All photos chip and a What's New note), plus the capture-time read for the first screenful. Runs right after W16 (they share `LibraryViewModel`, `LibraryScreen` and `core/model/Library.kt`; W16 adds the saved filter that this task extends). The pure Kotlin in section 4 compiled with Kotlin 2.4.10 together with the real `core/model` sources and ran on the host JVM: all 23 core/model tests pass (the 12 that exist plus 11 new), and a 12th new test checks the new text against W27's copy rules (it needs W27's `CopyRules` in the tree; delete it if W27 has not merged). NOT compiled or run: the ViewModel, DAO, scanner and Compose edits in section 5, and anything on the phone.

## 1. What the code does today (read 6 Oct 2026)
- `DeviceScanner.scanDevice` inserts a row with `takenAt` = MediaStore DATE_TAKEN if above 0, else the file's modified time; MediaStore usually has no DATE_TAKEN for RW2. `Indexer.markIndexed` later overwrites `takenAt` with the EXIF capture time. `LibraryFilter.apply` sorts on `takenAt` (else `modified`). So a card copied by a file manager first appears in copy order and re-sorts, in batches, as indexing proceeds.
- `LibraryViewModel.init` sets `source = "device:*"` when nothing is saved: every image on the phone plus every RAW file, newest first. On a phone in daily use the RAW files sit below screenshots and chat images.
- `PreviewDecoder.readExifOnly(context, uri)` already reads EXIF without decoding a preview (used for plain images); the first screenful can use it.

## 2. Decisions (no questions left)
- D1 `LibraryFilter.rawOnly` (default false in the type, so every existing caller and test is unchanged). It is not counted in `isActive` (it is a view choice, not a filter Jai built, so the "Clear filters" badge does not light up).
- D2 First run default: `DefaultView.rawOnly(choice, rawCount)`: an explicit choice wins; with no choice it is RAW only when the phone has at least one RAW file, otherwise everything. Evaluated after a scan completes and when the source changes, never while the user is scrolling. A phone with no RAW files sees no chips and no note.
- D3 The chip row sits above the grid: "RAW photos" and "All photos", 48 dp targets, the active one selected, shown only when the source has at least one RAW. Tapping one is an explicit choice, stored as `viewChoice` ("raw" or "all") and never overridden again.
- D4 What's New: once, as a dismissible banner above the grid, when the new default is in effect and no choice was made: title "What's new", text "Rawline now opens on your RAW photos. Tap All photos to see everything on your phone.", button "Close". Closing or tapping either chip stores `whatsNewSeen = 1`.
- D5 Stable order: `OrderGate` between the sorted list and the grid. While the grid is on screen and busy (a finger down, scrolling, a selection) and for 1.5 s after, the displayed order keeps its relative order: deleted photos leave at once, new photos wait. After 1.5 s idle the sorted list is applied once. If the grid is not on screen (a photo is open) it applies at once. Pull to refresh and a source change show the sorted list at once. Data of each tile (rating, flag, edited badge) is always the latest; only the order is held.
- D6 Capture time before first display: in the scan, for the newest 300 fresh RAW rows (by modified), read EXIF capture time with `readExifOnly` four at a time before the rows are inserted; a failure keeps the file time. The remaining rows get theirs from the indexer as today and the gate hides the reorder while Jai is busy.
- D7 Counter: `OrderGate.resorts` goes to the Copy report as `grid_resort_count`, so the claim "the grid does not jump" has a number.
- D8 The saved filter from W16 does not store `rawOnly`: it is derived from `viewChoice` and the content, so a stored filter cannot contradict the chip.

## 3. Order of work
1. Apply `library.patch` and add `LibraryView.kt` and the tests (section 4); run `./gradlew :core:model:testDebugUnitTest`.
2. DAO and ViewModel edits (section 5.1 to 5.3); then the scanner (5.4); then the screen (5.5).
3. Strings: add the five strings of section 6 to the string resources W27 creates (or as literals if W27 has not merged; they already pass the copy rules).
4. Phone checks (section 7).

## 4. Kotlin (compiled and tested on the host)
### core/model patch (Library.kt)
```diff
--- a/core/model/src/main/kotlin/app/rawline/core/model/Library.kt
+++ b/core/model/src/main/kotlin/app/rawline/core/model/Library.kt
@@ -10,6 +10,8 @@
     val edited: EditedFilter = EditedFilter.ANY,
     val camera: String? = null,
     val sort: SortOrder = SortOrder.NEWEST,
+    /** BK-498: show RAW photos only (the first run default when the phone has any). A view choice, not a filter the user built, so it does not count in [isActive]. */
+    val rawOnly: Boolean = false,
 ) {
     val isActive get() = minRating > 0 || flag != FlagFilter.ANY || edited != EditedFilter.ANY || camera != null
 
@@ -18,7 +20,8 @@
             it.rating >= minRating &&
                 when (flag) { FlagFilter.ANY -> true; FlagFilter.PICK -> it.flag == 1; FlagFilter.REJECT -> it.flag == -1; FlagFilter.NONE -> it.flag == 0 } &&
                 when (edited) { EditedFilter.ANY -> true; EditedFilter.EDITED -> it.edited; EditedFilter.UNEDITED -> !it.edited } &&
-                (camera == null || it.camera == camera)
+                (camera == null || it.camera == camera) &&
+                (!rawOnly || it.kind == Kind.RAW)
         }
         return when (sort) {
             SortOrder.NEWEST -> f.sortedWith(compareByDescending<Photo> { if (it.takenAt > 0) it.takenAt else it.modified }.thenByDescending { it.id })
```
### LibraryView.kt (new, `core/model/src/main/kotlin/app/rawline/core/model/LibraryView.kt`)
```kotlin
package app.rawline.core.model

/** W29: the first impression of the library (BK-497 and BK-498). Pure, no Android. */

/** What the user chose with the chip. Stored as "raw" or "all"; null means no choice yet. */
enum class ViewChoice(val key: String) {
    RAW("raw"), ALL("all");
    companion object { fun parse(s: String?): ViewChoice? = values().firstOrNull { it.key == s } }
}

object DefaultView {
    /**
     * Whether the grid shows RAW photos only. An explicit choice always wins. With no choice yet the grid shows RAW photos when the phone has at least one RAW file,
     * and everything otherwise (a phone with no RAW files, or All files access not granted yet, keeps the normal view and the "RAW files hidden" hint of BK-450).
     * Called after a scan and when the source changes, never while the user is scrolling, so the view cannot flip under a thumb.
     */
    fun rawOnly(choice: ViewChoice?, rawCount: Int): Boolean = when (choice) { ViewChoice.RAW -> true; ViewChoice.ALL -> false; null -> rawCount > 0 }
}

/** The one time note after an update that changes the default view (BK-498). */
object WhatsNew {
    const val NOTE_VERSION = 1
    /** Shown once, only when the new default is actually in effect (RAW only and no explicit choice made before). */
    fun shouldShow(seenVersion: Int, rawOnlyByDefault: Boolean, explicitChoice: ViewChoice?): Boolean = seenVersion < NOTE_VERSION && rawOnlyByDefault && explicitChoice == null
}

/**
 * BK-497: the order the grid shows. The full sorted list changes while indexing reads capture times; the grid must not move under the user.
 * While the user is active (scrolling, a selection, a touch in the last [holdMs]) the displayed order keeps its relative order: removals apply at once, new photos wait.
 * When the user has been idle for [holdMs] the displayed list becomes the sorted list. If the grid is not on screen (a photo is open) the caller passes `active = false` and the update applies at once.
 */
class OrderGate(private val holdMs: Long = 1500) {
    private var displayed: List<Long> = emptyList()
    private var lastActive = Long.MIN_VALUE / 2
    /** How many times the relative order of photos already on screen changed. The Copy report prints this as grid_resort_count. */
    var resorts = 0
        private set

    fun update(sorted: List<Long>, active: Boolean, nowMs: Long): List<Long> {
        if (active) lastActive = nowMs
        if (displayed.isEmpty()) { displayed = sorted; return displayed }
        if (sorted == displayed) return displayed
        if (!active && nowMs - lastActive >= holdMs) { apply(sorted); return displayed }
        val keep = sorted.toHashSet()
        displayed = displayed.filter { it in keep }
        return displayed
    }

    /** Pull to refresh or a source change: show the sorted list now. */
    fun refresh(sorted: List<Long>): List<Long> { apply(sorted); return displayed }

    private fun apply(sorted: List<Long>) {
        val old = displayed.toHashSet(); val now = sorted.toHashSet()
        val before = displayed.filter { it in now }; val after = sorted.filter { it in old }
        if (before != after) resorts++
        displayed = sorted
    }
}
```
### LibraryViewTest.kt (new, `core/model/src/test/kotlin/app/rawline/core/model/LibraryViewTest.kt`)
```kotlin
package app.rawline.core.model

import org.junit.Assert.*
import org.junit.Test

class LibraryViewTest {
    private fun photo(id: Long, kind: Kind) = Photo(id, "f", "u$id", "p$id.${if (kind == Kind.RAW) "rw2" else "jpg"}", 1, id, kind, true)

    @Test fun rawOnlyKeepsRawAndKeepsTheOrder() {
        val l = listOf(photo(1, Kind.RAW), photo(2, Kind.IMAGE), photo(3, Kind.RAW), photo(4, Kind.IMAGE))
        assertEquals(listOf(3L, 1L), LibraryFilter(rawOnly = true).apply(l).map { it.id })
        assertEquals(listOf(4L, 3L, 2L, 1L), LibraryFilter().apply(l).map { it.id })
        assertEquals(listOf(1L, 3L), LibraryFilter(rawOnly = true, sort = SortOrder.OLDEST).apply(l).map { it.id })
    }
    @Test fun theRawViewIsNotCountedAsABuiltFilter() {
        assertFalse(LibraryFilter(rawOnly = true).isActive); assertTrue(LibraryFilter(rawOnly = true, minRating = 1).isActive)
    }
    @Test fun defaultViewFollowsTheChoiceThenTheContent() {
        assertTrue(DefaultView.rawOnly(null, 1)); assertFalse(DefaultView.rawOnly(null, 0))
        assertTrue(DefaultView.rawOnly(ViewChoice.RAW, 0)); assertFalse(DefaultView.rawOnly(ViewChoice.ALL, 500))
        assertEquals(ViewChoice.RAW, ViewChoice.parse("raw")); assertNull(ViewChoice.parse("x")); assertNull(ViewChoice.parse(null))
    }
    @Test fun whatsNewShowsOnceAndOnlyWhenTheNewDefaultAppliesWithoutAChoice() {
        assertTrue(WhatsNew.shouldShow(0, true, null))
        assertFalse(WhatsNew.shouldShow(WhatsNew.NOTE_VERSION, true, null))     // seen
        assertFalse(WhatsNew.shouldShow(0, false, null))                         // no RAW on the phone: nothing changed for this user
        assertFalse(WhatsNew.shouldShow(0, true, ViewChoice.ALL))                // already chose
    }

    // ---- OrderGate
    @Test fun firstDisplayIsTheFirstList() { val g = OrderGate(); assertEquals(listOf(3L, 2L, 1L), g.update(listOf(3, 2, 1), false, 0)); assertEquals(0, g.resorts) }

    @Test fun indexingThatReordersEverythingDoesNotMoveTheGridWhileTheUserScrolls() {
        val g = OrderGate(1500); val n = 300
        val copyOrder = (n downTo 1).map { it.toLong() }                       // copy order: file times
        val first = g.update(copyOrder, active = true, nowMs = 0)
        var t = 0L
        val shooting = ArrayList(copyOrder)                                    // the indexer reads capture times in 30 batches over 3 s: rows move
        for (batch in 1..30) {
            t += 100
            val moved = shooting.shuffled(java.util.Random(batch.toLong())).take(10)
            shooting.removeAll(moved.toSet()); shooting.addAll(0, moved)
            assertEquals("batch $batch", first, g.update(ArrayList(shooting), active = true, nowMs = t))
        }
        assertEquals(0, g.resorts)
        assertEquals(first, g.update(ArrayList(shooting), active = false, nowMs = t + 1000))      // idle for 1 s: still held
        assertEquals(shooting, g.update(ArrayList(shooting), active = false, nowMs = t + 1500))   // idle for 1.5 s: one change, applied once
        assertEquals(1, g.resorts)
        assertEquals(shooting, g.update(ArrayList(shooting), active = false, nowMs = t + 9000)); assertEquals(1, g.resorts)
    }
    @Test fun removalsApplyAtOnceNewPhotosWait() {
        val g = OrderGate(1500); g.update(listOf(5, 4, 3, 2, 1), true, 0)
        assertEquals(listOf(5L, 4L, 2L, 1L), g.update(listOf(6, 5, 4, 2, 1), true, 100))     // 3 was deleted, 6 is new and waits
        assertEquals(listOf(6L, 5L, 4L, 2L, 1L), g.update(listOf(6, 5, 4, 2, 1), false, 2000))
        assertEquals(0, g.resorts)                                                           // a new photo on top is not a reorder
    }
    @Test fun aSelectionOrATouchKeepsTheHoldRunning() {
        val g = OrderGate(1500); g.update(listOf(3, 2, 1), true, 0)
        assertEquals(listOf(3L, 2L, 1L), g.update(listOf(1, 2, 3), true, 1000))
        assertEquals(listOf(3L, 2L, 1L), g.update(listOf(1, 2, 3), false, 2000))             // 1000 ms since the last touch
        assertEquals(listOf(1L, 2L, 3L), g.update(listOf(1, 2, 3), false, 2500))
    }
    @Test fun whenTheGridIsNotOnScreenTheChangeAppliesAtOnce() {
        val g = OrderGate(1500); g.update(listOf(3, 2, 1), true, 0)
        assertEquals(listOf(1L, 2L, 3L), g.update(listOf(1, 2, 3), false, 1_000_000))        // a photo has been open for minutes
    }
    @Test fun refreshAndSourceChangeShowTheSortedListNow() {
        val g = OrderGate(); g.update(listOf(3, 2, 1), true, 0)
        assertEquals(listOf(1L, 2L, 3L), g.refresh(listOf(1, 2, 3))); assertEquals(1, g.resorts)
        assertEquals(listOf(9L, 8L), g.refresh(listOf(9, 8)))                                // an unrelated list: nothing in common, not counted as a reorder
        assertEquals(1, g.resorts)
    }
    @Test fun anEmptyDisplayedListTakesTheNewListAtOnce() { val g = OrderGate(); assertTrue(g.update(emptyList(), true, 0).isEmpty()); assertEquals(listOf(1L), g.update(listOf(1), true, 10)) }
}
```
### W29CopyTest.kt (needs W27)
```kotlin
package app.rawline.core.model

import app.rawline.core.model.copy.CopyRules
import app.rawline.core.model.copy.Kind as CopyKind
import org.junit.Assert.*
import org.junit.Test

/** The text this task adds follows docs/COPY.md (W27). Runs once W27's CopyRules is in the tree; delete this file's import lines if W27 has not merged. */
class W29CopyTest {
    @Test fun newStringsFollowTheCopyRules() {
        val chips = listOf("RAW photos", "All photos")
        val titles = listOf("What's new")
        val body = listOf("Rawline now opens on your RAW photos. Tap All photos to see everything on your phone.", "Showing RAW photos only.", "Sorted by the time you took the photo.")
        val bad = ArrayList<String>()
        for (t in chips + titles) CopyRules.check(t, CopyKind.TITLE).forEach { bad += "${it.rule}: $t" }
        for (t in body) CopyRules.check(t, CopyKind.TEXT).forEach { bad += "${it.rule}: $t" }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
```

## 5. Edits (not compiled)
1. DAO: `@Query("SELECT COUNT(*) FROM photos WHERE isRaw = 1") fun rawCount(): Flow<Int>`. (Room schema unchanged.)
2. `LibraryViewModel` state:
```kotlin
private val viewChoice = MutableStateFlow(ViewChoice.parse(graph.prefs.getString("viewChoice", null)))
val rawCount = dao.rawCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
private var viewEvaluated = false
/** After a scan completes and when the source changes (never while scrolling). */
fun evaluateView() {
    val raw = DefaultView.rawOnly(viewChoice.value, rawCount.value)
    filter.value = filter.value.copy(rawOnly = raw); viewEvaluated = true
}
fun setViewChoice(c: ViewChoice) { graph.prefs.edit().putString("viewChoice", c.key).putInt("whatsNewSeen", WhatsNew.NOTE_VERSION).apply(); viewChoice.value = c; filter.value = filter.value.copy(rawOnly = c == ViewChoice.RAW) }
val whatsNew: StateFlow<Boolean> = combine(filter, viewChoice, whatsNewSeen) { f, c, seen -> WhatsNew.shouldShow(seen, f.rawOnly, c) }.stateIn(...)
fun dismissWhatsNew() { graph.prefs.edit().putInt("whatsNewSeen", WhatsNew.NOTE_VERSION).apply(); whatsNewSeen.value = WhatsNew.NOTE_VERSION }
```
Call `evaluateView()` from the end of `scanDevice` handling (once per scan, only if `!gridBusy.value` or not yet evaluated) and from `selectSource`. In `selectSource` the new filter is `LibraryFilter(sort = filter.value.sort)`, so evaluate right after it. Write `rawOnly` into nothing else (W16's `LibraryFilterJson.write` ignores it by design: `LibraryFilterJson.read(...)` then `.copy(rawOnly = ...)` in `evaluateView`).
3. Stable order in the ViewModel:
```kotlin
private val gate = OrderGate()
val gridVisible = MutableStateFlow(true)      // false while a photo or the editor is open
val gridBusy = MutableStateFlow(false)        // finger down, scrolling or a selection
private val ticker = flow { while (true) { emit(Unit); delay(500) } }
val photos: StateFlow<List<Photo>> = combine(sortedFiltered, gridBusy, gridVisible, ticker) { list, busy, visible, _ ->
    val ids = gate.update(list.map { it.id }, active = visible && busy, nowMs = android.os.SystemClock.elapsedRealtime())
    val byId = list.associateBy { it.id }
    ids.mapNotNull { byId[it] }              // latest data for every tile, held order
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
fun refreshOrder() { /* pull to refresh and selectSource */ gate.refresh(current sorted ids) }
val resortCount: Int get() = gate.resorts
```
`sortedFiltered` is what `photos` is today (the filtered, sorted list). Record `PerfLog.record("grid_resort_count", gate.resorts.toLong())` when `gate.resorts` changes (in the combine block).
4. `DeviceScanner.scanDevice`, before `fresh.chunked(300).forEach { dao.insertAll(it) }`:
```kotlin
val head = fresh.filter { it.isRaw }.sortedByDescending { it.modified }.take(300)
val t1 = System.nanoTime()
val times = head.chunked(4).flatMap { part -> coroutineScope { part.map { r -> async(Dispatchers.IO) { r.uri to runCatching { PreviewDecoder.readExifOnly(context, Uri.parse(r.uri))?.takenAt }.getOrNull() } }.awaitAll() } }.toMap()
for (i in fresh.indices) { val t = times[fresh[i].uri]; if (t != null && t > 0) fresh[i] = fresh[i].copy(takenAt = t) }
PerfLog.record("scan_exif_ms (n=${head.size})", (System.nanoTime() - t1) / 1_000_000)
```
(`fresh` becomes a `MutableList` copy; same for `importFiles`.) The Indexer's `markIndexed` continues to set the authoritative value later.
5. `LibraryScreen`: above the grid, `ViewChips(rawOnly, rawCount > 0, onRaw = { vm.setViewChoice(RAW) }, onAll = { vm.setViewChoice(ALL) })` (two `TouchChip`s, 48 dp, contentDescription "RAW photos, selected" and so on) and `WhatsNewBanner` when `whatsNew` is true. `vm.gridBusy`: in the grid modifier `pointerInput(Unit) { awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial); vm.gridBusy.value = true; waitForUpOrCancellation(); vm.gridBusy.value = false } }`, and `LaunchedEffect(gridState.isScrollInProgress, selecting) { vm.gridBusy.value = gridState.isScrollInProgress || selecting }` combined with the gesture flag (use a counter so the two do not overwrite each other). `vm.gridVisible` is set false when the navigation route is not `photos` (MainActivity observes `currentRoute`). Pull to refresh and the source menu call `vm.refreshOrder()`.

## 6. Strings (copy rules checked by the test above)
Chips: "RAW photos", "All photos". What's New: title "What's new", text "Rawline now opens on your RAW photos. Tap All photos to see everything on your phone.", button "Close". Optional status line when RAW only is on and the phone also has other photos: "Showing RAW photos only."

## 7. Acceptance
1. Host: 23 core/model tests green (plus the copy test when W27 is in).
2. Phone, first run state (clear the app's data, grant access): a phone with thousands of JPEG and HEIC photos and a dozen RW2 opens on the RAW photos; the chip row shows "RAW photos" selected and "All photos"; the What's New banner shows once; tapping "All photos" shows everything and the choice survives a restart; the banner never returns.
3. Phone, a phone with no RAW files: no chips, no banner, the normal grid.
4. Phone, copy 30 RW2 with a file manager into a folder and open Rawline; scroll the grid while it indexes: no tile moves under the thumb; the Copy report line `grid_resort_count` is 0 until the finger is lifted for 1.5 s, then at most 1; `scan_exif_ms` is recorded (no claim about it before the report).
5. Phone: rate a photo while the grid is busy: its badge updates at once; the order does not change.
6. Phone: open a photo for a minute and come back: the grid is in shooting order.

## 8. Risks
- Holding the order hides new photos for up to 1.5 s after the last touch; a shoot import (W15) shows them as soon as the user stops. If that feels slow the constant is `OrderGate(holdMs)`.
- The default view changes what existing users see at the next start; D4's note and the visible "All photos" chip are the mitigation, and the choice is one tap.
- `readExifOnly` on a 300 file head adds scan time; four at a time keeps one core free, and the number is recorded so it can be judged.

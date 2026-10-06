# W31 Studio S1d: reopen, RAW honesty, honest start failures, thumbnail off the Close path

Status at writing: main 8d45621, after S1c and while W13 is in flight. Entries: BK-504, BK-505, BK-506, BK-507, from the first release holes of `PHONE-TEST-S1.md`. Runs after W13 (it shares `StudioRoot`, `CanvasScreen` and the session) and after W16 (MainActivity). The pure Kotlin below compiled with Kotlin 2.4.10 against the real `core/studio-model` sources and ran on the host JVM: all 124 studio-model tests pass (the 104 that exist, 17 in `S1dTest` and 3 in `ModeJudgeTest`). The Compose, app module and picker edits are written as exact steps and are NOT compiled. Nothing here is measured on a phone.

## 1. What the code does today (read 6 Oct 2026 at 8d45621)
- `ModeState.startMode()` restores the last mode whatever its age, so one visit to Studio makes every later start open in Studio until the user switches back.
- `StudioRoot.open` is a plain `remember`: after a process death the Studio home is shown, and nothing says which project was open.
- `PhotoImport.decode` uses `ImageDecoder`: a DNG is rendered by Android (colour differs from Develop), an RW2 is usually not offered by the picker, and every failure says "Could not read that picture".
- `StartGuard` increments on every Studio start and resets only after two frames of the home; a swipe away in that window counts as a failed start, and two of them send the next start to Develop with a notice.
- `CanvasScreen.leave` renders the thumbnail and waits up to 3 s on Close.

## 2. Decisions (no questions left)
- D1 Studio is the start mode only when it was used less than 30 minutes ago (`ModeState.RESUME_WINDOW_MS`). "Used" is stamped when the user switches to Studio, when a project opens, on pause and on Close (`studioActive()`). A stored Studio mode with no time stamp (older installs) starts in Develop. A stale choice is rewritten to Develop so it is not asked again.
- D2 `OpenMark` stores the id of the open project; a normal Close clears it; a kill leaves it. The Studio home shows a "Continue <name>" card first when the mark names a project that still exists (`ContinueRule`). Tapping it opens the project like any other.
- D3 RAW in Studio (until the hand-off of W26 exists): the home carries the line "RAW photos open from Develop."; a DNG that decodes opens with the notice "Rendered by Android, so colours can differ from Develop." (shown once as a toast, and `Photo` stays the layer name); an RW2 or other RAW that does not decode says "RAW photos open from Develop. Use Open in Studio there."; any other failure names its cause (`DecodeFailure`, from `ImageDecoder.DecodeException.getError()` and the size check).
- D4 A start counts as failed only when the previous process crashed, failed to start, stopped responding, or was killed after hanging over 10 s without drawing the home; a user swipe away or force stop never counts (`StartFailure`). `ModeState.judgeLastStart(exit)` is called once at app start, before `startMode()`, with the previous exit reason from the existing `ExitReasons` reader (null when unknown, which does not count).
- D5 The project thumbnail is written by the autosave path: after 2 s without an edit, at most once a minute, and only if something changed (`ThumbScheduler`). Close never waits: if no thumbnail is due the home keeps the old one. `CanvasScreen.leave` keeps its spinner only for the save flush, not the thumbnail. The Copy report gets `studio_leave_ms`.
- D6 New strings follow docs/COPY.md (checked below with W27's rules; the two dialog buttons "Stay" and "Leave" need those two verbs added to `CopyRules.BUTTON_FIRST_WORDS`, done in the W27 file on 6 Oct 2026).

## 3. Order of work
1. Apply `mode.patch` and add `ResumeRules.kt` and the tests (section 4); run `:core:studio-model:testDebugUnitTest`.
2. App module: exit mapping and `judgeLastStart` (5.1), `studioActive()` calls (5.2).
3. `StudioRoot` and `StudioHome`: OpenMark, Continue card, the RAW home line and the picker outcomes (5.3, 5.4).
4. Thumbnail on the autosave path (5.5).
5. Phone: PHONE-TEST-S1 steps 5 and 7, and a Close timing.

## 4. Kotlin (compiled and tested on the host)
### core/studio-model patch (Mode.kt)
```diff
--- a/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/Mode.kt
+++ b/core/studio-model/src/main/kotlin/app/rawline/core/studio/model/Mode.kt
@@ -25,7 +25,7 @@
  * Which mode the app opens in and when the switch is shown (spec 3.5). With the flag off Studio does not exist: the stored mode is ignored, the
  * switch is never shown. The switch is shown only on the two home screens, never while editing or painting.
  */
-class ModeState(private val studioEnabled: Boolean, private val kv: KeyValue) {
+class ModeState(private val studioEnabled: Boolean, private val kv: KeyValue, private val clock: () -> Long = System::currentTimeMillis, private val resumeWindowMs: Long = RESUME_WINDOW_MS) {
     private val guard = StartGuard(kv)
     var notice: String? = null
         private set
@@ -33,14 +33,27 @@
     /** The mode to start in. A Studio start that crashed twice in a row falls back to Develop and says so once. */
     fun startMode(): AppMode {
         if (!studioEnabled) return AppMode.DEVELOP
-        val wanted = AppMode.fromKey(kv.getString(KEY))
+        var wanted = AppMode.fromKey(kv.getString(KEY))
+        // BK-504: Studio comes back only when it was in use recently; otherwise the app opens in Develop, its main job
+        if (wanted == AppMode.STUDIO && !studioRecentlyUsed()) { kv.putString(KEY, AppMode.DEVELOP.key); wanted = AppMode.DEVELOP }
         if (wanted == AppMode.STUDIO) {
             if (guard.shouldFallBack()) { kv.putString(KEY, AppMode.DEVELOP.key); kv.putInt(LAST_FALLBACK, 1); notice = "Studio did not start twice, so Rawline opened Develop. Switch to Studio again when you like."; return AppMode.DEVELOP }
-            guard.beginStudioStart()
+            beginStart()
         }
         return wanted
     }
 
+    /** True when Studio was last used less than [resumeWindowMs] ago. A stored mode with no time stamp (an older install) counts as not recent. */
+    fun studioRecentlyUsed(): Boolean { val t = kv.getString(LAST_ACTIVE)?.toLongOrNull() ?: return false; val d = clock() - t; return d in 0 until resumeWindowMs }
+
+    /** Call from Studio's home and canvas on pause, on close and when a project opens: keeps the "used recently" time current. */
+    fun studioActive() { if (studioEnabled) kv.putString(LAST_ACTIVE, clock().toString()) }
+
+    private fun beginStart() { kv.putString(START_MS, clock().toString()); guard.beginStudioStart() }
+
+    /** BK-506: call once at app start, before [startMode], with how the previous process ended (null when Android has no record). A swipe away is forgiven, a crash is not. */
+    fun judgeLastStart(lastExit: ExitInfo?) = guard.forgiveIfNotAFailure(lastExit, kv.getString(START_MS)?.toLongOrNull() ?: Long.MAX_VALUE)
+
     /** Call when the Studio home has drawn its first frame. */
     fun studioReady() = guard.studioReady()
 
@@ -49,7 +62,8 @@
     fun switchTo(mode: AppMode): AppMode {
         if (!studioEnabled) return AppMode.DEVELOP
         kv.putString(KEY, mode.key)
-        if (mode == AppMode.STUDIO) { kv.putInt(LAST_FALLBACK, 0); guard.beginStudioStart() } else guard.studioReady()
+        if (mode == AppMode.STUDIO) studioActive()
+        if (mode == AppMode.STUDIO) { kv.putInt(LAST_FALLBACK, 0); beginStart() } else guard.studioReady()
         return mode
     }
 
@@ -58,13 +72,22 @@
     /** For the Copy report: true when the last Studio start fell back to Develop and the user has not chosen Studio again since. */
     fun lastStartFellBack() = kv.getInt(LAST_FALLBACK, 0) == 1
 
-    companion object { const val KEY = "mode"; const val LAST_FALLBACK = "studio_last_fallback" }
+    companion object { const val KEY = "mode"; const val LAST_FALLBACK = "studio_last_fallback"; const val LAST_ACTIVE = "studio_last_active_ms"; const val START_MS = "studio_start_ms"; const val RESUME_WINDOW_MS = 30L * 60 * 1000 }
 }
 
 /** Counts Studio starts that did not reach the first frame. [limit] in a row means the next start goes to Develop (BK-409). */
 class StartGuard(private val kv: KeyValue, private val limit: Int = 2) {
     fun beginStudioStart() = kv.putInt(PENDING, kv.getInt(PENDING, 0) + 1)
+
+    /**
+     * BK-506: before counting a start that never drew the home, look at how the previous process ended. A user's own swipe away does not count; a crash, a not responding
+     * stop or a hang of more than [HANG_MS] does. [lastExit] is null when Android has no record, which counts only if the start was slow (unknown).
+     */
+    fun forgiveIfNotAFailure(lastExit: ExitInfo?, startedMs: Long) {
+        if (kv.getInt(PENDING, 0) == 0) return
+        if (!StartFailure.counts(lastExit, startedMs)) kv.putInt(PENDING, (kv.getInt(PENDING, 0) - 1).coerceAtLeast(0))
+    }
     fun studioReady() = kv.putInt(PENDING, 0)
     fun shouldFallBack(): Boolean { val n = kv.getInt(PENDING, 0); if (n >= limit) { kv.putInt(PENDING, 0); return true }; return false }
-    companion object { const val PENDING = "studio_pending_starts" }
+    companion object { const val PENDING = "studio_pending_starts"; const val HANG_MS = 10_000L }
 }
```
### core/studio-model/.../ResumeRules.kt (new)
```kotlin
package app.rawline.core.studio.model

import java.util.Locale

/** Studio S1d (BK-504 to BK-507): the small rules around reopening, RAW files, start failures and thumbnails. Pure, so the host tests run them. */

/** The project that was open when the last session ended. Cleared by a normal Close, so what is left after a kill or a crash is "continue this". */
class OpenMark(private val kv: KeyValue) {
    fun open(id: String) = kv.putString(KEY, id)
    fun close() = kv.putString(KEY, "")
    fun id(): String? = kv.getString(KEY)?.takeIf { it.isNotEmpty() }
    companion object { const val KEY = "studio_open_project" }
}

object ContinueRule {
    /** The row to offer as "Continue <name>": the marked project, if it is still in the list. */
    fun card(openId: String?, rows: List<ProjectRow>): ProjectRow? = openId?.let { id -> rows.firstOrNull { it.id == id } }
    fun label(row: ProjectRow): String = "Continue ${row.name.take(30).trimEnd()}"
}

/** How the previous process ended, as the app's exit reader reports it (mapped from ApplicationExitInfo in the app module). */
enum class ExitKind { CRASH, CRASH_NATIVE, ANR, INITIALIZATION_FAILURE, LOW_MEMORY, SIGNALED, USER_REQUESTED, OTHER }
class ExitInfo(val kind: ExitKind, val endedMs: Long)

object StartFailure {
    /** A Studio start that never drew the home is a failure when the process crashed, stopped responding, failed to start, or hung for over 10 s; a swipe away or force stop is not. */
    fun counts(exit: ExitInfo?, startedMs: Long): Boolean {
        if (exit == null) return false                       // nothing known: do not punish the user for it
        return when (exit.kind) {
            ExitKind.CRASH, ExitKind.CRASH_NATIVE, ExitKind.ANR, ExitKind.INITIALIZATION_FAILURE -> true
            ExitKind.USER_REQUESTED -> false
            ExitKind.LOW_MEMORY, ExitKind.SIGNALED, ExitKind.OTHER -> exit.endedMs - startedMs > StartGuard.HANG_MS
        }
    }
}

/** BK-505: what a picked file is and what to say about it. */
object RawPick {
    private val RAW_EXT = setOf("rw2", "dng", "orf", "cr2", "cr3", "nef", "arw", "raf")
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isDng(name: String, mime: String?) = ext(name) == "dng" || mime == "image/x-adobe-dng" || mime == "image/dng"
    fun isRaw(name: String, mime: String?) = ext(name) in RAW_EXT || (mime?.startsWith("image/x-") == true && mime.contains("raw"))

    const val HOME_NOTE = "RAW photos open from Develop."
    const val DNG_NOTICE = "Rendered by Android, so colours can differ from Develop."
    const val RAW_REFUSED = "RAW photos open from Develop. Use Open in Studio there."

    /** What to do after the decoder answered: [decoded] false means it could not read the file. */
    fun outcome(name: String, mime: String?, decoded: Boolean): Outcome = when {
        decoded && isDng(name, mime) -> Outcome(true, DNG_NOTICE)
        decoded -> Outcome(true, null)
        isRaw(name, mime) -> Outcome(false, RAW_REFUSED)
        else -> Outcome(false, null)
    }
    class Outcome(val ok: Boolean, val message: String?)
}

/** BK-505: a cause for "Could not read that picture". [code] is ImageDecoder.DecodeException.getError(): 1 source exception, 2 incomplete, 3 source error. */
object DecodeFailure {
    fun message(code: Int?, tooLarge: Boolean): String = when {
        tooLarge -> "That picture is too large for a canvas of 12 megapixels."
        code == 2 -> "That file is incomplete or damaged."
        code == 3 -> "That file is damaged, or Android cannot open this kind of picture."
        code == 1 -> "That file could not be read. Check that it is still on the phone."
        else -> "Could not read that picture."
    }
}

/**
 * BK-507: when the home's project thumbnail is written. After a quiet [quietMs] since the last edit, at most once per [minGapMs], and only if something changed since the last one.
 * Closing never waits for it: if it is not due, the home keeps the previous thumbnail.
 */
class ThumbScheduler(private val quietMs: Long = 2_000, private val minGapMs: Long = 60_000) {
    private var lastEdit = Long.MIN_VALUE / 2
    private var lastWrite = Long.MIN_VALUE / 2
    private var dirty = false
    fun onEdit(now: Long) { lastEdit = now; dirty = true }
    fun due(now: Long): Boolean = dirty && now - lastEdit >= quietMs && now - lastWrite >= minGapMs
    fun onWritten(now: Long) { lastWrite = now; if (lastEdit <= now) dirty = false }
    fun needsWrite(): Boolean = dirty
}
```
### core/studio-model/src/test/.../S1dTest.kt (new)
```kotlin
package app.rawline.core.studio.model

import org.junit.Assert.*
import org.junit.Test

class S1dTest {
    private fun row(id: String, name: String = id) = ProjectRow(id, name, 100, 100, 1, 1, 10, false)
    private class Clock(var t: Long) : () -> Long { override fun invoke() = t }

    // ---- BK-504 start mode and continue
    @Test fun studioComesBackOnlyWhenItWasUsedRecently() {
        val kv = MapKeyValue(); val c = Clock(1_000_000)
        ModeState(true, kv, c).switchTo(AppMode.STUDIO)
        c.t += 10 * 60_000; assertEquals(AppMode.STUDIO, ModeState(true, kv, c).startMode())
        kv.putInt(StartGuard.PENDING, 0)
        c.t += 40 * 60_000; assertEquals(AppMode.DEVELOP, ModeState(true, kv, c).startMode())
        assertEquals("develop", kv.getString(ModeState.KEY))                      // the stale choice is not asked again
    }
    @Test fun anOldInstallWithoutATimeStampStartsInDevelop() {
        val kv = MapKeyValue(); kv.putString(ModeState.KEY, "studio")
        assertEquals(AppMode.DEVELOP, ModeState(true, kv, Clock(5)).startMode())
    }
    @Test fun studioActivityKeepsTheWindowOpen() {
        val kv = MapKeyValue(); val c = Clock(0); val m = ModeState(true, kv, c); m.switchTo(AppMode.STUDIO)
        for (i in 1..5) { c.t += 25 * 60_000; m.studioActive() }                  // 25 minute gaps, each refreshed by a pause or close
        kv.putInt(StartGuard.PENDING, 0)
        assertEquals(AppMode.STUDIO, ModeState(true, kv, c).startMode())
    }
    @Test fun aClockThatWentBackwardsIsNotRecent() {
        val kv = MapKeyValue(); val c = Clock(10_000_000); ModeState(true, kv, c).switchTo(AppMode.STUDIO)
        c.t = 1_000; assertFalse(ModeState(true, kv, c).studioRecentlyUsed())
    }
    @Test fun withTheFlagOffNothingChanges() {
        val kv = MapKeyValue(); kv.putString(ModeState.KEY, "studio"); kv.putString(ModeState.LAST_ACTIVE, "5")
        val m = ModeState(false, kv, Clock(6)); assertEquals(AppMode.DEVELOP, m.startMode()); m.studioActive(); assertEquals("5", kv.getString(ModeState.LAST_ACTIVE))
    }
    @Test fun theOpenMarkSurvivesAKillAndIsClearedByAClose() {
        val kv = MapKeyValue(); val m = OpenMark(kv)
        assertNull(m.id()); m.open("p1"); assertEquals("p1", OpenMark(kv).id())     // a new process still sees it
        m.close(); assertNull(OpenMark(kv).id())
    }
    @Test fun theContinueCardNeedsTheProjectToStillExist() {
        val rows = listOf(row("a"), row("b", "Sunset over the long jetty at the end of the pier"))
        assertEquals("b", ContinueRule.card("b", rows)!!.id); assertNull(ContinueRule.card("gone", rows)); assertNull(ContinueRule.card(null, rows))
        assertEquals("Continue Sunset over the long jetty at", ContinueRule.label(rows[1]))
    }

    // ---- BK-506 start failures
    @Test fun aSwipeAwayDuringTheFirstFrameIsNotAFailedStart() {
        val kv = MapKeyValue(); val g = StartGuard(kv)
        g.beginStudioStart(); g.forgiveIfNotAFailure(ExitInfo(ExitKind.USER_REQUESTED, 5_500), 5_000)
        assertEquals(0, kv.getInt(StartGuard.PENDING, 0))
        g.beginStudioStart(); g.forgiveIfNotAFailure(ExitInfo(ExitKind.USER_REQUESTED, 5_100), 5_000); g.beginStudioStart()
        assertFalse(g.shouldFallBack())
    }
    @Test fun crashesAndHangsStillCountAndTwoOfThemFallBack() {
        val kv = MapKeyValue(); val g = StartGuard(kv)
        g.beginStudioStart(); g.forgiveIfNotAFailure(ExitInfo(ExitKind.CRASH, 5_300), 5_000); assertEquals(1, kv.getInt(StartGuard.PENDING, 0))
        g.beginStudioStart(); g.forgiveIfNotAFailure(ExitInfo(ExitKind.ANR, 6_000), 5_500); assertEquals(2, kv.getInt(StartGuard.PENDING, 0))
        assertTrue(g.shouldFallBack())
    }
    @Test fun killedWhileSlowCountsAndKilledQuicklyDoesNot() {
        assertFalse(StartFailure.counts(ExitInfo(ExitKind.OTHER, 6_000), 5_000)); assertTrue(StartFailure.counts(ExitInfo(ExitKind.OTHER, 20_000), 5_000))
        assertFalse(StartFailure.counts(ExitInfo(ExitKind.LOW_MEMORY, 8_000), 5_000)); assertTrue(StartFailure.counts(ExitInfo(ExitKind.SIGNALED, 16_000), 5_000))
        assertFalse(StartFailure.counts(null, 5_000)); assertTrue(StartFailure.counts(ExitInfo(ExitKind.CRASH_NATIVE, 5_001), 5_000)); assertTrue(StartFailure.counts(ExitInfo(ExitKind.INITIALIZATION_FAILURE, 5_001), 5_000))
    }
    @Test fun nothingPendingMeansNothingToForgive() { val kv = MapKeyValue(); StartGuard(kv).forgiveIfNotAFailure(null, 0); assertEquals(0, kv.getInt(StartGuard.PENDING, 0)) }

    // ---- BK-505 RAW
    @Test fun rawFilesAreRecognisedByNameOrType() {
        assertTrue(RawPick.isRaw("P1000123.RW2", null)); assertTrue(RawPick.isRaw("a.dng", "image/x-adobe-dng")); assertTrue(RawPick.isRaw("x", "image/x-panasonic-rw2".replace("rw2", "raw")))
        assertFalse(RawPick.isRaw("a.jpg", "image/jpeg")); assertFalse(RawPick.isRaw("a.png", null)); assertTrue(RawPick.isDng("A.DNG", null)); assertFalse(RawPick.isDng("a.rw2", null))
    }
    @Test fun aDngThatDecodedCarriesTheNoticeAndARawThatDidNotSaysWhereRawOpens() {
        val dng = RawPick.outcome("a.dng", null, decoded = true); assertTrue(dng.ok); assertEquals(RawPick.DNG_NOTICE, dng.message)
        val jpg = RawPick.outcome("a.jpg", "image/jpeg", decoded = true); assertTrue(jpg.ok); assertNull(jpg.message)
        val rw2 = RawPick.outcome("a.rw2", null, decoded = false); assertFalse(rw2.ok); assertEquals(RawPick.RAW_REFUSED, rw2.message)
        val bad = RawPick.outcome("a.png", "image/png", decoded = false); assertFalse(bad.ok); assertNull(bad.message)       // the decode failure message decides
    }
    @Test fun decodeFailuresNameTheCause() {
        assertEquals("That picture is too large for a canvas of 12 megapixels.", DecodeFailure.message(null, true))
        assertEquals("That file is incomplete or damaged.", DecodeFailure.message(2, false)); assertTrue(DecodeFailure.message(3, false).contains("Android cannot open"))
        assertTrue(DecodeFailure.message(1, false).startsWith("That file could not be read")); assertEquals("Could not read that picture.", DecodeFailure.message(null, false))
    }

    // ---- BK-507 thumbnail timing
    @Test fun theThumbnailWaitsForQuietAndForTheMinimumGap() {
        val t = ThumbScheduler(); assertFalse(t.due(10_000))                        // nothing changed
        t.onEdit(10_000); assertFalse(t.due(11_000)); assertTrue(t.due(12_000))
        t.onWritten(12_000); assertFalse(t.needsWrite()); assertFalse(t.due(100_000))
        t.onEdit(20_000); assertFalse("under 60 s since the last write", t.due(23_000)); assertTrue(t.due(72_000)); assertTrue(t.needsWrite())
    }
    @Test fun anEditDuringTheWriteKeepsItDirty() {
        val t = ThumbScheduler(); t.onEdit(0); t.onEdit(5_000); t.onWritten(4_000)   // the write started before the last edit
        assertTrue(t.needsWrite())
    }
    @Test fun closingNeverWaits() {
        val t = ThumbScheduler(); t.onEdit(1_000)
        assertFalse(t.due(1_500))                                                    // closing now: the caller just leaves, the home keeps the old thumbnail
        assertTrue(t.needsWrite())
    }
}

class ModeJudgeTest {
    private class Clock(var t: Long) : () -> Long { override fun invoke() = t }
    @Test fun aSwipeAwayDuringStudioStartIsForgivenByTheModeState() {
        val kv = MapKeyValue(); val c = Clock(100_000)
        ModeState(true, kv, c).switchTo(AppMode.STUDIO)                       // pending 1, start time stamped
        val next = ModeState(true, kv, Clock(130_000)); next.judgeLastStart(ExitInfo(ExitKind.USER_REQUESTED, 100_400))
        assertEquals(0, kv.getInt(StartGuard.PENDING, 0)); assertEquals(AppMode.STUDIO, next.startMode())
    }
    @Test fun twoCrashesStillFallBackToDevelop() {
        val kv = MapKeyValue(); val c = Clock(100_000)
        ModeState(true, kv, c).switchTo(AppMode.STUDIO)
        ModeState(true, kv, c).also { it.judgeLastStart(ExitInfo(ExitKind.CRASH, 100_200)) }.startMode()
        val third = ModeState(true, kv, c); third.judgeLastStart(ExitInfo(ExitKind.CRASH, 100_300))
        assertEquals(AppMode.DEVELOP, third.startMode()); assertTrue(third.lastStartFellBack())
    }
    @Test fun noStoredStartTimeMeansOnlyRealCrashesCount() {
        val kv = MapKeyValue(); kv.putInt(StartGuard.PENDING, 1)
        ModeState(true, kv).judgeLastStart(ExitInfo(ExitKind.OTHER, 99_999_999)); assertEquals(0, kv.getInt(StartGuard.PENDING, 0))
    }
}
```

## 5. Edits (not compiled)
1. App module, `StudioEntry.Root` (`app/src/studioOn/kotlin/app/rawline/StudioEntry.kt`), before `modeState.startMode()`: read the previous exit with the existing `ExitReasons` helper in core/cache (`ApplicationExitInfo`), map its reason to `ExitKind` (REASON_CRASH to CRASH, REASON_CRASH_NATIVE, REASON_ANR, REASON_INITIALIZATION_FAILURE, REASON_LOW_MEMORY, REASON_SIGNALED, REASON_USER_REQUESTED, everything else OTHER) and call `modeState.judgeLastStart(ExitInfo(kind, info.timestamp))`; with no record pass null.
2. `StudioRoot`: call `modeState.studioActive()` (hand a `onActive: () -> Unit` parameter down) when a project opens, on `ON_PAUSE` of the Studio home and canvas, and on Close.
3. `StudioRoot`: `val mark = remember { OpenMark(PrefsKeyValue(prefs)) }` (pass the preferences in like `StudioEntry` does); `openSession` calls `mark.open(doc.id)`; the `onExit` of the canvas (a real Close) calls `mark.close()` after the session is released; leaving to Develop through the mode switch does not clear it. `StudioHome` takes `continueRow: ProjectRow?` = `ContinueRule.card(mark.id(), rows)` and draws a first card with `ContinueRule.label(row)` that calls `onOpen(row.id)`.
4. Picker: replace the single failure message in the `photo` launcher. Read `OpenableColumns.DISPLAY_NAME` and the MIME type of the Uri, call `PhotoImport.decode` (make it return a result with `DecodeFailure` code and a `tooLarge` flag instead of null), then `RawPick.outcome(name, mime, decoded)`: show `outcome.message` (a toast for the DNG notice, an `AlertDialog` for a refusal) and otherwise `DecodeFailure.message(code, tooLarge)`. `StudioHome` shows `RawPick.HOME_NOTE` as one muted line under the new project buttons.
5. Thumbnail: `StudioSession` owns a `ThumbScheduler`; `onEdit(env.clock())` from `markDirty()` for user edits; a timer armed for the quiet gap checks `due(now)` and, if true, renders the thumbnail on the existing worker path (`exportSnapshot(2_000)` then `StudioExporter.thumbnailJpeg`, `projects.writeThumbnail`) and calls `onWritten`. `CanvasScreen.leave` drops the thumbnail work and its `withTimeoutOrNull(3_000)`; it records `perf.record("studio_leave_ms", ms)` from the tap to `onExit()`.

## 6. Copy check (run)
Checked with W27's rules: "RAW photos open from Develop.", "Rendered by Android, so colours can differ from Develop.", "RAW photos open from Develop. Use Open in Studio there.", the four decode messages, "Continue <name>", "Leave without saving?", "Your last changes are not saved.", "Not saved: the phone is almost full." and "Not enough space. Free about 300 MB and try again." all pass; the buttons "Stay" and "Leave" failed the verb list until the two verbs were added.

## 7. Acceptance
1. Host: 124 studio-model tests green; `:core:studio-render:test` green after the session edit.
2. Phone, PHONE-TEST-S1 step 7: kill the app in the Studio canvas and reopen within 30 minutes: Studio home with a Continue card for the project; after 40 minutes: Develop; Close the project normally and kill: no Continue card.
3. Phone, step 5: an RW2 gives "RAW photos open from Develop. Use Open in Studio there." if it is offered or decodes badly; a DNG opens with the notice; a damaged file names its cause.
4. Phone: open Studio, swipe the app away twice in the first second of the Studio home, reopen: still Studio (no "did not start twice" notice).
5. Phone: Close a 12 MP project: `studio_leave_ms` in the Copy report; no claim about it before then.

## 8. Risks
- The 30 minute window is a number to adjust after Jai uses it; it is one constant.
- `ApplicationExitInfo` can lag a process by a moment and may not exist on a first run; the rule treats unknown as not a failure, so a crash loop with no record would not fall back. The existing exit reader already handles the missing case; check it in the phone test with a forced crash (`adb shell am crash`), which counts as a CRASH.

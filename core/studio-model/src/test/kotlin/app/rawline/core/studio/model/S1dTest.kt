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
    @Test fun androidExitReasonsMapToKinds() {
        assertEquals(ExitKind.CRASH, ExitKind.fromReason(4)); assertEquals(ExitKind.CRASH_NATIVE, ExitKind.fromReason(5)); assertEquals(ExitKind.ANR, ExitKind.fromReason(6))
        assertEquals(ExitKind.INITIALIZATION_FAILURE, ExitKind.fromReason(7)); assertEquals(ExitKind.LOW_MEMORY, ExitKind.fromReason(3)); assertEquals(ExitKind.SIGNALED, ExitKind.fromReason(2))
        assertEquals(ExitKind.USER_REQUESTED, ExitKind.fromReason(10)); assertEquals(ExitKind.USER_REQUESTED, ExitKind.fromReason(11))
        assertEquals(ExitKind.OTHER, ExitKind.fromReason(13)); assertEquals(ExitKind.OTHER, ExitKind.fromReason(1)); assertEquals(ExitKind.OTHER, ExitKind.fromReason(99))
    }
    @Test fun theExitIsOnlyLookedAtWhenAStartIsPending() {
        val kv = MapKeyValue(); val m = ModeState(true, kv); assertFalse(m.startPending())
        m.switchTo(AppMode.STUDIO); assertTrue(m.startPending()); m.studioReady(); assertFalse(m.startPending())
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
    @Test fun waitMsSaysWhenTheNextWriteCanHappen() {
        val t = ThumbScheduler(); assertNull(t.waitMs(0))
        t.onEdit(10_000); assertEquals(2_000L, t.waitMs(10_000)); assertEquals(500L, t.waitMs(11_500)); assertEquals(0L, t.waitMs(12_000))
        t.onWritten(12_000); assertNull(t.waitMs(12_000))
        t.onEdit(13_000); assertEquals(59_000L, t.waitMs(13_000))                    // the gap since the last write is the longer wait
        assertEquals(0L, t.waitMs(72_000))
    }
    @Test fun aFailedPickNamesRawOrTheCause() {
        assertEquals(RawPick.RAW_REFUSED, RawPick.failure("P1.RW2", null, 3, false))
        assertEquals("That file is incomplete or damaged.", RawPick.failure("a.jpg", "image/jpeg", 2, false))
        assertEquals("That picture is too large for a canvas of 12 megapixels.", RawPick.failure("a.png", null, null, true))
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

package app.rawline.core.model.onboarding

import org.junit.Assert.*
import org.junit.Test

class MapStore : Store {
    val m = HashMap<String, Any>()
    override fun getInt(key: String, default: Int) = (m[key] as? Int) ?: default
    override fun putInt(key: String, value: Int) { m[key] = value }
    override fun getBool(key: String, default: Boolean) = (m[key] as? Boolean) ?: default
    override fun putBool(key: String, value: Boolean) { m[key] = value }
}

class OnboardingTest {
    private var p = Perms(false, false)
    private fun flow(store: Store = MapStore(), studio: Boolean = false) = Onboarding(store, studio) { p }

    @Test fun walksTheFiveScreensInOrderAndFinishes() {
        val f = flow()
        assertEquals(Step.WELCOME, f.current)
        f.next(); assertEquals(Step.PHOTOS, f.current)
        f.next(); assertEquals(Step.RAW_FILES, f.current)
        f.next(); assertEquals(Step.WHERE_FROM, f.current)
        f.next(); assertEquals(Step.GESTURES, f.current); assertFalse(f.done)
        f.next(); assertTrue(f.done)
    }
    @Test fun studioScreenOnlyWhenTheBuildHasStudio() {
        val off = flow(MapStore(), studio = false)
        assertEquals(listOf(Step.WELCOME, Step.PHOTOS, Step.RAW_FILES, Step.WHERE_FROM, Step.GESTURES), off.visibleSteps())
        val on = flow(MapStore(), studio = true)
        assertEquals(listOf(Step.WELCOME, Step.PHOTOS, Step.RAW_FILES, Step.WHERE_FROM, Step.STUDIO, Step.GESTURES), on.visibleSteps())
        on.next(); on.next(); on.next(); assertEquals(Step.WHERE_FROM, on.current)
        on.next(); assertEquals(Step.STUDIO, on.current)
        on.next(); assertEquals(Step.GESTURES, on.current)
        on.back(); assertEquals(Step.STUDIO, on.current)
        // with the flag off, a saved STUDIO position (a flag flipped between runs) lands on the next screen that applies
        val s = MapStore(); s.putInt(Onboarding.POS, Step.STUDIO.ordinal); assertEquals(Step.GESTURES, flow(s, studio = false).current)
        val g = flow(MapStore(), studio = false); g.next(); g.next(); g.next(); g.next(); assertEquals(Step.GESTURES, g.current)
        g.back(); assertEquals(Step.WHERE_FROM, g.current)   // back skips the Studio screen too
    }
    @Test fun grantedScreensAreSkipped() {
        p = Perms(true, false); val f = flow(); f.next(); assertEquals(Step.RAW_FILES, f.current)
        p = Perms(true, true); val g = flow(); g.next(); assertEquals(Step.WHERE_FROM, g.current)
    }
    @Test fun grantingInTheSystemScreenMovesOn() {
        val f = flow(); f.next(); assertEquals(Step.PHOTOS, f.current)
        p = Perms(true, false); f.permissionsChanged(); assertEquals(Step.RAW_FILES, f.current)
        p = Perms(true, true); f.permissionsChanged(); assertEquals(Step.WHERE_FROM, f.current)
    }
    @Test fun denialNeverBlocks() {
        val f = flow(); f.next(); p = Perms(false, false); f.permissionsChanged(); assertEquals(Step.PHOTOS, f.current)
        f.next(); assertEquals(Step.RAW_FILES, f.current)          // the user taps Skip on the photos screen
    }
    @Test fun skipRawKeepsGoingAndRemembersTheNote() {
        val f = flow(); f.next(); f.next(); assertEquals(Step.RAW_FILES, f.current)
        f.skipRaw(); assertEquals(Step.WHERE_FROM, f.current); assertTrue(f.rawSkipped)
        f.noteShown(); assertFalse(f.rawSkipped)
        val g = flow(); g.skipRaw(); assertFalse(g.rawSkipped)       // only counts on its own screen
    }
    @Test fun backSkipsScreensThatDoNotApply() {
        p = Perms(true, false); val f = flow(); f.next(); f.next(); assertEquals(Step.WHERE_FROM, f.current)
        f.back(); assertEquals(Step.RAW_FILES, f.current)
        f.back(); assertEquals(Step.WELCOME, f.current)               // PHOTOS is skipped because photos are allowed
        f.back(); assertEquals(Step.WELCOME, f.current)               // nothing before the first screen
    }
    @Test fun resumesAfterProcessDeath() {
        val s = MapStore(); val f = flow(s); f.next(); f.next(); assertEquals(Step.RAW_FILES, f.current)
        val g = flow(s); assertEquals(Step.RAW_FILES, g.current); assertFalse(g.done); assertTrue(g.started)
    }
    @Test fun resumeSkipsWhatWasGrantedMeanwhile() {
        val s = MapStore(); val f = flow(s); f.next(); assertEquals(Step.PHOTOS, f.current)
        p = Perms(true, true); val g = flow(s); assertEquals(Step.WHERE_FROM, g.current)
    }
    @Test fun skipAllAndDoneAreRemembered() {
        val s = MapStore(); flow(s).skipAll(); assertTrue(flow(s).done)
    }
    @Test fun resetFromSettingsShowsItAgain() {
        val s = MapStore(); val f = flow(s); f.skipAll(); assertTrue(f.done)
        f.reset(); assertFalse(f.done); assertEquals(Step.WELCOME, f.current); assertFalse(flow(s).done); assertFalse(f.started)
    }
    @Test fun corruptSavedPositionIsClamped() {
        val s = MapStore(); s.putInt(Onboarding.POS, 99); assertEquals(Step.GESTURES, flow(s).current)
        s.putInt(Onboarding.POS, -5); assertEquals(Step.WELCOME, flow(s).current)
    }
    @Test fun entryDecision() {
        assertEquals(Entry.NONE, Onboarding.entry(done = true, started = false, libraryLoaded = true, photoCount = 0))
        assertEquals(Entry.NONE, Onboarding.entry(true, true, false, 0))
        assertEquals(Entry.WAIT, Onboarding.entry(false, false, false, 0))                 // never decided before the library has loaded: the grid is not held back
        assertEquals(Entry.SHOW, Onboarding.entry(false, false, true, 0))                  // a fresh install
        assertEquals(Entry.MARK_DONE, Onboarding.entry(false, false, true, 120))           // a returning person with a library never sees it
        assertEquals(Entry.SHOW, Onboarding.entry(false, true, true, 120))                 // started, then the permission filled the library: resume
        assertEquals(Entry.SHOW, Onboarding.entry(false, true, false, 0))
    }
    @Test fun startedBecomesTrueOnlyAfterTheFirstMove() {
        val s = MapStore(); val f = flow(s); assertFalse(f.started); f.next(); assertTrue(f.started)
    }
}

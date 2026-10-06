package app.rawline.platform

import app.rawline.core.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PlatformRulesTest {
    @Test fun notificationAskedOnlyOnceOnAndroid13Plus() {
        assertTrue(NotificationRule.shouldAsk(33, granted = false, askedBefore = false))
        assertTrue(NotificationRule.shouldAsk(36, false, false))
        assertFalse(NotificationRule.shouldAsk(32, false, false))
        assertFalse(NotificationRule.shouldAsk(34, true, false))
        assertFalse(NotificationRule.shouldAsk(34, false, true))
    }
    @Test fun filterRoundTripsEveryField() {
        val f = LibraryFilter(minRating = 3, flag = FlagFilter.PICK, edited = EditedFilter.UNEDITED, camera = "Panasonic DC-S5M2X", sort = SortOrder.OLDEST)
        assertEquals(f, LibraryFilterJson.read(LibraryFilterJson.write(f)))
        assertEquals(LibraryFilter(), LibraryFilterJson.read(LibraryFilterJson.write(LibraryFilter())))
    }
    @Test fun theRawViewIsNotStoredInTheFilter() {
        // W29 D8: the RAW view comes from the user's chip choice and the content, so a stored filter cannot contradict it
        assertFalse(LibraryFilterJson.read(LibraryFilterJson.write(LibraryFilter(rawOnly = true, minRating = 2))).rawOnly)
        assertEquals(2, LibraryFilterJson.read(LibraryFilterJson.write(LibraryFilter(rawOnly = true, minRating = 2))).minRating)
    }
    @Test fun damagedOrUnknownValuesFallBackToDefaults() {
        assertEquals(LibraryFilter(), LibraryFilterJson.read(null)); assertEquals(LibraryFilter(), LibraryFilterJson.read("")); assertEquals(LibraryFilter(), LibraryFilterJson.read("{nope"))
        val g = LibraryFilterJson.read("""{"minRating":99,"flag":"SPARKLE","sort":"RATING","future":{"x":1}}""")
        assertEquals(5, g.minRating); assertEquals(FlagFilter.ANY, g.flag); assertEquals(SortOrder.RATING, g.sort); assertNull(g.camera)
    }
    @Test fun aSavedFilterThatNowFindsNothingStillAppliesCleanly() {
        val f = LibraryFilterJson.read(LibraryFilterJson.write(LibraryFilter(minRating = 5)))
        assertEquals(emptyList<Photo>(), f.apply(listOf(Photo(1, "f", "u", "a.rw2", 1, 1, Kind.RAW, true))))
    }
    @Test fun manifestRulesCatchEachProblem() {
        val good = """<application android:enableOnBackInvokedCallback="true"><activity android:name=".M"/></application>"""
        assertEquals(emptyList<String>(), ManifestRules.problems(good))
        assertEquals(1, ManifestRules.problems("""<application><activity android:name=".M"/></application>""").size)
        assertTrue(ManifestRules.problems(good.replace("<activity", """<activity android:screenOrientation="portrait" """)).any { it.contains("screenOrientation") })
        assertTrue(ManifestRules.problems(good.replace("<activity", """<activity android:resizeableActivity="false" """)).any { it.contains("resizeable") })
        assertTrue(ManifestRules.problems(good.replace("<activity", """<activity android:maxAspectRatio="1.8" """)).any { it.contains("aspect") })
        assertTrue(ManifestRules.problems(good.replace("true", "false")).any { it.contains("switched off") })
    }
    @Test fun backInventoryCountsCallsNotImportsOrComments() {
        val src = mapOf("A.kt" to "import androidx.activity.compose.BackHandler\n// BackHandler(x) {}\nBackHandler(enabled = a) { x() }\nandroidx.activity.compose.BackHandler { leave() }\n",
            "B.kt" to "val x = 1\n", "C.kt" to " * BackHandler { }\n")
        assertEquals(mapOf("A.kt" to 2), BackInventory.count(src))
    }


    /** The real files. app.dir points at the app module (Gradle sets it); skipped without it. */
    private fun root(): File? = System.getProperty("repo.root")?.let { File(it) }
    @Test fun theRealManifestFollowsTheRules() {
        val r = root(); assumeTrue(r != null)
        val problems = ManifestRules.problems(File(r, "app/src/main/AndroidManifest.xml").readText())
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
    @Test fun everyBackHandlerInTheAppIsInTheInventory() {
        val r = root(); assumeTrue(r != null)
        val files = HashMap<String, String>()
        for (dir in listOf("app/src/main", "feature", "core")) File(r, dir).walkTopDown().onEnter { it.name != "build" && it.name != "test" }.filter { it.isFile && it.name.endsWith(".kt") }.forEach { files[it.name] = it.readText() }
        assertTrue("scanner found too few files: ${files.size}", files.size > 100)
        assertEquals("a BackHandler was added or removed: update BackInventory.EXPECTED and docs/PLATFORM.md", BackInventory.EXPECTED.toSortedMap(), BackInventory.count(files))
    }
}

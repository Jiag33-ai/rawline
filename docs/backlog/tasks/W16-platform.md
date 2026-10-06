# W16 Platform compliance and small fixes (predictive back, manifest rules, notification ask, library state)

Status at writing: main 7cd1b06 (S1c in flight). Entries: BK-246, BK-426, BK-285, BK-120. Runs right after W28 and before W29 (they share MainActivity, LibraryViewModel and LibraryScreen). The Kotlin in section 4 compiled with Kotlin 2.4.10 against the real `core/model` sources and ran on the host JVM: 9 tests pass, including two that read the real repository (the manifest and every BackHandler in the app). Run against main today those two tests are red for exactly the two reasons in section 2, which is the point: they fail now and pass after the manifest edit (checked by running them on a copy of the manifest with the attribute added). NOT compiled or run: the Gradle wiring, the MainActivity and ViewModel edits, anything on the phone.

## 1. What the code already does (checked 6 Oct 2026, so the task is smaller than next25 says)
- BK-285 notification permission: `queueExport` in MainActivity already asks `POST_NOTIFICATIONS` once, when the first export is queued (flag `notifAsked`); nothing asks at launch. The task only extracts the rule, adds the one-line reason and a test.
- BK-120: `columns`, `selected` and `showFilters` are `rememberSaveable`, and the Activity declares `configChanges`, so rotation already keeps them. What is lost: the filter and sort (a plain `MutableStateFlow` in `LibraryViewModel`), the column count on a cold start (saveable state does not survive the user swiping the app away), and the scroll position on a cold start. `source` and `folder` are already in the preferences.
- BK-426: the manifest has no `screenOrientation`, no `resizeableActivity` and no aspect ratio limit. Nothing guards it.
- BK-246: the manifest does not set `android:enableOnBackInvokedCallback` (red in the new test). Back handlers (six at 7cd1b06, seven with S1c's `StudioRoot.kt`): EditorHost (leave), EditorScreen (panel, crop, curve page), MaskTray (step back), LoupeScreen (info and stars), LibraryScreen (selection), Studio CanvasScreen and StudioRoot. They work by registration order (the newest, innermost enabled one wins), which is correct today and easy to break; the inventory test below makes a new one fail until it is written down.
- `enableEdgeToEdge` is called in MainActivity (line 87). Targeting 37 means edge-to-edge and predictive back cannot be opted out of.

## 2. Decisions (no questions left)
- D1 `android:enableOnBackInvokedCallback="true"` on `<application>` (one line, section 5). BackHandler stays; no `PredictiveBackHandler` rewrite in this task. The system back-to-home preview is accepted as the only predictive animation; the in-app screens keep their own slide.
- D2 Guard tests, not behaviour changes, for the manifest and the BackHandler list (section 4). They read the real files through the system property `repo.root`.
- D3 The back order is the table in section 6, and `docs/PLATFORM.md` carries it. A change to any handler updates both the table and `BackInventory.EXPECTED` in the same pull request.
- D4 The notification reason is the same sentence W27 uses (`body_notifications`): "Rawline shows a notification while it exports so Android keeps it running." Shown as a toast before the system dialog; declining never blocks the export.
- D5 Library state: the filter is stored as JSON under the preference key `libraryFilter`, columns under `columns` (clamped 2..8 by the existing pinch code), the top photo id under `libraryTop` written when the app pauses. Changing source resets the filter but keeps the sort, as today.
- D6 No large-screen redesign. The tablet emulator run only has to show no crash and no unusable screen (letterboxed is fine).

## 3. Order of work
1. Add the tests and the three pure objects (section 4); wire `repo.root` (section 5). Run `./gradlew :app:testDebugUnitTest`: two tests red.
2. Manifest edit (section 5): green.
3. MainActivity and LibraryViewModel edits (section 5).
4. `docs/PLATFORM.md` (section 7).
5. Phone checks (section 8).

## 4. Kotlin (compiled and tested on the host)
Split when committing: `NotificationRule`, `LibraryFilterJson` and `RestorePoint` go to `app/src/main/kotlin/app/rawline/platform/PlatformRules.kt`; `ManifestRules`, `BackInventory` and the test class go to `app/src/test/kotlin/app/rawline/platform/`. `app` needs `testImplementation(libs.json)` if it does not already have it (core/model has it).
### PlatformRules.kt (with the test-only objects, as I ran it)
```kotlin
package app.rawline.platform

import org.json.JSONObject
import app.rawline.core.model.EditedFilter
import app.rawline.core.model.FlagFilter
import app.rawline.core.model.LibraryFilter
import app.rawline.core.model.SortOrder

/** W16: pure pieces of the platform task. No Android imports, so host tests run them. */

/** BK-285: the notification question is asked once, when the first export is queued, on Android 13 and later, if it is not granted. */
object NotificationRule {
    fun shouldAsk(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean = sdk >= 33 && !granted && !askedBefore
}

/**
 * BK-120: the library's filter and sort survive a cold start. Stored as JSON in the preferences. Unknown keys are ignored, a missing key takes the default
 * and an unknown enum name takes the default, so a later task can add a field (W29 adds `rawOnly`) without breaking a stored value.
 */
object LibraryFilterJson {
    fun write(f: LibraryFilter): String = JSONObject()
        .put("minRating", f.minRating).put("flag", f.flag.name).put("edited", f.edited.name).put("sort", f.sort.name)
        .also { if (f.camera != null) it.put("camera", f.camera) }.toString()

    fun read(s: String?): LibraryFilter {
        if (s.isNullOrBlank()) return LibraryFilter()
        return try {
            val o = JSONObject(s)
            LibraryFilter(
                minRating = o.optInt("minRating", 0).coerceIn(0, 5),
                flag = enumOr(o.optString("flag", ""), FlagFilter.ANY),
                edited = enumOr(o.optString("edited", ""), EditedFilter.ANY),
                camera = if (o.has("camera") && !o.isNull("camera")) o.getString("camera") else null,
                sort = enumOr(o.optString("sort", ""), SortOrder.NEWEST),
            )
        } catch (e: Exception) { LibraryFilter() }
    }
    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default
}

/** BK-246 and BK-426: what the manifest must and must not say. Returns the problems found (empty is good). */
object ManifestRules {
    fun problems(xml: String): List<String> {
        val out = ArrayList<String>()
        if (Regex("""android:screenOrientation\s*=""").containsMatchIn(xml)) out += "screenOrientation is set: no orientation lock (Android 17 ignores it on large screens, and a phone user rotates)"
        if (Regex("""android:resizeableActivity\s*=\s*"false"""").containsMatchIn(xml)) out += "resizeableActivity=false: not allowed"
        if (Regex("""android:(minAspectRatio|maxAspectRatio)\s*=""").containsMatchIn(xml)) out += "an aspect ratio limit is set: not allowed"
        if (!Regex("""android:enableOnBackInvokedCallback\s*=\s*"true"""").containsMatchIn(xml)) out += "android:enableOnBackInvokedCallback=\"true\" is missing: say it explicitly so predictive back never depends on a default"
        if (Regex("""android:enableOnBackInvokedCallback\s*=\s*"false"""").containsMatchIn(xml)) out += "predictive back is switched off"
        return out
    }
}

/**
 * BK-246: every BackHandler in the app is listed here with where it is and what it closes, in the order they take effect (the one registered last, which is the innermost
 * and the newest composition, wins). A new BackHandler fails `BackInventoryTest` until it is added to this table and to docs/PLATFORM.md.
 */
object BackInventory {
    /** file name (no path) to the number of BackHandler registrations it holds. */
    val EXPECTED: Map<String, Int> = mapOf(
        "EditorHost.kt" to 1,      // leave the editor: save the recipe, then Back to the viewer. Always enabled; the outermost, so everything below wins first.
        "EditorScreen.kt" to 1,    // close the open panel (a crop is cancelled first, the curve page steps back to Basic, then the panel closes)
        "MaskTray.kt" to 1,        // step back inside the masking tray (busy, picking, renaming, edit page, pick page)
        "LoupeScreen.kt" to 1,     // close the info panel or the star picker
        "LibraryScreen.kt" to 1,   // clear the selection
        "CanvasScreen.kt" to 1,    // Studio: close export, then the layers panel, then save and leave
        "StudioRoot.kt" to 1,      // Studio (S1c, in flight when this was written): Back on the Studio home goes back to Develop; check the text of this handler when S1c merges
    )

    private val CALL = Regex("""\bBackHandler\s*[({]""")
    private val IMPORT = Regex("""^\s*import\s""")

    /** Counts registrations per file in the given sources (file name to text). Comments and imports do not count. */
    fun count(sources: Map<String, String>): Map<String, Int> {
        val out = sortedMapOf<String, Int>()
        for ((name, text) in sources) {
            var n = 0
            for (line in text.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || IMPORT.containsMatchIn(line)) continue
                n += CALL.findAll(line).count()
            }
            if (n > 0) out[name] = n
        }
        return out
    }
}

/** BK-120: after a cold start the grid goes back to the photo that was at the top, if it is still in the list. */
object RestorePoint {
    /** Index to scroll to: the position of [savedId] in [ids], or 0 when there is no saved photo or it is gone. */
    fun resolve(ids: List<Long>, savedId: Long?): Int = if (savedId == null) 0 else ids.indexOf(savedId).coerceAtLeast(0)
}
```
### PlatformRulesTest.kt
```kotlin
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

    @Test fun restorePointFindsThePhotoOrStartsAtTheTop() {
        assertEquals(2, RestorePoint.resolve(listOf(5, 6, 7, 8), 7)); assertEquals(0, RestorePoint.resolve(listOf(5, 6), 99)); assertEquals(0, RestorePoint.resolve(listOf(5, 6), null)); assertEquals(0, RestorePoint.resolve(emptyList(), 1))
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
```

## 5. Edits (not compiled)
1. `app/src/main/AndroidManifest.xml`, on `<application>` (the diff I tested the guard with):
```diff
15a16
>         android:enableOnBackInvokedCallback="true"
```
2. `app/build.gradle.kts`, inside `android { ... }`:
```kotlin
testOptions { unitTests.all { it.systemProperty("repo.root", rootDir.path) } }
```
3. MainActivity `queueExport`: replace the inline condition by
```kotlin
val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
if (NotificationRule.shouldAsk(android.os.Build.VERSION.SDK_INT, granted, graph.prefs.getBoolean("notifAsked", false))) {
    graph.prefs.edit().putBoolean("notifAsked", true).apply()
    showToast("Rawline shows a notification while it exports so Android keeps it running.")
    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
}
```
4. `LibraryViewModel`: `val filter = MutableStateFlow(LibraryFilterJson.read(graph.prefs.getString("libraryFilter", null)))`; a collector `viewModelScope.launch { filter.collect { graph.prefs.edit().putString("libraryFilter", LibraryFilterJson.write(it)).apply() } }` (`drop(1)` not needed, the first write is the same value); `selectSource` keeps `LibraryFilter(sort = filter.value.sort)` as it does.
5. `LibraryScreen`: `var columns by rememberSaveable { mutableStateOf(prefs.columns) }` and write `columns` to the preference in the `pinchColumns` callback. On `ON_PAUSE` write the id of the first visible photo (`photos.getOrNull(gridState.firstVisibleItemIndex)?.id`) to `libraryTop`. After the first non-empty list for the saved source (once per process, skipped when the viewer's own `jump` is set) call `gridState.scrollToItem(RestorePoint.resolve(photos.map { it.id }, savedTop))`.
6. Large-screen rule: nothing to edit; the manifest test keeps it true.

## 6. Back order (the table for docs/PLATFORM.md; innermost first)
| Screen | A back press does, in this order |
|---|---|
| Editor with the masking tray | 1 a busy, picking, renaming or edit page of the tray steps back one page (MaskTray); 2 the open panel: a crop is cancelled to its start, the curve page returns to Basic, otherwise the panel closes (EditorScreen); 3 leave: save the edit, then back to the viewer (EditorHost) |
| Loupe (viewer) | 1 close the info panel or the star picker; 2 back to the library |
| Library | 1 clear the selection; 2 back to Android home (the system back-to-home animation shows) |
| Studio canvas | 1 close export; 2 close the layers panel; 3 save and leave to the Studio home; Studio home goes back to Develop |
The editor leaves with a visible spinner only if the write takes longer than 150 ms, and a failed write keeps the editor open so Back can try again (existing behaviour, keep).

## 7. docs/PLATFORM.md (file content, short)
```markdown
# Platform checklist (API 37)
Re-run this list whenever Android publishes behaviour changes for the next API level.

| Item | Rule | How it is checked |
|---|---|---|
| Targeting | targetSdk 37, compileSdk 37 | build files |
| Edge to edge | cannot be opted out of; every screen draws behind the bars and pads with insets | phone: gesture navigation, three button navigation, landscape, a cutout emulator profile |
| Predictive back | `enableOnBackInvokedCallback="true"` on the application; the back order table in W16 is the contract | PlatformRulesTest (manifest and BackHandler list), phone back checks |
| Orientation and resizing | no `screenOrientation`, no `resizeableActivity=false`, no aspect ratio limit | PlatformRulesTest |
| Large screens | no crash, nothing unusable at 600 dp and wider (letterboxed is fine) | one run on a tablet emulator profile per API level |
| Notifications | asked once at the first export, Android 13 and later | NotificationRule test, phone |
| Photo access | the media permission is asked once at first launch; All files access is a separate, explained step | MediaAccess tests, phone |
| Foreground service | types dataSync and mediaProcessing declared; the app keeps working when the notification is hidden | phone |
```

## 8. Acceptance
1. `:app:testDebugUnitTest` green (9 new tests); before the manifest edit exactly the manifest test is red, after it all green; adding any `BackHandler` makes the inventory test fail until the table is updated (try it once and paste the message).
2. Phone, back gesture, each screen: editor with a crop open (back cancels the crop, a second back closes nothing more, a third leaves); editor with the masking tray on an edit page (steps back one page, then closes, then leaves); loupe with the info panel open; library with three photos selected (back clears, second back goes home with the system animation); Studio canvas with the layers panel open.
3. Insets: all of Library, Loupe, Editor (portrait and landscape), Settings, Queue under gesture navigation and under three button navigation; nothing under the status bar, navigation bar or camera cutout.
4. First export ever: the toast, then the system dialog; decline it and the export still runs; second export asks nothing.
5. Set a filter and a sort, swipe the app away, reopen: both are back, columns are back, the grid is at the same photo.
6. Tablet emulator: launch, open a photo, open the editor, export; no crash.

## 9. Risks
- `enableOnBackInvokedCallback` makes the system animate back-to-home when no handler is enabled; with a handler always enabled in the editor the animation is not shown there (the editor must save first). That is intended.
- A saved filter that hides everything on the next start can look like an empty library: the existing "Clear filters" chip must be visible whenever a filter is active (check on the phone, step 5).

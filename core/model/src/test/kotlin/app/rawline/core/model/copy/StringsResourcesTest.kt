package app.rawline.core.model.copy

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Checks the onboarding, help, glossary and message resources shipped with BK-392 to 395. `res.dir` points at the folder with the four strings_*.xml files. */
class StringsResourcesTest {
    private fun load(): Map<String, List<Found>> {
        val dir = System.getProperty("res.dir"); assumeTrue(dir != null)
        return File(dir!!).listFiles { f -> f.name.startsWith("strings_") && f.name.endsWith(".xml") }!!.sortedBy { it.name }.associate { it.name to CopyScan.stringsXml(it.name, it.readText()) }
    }
    @Test fun everyStringFollowsTheRules() {
        val bad = ArrayList<String>()
        for ((file, items) in load()) for (x in items) for (v in CopyRules.check(x.text, x.kind)) bad += "$file ${x.name} [${v.rule}] ${v.detail}: \"${x.text}\""
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
    @Test fun theDeckIsComplete() {
        val all = load().values.flatten()
        val names = all.map { it.name }
        for (n in listOf("title_welcome", "title_photos", "title_raw_files", "title_where_from", "title_gestures", "title_notifications", "button_get_started", "button_allow_photos", "button_open_settings", "button_skip_for_now", "button_open_my_photos", "button_allow", "button_not_now"))
            assertTrue("missing $n", n in names)
        assertEquals("fourteen messages", 14, names.count { it.startsWith("error_") })
        assertEquals(10, names.count { it.startsWith("help_gloss_") })
        assertEquals(setOf("help_library", "help_viewer", "help_editor", "help_masks", "help_remove", "help_export"), names.filter { it.startsWith("help_") && !it.startsWith("help_gloss_") }.toSet())
    }
    @Test fun noDuplicateResourceNamesOutsideArrays() {
        val singles = load().values.flatten().filter { !it.name.startsWith("help_") || it.name.startsWith("help_gloss") }.map { it.name }
        assertEquals(singles.size, singles.toSet().size)
    }
    @Test fun settingsNamesAreRealAndroidWords() {
        val t = load().values.flatten().first { it.name == "body_raw_files" }.text
        assertTrue(t.contains("All files access"))      // the exact label on the Android settings screen
    }
    @Test fun everyHelpTopicHasATitle() {
        val names = load().values.flatten().map { it.name }.toSet()
        for (t in listOf("library", "viewer", "editor", "masks", "remove", "export")) assertTrue("title_help_$t", "title_help_$t" in names)
    }
}

package app.rawline

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** W27: the Studio welcome screen's words exist only where Studio does, so a build without Studio carries none of them. */
class StudioOnboardingGuardTest {
    private fun root(): File? = System.getProperty("repo.root")?.let { File(it) }

    @Test fun studioWordsLiveOnlyInTheStudioOnSourceSet() {
        val r = root(); assumeTrue(r != null)
        val on = File(r, "app/src/studioOn/res/values/strings_studio_onboarding.xml")
        assertTrue("the Studio screen's strings file is missing", on.exists())
        val text = on.readText()
        for (n in listOf("title_studio", "body_studio", "note_studio")) assertTrue(n, text.contains("name=\"$n\""))
        assertFalse("the flag-off source set must carry no resources", File(r, "app/src/studioOff/res").exists())
        for (dir in listOf("core/ui/src/main/res", "feature/onboarding/src/main", "app/src/main/res")) {
            val hits = File(r, dir).walkTopDown().filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".kt")) && it.readText().contains("Meet Studio") }.map { it.path }.toList()
            assertEquals("Studio words outside studioOn: $hits", emptyList<String>(), hits)
        }
    }
    @Test fun theOnboardingModuleDoesNotDependOnStudio() {
        val r = root(); assumeTrue(r != null)
        val gradle = File(r, "feature/onboarding/build.gradle.kts").readText()
        assertFalse(gradle.contains("studio"))
    }
    @Test fun theStudioScreenIsPartOfTheFlowOnlyThroughTheFlag() {
        val r = root(); assumeTrue(r != null)
        val off = File(r, "app/src/studioOff/kotlin/app/rawline/StudioEntry.kt").readText()
        assertTrue(off.contains("fun onboardingText(): StudioText? = null"))
        assertTrue(File(r, "app/src/main/kotlin/app/rawline/MainActivity.kt").readText().contains("Onboarding(PrefsStore(graph.prefs), StudioEntry.available)"))
    }
}

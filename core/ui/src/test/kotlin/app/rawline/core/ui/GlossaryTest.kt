package app.rawline.core.ui

import org.junit.Assert.*
import org.junit.Test

class GlossaryTest {
    @Test fun everyGlossaryLineIsReachableFromASliderName() {
        val all = R.string::class.java.fields.filter { it.name.startsWith("help_gloss_") }.map { it.getInt(null) }.toSet()
        assertEquals("ten glossary lines", 10, all.size)
        val mapped = listOf("Exposure", "Highlights", "Shadows", "Whites", "Blacks", "Texture", "Clarity", "Dehaze", "Vibrance", "Saturation", "Grain").map { Glossary.idFor(it) }
        assertTrue(mapped.all { it != null })
        assertEquals(all, mapped.filterNotNull().toSet())
    }
    @Test fun unknownNamesAndStrayWhitespace() {
        assertNull(Glossary.idFor("Midpoint"))
        assertEquals(Glossary.idFor("Exposure"), Glossary.idFor(" Exposure "))
    }
    @Test fun everyHelpTopicNamesRealResources() {
        for (t in HelpTopic.values()) { assertTrue(t.name, t.title != 0); assertTrue(t.name, t.items != 0) }
        assertEquals(6, HelpTopic.values().size)
    }
}

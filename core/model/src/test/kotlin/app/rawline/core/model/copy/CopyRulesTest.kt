package app.rawline.core.model.copy

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CopyRulesTest {
    private fun rules(t: String, k: CopyKind = CopyKind.TEXT) = CopyRules.check(t, k).map { it.rule }

    @Test fun cleanCopyPasses() {
        for (t in listOf("Rawline opens your Panasonic RW2 files fast and edits them without ever changing the originals.",
            "Double tap a slider name to reset it.", "Colour range", "Centre the crop", "Open the catalogue", "A licence is needed", "Grey card"))
            assertEquals(t, emptyList<String>(), rules(t))
    }
    @Test fun emDashAndSpacedEnDash() {
        assertEquals(listOf("em-dash"), rules("Export \u2014 now"))
        assertEquals(listOf("spaced-en-dash"), rules("Export \u2013 now"))
        assertEquals(emptyList<String>(), rules("Pages 3\u20135"))        // a range with no spaces is fine
    }
    @Test fun exclamation() { assertEquals(listOf("exclamation"), rules("Done!")) }
    @Test fun usSpellings() {
        for (t in listOf("Color range", "colors", "Gray card", "Center", "organize photos", "Organizing", "your favorites", "Optimize", "customize", "analyzes", "Catalog", "behavior", "Recognize", "normalization"))
            assertTrue(t, "spelling" in rules(t))
        for (t in listOf("Colour", "Grey", "Centre", "organise", "favourites", "optimise", "catalogue", "colourful", "Organisation", "Recognised"))
            assertFalse(t, "spelling" in rules(t))
    }
    @Test fun wordBoundariesAvoidFalsePositives() {
        for (t in listOf("Concentrate", "Decorate", "Concentric circles", "Epicentre", "Colorado")) assertFalse(t, "spelling" in rules(t))
    }
    @Test fun pluralMistakes() {
        for (t in listOf("1 photos", "Export 1 files now", "Delete photo(s)", "Undo 1 edits")) assertTrue(t, "plural" in rules(t))
        for (t in listOf("1 photo", "2 photos", "11 photos", "21 photos", "1.1 photos", "X photos", "Select photos")) assertFalse(t, "plural" in rules(t))
    }
    @Test fun modelNames() {
        assertEquals(listOf("name"), rules("Powered by " + "Cla" + "ude"))
        assertEquals(listOf("name"), rules("Uses LaMa to remove things"))
        assertEquals(emptyList<String>(), rules("Made for the Panasonic S5IIX and Samsung S24 Ultra"))
    }
    @Test fun whitespace() {
        assertEquals(listOf("whitespace"), rules(" Open"))
        assertEquals(listOf("whitespace"), rules("Open  now", CopyKind.TITLE))
        assertEquals(emptyList<String>(), rules("Open  now", CopyKind.TEXT))
    }
    @Test fun sentenceCaseTitles() {
        assertEquals(emptyList<String>(), rules("Edit your RAW photos on your phone", CopyKind.TITLE))
        assertEquals(emptyList<String>(), rules("Where are your photos?", CopyKind.TITLE))
        assertEquals(emptyList<String>(), rules("Turn on All files access", CopyKind.TITLE))
        assertEquals(listOf("sentence-case"), rules("Edit Your Photos", CopyKind.TITLE))
        assertEquals(emptyList<String>(), rules("Let Rawline see your photos", CopyKind.TITLE))
        assertEquals(emptyList<String>(), rules("Meet Studio", CopyKind.TITLE))
    }
    @Test fun buttonsStartWithAVerb() {
        for (t in listOf("Get started", "Allow photos", "Open settings", "Skip for now", "Not now", "Open my photos", "Export")) assertEquals(t, emptyList<String>(), rules(t, CopyKind.BUTTON))
        assertEquals(listOf("button-verb"), rules("Photos", CopyKind.BUTTON))
        assertEquals(listOf("button-verb"), rules("Settings", CopyKind.BUTTON))
        assertEquals(listOf("sentence-case", "button-verb").sorted(), rules("Photos Access", CopyKind.BUTTON).sorted())
    }
    @Test fun helpLength() {
        assertEquals(emptyList<String>(), rules("x".repeat(140), CopyKind.HELP))
        assertEquals(listOf("help-length"), rules("x".repeat(141), CopyKind.HELP))
    }
    @Test fun kindFromResourceName() {
        assertEquals(CopyKind.TITLE, CopyScan.kindOf("title_welcome")); assertEquals(CopyKind.BUTTON, CopyScan.kindOf("button_get_started"))
        assertEquals(CopyKind.BUTTON, CopyScan.kindOf("action_undo")); assertEquals(CopyKind.HELP, CopyScan.kindOf("help_editor_1")); assertEquals(CopyKind.TEXT, CopyScan.kindOf("body_welcome"))
    }
    @Test fun stringsXmlParsing() {
        val xml = """<resources>
  <string name="title_welcome">Edit your RAW photos on your phone</string>
  <string name="body_x">It\'s quick &amp; simple.</string>
  <string name="app_name" translatable="false">Rawline</string>
  <string name="quoted">"  keep spaces  "</string>
  <string-array name="help_library"><item>Pinch to change how many photos fit across.</item><item>Use the filter button.</item></string-array>
</resources>"""
        val f = CopyScan.stringsXml("strings.xml", xml)
        assertEquals(listOf("title_welcome", "body_x", "quoted", "help_library", "help_library"), f.map { it.name })
        assertEquals("It's quick & simple.", f[1].text); assertEquals("  keep spaces  ", f[2].text); assertEquals(CopyKind.HELP, f[3].kind); assertEquals(2, f[0].line)
    }
    @Test fun kotlinLiteralScan() {
        val src = """
            Text("Colour range", fontSize = 12.sp)
            Text(text = "Delete this layer?")
            val k = "not user visible"
            // Text("Color in a comment")
            Text("${'$'}{sel.size} selected")
            Toast.makeText(ctx, "Could not read that picture.", Toast.LENGTH_SHORT)
            label = "PNG"
        """.trimIndent()
        val f = CopyScan.kotlinLiterals("A.kt", src)
        assertEquals(listOf("Colour range", "Delete this layer?", "X selected", "Could not read that picture."), f.map { it.text })
        assertEquals(listOf(1, 2, 5, 6), f.map { it.line })
    }
    @Test fun broadScanFindsTextHiddenInPairsAndBranches() {
        val src = "LrTabs(listOf(\"noise\" to \"Color noise\"))\nval id = \"color\"\nelse -> \"Reset color mix\"\n// \"Color in a comment\"\nval n = \"\${a}\\u2014 gone\"\n"
        val f = CopyScan.textLikeLiterals("A.kt", src)
        assertEquals(listOf("Color noise", "Reset color mix", "X\u2014 gone"), f.filter { x -> CopyRules.check(x.text).any { it.rule in CopyScan.LEXICAL_RULES } }.map { it.text })
        assertEquals(listOf(1, 3, 5), f.filter { x -> CopyRules.check(x.text).any { it.rule in CopyScan.LEXICAL_RULES } }.map { it.line })
    }
    @Test fun docsIgnoreCodeFences() {
        val md = "A line \u2014 with a dash\n```\nfenced \u2014 ok\n```\nclean\n"
        assertEquals(listOf(1), CopyScan.docViolations("a.md", md).map { it.line })
    }
    @Test fun allowListNeedsReasons() {
        assertEquals(mapOf("Color picker" to "OS term"), CopyScan.parseAllow("# c\nColor picker\tOS term\n"))
        try { CopyScan.parseAllow("Color picker\n"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun aBadStringIsReportedWithItsFileAndLine() {
        // the shape of the failure the repository test prints: file, line, rule, text
        val f = CopyScan.kotlinLiterals("feature/x/X.kt", "val a = 1\nText(\"Color range!\")\n").single()
        val bad = CopyRules.check(f.text, f.kind).map { it.rule }
        assertEquals(2, f.line); assertEquals(listOf("spelling", "exclamation").sorted(), bad.sorted())
    }

    /** The real check over resources, Kotlin literals and docs. repo.root is set by Gradle (core/model/build.gradle.kts); skipped when the property is missing. */
    @Test fun repositoryCopyFollowsTheRules() {
        val rootProp = System.getProperty("repo.root"); assumeTrue(rootProp != null)
        val root = File(rootProp!!)
        val skipDirs = setOf("build", ".git", ".gradle", ".claude", ".android-sdk", "node_modules", "test", "androidTest", "tools", "docs", "dist")
        val skip = { f: File -> f.name in skipDirs }
        val allowFile = File(root, "docs/copy-allow.txt")
        val allow = if (allowFile.exists()) CopyScan.parseAllow(allowFile.readText()) else emptyMap()
        val found = ArrayList<Found>()
        for (f in CopyScan.walk(root, skip) { it.name.startsWith("strings") && it.name.endsWith(".xml") }) found += CopyScan.stringsXml(f.relativeTo(root).path, f.readText())
        for (f in CopyScan.walk(root, skip) { it.name.endsWith(".kt") }) found += CopyScan.kotlinLiterals(f.relativeTo(root).path, f.readText())
        // the broad pass: text hiding outside the common calls, checked for the word rules only
        for (f in CopyScan.walk(root, skip) { it.name.endsWith(".kt") }) found += CopyScan.textLikeLiterals(f.relativeTo(root).path, f.readText())
        assertTrue("the scanner found only ${found.size} strings, it must be blind", found.size >= 80)   // guards against a scanner that stops matching
        val bad = LinkedHashSet<String>()
        for (x in found) if (x.text !in allow) for (v in CopyRules.check(x.text, x.kind)) if (x.name != "text" || v.rule in CopyScan.LEXICAL_RULES) bad += "${x.file}:${x.line} [${v.rule}] ${v.detail}: \"${x.text}\""
        for (f in File(root, "docs").listFiles { g -> g.name.endsWith(".md") }.orEmpty().sortedBy { it.name }) for (d in CopyScan.docViolations(f.name, f.readText())) bad += "docs/${d.file}:${d.line} [em-dash] ${d.text}"
        assertTrue("copy rule violations (fix the text, or add it to docs/copy-allow.txt with a reason):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}

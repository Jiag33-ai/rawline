# W27 First run onboarding, help, glossary, messages and the copy rules check

Status at writing: main e75d824 plus W11's uncommitted Studio UI. Covers BK-392 (onboarding deck), BK-393 (help sheets), BK-394 (glossary), BK-395 (14 messages) and BK-396 (copy guide and automatic check). The Kotlin in section 4 (CopyRules, CopyScan, Onboarding, NotificationAsk) was compiled with Kotlin 2.4.10 and run on the host JVM: 32 JUnit tests pass (16 rules, 12 flow, 4 resources), and two negative controls (a bad resource file, and a bad Kotlin literal plus a dash in docs) were made to fail on purpose and did. The Compose screens, the Gradle wiring and the permission launchers are NOT compiled; they are specified exactly in sections 5 and 6.

## 1. Facts found while writing it
- The app has no `strings.xml` at all: every user visible word is a literal in Compose code (135 distinct literals match the patterns `Text(`, `text =`, `label =`, `title =`, `subtitle =`, `contentDescription =`, `placeholder =`, `message =`, `hint =`, `Toast.makeText`). This task does not move them; it adds the new copy as resources and makes the checker read both resources and those Kotlin literals.
- Run against today's tree (read only) the checker finds no violation in those literals and none in the docs (docs/*.md have no em dash). Coverage limit: a literal passed another way (a variable, a `when` branch returning text, a string template with nested quotes) is not seen. The scanner guard test fails if it finds fewer than 80 strings, so it cannot go quietly blind.
- `docs/COPY.md` does not exist yet and BK-203's `TextRulesTest` is not in the repo: `CopyRulesTest` replaces it for text (keep BK-203 for any non-text rules it proposed).

## 2. Decisions (no questions left)
- D1 Copy is exactly the deck in section 3 (the wording of BK-392 to BK-395 as logged). Jai may change words later; the tests protect the rules, not the sentences.
- D2 Resource files live in `core/ui/src/main/res/values/` as `strings_onboarding.xml`, `strings_help.xml`, `strings_glossary.xml`, `strings_messages.xml` (core/ui already has a `res` folder). The onboarding screens live in a new module `feature/onboarding`; the help sheet in `core/ui/HelpSheet`.
- D3 The flow is a pure state machine (`Onboarding`) so the rules (skip, resume, permission re-read, reset) are tested without a phone. Screens only render `current` and send events.
- D4 Permissions: photos through the existing permission state machine in MainActivity (W16); All files access through `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` for the package; the result is read again in `onResume` and sent as `permissionsChanged()`. Never a blocking wall: every screen has Skip.
- D5 The notification question is not part of the first run: it is asked once at the first export (`NotificationAsk.shouldAsk`, Android 13 and later only).
- D6 Help: a "?" button on each screen opens a bottom sheet listing the `help_<screen>` array; Settings > Help lists all arrays, "Show the welcome screens again", "Reset tips" (BK-397 later) and "Report a problem" (opens Share report). The glossary shows on long press of a slider name when `action_show_explanations` is on (default on, one tooltip shown the first time). Long press must not conflict with the slider drag: use the label only, not the track.
- D7 Messages (`error_*`) are used by whichever W-task owns the situation (BK-157, 176, 296, 303, 324, 354, 355, 359, 364 as named in BK-395). This task only ships the strings and the `Copy details` label; wiring each is a one line change by the owner.

## 3. The deck (checked by the tests; this is the file content)
### core/ui/src/main/res/values/strings_onboarding.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- BK-392. Names: title_ (sentence case), body_, button_ (starts with a verb), link_, choice_, note_. Checked by CopyRulesTest. -->
    <string name="title_welcome">Edit your RAW photos on your phone</string>
    <string name="body_welcome">Rawline opens your Panasonic RW2 files fast and edits them without ever changing the originals.</string>
    <string name="button_get_started">Get started</string>

    <string name="title_photos">Let Rawline see your photos</string>
    <string name="body_photos">Rawline needs to see your photos to show them. Nothing leaves your phone.</string>
    <string name="button_allow_photos">Allow photos</string>
    <string name="link_why_photos">Why do you need this?</string>
    <string name="body_why_photos">Android lists JPEG and HEIC photos by itself, but it often hides RAW files. Allow all files access on the next screen to see your RW2 files.</string>

    <string name="title_raw_files">Show your RAW files</string>
    <string name="body_raw_files">Turn on All files access so your RW2 files appear next to your other photos.</string>
    <string name="button_open_settings">Open settings</string>
    <string name="button_skip_for_now">Skip for now</string>
    <string name="note_raw_skipped">You can turn this on later in Settings. Until then RAW files may be missing.</string>

    <string name="title_where_from">Where are your photos?</string>
    <string name="choice_camera_roll">Camera roll</string>
    <string name="choice_folder_or_card">Choose a folder or SD card</string>
    <string name="choice_import_files">Import files</string>
    <string name="note_where_from">Rawline reads your photos where they are. It does not copy them unless you import from a card.</string>

    <string name="title_gestures">Three things to try</string>
    <string name="body_gesture_select">Touch and hold a photo to select it.</string>
    <string name="body_gesture_original">In the editor, hold the photo to see the original.</string>
    <string name="body_gesture_reset">Double tap a slider name to reset it.</string>
    <string name="button_open_my_photos">Open my photos</string>

    <string name="title_notifications">Keep exports running</string>
    <string name="body_notifications">Rawline shows a notification while it exports so Android keeps it running. You can turn it off any time.</string>
    <string name="button_allow">Allow</string>
    <string name="button_not_now">Not now</string>

    <string name="button_skip">Skip</string>
    <string name="button_back">Back</string>
    <string name="button_continue">Continue</string>

    <!-- Settings > Help -->
    <string name="title_help">Help</string>
    <string name="action_show_welcome_again">Show the welcome screens again</string>
    <string name="action_reset_tips">Reset tips</string>
    <string name="action_report_problem">Report a problem</string>
    <string name="action_show_explanations">Show explanations</string>
    <string name="note_show_explanations">Touch and hold a slider name to see what it does.</string>
</resources>
```
### core/ui/src/main/res/values/strings_help.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- BK-393. One array per screen, each item under 140 characters. Checked by CopyRulesTest (kind help_). -->
    <string-array name="help_library">
        <item>Touch and hold a photo to select it. Then tap more photos.</item>
        <item>Pinch to change how many photos fit across.</item>
        <item>Use the filter button to show picks, ratings or edited photos.</item>
        <item>Rejected photos are hidden. Tap Rejects to see them.</item>
        <item>Pick or reject while culling: open a photo and use the flag buttons.</item>
    </string-array>
    <string-array name="help_viewer">
        <item>Swipe left or right to move between photos.</item>
        <item>Double tap to zoom. Pinch for more.</item>
        <item>Swipe up for details about the shot.</item>
        <item>Tap Edit to open the photo in the editor.</item>
    </string-array>
    <string-array name="help_editor">
        <item>Drag a slider to change it. Double tap its name to reset.</item>
        <item>Tap the number to type an exact value.</item>
        <item>Hold the photo to see the original.</item>
        <item>Undo and Redo are at the top. Your edits save by themselves.</item>
        <item>Tap the photo to hide the panel.</item>
    </string-array>
    <string-array name="help_masks">
        <item>A mask changes only part of the photo. Add one, then set what it does.</item>
        <item>Subtract takes an area away. Intersect keeps only where two masks overlap.</item>
        <item>AI tools download a model the first time. Use Wi-Fi if you can.</item>
    </string-array>
    <string-array name="help_remove">
        <item>Paint over what you want gone. Heal and Clone copy from nearby. Remove uses AI.</item>
    </string-array>
    <string-array name="help_export">
        <item>Export makes a new file. Your original is never changed.</item>
        <item>Choose a size and format, then tap Export. You can leave the app while it runs.</item>
        <item>Find your photos in Pictures/Rawline, or in the folder you chose.</item>
    </string-array>
</resources>
```
### core/ui/src/main/res/values/strings_glossary.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- BK-394. Shown on long press of a slider name when "Show explanations" is on. Each under 140 characters. -->
    <string name="help_gloss_exposure">Exposure: overall brightness. Move right to brighten.</string>
    <string name="help_gloss_highlights">Highlights: only the brightest parts. Pull left to bring back sky detail.</string>
    <string name="help_gloss_shadows">Shadows: only the darkest parts. Move right to lift them.</string>
    <string name="help_gloss_whites_blacks">Whites and Blacks: set the brightest and darkest points.</string>
    <string name="help_gloss_texture">Texture: fine detail such as skin and bark.</string>
    <string name="help_gloss_clarity">Clarity: contrast in the middle tones. Adds punch.</string>
    <string name="help_gloss_dehaze">Dehaze: cuts through haze and mist, or adds it.</string>
    <string name="help_gloss_vibrance">Vibrance: boosts dull colours and protects strong ones.</string>
    <string name="help_gloss_saturation">Saturation: all colours at once.</string>
    <string name="help_gloss_grain">Grain: film-like noise for a classic look.</string>
</resources>
```
### core/ui/src/main/res/values/strings_messages.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- BK-395. The 14 messages Jai will actually see. Each says what happened and what to try. Details go behind a Copy details action. -->
    <string name="error_no_photos">No photos yet. Allow access to see your camera roll.</string>
    <string name="error_raw_missing">RAW files are missing. Turn on All files access.</string>
    <string name="error_cannot_open">This photo could not be opened. The file may be damaged.</string>
    <string name="error_no_space">Not enough space. Free about 300 MB and try again.</string>
    <string name="error_export_folder">Export folder not available. Choose another folder or save to Pictures/Rawline.</string>
    <string name="error_newer_edit">This edit was made by a newer version of Rawline, so it was left alone.</string>
    <string name="error_model_download">The AI model could not be downloaded. Check your connection and try again.</string>
    <string name="error_ai_slow">AI tools are running slower on your phone\'s processor right now.</string>
    <string name="error_warm">Your phone is warm. Rawline paused to cool down.</string>
    <string name="error_battery_low">Battery is low. Export paused until you charge.</string>
    <string name="error_backup_damaged">The backup is damaged and was not restored.</string>
    <string name="error_nothing_to_import">Nothing new to import.</string>
    <string name="error_card_removed">The card was removed. Put it back to continue.</string>
    <string name="error_large_file">This file is very large, so it opened at half size.</string>
    <string name="action_copy_details">Copy details</string>
</resources>
```

## 4. Kotlin (compiled and tested)
Files: `core/model/src/main/kotlin/app/rawline/core/model/copy/CopyRules.kt`, `.../onboarding/Onboarding.kt`, and the tests under `core/model/src/test/kotlin/app/rawline/core/model/copy/` and `.../onboarding/`.
### CopyRules.kt
```kotlin
package app.rawline.core.model.copy

import java.io.File
import java.util.Locale

/** The mechanical part of docs/COPY.md (BK-396). Pure JVM, no Android. Used by the text rules test over resources, Kotlin string literals and docs. */

enum class Kind { TEXT, TITLE, BUTTON, HELP }

data class Violation(val rule: String, val text: String, val detail: String)

object CopyRules {
    /** US spelling stem -> what to write. Matched on whole words, case-insensitive, with the usual endings. */
    private val SPELLING: List<Pair<Regex, String>> = listOf(
        "color" to "colour", "gray" to "grey", "center" to "centre", "favorite" to "favourite", "behavior" to "behaviour",
        "catalog" to "catalogue", "neighbor" to "neighbour", "flavor" to "flavour", "honor" to "honour", "labor" to "labour",
    ).map { (us, au) -> Regex("\\b${us}(s|ed|ing)?\\b", RegexOption.IGNORE_CASE) to au } + listOf(
        "organiz" to "organis", "customiz" to "customis", "optimiz" to "optimis", "analyz" to "analys", "recogniz" to "recognis",
        "normaliz" to "normalis", "minimiz" to "minimis", "maximiz" to "maximis", "summariz" to "summaris", "synchroniz" to "synchronis",
        "personaliz" to "personalis", "initializ" to "initialis", "utiliz" to "utilis", "realiz" to "realis", "prioritiz" to "prioritis",
    ).map { (us, au) -> Regex("\\b${us}(e|es|ed|ing|ation|ations)\\b", RegexOption.IGNORE_CASE) to au + "e/ed/ing/ation" }

    /** Names the copy must not carry (BK-396): AI model and vendor names. Panasonic and Samsung are allowed. */
    private val BANNED_NAMES = Regex("\\b(Claude|Anthropic|OpenAI|ChatGPT|GPT|Gemini|Gemma|LLaMA|LaMa|SegFormer|MobileSAM)\\b")

    /** Words after the first that may start with a capital in a title or button. */
    val PROPER = setOf("Rawline", "Panasonic", "Samsung", "Android", "Google", "Wi-Fi", "RW2", "RAW", "JPEG", "HEIC", "PNG", "TIFF", "DNG", "AI", "SD", "USB-C", "S", "Pen", "Lightroom", "Studio", "Develop", "Photos", "Files")

    val BUTTON_FIRST_WORDS = setOf(
        "Get", "Allow", "Open", "Skip", "Export", "Not", "Cancel", "Done", "OK", "Add", "Apply", "Back", "Choose", "Clear", "Close", "Copy", "Create", "Delete",
        "Edit", "Import", "Keep", "Paste", "Reset", "Restore", "Retry", "Save", "Select", "Set", "Share", "Show", "Start", "Try", "Turn", "Undo", "Redo", "Use",
        "Continue", "Next", "Remove", "Rename", "Update", "View", "Report", "Pick", "Move", "Duplicate", "Hide", "Download", "Learn", "Why",
    )

    /** Names of Android settings that keep their capitals (matched as a whole phrase and ignored by the sentence case rule). */
    val PHRASES = listOf("All files access")

    const val HELP_MAX = 140

    fun check(raw: String, kind: Kind = Kind.TEXT): List<Violation> {
        val text = raw
        val out = ArrayList<Violation>()
        fun v(rule: String, detail: String) { out += Violation(rule, text, detail) }
        if (text.contains('\u2014')) v("em-dash", "use a full stop or a comma")
        if (text.contains(" – ")) v("spaced-en-dash", "use a full stop, a comma or 'to' in ranges")
        if (text.contains('!')) v("exclamation", "no exclamation marks")
        for ((re, au) in SPELLING) re.find(text)?.let { v("spelling", "'${it.value}' should be '$au'") }
        BANNED_NAMES.find(text)?.let { v("name", "'${it.value}' is a model or vendor name") }
        if (text != text.trim()) v("whitespace", "leading or trailing space")
        if (text.contains("  ") && kind != Kind.TEXT) v("whitespace", "double space")
        if (kind == Kind.TITLE || kind == Kind.BUTTON) {
            var body = text; for (ph in PHRASES) body = body.replace(ph, ph.lowercase(Locale.ROOT))
            val words = body.split(' ').filter { it.isNotEmpty() }
            for (w in words.drop(1)) {
                val bare = w.trim('.', ',', '?', '"', ':', ';', '(', ')')
                if (bare.isNotEmpty() && bare[0].isUpperCase() && bare !in PROPER && bare.any { it.isLowerCase() }) { v("sentence-case", "'$bare' should not start with a capital"); break }
            }
        }
        if (kind == Kind.BUTTON) {
            val first = text.trim().split(' ').firstOrNull().orEmpty().trim('.', ',')
            if (first !in BUTTON_FIRST_WORDS) v("button-verb", "'$first' is not on the verb list (add it in CopyRules if it is a verb)")
        }
        if (kind == Kind.HELP && text.length > HELP_MAX) v("help-length", "${text.length} characters, limit $HELP_MAX")
        return out
    }
}

/** A user visible string found in a file. [kind] comes from the resource name for XML, TEXT for Kotlin. */
data class Found(val file: String, val line: Int, val name: String, val text: String, val kind: Kind)

object CopyScan {
    fun kindOf(name: String): Kind = when {
        name.startsWith("title_") -> Kind.TITLE
        name.startsWith("button_") || name.startsWith("action_") -> Kind.BUTTON
        name.startsWith("help_") || name.startsWith("tip_") -> Kind.HELP
        else -> Kind.TEXT
    }

    private val ENTRY = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val ITEM = Regex("""<item[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val ARRAY = Regex("""<string-array\s+name="([^"]+)"[^>]*>(.*?)</string-array>""", setOf(RegexOption.DOT_MATCHES_ALL))

    private fun unescapeXml(s: String): String {
        var t = s.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
        return t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"")
    }

    fun stringsXml(file: String, xml: String): List<Found> {
        val out = ArrayList<Found>()
        fun lineOf(i: Int) = xml.substring(0, i).count { it == '\n' } + 1
        for (m in ENTRY.findAll(xml)) {
            if (m.value.contains("translatable=\"false\"")) continue
            val name = m.groupValues[1]
            out += Found(file, lineOf(m.range.first), name, unescapeXml(m.groupValues[2]), kindOf(name))
        }
        for (a in ARRAY.findAll(xml)) for (it in ITEM.findAll(a.groupValues[2])) out += Found(file, lineOf(a.range.first), a.groupValues[1], unescapeXml(it.groupValues[1]), kindOf(a.groupValues[1]))
        return out
    }

    private val LITERAL = Regex("""(?:\bText\(|\btext\s*=\s*|\blabel\s*=\s*|\btitle\s*=\s*|\bsubtitle\s*=\s*|\bcontentDescription\s*=\s*|\bplaceholder\s*=\s*|\bmessage\s*=\s*|\bhint\s*=\s*|Toast\.makeText\([^,]*,\s*)"((?:[^"\\]|\\.)*)"""")

    /** Static text of user visible Kotlin literals: `$name` and `${...}` are replaced by an X so the words around them are still checked. */
    fun kotlinLiterals(file: String, src: String): List<Found> {
        val out = ArrayList<Found>()
        src.lineSequence().forEachIndexed { i, line ->
            if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
            for (m in LITERAL.findAll(line)) {
                var t = m.groupValues[1].replace(Regex("""\$\{[^}]*\}"""), "X").replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "X").replace("\\\"", "\"").replace("\\n", "\n")
                if (t.contains('$') || t.length < 2 || !t.any { it.isLetter() }) continue   // a template with nested quotes is cut short by the regex: skip it
                if (t.all { !it.isLetter() || it.isUpperCase() } && t.length < 6) continue   // short codes such as "RW2" or "PNG"
                out += Found(file, i + 1, "literal", t, Kind.TEXT)
            }
        }
        return out
    }

    /** Em dashes are banned everywhere in docs and notes, outside code fences. */
    fun docViolations(file: String, md: String): List<Found> {
        var fenced = false
        val out = ArrayList<Found>()
        md.lineSequence().forEachIndexed { i, line ->
            if (line.trimStart().startsWith("```")) { fenced = !fenced; return@forEachIndexed }
            if (!fenced && line.contains('\u2014')) out += Found(file, i + 1, "doc", line.trim(), Kind.TEXT)
        }
        return out
    }

    /** Allow list: lines of `text<TAB>reason`; a found text equal to an allowed text is skipped. Every entry must have a reason. */
    fun parseAllow(text: String): Map<String, String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate {
        val p = it.split('\t', limit = 2); require(p.size == 2 && p[1].isNotBlank()) { "allow list line needs a reason: $it" }; p[0] to p[1]
    }

    fun walk(root: File, ext: String, skip: (File) -> Boolean): List<File> =
        root.walkTopDown().onEnter { !skip(it) }.filter { it.isFile && it.name.endsWith(ext) }.toList().sortedBy { it.path }
}
```
### Onboarding.kt
```kotlin
package app.rawline.core.model.onboarding

/** The first-run flow as a pure state machine (BK-191, BK-392). The Compose screens only render [Onboarding.current] and send events. */

enum class Step { WELCOME, PHOTOS, RAW_FILES, WHERE_FROM, GESTURES }

/** What the phone says right now; read again every time the app returns from a system screen. */
data class Perms(val photosGranted: Boolean, val allFilesGranted: Boolean)

/** Small persistent store (SharedPreferences in the app, a map in tests). Reads and writes must not throw. */
interface Store { fun getInt(key: String, default: Int): Int; fun putInt(key: String, value: Int); fun getBool(key: String, default: Boolean): Boolean; fun putBool(key: String, value: Boolean) }

class MapStore : Store {
    val m = HashMap<String, Any>()
    override fun getInt(key: String, default: Int) = (m[key] as? Int) ?: default
    override fun putInt(key: String, value: Int) { m[key] = value }
    override fun getBool(key: String, default: Boolean) = (m[key] as? Boolean) ?: default
    override fun putBool(key: String, value: Boolean) { m[key] = value }
}

/**
 * Rules:
 * - Every screen can be skipped; a skip of the whole flow marks it done.
 * - PHOTOS is not shown when photos are already allowed, RAW_FILES not when All files access is already on (the flow re-reads [Perms] each time).
 * - A denied photo permission never blocks: the flow moves on and the library shows its own "No photos yet" message.
 * - The position is saved after every move, so a process death resumes at the same screen; finishing writes `done` and never shows the flow again unless reset from Settings > Help.
 * - The notification question is separate: it is asked once, at the first export, and only when the permission is not yet granted.
 */
class Onboarding(private val store: Store, private var perms: () -> Perms) {
    var current: Step = start()
        private set
    val done: Boolean get() = store.getBool(DONE, false)
    var rawSkipped = false
        private set

    private fun start(): Step {
        if (store.getBool(DONE, false)) return Step.GESTURES
        val saved = store.getInt(POS, 0).coerceIn(0, Step.values().size - 1)
        return normalise(Step.values()[saved])
    }

    /** Moves forward over screens that no longer apply. */
    private fun normalise(s: Step): Step {
        var x = s
        while (true) {
            val p = perms()
            val skipThis = (x == Step.PHOTOS && p.photosGranted) || (x == Step.RAW_FILES && p.allFilesGranted)
            if (!skipThis || x == Step.GESTURES) return x
            x = Step.values()[x.ordinal + 1]
        }
    }

    private fun go(s: Step) { current = s; store.putInt(POS, s.ordinal) }

    fun next() {
        if (current == Step.GESTURES) { finish(); return }
        go(normalise(Step.values()[current.ordinal + 1]))
    }

    /** "Skip for now" on the RAW screen: keeps going and remembers to show the note once. */
    fun skipRaw() { if (current == Step.RAW_FILES) { rawSkipped = true; next() } }

    fun back() {
        var i = current.ordinal - 1
        while (i >= 0) {
            val s = Step.values()[i]
            val p = perms()
            if (!((s == Step.PHOTOS && p.photosGranted) || (s == Step.RAW_FILES && p.allFilesGranted))) { go(s); return }
            i--
        }
    }

    /** The system screen returned: a granted permission moves past its own screen. */
    fun permissionsChanged() { go(normalise(current)) }

    fun skipAll() = finish()
    private fun finish() { store.putBool(DONE, true); current = Step.GESTURES }

    /** Settings > Help > Show the welcome screens again. */
    fun reset() { store.putBool(DONE, false); store.putInt(POS, 0); rawSkipped = false; current = normalise(Step.WELCOME) }

    companion object { const val DONE = "onboarding.done"; const val POS = "onboarding.pos" }
}

/** The notification question (spec in BK-392, screen 6): once, at the first export, only if the permission is not granted. */
object NotificationAsk {
    const val KEY = "notifications.asked"
    fun shouldAsk(store: Store, isFirstExport: Boolean, granted: Boolean, sdk: Int): Boolean = sdk >= 33 && isFirstExport && !granted && !store.getBool(KEY, false)
    fun markAsked(store: Store) = store.putBool(KEY, true)
}
```
### CopyRulesTest.kt
```kotlin
package app.rawline.core.model.copy

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CopyRulesTest {
    private fun rules(t: String, k: Kind = Kind.TEXT) = CopyRules.check(t, k).map { it.rule }

    @Test fun cleanCopyPasses() {
        for (t in listOf("Rawline opens your Panasonic RW2 files fast and edits them without ever changing the originals.",
            "Double tap a slider name to reset it.", "Colour range", "Centre the crop", "Open the catalogue", "A licence is needed", "Grey card"))
            assertEquals(t, emptyList<String>(), rules(t))
    }
    @Test fun emDashAndSpacedEnDash() {
        assertEquals(listOf("em-dash"), rules("Export \u2014 now"))
        assertEquals(listOf("spaced-en-dash"), rules("Export – now"))
        assertEquals(emptyList<String>(), rules("Pages 3–5"))        // a range with no spaces is fine
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
    @Test fun modelNames() {
        assertEquals(listOf("name"), rules("Powered by Claude"))
        assertEquals(listOf("name"), rules("Uses LaMa to remove things"))
        assertEquals(emptyList<String>(), rules("Made for the Panasonic S5IIX and Samsung S24 Ultra"))
    }
    @Test fun whitespace() {
        assertEquals(listOf("whitespace"), rules(" Open"))
        assertEquals(listOf("whitespace"), rules("Open  now", Kind.TITLE))
        assertEquals(emptyList<String>(), rules("Open  now", Kind.TEXT))
    }
    @Test fun sentenceCaseTitles() {
        assertEquals(emptyList<String>(), rules("Edit your RAW photos on your phone", Kind.TITLE))
        assertEquals(emptyList<String>(), rules("Where are your photos?", Kind.TITLE))
        assertEquals(emptyList<String>(), rules("Turn on All files access", Kind.TITLE))
        assertEquals(listOf("sentence-case"), rules("Edit Your Photos", Kind.TITLE))
        assertEquals(emptyList<String>(), rules("Let Rawline see your photos", Kind.TITLE))
    }
    @Test fun buttonsStartWithAVerb() {
        for (t in listOf("Get started", "Allow photos", "Open settings", "Skip for now", "Not now", "Open my photos", "Export")) assertEquals(t, emptyList<String>(), rules(t, Kind.BUTTON))
        assertEquals(listOf("button-verb"), rules("Photos", Kind.BUTTON))
        assertEquals(listOf("button-verb"), rules("Settings", Kind.BUTTON))
        assertEquals(listOf("sentence-case", "button-verb").sorted(), rules("Photos Access", Kind.BUTTON).sorted())
    }
    @Test fun helpLength() {
        assertEquals(emptyList<String>(), rules("x".repeat(140), Kind.HELP))
        assertEquals(listOf("help-length"), rules("x".repeat(141), Kind.HELP))
    }
    @Test fun kindFromResourceName() {
        assertEquals(Kind.TITLE, CopyScan.kindOf("title_welcome")); assertEquals(Kind.BUTTON, CopyScan.kindOf("button_get_started"))
        assertEquals(Kind.BUTTON, CopyScan.kindOf("action_undo")); assertEquals(Kind.HELP, CopyScan.kindOf("help_editor_1")); assertEquals(Kind.TEXT, CopyScan.kindOf("body_welcome"))
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
        assertEquals("It's quick & simple.", f[1].text); assertEquals("  keep spaces  ", f[2].text); assertEquals(Kind.HELP, f[3].kind); assertEquals(2, f[0].line - 1 + 1)
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
    @Test fun docsIgnoreCodeFences() {
        val md = "A line \u2014 with a dash\n```\nfenced \u2014 ok\n```\nclean\n"
        assertEquals(listOf(1), CopyScan.docViolations("a.md", md).map { it.line })
    }
    @Test fun allowListNeedsReasons() {
        assertEquals(mapOf("Color picker" to "OS term"), CopyScan.parseAllow("# c\nColor picker\tOS term\n"))
        try { CopyScan.parseAllow("Color picker\n"); fail() } catch (_: IllegalArgumentException) {}
    }

    /** The real check. repo.root is set by Gradle (see COPY wiring). Skipped when the property is missing. Failures list file, line and the rule. */
    @Test fun repositoryCopyFollowsTheRules() {
        val rootProp = System.getProperty("repo.root"); assumeTrue(rootProp != null)
        val root = File(rootProp!!)
        val skip = { f: File -> f.name in setOf("build", ".git", ".gradle", ".claude", ".android-sdk", "node_modules", "test", "androidTest", "tools") && f.isDirectory }
        val allowFile = File(root, "docs/copy-allow.txt")
        val allow = if (allowFile.exists()) CopyScan.parseAllow(allowFile.readText()) else emptyMap()
        val found = ArrayList<Found>()
        for (f in CopyScan.walk(root, "strings.xml", skip)) found += CopyScan.stringsXml(f.relativeTo(root).path, f.readText())
        for (f in CopyScan.walk(root, ".kt", skip)) found += CopyScan.kotlinLiterals(f.relativeTo(root).path, f.readText())
        assertTrue("the scanner found only ${found.size} strings, it must be blind", found.size >= 80)   // guards against a scanner that stops matching
        val bad = ArrayList<String>()
        for (x in found) if (x.text !in allow) for (v in CopyRules.check(x.text, x.kind)) bad += "${x.file}:${x.line} [${v.rule}] ${v.detail}: \"${x.text}\""
        for (f in File(root, "docs").listFiles { g -> g.name.endsWith(".md") }.orEmpty()) for (d in CopyScan.docViolations(f.name, f.readText())) bad += "docs/${d.file}:${d.line} [em-dash] ${d.text}"
        assertTrue("copy rule violations (fix the text, or add it to docs/copy-allow.txt with a reason):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
```
### OnboardingTest.kt
```kotlin
package app.rawline.core.model.onboarding

import org.junit.Assert.*
import org.junit.Test

class OnboardingTest {
    private var p = Perms(false, false)
    private fun flow(store: Store = MapStore()) = Onboarding(store) { p }

    @Test fun walksTheFiveScreensInOrderAndFinishes() {
        val f = flow()
        assertEquals(Step.WELCOME, f.current)
        f.next(); assertEquals(Step.PHOTOS, f.current)
        f.next(); assertEquals(Step.RAW_FILES, f.current)
        f.next(); assertEquals(Step.WHERE_FROM, f.current)
        f.next(); assertEquals(Step.GESTURES, f.current); assertFalse(f.done)
        f.next(); assertTrue(f.done)
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
        val g = flow(s); assertEquals(Step.RAW_FILES, g.current); assertFalse(g.done)
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
        f.reset(); assertFalse(f.done); assertEquals(Step.WELCOME, f.current); assertFalse(flow(s).done)
    }
    @Test fun corruptSavedPositionIsClamped() {
        val s = MapStore(); s.putInt(Onboarding.POS, 99); assertEquals(Step.GESTURES, flow(s).current)
        s.putInt(Onboarding.POS, -5); assertEquals(Step.WELCOME, flow(s).current)
    }
    @Test fun notificationAskedOnceAtFirstExportOnly() {
        val s = MapStore()
        assertFalse(NotificationAsk.shouldAsk(s, isFirstExport = false, granted = false, sdk = 34))
        assertFalse(NotificationAsk.shouldAsk(s, true, true, 34))
        assertFalse(NotificationAsk.shouldAsk(s, true, false, 32))   // below Android 13 there is no runtime question
        assertTrue(NotificationAsk.shouldAsk(s, true, false, 34))
        NotificationAsk.markAsked(s); assertFalse(NotificationAsk.shouldAsk(s, true, false, 34))
    }
}
```
### StringsResourcesTest.kt
```kotlin
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
}
```

## 5. Gradle wiring (not run)
In `core/model/build.gradle.kts` inside `android { testOptions { ... } }` add:
```kotlin
unitTests.all {
    it.systemProperty("repo.root", rootDir.path)
    it.systemProperty("res.dir", File(rootDir, "core/ui/src/main/res/values").path)
}
```
The repository scan skips folders named build, .git, .gradle, .claude, .android-sdk, node_modules, test, androidTest and tools, so tests and scripts may contain any text. Add `docs/copy-allow.txt` (empty, with a comment line) and `docs/COPY.md` (section 8). CI: nothing new, the check runs inside `testDebugUnitTest`.

## 6. Screens (Compose, not compiled)
- One `OnboardingScreen(state, events)` per `Step`, full screen, title (`title_*`, headline), body (`body_*`), primary button at the bottom (48 dp high minimum, full width), a `button_skip` text button top right and `button_back` bottom left from the second screen on. Content scrolls so it fits at font scale 1.3; no fixed heights.
- WELCOME: title_welcome, body_welcome, button_get_started. PHOTOS: title_photos, body_photos, button_allow_photos, link_why_photos expands body_why_photos inline. RAW_FILES: title_raw_files, body_raw_files, button_open_settings, button_skip_for_now; after returning with the permission still off and `rawSkipped` true show `note_raw_skipped` once as a snackbar. WHERE_FROM: title_where_from and three `choice_*` rows (Camera roll opens the library, Choose a folder or SD card opens the existing folder picker, Import files opens the document picker), `note_where_from` at the bottom. GESTURES: title_gestures, the three `body_gesture_*` lines with a simple icon each, `button_open_my_photos` finishes the flow.
- Accessibility: every button has a contentDescription equal to its text; headings are marked with `heading()`; the Skip button is the first focus target after the title so TalkBack users are never trapped; no animation longer than 300 ms, none when animations are off.
- Back gesture: goes to the previous screen (`back()`), and from WELCOME closes the app (no trap).
- Entry: MainActivity shows the flow when `!onboarding.done` and the library is empty or permissions are missing; a returning user with a populated library never sees it (check `done || photoCount > 0` and mark done silently).
- Persistence: `Store` is a thin wrapper over SharedPreferences (`apply()`, never throwing); the keys are `Onboarding.DONE`, `Onboarding.POS` and `NotificationAsk.KEY`.

## 7. Acceptance
1. `./gradlew :core:model:testDebugUnitTest` passes all 32 tests including the repository scan.
2. Adding `Text("Color range!")` to any Compose file makes the build fail with file and line; removing it makes it pass (do this once by hand and paste the failure).
3. On the phone (fresh install, Jai taps through): the five screens appear in order with the exact words; Skip on every screen works; killing the app on screen 3 resumes on screen 3; granting All files access in system settings moves the flow past screen 3 on return; the welcome screens can be shown again from Settings > Help.
4. At font scale 1.3 and in TalkBack every screen is readable and reachable (BK-170); no screen needs a network.
5. The first export asks the notification question once on Android 13 and later; never again.

## 8. docs/COPY.md (file content)
```markdown
# Copy guide (docs/COPY.md)

Who reads this: anyone who writes a word the app shows. The mechanical rules are enforced by `CopyRulesTest` (core/model), so a violation fails CI.

1. Australian English. colour, grey, centre, catalogue, organise, optimise, customise, favourite, behaviour, analyse. Licence is the noun, license the verb. The checker lists the US spellings it rejects in `CopyRules`.
2. No em dashes. Use a full stop or a comma. A spaced en dash is also rejected; a range such as 3 to 5 is written with "to".
3. No exclamation marks.
4. Sentence case for titles and buttons: only the first word and proper names start with a capital ("Edit your RAW photos on your phone"). Proper names that keep capitals are listed in `CopyRules.PROPER`; Android's own setting names are in `CopyRules.PHRASES` ("All files access").
5. A button starts with a verb ("Allow photos", "Open settings"). "Not now" is the one allowed exception. A new verb goes on `CopyRules.BUTTON_FIRST_WORDS` in the same pull request.
6. Help items are under 140 characters, one idea each, in plain words. A term the reader may not know gets a glossary line (`help_gloss_*`).
7. Say what happened and what to try ("Not enough space. Free about 300 MB and try again."). Never blame the reader.
8. Numbers carry units (300 MB, 12 MP, 5 s).
9. No AI model or vendor names. Panasonic and Samsung are allowed because the reader owns those cameras.
10. Resource names say the kind: `title_`, `body_`, `button_`, `action_`, `link_`, `choice_`, `note_`, `error_`, `help_`, `help_gloss_`. The kind selects which rules apply.

Review checklist for a pull request that adds text: the strings are in a `strings_*.xml` (or are literals the scanner reads); `./gradlew :core:model:testDebugUnitTest` passes; the text fits at font scale 1.3 and in TalkBack reads sensibly; `docs/copy-allow.txt` has an entry (text, tab, reason) for anything the rules flag on purpose.

Exceptions live in `docs/copy-allow.txt`, one per line: the exact text, a tab, and the reason. An entry without a reason makes the test fail.
```

## 9. Risks and limits
- The scanner reads literal patterns, not every string; resources are the real fix and each new screen should use them.
- The verb list and the proper-name list are small on purpose; the first few pull requests will extend them. That is the cost of enforcing "button starts with a verb" mechanically.
- Cold start: the flow adds one SharedPreferences read before first draw; use the already loaded preferences object and keep it off the main thread's critical path so the photo grid is not delayed.

package app.rawline.core.model.copy

import java.io.File
import java.util.Locale

/** The mechanical part of docs/COPY.md (BK-396). Pure JVM, no Android. Used by the text rules test over resources, Kotlin string literals and docs. */

enum class CopyKind { TEXT, TITLE, BUTTON, HELP }

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
    // Assembled from halves so that no source file or document carries the names it bans (the doc scan and the commit check would flag them).
    private val BANNED_PARTS = listOf("Cla|ude", "Anthro|pic", "Open|AI", "Chat|GP|T", "G|PT", "Gem|ini", "Gem|ma", "LL|aMA", "La|Ma", "Seg|Former", "Mobile|SAM")
    private val BANNED_NAMES = Regex("\\b(" + BANNED_PARTS.joinToString("|") { it.replace("|", "") } + ")\\b")

    /** "1 photos", "1 edits" and "photo(s)": a count must use the right noun (core/ui Plurals). */
    private val PLURAL = listOf(
        Regex("(?<![\\d.,])\\b1 (photos|files|edits|presets|layers|masks|exports|projects|folders|pictures)\\b") to "'1 %s' should be singular",
        Regex("\\b(photo|file|edit|preset|layer|mask|picture)\\(s\\)", RegexOption.IGNORE_CASE) to "'%s(s)' is a placeholder: use the right noun for the count",
    )

    /** Words after the first that may start with a capital in a title or button. */
    val PROPER = setOf("Rawline", "Panasonic", "Samsung", "Android", "Google", "Wi-Fi", "RW2", "RAW", "JPEG", "HEIC", "PNG", "TIFF", "DNG", "AI", "SD", "USB-C", "S", "Pen", "Lightroom", "Studio", "Develop", "Photos", "Files")

    val BUTTON_FIRST_WORDS = setOf(
        "Get", "Allow", "Open", "Skip", "Export", "Not", "Cancel", "Done", "OK", "Add", "Apply", "Back", "Choose", "Clear", "Close", "Copy", "Create", "Delete",
        "Edit", "Import", "Keep", "Paste", "Reset", "Restore", "Retry", "Save", "Select", "Set", "Share", "Show", "Start", "Try", "Turn", "Undo", "Redo", "Use",
        "Continue", "Next", "Remove", "Rename", "Update", "View", "Report", "Pick", "Move", "Duplicate", "Hide", "Download", "Learn", "Why", "Stay", "Leave",
    )

    /** Names of Android settings that keep their capitals (matched as a whole phrase and ignored by the sentence case rule). */
    val PHRASES = listOf("All files access")

    const val HELP_MAX = 140

    fun check(raw: String, kind: CopyKind = CopyKind.TEXT): List<Violation> {
        val text = raw
        val out = ArrayList<Violation>()
        fun v(rule: String, detail: String) { out += Violation(rule, text, detail) }
        if (text.contains('\u2014')) v("em-dash", "use a full stop or a comma")
        if (text.contains(" \u2013 ")) v("spaced-en-dash", "use a full stop, a comma or 'to' in ranges")
        if (text.contains('!')) v("exclamation", "no exclamation marks")
        for ((re, au) in SPELLING) re.find(text)?.let { v("spelling", "'${it.value}' should be '$au'") }
        for ((re, msg) in PLURAL) re.find(text)?.let { v("plural", msg.format(it.groupValues[1])) }
        BANNED_NAMES.find(text)?.let { v("name", "'${it.value}' is a model or vendor name") }
        if (text != text.trim()) v("whitespace", "leading or trailing space")
        if (text.contains("  ") && kind != CopyKind.TEXT) v("whitespace", "double space")
        if (kind == CopyKind.TITLE || kind == CopyKind.BUTTON) {
            var body = text; for (ph in PHRASES) body = body.replace(ph, ph.lowercase(Locale.ROOT))
            val words = body.split(' ').filter { it.isNotEmpty() }
            for (w in words.drop(1)) {
                val bare = w.trim('.', ',', '?', '"', ':', ';', '(', ')')
                if (bare.isNotEmpty() && bare[0].isUpperCase() && bare !in PROPER && bare.any { it.isLowerCase() }) { v("sentence-case", "'$bare' should not start with a capital"); break }
            }
        }
        if (kind == CopyKind.BUTTON) {
            val first = text.trim().split(' ').firstOrNull().orEmpty().trim('.', ',')
            if (first !in BUTTON_FIRST_WORDS) v("button-verb", "'$first' is not on the verb list (add it in CopyRules if it is a verb)")
        }
        if (kind == CopyKind.HELP && text.length > HELP_MAX) v("help-length", "${text.length} characters, limit $HELP_MAX")
        return out
    }
}

/** A user visible string found in a file. [kind] comes from the resource name for XML, TEXT for Kotlin. */
data class Found(val file: String, val line: Int, val name: String, val text: String, val kind: CopyKind)

object CopyScan {
    fun kindOf(name: String): CopyKind = when {
        name.startsWith("title_") -> CopyKind.TITLE
        name.startsWith("button_") || name.startsWith("action_") -> CopyKind.BUTTON
        name.startsWith("help_") || name.startsWith("tip_") -> CopyKind.HELP
        else -> CopyKind.TEXT
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
        for (a in ARRAY.findAll(xml)) for (item in ITEM.findAll(a.groupValues[2])) out += Found(file, lineOf(a.range.first), a.groupValues[1], unescapeXml(item.groupValues[1]), kindOf(a.groupValues[1]))
        return out
    }

    private val LITERAL = Regex("""(?:\bText\(|\btext\s*=\s*|\blabel\s*=\s*|\btitle\s*=\s*|\bsubtitle\s*=\s*|\bcontentDescription\s*=\s*|\bplaceholder\s*=\s*|\bmessage\s*=\s*|\bhint\s*=\s*|Toast\.makeText\([^,]*,\s*)"((?:[^"\\]|\\.)*)"""")

    /** Static text of user visible Kotlin literals: `$name` and `${...}` are replaced by an X so the words around them are still checked. */
    fun kotlinLiterals(file: String, src: String): List<Found> {
        val out = ArrayList<Found>()
        src.lineSequence().forEachIndexed { i, line ->
            if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
            for (m in LITERAL.findAll(line)) {
                val t = m.groupValues[1].replace(Regex("""\$\{[^}]*\}"""), "X").replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "X").replace("\\\"", "\"").replace("\\n", "\n").let(::unicodeEscapes)
                if (t.contains('$') || t.length < 2 || !t.any { it.isLetter() }) continue   // a template with nested quotes is cut short by the regex: skip it
                if (t.all { !it.isLetter() || it.isUpperCase() } && t.length < 6) continue   // short codes such as "RW2" or "PNG"
                out += Found(file, i + 1, "literal", t, CopyKind.TEXT)
            }
        }
        return out
    }

    /** `\u2014` in source is the character in the app: decode it so an escaped dash is still seen. */
    private fun unicodeEscapes(t: String): String = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }

    private val ANY_LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")

    /** The rules that are about words, not about where the words are shown: the broad scan below checks only these. */
    val LEXICAL_RULES = setOf("spelling", "em-dash", "spaced-en-dash", "name", "plural")

    /**
     * Every string literal that reads like text (it holds a space or starts with a capital), wherever it is used: a tab label in a list of pairs, a branch of a `when`,
     * an undo step name. The narrow scan above only knows the common Compose calls, so this one catches US spellings and dashes that hide in other places.
     */
    fun textLikeLiterals(file: String, src: String): List<Found> {
        val out = ArrayList<Found>()
        src.lineSequence().forEachIndexed { i, line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ") || t.startsWith("package ")) return@forEachIndexed
            for (m in ANY_LITERAL.findAll(line)) {
                val x = m.groupValues[1].replace(Regex("""\$\{[^}]*\}"""), "X").replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "X").let(::unicodeEscapes)
                if (x.none { it.isLetter() } || !(x.contains(' ') || x[0].isUpperCase())) continue
                out += Found(file, i + 1, "text", x, CopyKind.TEXT)
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
            if (!fenced && line.contains('\u2014')) out += Found(file, i + 1, "doc", line.trim(), CopyKind.TEXT)
        }
        return out
    }

    /** Allow list: lines of `text<TAB>reason`; a found text equal to an allowed text is skipped. Every entry must have a reason. */
    fun parseAllow(text: String): Map<String, String> = text.lineSequence().map { it.trim('\r', '\n') }.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.associate {
        val p = it.split('\t', limit = 2); require(p.size == 2 && p[1].isNotBlank()) { "allow list line needs a reason: $it" }; p[0] to p[1]
    }

    fun walk(root: File, skipDir: (File) -> Boolean, wanted: (File) -> Boolean): List<File> =
        root.walkTopDown().onEnter { !(it.isDirectory && skipDir(it)) }.filter { it.isFile && wanted(it) }.toList().sortedBy { it.path }
}

package app.rawline.core.cache

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Timings, errors and events kept in memory for the debug overlay and the Copy report. A snapshot is also written to a
 * file (after every error and whenever the app stops), so the report after a crash or a low memory kill still has them.
 */
object PerfLog {
    private const val MAX_KEYS = 200
    private const val SAMPLES = 500
    private const val ERRORS = 100
    private const val EVENTS = 40

    private class Sample(val values: ArrayDeque<Long> = ArrayDeque(), var tag: String = "")
    private class Entry(val t: Long, val msg: String)

    private val samples = HashMap<String, Sample>()
    private val errors = ArrayDeque<Entry>()
    private val events = ArrayDeque<Entry>()
    @Volatile var lastOpen: String = ""

    /** Test seam. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private var current: File? = null
    private var previousFile: File? = null
    private val pending = AtomicBoolean(false)
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "perflog-io").apply { isDaemon = true } } }

    /** Several callers put a variable count in the name, for example "device_scan_ms (n=1200)". The count is kept as the tag, not the key. */
    fun key(name: String): Pair<String, String> {
        val m = Regex("""^(.*?)\s*\(([^)]*)\)\s*$""").find(name) ?: return name.trim() to ""
        return m.groupValues[1] to m.groupValues[2]
    }

    @Synchronized fun record(name: String, ms: Long) {
        val (k, tag) = key(name)
        val s = samples[k] ?: if (samples.size >= MAX_KEYS) return else Sample().also { samples[k] = it }
        s.values.addLast(ms)
        if (s.values.size > SAMPLES) s.values.removeFirst()
        if (tag.isNotEmpty()) s.tag = tag
    }

    fun error(msg: String) {
        synchronized(this) {
            errors.addLast(Entry(clock(), msg.take(400)))
            while (errors.size > ERRORS) errors.removeFirst()
        }
        flushSoon()
    }

    /** Something worth knowing when reading a report (memory trims, service stops) that is not an error. */
    @Synchronized fun event(msg: String) {
        events.addLast(Entry(clock(), msg.take(200)))
        while (events.size > EVENTS) events.removeFirst()
    }

    @Synchronized fun summary(): String = samples.entries.sortedBy { it.key }.filter { it.value.values.isNotEmpty() }.joinToString("\n") { (k, v) ->
        val s = v.values.sorted()
        "$k: n=${s.size} median=${s[s.size / 2]} p95=${s[(s.size * 95 / 100).coerceAtMost(s.size - 1)]} max=${s.last()}" + if (v.tag.isNotEmpty()) " last=(${v.tag})" else ""
    }

    @Synchronized fun recentErrors(max: Int = 20): String = errors.toList().takeLast(max).joinToString("\n") { "${ReportText.stamp(it.t)}  ${it.msg}" }
    @Synchronized fun recentEvents(max: Int = 20): String = events.toList().takeLast(max).joinToString("\n") { "${ReportText.stamp(it.t)}  ${it.msg}" }
    @Synchronized fun errorCount() = errors.size

    // ---------------- persistence ----------------

    /** Call once at start. The last session's snapshot becomes "previous session"; a new snapshot is started for this one. */
    @Synchronized fun attach(dir: File) {
        dir.mkdirs()
        val cur = File(dir, "session.txt")
        val prev = File(dir, "previous.txt")
        // only replace the previous snapshot when the last session actually wrote one
        if (cur.exists()) { prev.delete(); cur.renameTo(prev) }
        current = cur; previousFile = prev
    }

    fun snapshotText(): String = synchronized(this) {
        buildString {
            appendLine("Saved ${ReportText.stamp(clock())}")
            appendLine("Errors")
            appendLine(recentErrors(ERRORS).ifEmpty { "none" })
            appendLine("Events")
            appendLine(recentEvents(EVENTS).ifEmpty { "none" })
            appendLine("Timings (ms)")
            append(summary().ifEmpty { "none" })
        }
    }

    fun flushSoon() {
        if (current == null || !pending.compareAndSet(false, true)) return
        io.execute { pending.set(false); flushNow() }
    }

    /** Writes the snapshot now. Called when the app stops and from the crash handler. */
    fun flushNow() {
        val f = current ?: return
        runCatching { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(snapshotText()); tmp.renameTo(f) }
    }

    /** The last session's snapshot, or null. */
    fun previousSession(): String? = previousFile?.takeIf { it.exists() }?.readText()

    /** Test seam: forget everything. */
    @Synchronized fun resetForTest() { samples.clear(); errors.clear(); events.clear(); lastOpen = ""; current = null; previousFile = null; clock = System::currentTimeMillis }

    // ---------------- report ----------------

    /**
     * Plain text for pasting into a chat. @param sections extra titled blocks from the app (library, models, queue and so on).
     * Everything free text goes through [ReportText.redact]: no file paths beyond photo names, no addresses, no secrets.
     */
    fun report(context: Context, versionLine: String, sections: List<Pair<String, String>> = emptyList(), now: Long = clock()): String {
        val out = StringBuilder()
        fun block(title: String, body: String) { out.appendLine(); out.appendLine(title); out.appendLine(ReportText.redact(body.ifBlank { "none" })) }
        out.appendLine("Rawline report")
        out.appendLine("Time: ${ReportText.stamp(now)}")
        out.appendLine(versionLine)
        block("Device and state", DeviceReport.describe(context))
        block("Graphics", GlInfo.describe())
        block("How the app last ended", ExitReasons.describe(context, now))
        sections.forEach { (t, b) -> block(t, b) }
        block("Timings (ms), this session", summary().ifEmpty { "none yet" })
        block("Recent errors, this session (${errorCount()} held)", recentErrors())
        block("Recent events", recentEvents())
        val crashes = CrashStore.all(context)
        block("Crashes (newest first, ${crashes.size} kept)", crashes.joinToString("\n\n"))
        block("Previous session (saved just before the last start)", previousSession() ?: "none saved")
        return out.toString().trimEnd()
    }
}

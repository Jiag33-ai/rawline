package app.rawline.core.cache

import android.content.Context
import android.os.Build
import java.io.File
import java.util.ArrayDeque

/** In-memory timing and error log used by the debug overlay and the Copy report button. */
object PerfLog {
    private val samples = HashMap<String, ArrayList<Long>>()
    private val errors = ArrayDeque<String>()
    @Volatile var lastOpen: String = ""

    @Synchronized fun record(name: String, ms: Long) {
        val l = samples.getOrPut(name) { ArrayList() }
        l.add(ms)
        if (l.size > 500) l.removeAt(0)
    }

    @Synchronized fun error(msg: String) {
        errors.addLast(msg)
        while (errors.size > 20) errors.removeFirst()
    }

    @Synchronized fun summary(): String = samples.entries.sortedBy { it.key }.joinToString("\n") { (k, v) ->
        val s = v.sorted()
        "$k: n=${s.size} median=${s[s.size / 2]} p95=${s[(s.size * 95 / 100).coerceAtMost(s.size - 1)]} max=${s.last()}"
    }

    @Synchronized fun recentErrors(): String = errors.joinToString("\n")

    fun report(context: Context, versionLine: String, extra: String = ""): String = buildString {
        appendLine("Rawline report")
        appendLine(versionLine)
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Heap max: ${Runtime.getRuntime().maxMemory() / 1048576} MB")
        appendLine()
        appendLine("Timings (ms)")
        appendLine(summary().ifEmpty { "none yet" })
        if (extra.isNotEmpty()) { appendLine(); appendLine(extra) }
        appendLine()
        appendLine("Recent errors")
        appendLine(recentErrors().ifEmpty { "none" })
        appendLine()
        appendLine("Last crash")
        appendLine(CrashStore.last(context) ?: "none")
    }
}

/** Saves the last uncaught exception so it can be shown on next launch and in the report. */
object CrashStore {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file(app).writeText("Thread ${t.name}\n" + e.stackTraceToString().take(6000)) }
            previous?.uncaughtException(t, e)
        }
    }

    fun last(context: Context): String? = file(context).takeIf { it.exists() }?.readText()
}

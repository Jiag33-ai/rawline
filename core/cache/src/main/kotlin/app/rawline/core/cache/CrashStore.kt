package app.rawline.core.cache

import android.content.Context
import java.io.File

/**
 * Saves uncaught exceptions so they can be shown on the next launch and in the Copy report. The newest three are kept.
 * Each file starts with the time, build and memory state, then the root cause, then the head and tail of the trace.
 */
object CrashStore {
    const val KEEP = 3
    const val TRACE_HEAD = 3000
    const val TRACE_TAIL = 3000

    private fun dir(c: Context) = File(c.filesDir, "crashes").apply { mkdirs() }

    /** @param buildLabel for example "0.1.57 build 57 release", stored in the file so an old crash can be told from a new one. */
    fun install(context: Context, buildLabel: String) {
        val app = context.applicationContext
        PerfLog.attach(File(app.filesDir, "perflog").apply { mkdirs() })
        runCatching { File(app.filesDir, "last_crash.txt").delete() }   // pre-3-crash format, no time or build in it
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val rt = Runtime.getRuntime()
                val mem = "java ${ReportText.mb(rt.totalMemory() - rt.freeMemory())} of ${ReportText.mb(rt.maxMemory())}, native ${ReportText.mb(android.os.Debug.getNativeHeapAllocatedSize())}"
                write(dir(app), format(System.currentTimeMillis(), buildLabel, t.name, e, mem, PerfLog.lastOpen))
            }
            runCatching { PerfLog.error("uncaught ${ReportText.rootCause(e).javaClass.simpleName} on ${t.name}"); PerfLog.flushNow() }
            previous?.uncaughtException(t, e)
        }
    }

    fun format(time: Long, buildLabel: String, thread: String, e: Throwable, memory: String, lastOpen: String): String = buildString {
        appendLine("Crash at ${ReportText.stamp(time)}")
        appendLine("Build: $buildLabel")
        appendLine("Thread: $thread")
        appendLine("Memory: $memory")
        if (lastOpen.isNotBlank()) appendLine("Last photo opened: $lastOpen")
        val root = ReportText.rootCause(e)
        appendLine("Root cause: ${root.javaClass.name}: ${root.message}")
        appendLine("Trace:")
        append(ReportText.headAndTail(e.stackTraceToString(), TRACE_HEAD, TRACE_TAIL))
    }.let { ReportText.redact(it) }

    /** Moves crash_0 to crash_1 and so on (dropping the oldest), then writes the new one as crash_0. */
    fun write(dir: File, text: String) {
        File(dir, "crash_${KEEP - 1}.txt").delete()
        for (i in KEEP - 2 downTo 0) File(dir, "crash_$i.txt").takeIf { it.exists() }?.renameTo(File(dir, "crash_${i + 1}.txt"))
        File(dir, "crash_0.txt").writeText(text)
    }

    fun all(dir: File): List<String> = (0 until KEEP).mapNotNull { File(dir, "crash_$it.txt").takeIf { f -> f.exists() }?.readText() }

    fun all(context: Context): List<String> = all(dir(context))

    /** The newest crash, or null. */
    fun last(context: Context): String? = all(context).firstOrNull()

    /**
     * What Settings shows: the newest crash, but only when it was recorded by this build, so a fixed old crash does not
     * sit there forever after an update. Older ones stay in the Copy report with their times.
     */
    fun lastForBuild(context: Context, buildLabel: String): String? = last(context)?.takeIf { sameBuild(it, buildLabel) }

    fun sameBuild(crash: String, buildLabel: String) = crash.lineSequence().firstOrNull { it.startsWith("Build: ") } == "Build: $buildLabel"

    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }
}

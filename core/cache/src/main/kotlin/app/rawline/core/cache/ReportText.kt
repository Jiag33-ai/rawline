package app.rawline.core.cache

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Small pure helpers shared by the report, the crash file and the exit reasons. No Android classes, so they run on the host. */
object ReportText {
    fun stamp(t: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).apply { timeZone = tz }.format(Date(t))

    fun ago(from: Long, now: Long): String {
        val s = ((now - from) / 1000).coerceAtLeast(0)
        return when {
            s < 90 -> "${s}s ago"
            s < 5400 -> "${s / 60} min ago"
            s < 129600 -> "${s / 3600} h ago"
            else -> "${s / 86400} days ago"
        }
    }

    private val uri = Regex("""(content|file|https?)://\S+""")
    private val path = Regex("""(?<![\w.:/])/(?:[\w.\-+@%$]+/)+([\w.\-+@%$]*)""")
    private val secret = Regex("""(?i)\b(token|secret|password|passwd|apikey|api_key|authorization)\s*[=:]\s*\S+""")

    /**
     * The report is pasted into a chat, so it must never carry a file path, a content address or a secret. Photo names are kept:
     * a path is cut down to its last segment, which is the file name.
     */
    fun redact(s: String): String = s
        .replace(uri, "[address]")
        .replace(path) { m -> if (m.groupValues[1].isEmpty()) "[path]" else ".../" + m.groupValues[1] }
        .replace(secret) { m -> m.groupValues[1] + "=[hidden]" }

    /** Keeps the start and the end of a long text. A Kotlin trace lists "Caused by" last, so the end holds the root cause. */
    fun headAndTail(s: String, head: Int, tail: Int): String {
        if (s.length <= head + tail) return s
        return s.substring(0, head) + "\n... ${s.length - head - tail} characters cut ...\n" + s.substring(s.length - tail)
    }

    fun rootCause(e: Throwable): Throwable {
        var c = e
        val seen = HashSet<Throwable>()
        while (c.cause != null && seen.add(c)) c = c.cause!!
        return c
    }

    fun mb(bytes: Long) = "${bytes / 1048576} MB"
}

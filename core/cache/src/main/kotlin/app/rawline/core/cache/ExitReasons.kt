package app.rawline.core.cache

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/**
 * Why the app's earlier processes ended, from Android's own record (ApplicationExitInfo). This is the only way to see a native
 * crash, an ANR or a low memory kill, none of which reach the Kotlin crash handler.
 */
object ExitReasons {
    data class Record(
        val time: Long, val reason: Int, val importance: Int, val description: String?,
        val pssKb: Long, val rssKb: Long, val status: Int, val detail: String? = null,
    )

    fun reasonName(r: Int): String = when (r) {
        1 -> "exited by itself"
        2 -> "killed by a signal"
        3 -> "killed because the phone ran low on memory"
        4 -> "Java crash"
        5 -> "NATIVE CRASH"
        6 -> "ANR (app not responding)"
        7 -> "failed to start"
        8 -> "permission change"
        9 -> "killed for using too many resources"
        10 -> "user requested stop"
        11 -> "user stopped"
        12 -> "dependency died"
        13 -> "other"
        14 -> "frozen by the system"
        15 -> "package state change"
        16 -> "app updated"
        else -> "unknown ($r)"
    }

    fun importanceName(i: Int): String = when {
        i <= 100 -> "foreground"
        i <= 125 -> "foreground service"
        i <= 200 -> "visible"
        i <= 230 -> "perceptible"
        i <= 300 -> "service"
        i < 1000 -> "cached or background"
        else -> "gone"
    }

    fun format(r: Record, now: Long): String = buildString {
        append("- ${ReportText.stamp(r.time)} (${ReportText.ago(r.time, now)}): ${reasonName(r.reason)}")
        append("; was ${importanceName(r.importance)}")
        if (r.pssKb > 0 || r.rssKb > 0) append("; pss ${r.pssKb / 1024} MB, rss ${r.rssKb / 1024} MB")
        append("; status ${r.status}")
        if (!r.description.isNullOrBlank()) append("; ${r.description}")
        if (!r.detail.isNullOrBlank()) append("\n    ${r.detail.replace("\n", "\n    ")}")
    }

    /**
     * A native crash tombstone is a binary protobuf. Pull out only what is both readable and safe to paste: signal names and
     * library file names (never directories). Pure, so it is tested on the host.
     */
    fun tombstoneHints(bytes: ByteArray): String {
        val runs = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.length >= 4) runs.add(sb.toString()); sb.setLength(0) }
        for (b in bytes) { val c = b.toInt() and 0xff; if (c in 0x20..0x7e) sb.append(c.toChar()) else flush() }
        flush()
        val signals = LinkedHashSet<String>()
        val libs = LinkedHashSet<String>()
        val sig = Regex("""\b(SIG[A-Z0-9]+|SEGV_[A-Z]+|BUS_[A-Z]+|FPE_[A-Z]+|ILL_[A-Z]+)\b""")
        val lib = Regex("""([\w.\-+]+\.so)\b""")
        for (r in runs) {
            sig.findAll(r).forEach { signals.add(it.groupValues[1]) }
            lib.findAll(r).forEach { libs.add(it.groupValues[1]) }
        }
        val parts = ArrayList<String>()
        if (signals.isNotEmpty()) parts.add("signal " + signals.take(4).joinToString(", "))
        if (libs.isNotEmpty()) parts.add("libraries seen " + libs.take(10).joinToString(", "))
        return parts.joinToString("; ")
    }

    fun describe(context: Context, now: Long = System.currentTimeMillis(), max: Int = 6): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val list = am.getHistoricalProcessExitReasons(null, 0, max)
        if (list.isEmpty()) "no earlier exits recorded by Android"
        else list.joinToString("\n") { format(toRecord(it), now) }
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }

    private fun toRecord(i: ApplicationExitInfo): Record {
        val detail = runCatching {
            when (i.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> i.traceInputStream?.use { tombstoneHints(it.readNBytes(256 * 1024)) }
                // an ANR trace is text; keep the start (the main thread) and drop directories
                ApplicationExitInfo.REASON_ANR -> i.traceInputStream?.use { ReportText.redact(String(it.readNBytes(1400), Charsets.UTF_8)) }
                else -> null
            }
        }.getOrNull()
        return Record(i.timestamp, i.reason, i.importance, i.description, i.pss, i.rss, i.status, detail)
    }
}

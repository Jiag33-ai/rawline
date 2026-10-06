package app.rawline

import android.content.Context
import app.rawline.core.cache.PerfLog
import app.rawline.core.ml.Models

/** Collects the app level facts for the Copy report (library, models, export queue, settings) and joins them to the device ones. */
object ReportBuilder {
    /** Stored in every crash file so a crash from an older build can be told from one in this build. */
    val buildLabel: String get() = "${BuildConfig.VERSION_NAME} build ${BuildConfig.BUILD_NUMBER} ${if (BuildConfig.DEBUG) "debug" else "release"}"

    private val statusNames = mapOf(0 to "waiting", 1 to "running", 2 to "done", 3 to "failed", 4 to "cancelled")

    /** Call off the main thread: it reads the database and queries the GPU driver. */
    suspend fun build(context: Context, graph: Graph, shownPhotos: Int, shownLabel: String): String {
        val now = System.currentTimeMillis()
        val version = "Build: Rawline $buildLabel (built ${BuildConfig.BUILD_DATE})"
        val sections = ArrayList<Pair<String, String>>()
        sections += "Library" to runCatching {
            val c = graph.db.photos().counts()
            "${c.total} photos in the library (${c.raw} RAW), ${c.edited} edited\n" +
                "Indexing: ${c.total - c.pending - c.noPreview} done, ${c.pending} waiting, ${c.noPreview} unreadable\n" +
                "The photo grid is showing $shownPhotos ($shownLabel)"
        }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
        sections += "Export queue" to runCatching {
            val counts = graph.db.exports().statusCounts().associate { it.status to it.n }
            val line = if (counts.isEmpty()) "empty" else counts.entries.sortedBy { it.key }.joinToString(", ") { "${it.value} ${statusNames[it.key] ?: "status ${it.key}"}" }
            val fails = graph.db.exports().recentFailures().joinToString("\n") { "  ${it.photoName}: ${it.message ?: "no message"}" }
            "$line; service running ${ExportService.isRunning}" + if (fails.isNotEmpty()) "\nRecent failures\n$fails" else ""
        }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
        sections += "AI models" to runCatching {
            val ml = context.getSharedPreferences("rawline_ml", Context.MODE_PRIVATE)
            val states = graph.modelStore.state.value
            Models.ALL.joinToString("\n") { p ->
                val st = states[p.id]
                val delegate = ml.getString("accel_" + (p.localName?.substringBefore('.') ?: p.id), null) ?: ml.all.keys.firstOrNull { it.startsWith("accel_") && it.contains(p.id) }?.let { ml.getString(it, null) }
                "${p.id}: " + when {
                    st == null -> "unknown"
                    st.downloading -> "downloading ${(st.progress * 100).toInt()}%"
                    st.ready -> "downloaded"
                    else -> "not downloaded"
                } + (st?.error?.let { ", last error: $it" } ?: "") + (delegate?.let { ", delegate $it" } ?: "")
            } + (graph.modelStore.message.value?.let { "\nNow: $it" } ?: "") + "\nAll saved delegates: " + ml.all.filterKeys { it.startsWith("accel_") }.entries.joinToString { "${it.key.removePrefix("accel_")}=${it.value}" }.ifEmpty { "none yet" }
        }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
        sections += "Settings" to "XMP sidecars ${if (graph.prefs.getBoolean("xmp", false)) "on" else "off"}, overlay ${if (graph.prefs.getBoolean("overlay", false)) "on" else "off"}, " +
            "LibRaw ${runCatching { app.rawline.core.nativelib.Native.librawVersion() }.getOrElse { "failed: ${it.javaClass.simpleName}" }}"
        DebugEntry.reportSection()?.let { sections += it }   // debug builds only; null in release
        return PerfLog.report(context, version, sections, now)
    }
}

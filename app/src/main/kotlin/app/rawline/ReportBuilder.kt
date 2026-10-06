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
    suspend fun build(context: Context, graph: Graph, shownPhotos: Int, shownLabel: String, gridResorts: Int = 0): String {
        val now = System.currentTimeMillis()
        val version = "Build: Rawline $buildLabel (built ${BuildConfig.BUILD_DATE})"
        val sections = ArrayList<Pair<String, String>>()
        sections += "Library" to runCatching {
            val c = graph.db.photos().counts()
            "${c.total} photos in the library (${c.raw} RAW), ${c.edited} edited\n" +
                "Indexing: ${c.total - c.pending - c.noPreview} done, ${c.pending} waiting, ${c.noPreview} unreadable\n" +
                "The photo grid is showing $shownPhotos ($shownLabel)\n" +
                "grid_resort_count: $gridResorts (times the order of photos already on screen changed this session)\n" +
                app.rawline.core.model.Look.report(graph.db.edits().all().map { it.json })
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
        sections += "Card import" to run {
            val last = graph.prefs.getString(app.rawline.ingest.ImportService.PREF_LAST, null)
            val at = graph.prefs.getLong(app.rawline.ingest.ImportService.PREF_LAST_AT, 0L)
            val ledger = java.io.File(context.filesDir, "import-ledger.txt").let { f -> if (f.isFile) f.readLines().count { it.isNotBlank() } else 0 }
            "service running ${app.rawline.ingest.ImportService.isRunning}, ${ledger} files remembered as imported\n" +
                (if (last != null) "Last run ${((now - at) / 60_000).coerceAtLeast(0)} min ago: $last" else "No card import has been run on this phone")
        }
        sections += "DNG files (Compression tag)" to dngSection(context, graph)
        sections += "Backups" to run {
            val p = graph.prefs
            val last = app.rawline.core.data.RestoreText.lastLine(p.getLong(app.rawline.backup.BackupPrefs.LAST, 0), p.getLong(app.rawline.backup.BackupPrefs.LAST_BYTES, 0), now)
            val kind = runCatching { app.rawline.backup.BackupTargets.choose(context, p).kind.name.lowercase() }.getOrElse { "unknown" }
            "automatic ${if (p.getBoolean(app.rawline.backup.BackupPrefs.AUTO, true)) "on" else "off"}, target $kind, all files access ${if (android.os.Environment.isExternalStorageManager()) "yes" else "no"}\n$last" +
                (p.getString(app.rawline.backup.BackupPrefs.LAST_WHERE, null)?.let { " (in $it)" } ?: "") +
                (p.getString(app.rawline.backup.BackupPrefs.ERROR, null)?.let { "\nLast failure: $it" } ?: "") + "\nchanges since install: ${graph.catalog.changeCount()}"
        }
        StudioEntry.reportSection(context, graph.prefs)?.let { sections += it }   // null when this build has no Studio
        return PerfLog.report(context, version, sections, now)
    }

    /**
     * W15 section 3: the Compression tag of the newest DNG files in the library, read from the TIFF directory only (no pixel is decoded), so one
     * look at the report tells whether Samsung Expert RAW files are written with a compression LibRaw can develop (1, 7 and 8) or not (52546 is
     * JPEG XL). Reads at most 20 files, each a few small windows.
     */
    private fun dngSection(context: Context, graph: Graph): String = runCatching {
        val rows = kotlinx.coroutines.runBlocking { graph.db.photos().newestDng(20) }
        if (rows.isEmpty()) return@runCatching "no DNG files in the library"
        val tally = java.util.TreeMap<Int, Int>()
        val lines = rows.map { p ->
            val probe = runCatching {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(p.uri), "r")?.use { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { app.rawline.core.data.ingest.DngProber.probeChannel(it.channel) } }
            }.getOrNull() ?: app.rawline.core.data.ingest.DngProbe(app.rawline.core.data.ingest.DngSupport.UNREADABLE, -1, "could not open the file")
            tally.merge(probe.compression, 1, Int::plus)
            "${p.name}: compression ${probe.compression}, ${probe.support.name} (${probe.reason})"
        }
        "newest ${rows.size}: " + tally.entries.joinToString { "compression ${it.key} x${it.value}" } + "\n" + lines.joinToString("\n")
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
}

package app.rawline.backup

import android.content.Context
import app.rawline.BuildConfig
import app.rawline.Graph
import app.rawline.core.cache.PerfLog
import app.rawline.core.data.BackupName
import app.rawline.core.data.BackupPolicy
import app.rawline.core.data.BackupRun
import app.rawline.core.data.BackupRunner
import app.rawline.core.data.BackupState
import app.rawline.core.data.BackupZip
import app.rawline.core.data.FileBackupTarget
import app.rawline.core.data.RestoreStaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** One backup the restore list shows. [line] is "4 Oct, 3.2 MB" and, when the phone can read it cheaply, the edit count. */
class BackupChoice(val name: String, val line: String)

/** The preferences the backup code keeps in the "rawline" file. */
object BackupPrefs {
    const val LAST = "backup_last"; const val CHANGES_AT_LAST = "backup_changes_at_last"; const val LAST_BYTES = "backup_last_bytes"
    const val LAST_WHERE = "backup_last_where"; const val ERROR = "backup_error"; const val AUTO = "backup_auto"
}

/** Runs backups one at a time (a manual run and a scheduled one never overlap, which is also why a leftover `.part` is always stale) and remembers the outcome. */
object BackupCoordinator {
    private val lock = Mutex()

    /**
     * [manual] runs whatever the policy says; otherwise only when [BackupPolicy] says a backup is due and automatic backups are on.
     * Returns null when nothing was due; a failed run comes back as ok = false with the message to show.
     */
    suspend fun run(context: Context, graph: Graph, manual: Boolean, nowMs: Long = System.currentTimeMillis()): BackupRun? {
        val prefs = graph.prefs
        if (!manual && !prefs.getBoolean(BackupPrefs.AUTO, true)) return null
        val changes = graph.catalog.changeCount()      // read before writing: a change made during the run still counts as new
        if (!manual && BackupPolicy().due(BackupState(prefs.getLong(BackupPrefs.LAST, 0), prefs.getLong(BackupPrefs.CHANGES_AT_LAST, 0), changes), nowMs) == null) return null
        if (!lock.tryLock()) return if (manual) BackupRun(false, null, 0, emptyList(), "A backup is already running") else null
        try {
            val chosen = BackupTargets.choose(context, prefs)
            val run = try {
                withContext(Dispatchers.IO) { BackupRunner(chosen.target).run(nowMs) { out -> runBlocking { graph.catalog.writeBackup(out, BuildConfig.VERSION_NAME, nowMs) } } }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
            catch (e: Throwable) { BackupRun(false, null, 0, emptyList(), "something went wrong (${e.message ?: e.javaClass.simpleName})") }
            val ed = prefs.edit()
            if (run.ok) ed.putLong(BackupPrefs.LAST, nowMs).putLong(BackupPrefs.CHANGES_AT_LAST, changes).putLong(BackupPrefs.LAST_BYTES, run.bytes).putString(BackupPrefs.LAST_WHERE, chosen.label).remove(BackupPrefs.ERROR)
            else ed.putString(BackupPrefs.ERROR, run.message)
            ed.apply()
            PerfLog.event(if (run.ok) "backup saved to ${chosen.kind} (${run.bytes / 1024} KB, ${if (manual) "manual" else "scheduled"})" else "backup failed (${chosen.kind}): ${run.message}")
            return run
        } finally { lock.unlock() }
    }

    /** The backups in the current target, newest first. Reading the edit count needs random access, so only plain files give it. */
    suspend fun list(context: Context, graph: Graph): List<BackupChoice> = withContext(Dispatchers.IO) {
        val chosen = BackupTargets.choose(context, graph.prefs)
        chosen.target.list().filter { BackupName.key(it.name) != null }.sortedByDescending { BackupName.key(it.name) }.map { e ->
            val edits = (chosen.target as? FileBackupTarget)?.let { t -> runCatching { BackupZip.manifestOf(t.file(e.name))?.counts?.get("edits") }.getOrNull() }
            BackupChoice(e.name, app.rawline.core.data.RestoreText.listLine(BackupName.timeOf(e.name) ?: e.modified, e.size, edits))
        }
    }

    /** Copies a backup from the current target into the cache and checks it (nothing is restored). */
    suspend fun stage(context: Context, graph: Graph, name: String): RestoreStaging.Staged = withContext(Dispatchers.IO) {
        RestoreStaging.stage(BackupTargets.choose(context, graph.prefs).target.open(name), File(context.cacheDir, "restore"))
    }

    fun cleanStaging(context: Context) = RestoreStaging.clean(File(context.cacheDir, "restore"))
}

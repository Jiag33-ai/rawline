package app.rawline

import android.app.Application
import android.content.Context
import app.rawline.core.cache.CrashStore
import app.rawline.core.cache.MaskStore
import app.rawline.core.cache.PatchStore
import app.rawline.core.cache.PreviewCache
import app.rawline.core.cache.ThumbStore
import app.rawline.core.data.Catalog
import app.rawline.core.ml.ModelStore
import app.rawline.core.data.DeviceScanner
import app.rawline.core.data.Indexer
import app.rawline.core.data.RawlineDb
import app.rawline.core.render.RawPrefetch
import kotlinx.coroutines.launch

/** Plain constructor wiring, no DI framework. */
class Graph(context: Context) {
    val db = RawlineDb.create(context)
    val thumbs = ThumbStore(context)
    val previews = PreviewCache(context)
    val maskStore = MaskStore(context)
    val patchStore = PatchStore(context)
    val catalog = Catalog(context, db, maskStore, patchStore)
    val indexer = Indexer(context, db.photos(), thumbs, catalog)
    val deviceScanner = DeviceScanner(context, db.photos(), catalog)
    val rawPrefetch = RawPrefetch(context)
    val modelStore = ModelStore(context)
    val exportRunner by lazy { ExportRunner(context, this) }
    /** Outlives screens: used for saves that must finish after leaving the editor. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> app.rawline.core.cache.PerfLog.error("background task: ${e.javaClass.simpleName} ${e.message}") })
    val prefs = context.getSharedPreferences("rawline", Context.MODE_PRIVATE)
}

class RawlineApplication : Application() {
    private companion object { const val TRIM_MODERATE = 60 }

    lateinit var graph: Graph
        private set

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // recorded so a report after a low memory kill shows the phone was squeezed first
        if (level >= TRIM_MODERATE) app.rawline.core.cache.PerfLog.event("memory trim level $level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        app.rawline.core.cache.PerfLog.event("low memory warning")
    }

    override fun onCreate() {
        super.onCreate()
        CrashStore.install(this, ReportBuilder.buildLabel)
        graph = Graph(this)
        // automatic backups: every 25th change (and the first one ever) asks for a run in about 2 minutes; the policy decides whether one is due
        graph.catalog.onChange = { before, after ->
            val never = graph.prefs.getLong(app.rawline.backup.BackupPrefs.LAST, 0L) <= 0L
            if ((never || app.rawline.core.data.ChangeCounter.crossedThreshold(before, after)) && graph.prefs.getBoolean(app.rawline.backup.BackupPrefs.AUTO, true)) app.rawline.backup.BackupScheduler.afterEdits(this)
        }
        // the daily job is put in place a few seconds after start, not before the first frame
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ graph.appScope.launch { app.rawline.backup.BackupScheduler.ensure(this@RawlineApplication, graph.prefs.getBoolean(app.rawline.backup.BackupPrefs.AUTO, true)) } }, 5_000)
    }
}

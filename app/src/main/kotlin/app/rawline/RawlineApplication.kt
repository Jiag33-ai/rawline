package app.rawline

import android.app.Application
import android.content.Context
import app.rawline.core.cache.CrashStore
import app.rawline.core.cache.PreviewCache
import app.rawline.core.cache.ThumbStore
import app.rawline.core.data.Indexer
import app.rawline.core.data.RawlineDb

/** Plain constructor wiring, no DI framework. */
class Graph(context: Context) {
    val db = RawlineDb.create(context)
    val thumbs = ThumbStore(context)
    val previews = PreviewCache(context)
    val indexer = Indexer(context, db.photos(), thumbs)
    val prefs = context.getSharedPreferences("rawline", Context.MODE_PRIVATE)
}

class RawlineApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashStore.install(this)
        graph = Graph(this)
    }
}

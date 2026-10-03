package app.rawline.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream

/** 320 px grid thumbnails: memory LRU in front of JPEG files in the app cache. */
class ThumbStore(private val context: Context) {
    private val gate = kotlinx.coroutines.sync.Semaphore(3)
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val lru = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    private fun file(id: Long) = File(dir, "$id.jpg")

    fun peek(id: Long): Bitmap? = lru.get(id)

    /** Disk read plus decode; call off the main thread. */
    fun load(id: Long): Bitmap? {
        lru.get(id)?.let { return it }
        val f = file(id)
        if (!f.exists()) return null
        val t0 = System.nanoTime()
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        PerfLog.record("thumb_load_ms", (System.nanoTime() - t0) / 1_000_000)
        lru.put(id, b)
        return b
    }

    /**
     * Memory, then disk, then make it now. Device photos use the system thumbnail (fast and shared with the gallery);
     * RAWs and anything else use the embedded preview. Safe to call for every visible tile.
     */
    suspend fun obtain(p: app.rawline.core.model.Photo): Bitmap? {
        peek(p.id)?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            load(p.id)?.let { return@withContext it }
            gate.withPermit {
                load(p.id)?.let { return@withPermit it }
                val b = runCatching { make(p) }.getOrNull() ?: return@withPermit null
                save(p.id, b)
                lru.put(p.id, b)
                b
            }
        }
    }

    private fun make(p: app.rawline.core.model.Photo): Bitmap? {
        if (p.kind == app.rawline.core.model.Kind.IMAGE && p.uri.startsWith("content://media/")) {
            runCatching { return context.contentResolver.loadThumbnail(android.net.Uri.parse(p.uri), android.util.Size(320, 320), null) }
        }
        return PreviewDecoder.decode(context, p, 320, software = true, wantExif = false)?.bitmap
    }

    fun save(id: Long, bmp: Bitmap) {
        FileOutputStream(file(id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    fun exists(id: Long) = file(id).exists()
}

package app.rawline.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/** 320 px grid thumbnails: memory LRU in front of JPEG files in the app cache. */
class ThumbStore(context: Context) {
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

    fun save(id: Long, bmp: Bitmap) {
        FileOutputStream(file(id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    fun exists(id: Long) = file(id).exists()
}

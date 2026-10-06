package app.rawline.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 320 px grid thumbnails: memory LRU in front of JPEG files in the app cache. */
class ThumbStore(private val context: Context) {
    private val gate = kotlinx.coroutines.sync.Semaphore(3)
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val lru = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    private fun file(id: Long) = File(dir, "$id.jpg")
    private val saves = AtomicInteger()
    private val trimmedOnce = AtomicBoolean(false)
    @Volatile private var lastSaveError = 0L

    companion object {
        /** The disk cache is capped; the least recently used thumbnails go first and are simply made again when needed. */
        const val MAX_DISK_BYTES = 300L * 1024 * 1024
        private const val TOUCH_AFTER_MS = 60L * 60 * 1000
    }

    fun peek(id: Long): Bitmap? = lru.get(id)

    /** Disk read plus decode; call off the main thread. */
    fun load(id: Long): Bitmap? {
        lru.get(id)?.let { return it }
        val f = file(id)
        if (!f.exists()) return null
        val t0 = System.nanoTime()
        val b = try { BitmapFactory.decodeFile(f.path) } catch (e: OutOfMemoryError) { return null }
        // Files are written atomically (see save), so one that exists but cannot be decoded is damaged: drop it so it is made again.
        if (b == null) { f.delete(); return null }
        PerfLog.record("thumb_load_ms", (System.nanoTime() - t0) / 1_000_000)
        lru.put(id, b)
        // recently shown thumbnails survive a trim (oldest use goes first); at most one touch an hour per file
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > TOUCH_AFTER_MS) f.setLastModified(now)
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
                save(p.id, b)   // never throws: a full disk only means this thumbnail is not kept for next time
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

    /**
     * Writes to a private temp file and renames it over the final name, so a reader (the indexer and a tile can work on the same
     * photo) never sees a half written JPEG and a kill mid write leaves no truncated file behind. Never throws: a full disk or
     * an encode failure returns false and the caller carries on with the bitmap it already has.
     */
    fun save(id: Long, bmp: Bitmap): Boolean {
        val tmp = File(dir, "$id.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("JPEG encode failed") }
            if (!tmp.renameTo(file(id))) throw IOException("rename failed")
        } catch (e: Throwable) {
            tmp.delete()
            val now = System.currentTimeMillis()
            if (now - lastSaveError > 60_000) { lastSaveError = now; PerfLog.error("thumbnail not cached: ${e.javaClass.simpleName} ${e.message}") }
            return false
        }
        if (trimmedOnce.compareAndSet(false, true) || saves.incrementAndGet() % 200 == 0) trim()
        return true
    }

    /** Keeps the disk cache under [MAX_DISK_BYTES]. Called from the IO threads that save. */
    fun trim(max: Long = MAX_DISK_BYTES) {
        runCatching {
            val files = dir.listFiles() ?: return
            val gone = CacheTrim.plan(files.map { CacheTrim.Entry(it.name, it.length(), it.lastModified()) }, max, System.currentTimeMillis())
            gone.forEach { File(dir, it).delete() }
            if (gone.isNotEmpty()) PerfLog.event("thumbnail cache trimmed: ${gone.size} files")
        }
    }

    /** Total bytes the thumbnail cache uses on disk. */
    fun diskBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes every cached thumbnail file and empties the memory cache. They are made again as tiles are shown. */
    fun clearDisk() {
        lru.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun exists(id: Long) = file(id).exists()
}

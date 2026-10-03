package app.rawline.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import app.rawline.core.model.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Screen-size previews (tier 2) with prefetch. Decoding never touches the raw data. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewCache(private val context: Context) {
    private val lru = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    private val inflight = ConcurrentHashMap<Long, Deferred<Bitmap?>>()

    private val longEdge = 2048

    fun peek(id: Long): Bitmap? = lru.get(id)

    /** Returns the preview, decoding it if needed. Concurrent callers share one decode. */
    suspend fun load(p: Photo): Bitmap? {
        lru.get(p.id)?.let { return it }
        val d = inflight.getOrPut(p.id) {
            scope.async {
                val t0 = System.nanoTime()
                try {
                    val r = PreviewDecoder.decode(context, p, longEdge, software = false, wantExif = false)
                    if (r == null) { PerfLog.error("preview failed: ${p.name}"); null } else {
                        lru.put(p.id, r.bitmap)
                        val total = (System.nanoTime() - t0) / 1_000_000
                        PerfLog.record("tier2_total_ms", total)
                        PerfLog.record("tier2_parse_ms", r.parseMs)
                        PerfLog.record("tier2_read_ms", r.readMs)
                        PerfLog.record("tier2_decode_ms", r.decodeMs)
                        PerfLog.lastOpen = "${p.name} ${r.width}x${r.height} total=${total}ms parse=${r.parseMs} read=${r.readMs} decode=${r.decodeMs}"
                        r.bitmap
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    PerfLog.error("preview ${p.name}: ${e.message}"); null
                } finally {
                    inflight.remove(p.id)
                }
            }
        }
        return try { d.await() } catch (e: CancellationException) { lru.get(p.id) }
    }

    /** Warm [wanted] and cancel decodes for anything else (user jumped away). */
    fun prefetch(wanted: List<Photo>) {
        val ids = wanted.map { it.id }.toSet()
        inflight.forEach { (id, d) -> if (id !in ids) d.cancel() }
        wanted.forEach { p ->
            if (lru.get(p.id) == null && !inflight.containsKey(p.id)) scope.async { load(p) }
        }
    }
}

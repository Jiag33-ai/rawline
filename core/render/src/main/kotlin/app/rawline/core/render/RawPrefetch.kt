package app.rawline.core.render

import android.content.Context
import android.net.Uri
import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import app.rawline.core.nativelib.Native
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Decodes half size raws ahead of time (when the user lingers on a photo, and for the neighbours of the photo being edited)
 * so Edit and swiping between edited photos open quickly. Holds at most [capacity] results (about 100 MB each).
 *
 * [decode] returns a native handle (0 on failure) and [free] releases one; both are parameters so the bookkeeping can be tested
 * without the native library.
 */
class RawPrefetch internal constructor(
    private val capacity: Int,
    private val decode: (Photo) -> Long,
    private val free: (Long) -> Unit,
    dispatcher: CoroutineDispatcher,
) {
    constructor(context: Context, capacity: Int = 3) : this(capacity, { p ->
        // a deleted file or an unplugged card must not take the app down, and must not leave the photo marked as wanted
        runCatching { context.contentResolver.openFileDescriptor(Uri.parse(p.uri), "r")?.use { Native.decodeRaw(it.fd, true) } }.getOrNull() ?: 0L
    }, { Native.freeRaw(it) }, Dispatchers.IO.limitedParallelism(1))

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val ready = LinkedHashMap<Long, Long>()   // photo id -> native handle, oldest first
    private val wanted = HashSet<Long>()
    private var generation = 0                        // bumped by cancel(): a decode that started before it must not store its result

    fun prefetch(p: Photo) {
        if (p.kind != Kind.RAW) return
        val gen: Int
        synchronized(lock) { if (ready.containsKey(p.id) || !wanted.add(p.id)) return; gen = generation }
        scope.launch {
            val h = decode(p)
            var drop = 0L
            synchronized(lock) {
                if (gen == generation) wanted.remove(p.id)   // after a cancel the wanted set was already cleared (and may hold a newer request)
                if (h == 0L) return@synchronized
                if (gen != generation) { drop = h; return@synchronized }   // cancelled while decoding: nobody will ever take this one
                ready[p.id] = h
                while (ready.size > capacity) { val oldest = ready.keys.first(); free(ready.remove(oldest)!!) }
            }
            if (drop != 0L) free(drop)
        }
    }

    /** Hands over a ready decode for this photo, or 0. The caller owns the handle afterwards. */
    fun take(p: Photo): Long = synchronized(lock) { ready.remove(p.id) ?: 0L }

    /** Frees everything decoded and makes decodes that are still running throw their result away when they finish. */
    fun cancel() = synchronized(lock) { generation++; ready.values.forEach { free(it) }; ready.clear(); wanted.clear() }
}

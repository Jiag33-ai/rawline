package app.rawline.core.render

import android.content.Context
import android.net.Uri
import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import app.rawline.core.nativelib.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Decodes half size raws ahead of time (when the user lingers on a photo, and for the neighbours of the photo being edited)
 * so Edit and swiping between edited photos open quickly. Holds at most [capacity] results (about 100 MB each).
 */
class RawPrefetch(private val context: Context, private val capacity: Int = 3) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lock = Any()
    private val ready = LinkedHashMap<Long, Long>()   // photo id -> native handle, oldest first
    private val wanted = HashSet<Long>()

    fun prefetch(p: Photo) {
        if (p.kind != Kind.RAW) return
        synchronized(lock) { if (ready.containsKey(p.id) || !wanted.add(p.id)) return }
        scope.launch {
            // a deleted file or an unplugged card must not take the app down, and must not leave the photo marked as wanted
            val h = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(p.uri), "r")?.use { Native.decodeRaw(it.fd, true) } }.getOrNull() ?: 0L
            synchronized(lock) {
                wanted.remove(p.id)
                if (h == 0L) return@synchronized
                ready[p.id] = h
                while (ready.size > capacity) { val oldest = ready.keys.first(); Native.freeRaw(ready.remove(oldest)!!) }
            }
        }
    }

    /** Hands over a ready decode for this photo, or 0. The caller owns the handle afterwards. */
    fun take(p: Photo): Long = synchronized(lock) { ready.remove(p.id) ?: 0L }

    fun cancel() = synchronized(lock) { ready.values.forEach { Native.freeRaw(it) }; ready.clear(); wanted.clear() }
}

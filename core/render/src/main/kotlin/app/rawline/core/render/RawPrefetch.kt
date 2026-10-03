package app.rawline.core.render

import android.content.Context
import android.net.Uri
import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import app.rawline.core.nativelib.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Decodes the half size raw ahead of time when the user lingers on a photo, so Edit opens fast. Keeps one result. */
class RawPrefetch(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var handle = 0L
    private var photoId = -1L
    private val lock = Any()

    fun prefetch(p: Photo) {
        if (p.kind != Kind.RAW) return
        synchronized(lock) { if (photoId == p.id) return }
        job?.cancel()
        job = scope.launch {
            val h = context.contentResolver.openFileDescriptor(Uri.parse(p.uri), "r")?.use { Native.decodeRaw(it.fd, true) } ?: 0L
            synchronized(lock) {
                if (handle != 0L) Native.freeRaw(handle)
                handle = h; photoId = if (h != 0L) p.id else -1L
            }
        }
    }

    /** Hands over a ready decode for this photo, or 0. The caller owns the handle afterwards. */
    fun take(p: Photo): Long = synchronized(lock) {
        if (photoId == p.id && handle != 0L) { val h = handle; handle = 0; photoId = -1; h } else 0L
    }

    fun cancel() { job?.cancel(); synchronized(lock) { if (handle != 0L) Native.freeRaw(handle); handle = 0; photoId = -1 } }
}

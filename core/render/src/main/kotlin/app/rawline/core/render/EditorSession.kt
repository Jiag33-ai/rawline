package app.rawline.core.render

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Kind
import app.rawline.core.model.Photo
import app.rawline.core.nativelib.Native
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class Stage { IDLE, LOADING, READY, ERROR }

data class SessionState(
    val stage: Stage = Stage.IDLE,
    val message: String = "",
    val outW: Int = 0,
    val outH: Int = 0,
    val usingFull: Boolean = false,
    val decodeMs: Long = 0,
    val uploadMs: Long = 0,
    val firstFrameMs: Long = 0,
)

/**
 * Owns the GL engine for one open photo. Public setters are safe to call from any thread;
 * everything that touches the engine runs on the GL thread via queueEvent.
 */
/** Optional heavy step run on a decoded raw before it is uploaded (AI denoise). Returns false if it could not run. */
typealias SourceHook = suspend (handle: Long, amountPercent: Float, onProgress: (Float) -> Unit) -> Boolean

class EditorSession(
    private val context: Context,
    private val prefetch: RawPrefetch? = null,
    private val onTiming: (String, Long) -> Unit = { _, _ -> },
    private val denoise: SourceHook? = null,
) : OverlaySink {
    private val _status = MutableStateFlow<String?>(null)
    /** Inline progress text for long tasks (never a blocking dialog). */
    val status: StateFlow<String?> = _status
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state
    private val _histogram = MutableStateFlow<IntArray?>(null)
    val histogram: StateFlow<IntArray?> = _histogram

    var glView: GLSurfaceView? = null
    val renderer: GLSurfaceView.Renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = this@EditorSession.onSurfaceCreated()
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { surfaceW = width; surfaceH = height }
        override fun onDrawFrame(gl: GL10?) = this@EditorSession.onDrawFrame()
    }

    private var engine = 0L
    private var surfaceW = 1
    private var surfaceH = 1
    private var photo: Photo? = null
    @Volatile var lens: LensCorrection? = null
        private set
    private var orientation = 1
    private var srcW = 1
    private var srcH = 1
    private var loadStart = 0L
    private var firstFrameDone = false
    @Volatile private var wantHistogram = false

    @Volatile private var recipe = EditRecipe()
    @Volatile private var before = false
    @Volatile private var showMask = -1
    @Volatile private var overlayOn = false
    @Volatile private var cropMode = false
    @Volatile private var zoom = 1f
    @Volatile private var cx = 0.5f
    @Volatile private var cy = 0.5f
    @Volatile private var params = FloatArray(P.TOTAL)
    @Volatile private var geometryOutSize = intArrayOf(1, 1)
    private val _outputRevision = MutableStateFlow(0)
    /** Bumps whenever the output size changes (crop, rotate, straighten) so the UI recomputes the fit rectangle. */
    val outputRevision: StateFlow<Int> = _outputRevision
    private val layers = LinkedHashMap<String, Int>()
    /** Layer bytes kept so a lost GL context can be refilled without the masking code. */
    private class LayerData(val alpha: ByteArray, val w: Int, val h: Int)
    private val layerData = HashMap<String, LayerData>()
    /** Called on the GL thread after a lost context was rebuilt, so the owner of the heal overlay can send it again. */
    @Volatile var onContextRestored: (() -> Unit)? = null
    @Volatile private var released = false
    /** A finished picture (JPEG, HEIC, PNG) already has its tone curve baked in, so the raw base curve and baseline look must not be applied. */
    @Volatile private var finishedPicture = false
    @Volatile private var fullRequested = false
    @Volatile private var generation = 0

    val layerIndex: Map<String, Int> get() = synchronized(layers) { LinkedHashMap(layers) }

    // ---- public API ----

    fun load(p: Photo) {
        photo = p
        finishedPicture = p.kind != Kind.RAW
        lens = LensProfiles.get(context).find(p.lens, p.focal.toFloat(), p.aperture.toFloat())
        generation++
        fullRequested = false
        firstFrameDone = false
        loadStart = System.nanoTime()
        _state.value = SessionState(Stage.LOADING, "Decoding raw")
        val gen = generation
        scope.launch {
            try {
                val t0 = System.nanoTime()
                val handle = decodeFor(p, half = true)
                if (handle == 0L) { _state.value = SessionState(Stage.ERROR, "Could not decode ${p.name}"); return@launch }
                val info = Native.rawInfo(handle)
                val decodeMs = (System.nanoTime() - t0) / 1_000_000
                onTiming("edit_decode_ms", decodeMs)
                try { maybeDenoise(handle) } catch (e: Throwable) { Native.freeRaw(handle); throw e }
                post {
                    if (gen != generation || engine == 0L) { Native.freeRaw(handle); return@post }
                    val u0 = System.nanoTime()
                    val ok = Native.engineSetSource(engine, handle).also { Native.engineSetBaseCurve(engine, !finishedPicture) }
                    val upMs = (System.nanoTime() - u0) / 1_000_000
                    onTiming("edit_upload_ms", upMs)
                    if (!ok) { _state.value = SessionState(Stage.ERROR, "GPU upload failed"); return@post }
                    orientation = info[2]
                    srcW = info[0]; srcH = info[1]
                    rebuild()
                    _state.value = SessionState(Stage.READY, "", geometryOutSize[0], geometryOutSize[1], false, decodeMs, upMs)
                    requestRender()
                }
            } catch (e: Throwable) {
                _state.value = SessionState(Stage.ERROR, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setRecipe(r: EditRecipe) { recipe = r; post { rebuild(); requestRender() }; wantHistogram = true }
    fun setBefore(b: Boolean) { before = b; post { rebuild(); requestRender() } }
    /** While cropping, the full uncropped frame is shown so the crop rectangle can be edited against it. */
    fun setCropMode(on: Boolean) { cropMode = on; post { rebuild(); requestRender() } }
    fun setShowMask(i: Int) { showMask = i; post { rebuild(); requestRender() } }
    fun setView(zoom: Float, cx: Float, cy: Float) { this.zoom = zoom; this.cx = cx; this.cy = cy; requestRender() }
    fun requestHistogram() { wantHistogram = true; requestRender() }

    /** Binds an 8 bit alpha mask (brush, AI) to a key. The mask covers the whole image (stretched to the layer size). */
    fun setLayer(key: String, alpha: ByteArray, w: Int, h: Int) {
        val idx = synchronized(layers) {
            layers[key] ?: (0 until P.MAX_LAYERS).firstOrNull { it !in layers.values }?.also { layers[key] = it }
        } ?: return
        synchronized(layers) { layerData[key] = LayerData(alpha, w, h) }
        post { Native.engineSetLayer(engine, idx, alpha, w, h); rebuild(); requestRender() }
    }

    /** Frees the key's slot (there are only [P.MAX_LAYERS]) once no mask refers to it. */
    fun removeLayer(key: String) { synchronized(layers) { layers.remove(key); layerData.remove(key) } }

    override fun updateOverlay(x: Int, y: Int, w: Int, h: Int, rgbaHalf: ShortArray) {
        post { Native.engineUpdateOverlay(engine, x, y, w, h, rgbaHalf); requestRender() }
    }

    fun setOverlayActive(on: Boolean) { overlayOn = on; post { rebuild(); requestRender() } }

    val sourceWidth get() = srcW
    val sourceHeight get() = srcH
    val orientationValue get() = orientation
    val currentRecipe get() = recipe

    /**
     * A rectangle of the source picture in its stored orientation (no crop, rotation or edits; camera look only), as a
     * software bitmap of the given pixel size. [x], [y], [w], [h] are normalised source coordinates.
     */
    suspend fun renderSource(x: Float, y: Float, w: Float, h: Float, pw: Int, ph: Int): android.graphics.Bitmap? {
        val d = CompletableDeferred<android.graphics.Bitmap?>()
        post {
            val arr = RenderParams.build(EditRecipe(), 1, emptyMap(), overlayOn = false, useBaseline = !finishedPicture)
            val buf = ByteArray(pw * ph * 4)
            if (!Native.engineRenderRegion(engine, arr, pw, ph, x, y, w, h, buf)) { d.complete(null); return@post }
            val px = IntArray(pw * ph) { i -> (0xFF shl 24) or ((buf[i * 4].toInt() and 0xFF) shl 16) or ((buf[i * 4 + 1].toInt() and 0xFF) shl 8) or (buf[i * 4 + 2].toInt() and 0xFF) }
            d.complete(android.graphics.Bitmap.createBitmap(px, pw, ph, android.graphics.Bitmap.Config.ARGB_8888))
            requestRender()
        }
        return d.await()
    }

    /** Overlay of healed / removed areas: premultiplied linear working space, same orientation as the source. */
    override fun setOverlay(rgbaHalf: ShortArray?, w: Int, h: Int) {
        overlayOn = rgbaHalf != null
        post { Native.engineSetOverlay(engine, rgbaHalf, w, h); rebuild(); requestRender() }
    }

    /** Switches the GPU source to the full resolution decode (zoomed in or exporting). */
    fun ensureFull() {
        val p = photo ?: return
        if (fullRequested || p.kind != Kind.RAW) return
        fullRequested = true
        val gen = generation
        scope.launch {
            val t0 = System.nanoTime()
            val handle = decodeFor(p, half = false)
            if (handle == 0L) return@launch
            onTiming("full_decode_ms", (System.nanoTime() - t0) / 1_000_000)
            maybeDenoise(handle)
            post {
                if (gen != generation || engine == 0L) { Native.freeRaw(handle); return@post }
                Native.engineSetSource(engine, handle).also { Native.engineSetBaseCurve(engine, !finishedPicture) }
                rebuild()
                _state.value = _state.value.copy(usingFull = true, outW = geometryOutSize[0], outH = geometryOutSize[1])
                requestRender()
            }
        }
    }

    class ImageStats(val p1: Float, val p5: Float, val p50: Float, val p95: Float, val p99: Float, val meanR: Float, val meanG: Float, val meanB: Float)

    /** Display-referred statistics of the unedited image (camera look only). Used by Auto and Auto white balance. */
    suspend fun baseStats(): ImageStats? {
        val d = CompletableDeferred<ImageStats?>()
        post {
            val arr = RenderParams.build(EditRecipe(geometry = recipe.geometry.copy(cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f, angle = 0f)), orientation, emptyMap(), useBaseline = !finishedPicture)
            val w = 160
            val ow = Native.engineOutputSize(engine, arr)
            val h = (w * ow[1].toFloat() / ow[0]).toInt().coerceIn(16, 400)
            val buf = ByteArray(w * h * 4)
            if (!Native.engineRenderRegion(engine, arr, w, h, 0f, 0f, 1f, 1f, buf)) { d.complete(null); return@post }
            val luma = IntArray(256)
            var r = 0.0; var g = 0.0; var b = 0.0
            var i = 0
            while (i < buf.size) {
                val R = buf[i].toInt() and 0xFF; val G = buf[i + 1].toInt() and 0xFF; val B = buf[i + 2].toInt() and 0xFF
                luma[((R * 54 + G * 183 + B * 19) shr 8).coerceIn(0, 255)]++
                r += R; g += G; b += B
                i += 4
            }
            val n = w * h
            fun pct(p: Float): Float { var acc = 0; val t = (n * p).toInt(); for (k in 0 until 256) { acc += luma[k]; if (acc >= t) return k / 255f }; return 1f }
            d.complete(ImageStats(pct(0.01f), pct(0.05f), pct(0.5f), pct(0.95f), pct(0.99f), (r / n / 255).toFloat(), (g / n / 255).toFloat(), (b / n / 255).toFloat()))
            requestRender()
        }
        return d.await()
    }

    /** Mean display colour (0..1) in a small square around a point of the shown image, using the current edit. */
    suspend fun sample(nx: Float, ny: Float): FloatArray? {
        val d = CompletableDeferred<FloatArray?>()
        post {
            val buf = ByteArray(8 * 8 * 4)
            val hw = 0.006f
            val x = (nx - hw).coerceIn(0f, 1f - 2 * hw); val y = (ny - hw).coerceIn(0f, 1f - 2 * hw)
            if (!Native.engineRenderRegion(engine, params, 8, 8, x, y, 2 * hw, 2 * hw, buf)) { d.complete(null); return@post }
            var r = 0f; var g = 0f; var b = 0f
            for (i in 0 until 64) { r += buf[i * 4].toInt() and 0xFF; g += buf[i * 4 + 1].toInt() and 0xFF; b += buf[i * 4 + 2].toInt() and 0xFF }
            d.complete(floatArrayOf(r / 64f / 255f, g / 64f / 255f, b / 64f / 255f))
            requestRender()
        }
        return d.await()
    }

    /**
     * The whole uncropped frame in the current orientation, rotation and straightening, without edits (camera look only),
     * as a software bitmap. This is the frame masks and AI work in.
     */
    suspend fun renderFrame(maxEdge: Int): android.graphics.Bitmap? {
        val d = CompletableDeferred<android.graphics.Bitmap?>()
        post {
            val g = recipe.geometry.copy(cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f, keystoneV = recipe.geometry.keystoneV, keystoneH = recipe.geometry.keystoneH)
            val arr = RenderParams.build(EditRecipe(geometry = g), orientation, emptyMap(), useBaseline = !finishedPicture)
            val ow = Native.engineOutputSize(engine, arr)
            val s = maxEdge.toFloat() / maxOf(ow[0], ow[1])
            val w = (ow[0] * s).toInt().coerceAtLeast(8); val h = (ow[1] * s).toInt().coerceAtLeast(8)
            val buf = ByteArray(w * h * 4)
            if (!Native.engineRenderRegion(engine, arr, w, h, 0f, 0f, 1f, 1f, buf)) { d.complete(null); return@post }
            val px = IntArray(w * h) { i -> (0xFF shl 24) or ((buf[i * 4].toInt() and 0xFF) shl 16) or ((buf[i * 4 + 1].toInt() and 0xFF) shl 8) or (buf[i * 4 + 2].toInt() and 0xFF) }
            d.complete(android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888))
            requestRender()
        }
        return d.await()
    }

    /** Fit rectangle of the current output inside a view of the given size, in view pixels: left, top, width, height. */
    fun fitRect(viewW: Float, viewH: Float): FloatArray {
        val ow = geometryOutSize[0].toFloat().coerceAtLeast(1f); val oh = geometryOutSize[1].toFloat().coerceAtLeast(1f)
        val s = minOf(viewW / ow, viewH / oh)
        return floatArrayOf((viewW - ow * s) / 2f, (viewH - oh * s) / 2f, ow * s, oh * s)
    }

    /** Maps a point in the view (px) to normalised output-image coordinates, or null when outside the image. */
    fun mapPoint(px: Float, py: Float, viewW: Float, viewH: Float): FloatArray? {
        val ow = geometryOutSize[0].toFloat().coerceAtLeast(1f); val oh = geometryOutSize[1].toFloat().coerceAtLeast(1f)
        val fit = minOf(viewW / ow, viewH / oh)
        val s = fit * zoom
        val vpw = minOf(viewW, ow * s); val vph = minOf(viewH, oh * s)
        val visW = vpw / (ow * s); val visH = vph / (oh * s)
        val x0 = cx.coerceIn(visW / 2, 1 - visW / 2) - visW / 2
        val y0 = cy.coerceIn(visH / 2, 1 - visH / 2) - visH / 2
        val vx = (viewW - vpw) / 2; val vy = (viewH - vph) / 2
        val nx = x0 + (px - vx) / (ow * s); val ny = y0 + (py - vy) / (oh * s)
        return if (nx in 0f..1f && ny in 0f..1f) floatArrayOf(nx, ny) else null
    }

    /** Inverse of [mapPoint]: normalised output coordinates to a position in the view (px). */
    fun pointToView(nx: Float, ny: Float, viewW: Float, viewH: Float): FloatArray {
        val ow = geometryOutSize[0].toFloat().coerceAtLeast(1f); val oh = geometryOutSize[1].toFloat().coerceAtLeast(1f)
        val fit = minOf(viewW / ow, viewH / oh)
        val s = fit * zoom
        val vpw = minOf(viewW, ow * s); val vph = minOf(viewH, oh * s)
        val visW = vpw / (ow * s); val visH = vph / (oh * s)
        val x0 = cx.coerceIn(visW / 2, 1 - visW / 2) - visW / 2
        val y0 = cy.coerceIn(visH / 2, 1 - visH / 2) - visH / 2
        val vx = (viewW - vpw) / 2; val vy = (viewH - vph) / 2
        return floatArrayOf(vx + (nx - x0) * ow * s, vy + (ny - y0) * oh * s)
    }

    /** Output size in source pixels of the current geometry (not the screen). */
    /** Width over height of the oriented, uncropped frame. Masks are stored in this frame. */
    fun baseAspect(): Float {
        val rot = (orientationRot() + recipe.geometry.rotate90) % 4
        return if (rot % 2 == 1) srcH.toFloat() / srcW else srcW.toFloat() / srcH
    }
    private fun orientationRot() = when (orientation) { 5, 6 -> 1; 3, 4 -> 2; 7, 8 -> 3; else -> 0 }

    val outputSize: IntArray get() = geometryOutSize

    private suspend fun maybeDenoise(handle: Long) {
        val d = recipe.detail
        val hook = denoise ?: return
        if (!d.aiDenoise) return
        val ok = try {
            hook(handle, d.aiDenoiseAmount) { _status.value = "AI denoise ${(it * 100).toInt()}%" }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { false }
        _status.value = if (ok) null else "AI denoise could not run"
        if (ok) appliedDenoise = d.aiDenoiseAmount
    }
    private var appliedDenoise = -1f

    /** Call after the AI denoise setting was committed: decodes again with the new setting and swaps the source. */
    fun reloadSource() {
        val p = photo ?: return
        if (_state.value.stage != Stage.READY) return
        val d = recipe.detail
        val wanted = if (d.aiDenoise) d.aiDenoiseAmount else -1f
        if (wanted == appliedDenoise) return
        val gen = ++generation
        fullRequested = false
        _status.value = if (d.aiDenoise) "AI denoise" else "Updating"
        scope.launch {
            try {
                val handle = decodeFor(p, half = true)
                if (handle == 0L) { _status.value = null; return@launch }
                val info = Native.rawInfo(handle)
                appliedDenoise = -1f
                maybeDenoise(handle)
                if (!d.aiDenoise) { _status.value = null }
                post {
                    if (gen != generation || engine == 0L) { Native.freeRaw(handle); return@post }
                    Native.engineSetSource(engine, handle).also { Native.engineSetBaseCurve(engine, !finishedPicture) }
                    srcW = info[0]; srcH = info[1]
                    rebuild(); requestRender()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { _status.value = "Update failed" }
        }
    }

    fun release() {
        released = true
        scope.cancel()
        // If the view is already detached its GL thread has stopped and destroyEngine() ran from the detach.
        if (glView?.isAttachedToWindow == true) destroyEngine()
        synchronized(pending) { pending.clear() }
    }

    /** Destroys the engine on the GL thread and waits briefly for it. Must run before the GL thread stops (view detach). */
    fun destroyEngine() {
        val v = glView ?: return
        synchronized(pending) { glReady = false }
        val done = java.util.concurrent.CountDownLatch(1)
        v.queueEvent { try { if (engine != 0L) { Native.engineDestroy(engine); engine = 0 } } finally { done.countDown() } }
        done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // ---- internals ----

    private val pending = ArrayList<() -> Unit>()
    @Volatile private var glReady = false

    /** Runs on the GL thread once the engine exists; earlier calls wait in a queue. */
    private fun post(block: () -> Unit) {
        synchronized(pending) { if (!glReady) { pending.add(block); return } }
        glView?.queueEvent(block)
    }
    private fun requestRender() { glView?.requestRender() }

    private fun decodeFor(p: Photo, half: Boolean): Long {
        val uri = Uri.parse(p.uri)
        if (p.kind == Kind.RAW) {
            if (half) prefetch?.take(p)?.let { if (it != 0L) return it }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { return Native.decodeRaw(it.fd, half) }
            return 0
        }
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            val long = maxOf(info.size.width, info.size.height)
            val cap = if (half) 3072 else 8192
            if (long > cap) { val s = cap.toFloat() / long; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
        }
        val argb = if (bmp.config == android.graphics.Bitmap.Config.ARGB_8888) bmp else bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val buf = java.nio.ByteBuffer.allocate(argb.byteCount)
        argb.copyPixelsToBuffer(buf)
        return Native.rawFromRgba(buf.array(), argb.width, argb.height)
    }

    private fun rebuild() {
        if (engine == 0L) return
        var r = if (before) EditRecipe() else recipe
        if (cropMode && !before) r = r.copy(geometry = r.geometry.copy(cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f))
        val arr = RenderParams.build(r, orientation, layerIndex, showMask = if (before) -1 else showMask, overlayOn = overlayOn && !before, lens = if (before) null else lens, useBaseline = !finishedPicture)
        params = arr
        val size = Native.engineOutputSize(engine, arr)
        if (!size.contentEquals(geometryOutSize)) { geometryOutSize = size; _outputRevision.value++ }
    }

    private fun onSurfaceCreated() {
        if (released) return
        synchronized(pending) { glReady = false }
        if (engine != 0L) Native.engineDestroy(engine)
        engine = Native.engineCreate()
        val err = Native.engineInit(engine)
        if (err != null) { _state.value = SessionState(Stage.ERROR, "GPU init failed: $err"); return }
        val wasReady = _state.value.stage == Stage.READY
        val queued = synchronized(pending) { glReady = true; ArrayList(pending).also { pending.clear() } }
        queued.forEach { it() }
        // A recreated context lost its textures: bring back the layers and heal overlay, then the photo.
        if (wasReady) {
            val saved = synchronized(layers) { layers.mapNotNull { (k, i) -> layerData[k]?.let { i to it } } }
            saved.forEach { (i, d) -> Native.engineSetLayer(engine, i, d.alpha, d.w, d.h) }
            onContextRestored?.invoke()
        }
        photo?.let { if (wasReady) load(it) }
    }

    private fun onDrawFrame() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (engine == 0L || _state.value.stage != Stage.READY) return
        val ow = geometryOutSize[0].toFloat().coerceAtLeast(1f)
        val oh = geometryOutSize[1].toFloat().coerceAtLeast(1f)
        val fit = minOf(surfaceW / ow, surfaceH / oh)
        val s = fit * zoom
        val vpw = minOf(surfaceW.toFloat(), ow * s)
        val vph = minOf(surfaceH.toFloat(), oh * s)
        val visW = vpw / (ow * s)
        val visH = vph / (oh * s)
        val x = (cx.coerceIn(visW / 2, 1 - visW / 2)) - visW / 2
        val y = (cy.coerceIn(visH / 2, 1 - visH / 2)) - visH / 2
        val vx = ((surfaceW - vpw) / 2).toInt()
        val vy = ((surfaceH - vph) / 2).toInt()
        val t0 = System.nanoTime()
        Native.engineRender(engine, params, vx, vy, vpw.toInt().coerceAtLeast(1), vph.toInt().coerceAtLeast(1), x, y, visW, visH)
        if (!firstFrameDone) {
            GLES20.glFinish()
            firstFrameDone = true
            val total = (System.nanoTime() - loadStart) / 1_000_000
            onTiming("edit_first_frame_ms", total)
            _state.value = _state.value.copy(firstFrameMs = total)
        } else if (s > 0.9f) {
            // Zoomed past what the half size decode can show: bring in the full resolution source.
            ensureFull()
        }
        if (wantHistogram) {
            wantHistogram = false
            computeHistogram()
        }
        onTiming("frame_render_ms", (System.nanoTime() - t0) / 1_000_000)
    }

    private fun computeHistogram() {
        val w = 256
        val ow = geometryOutSize[0].toFloat().coerceAtLeast(1f)
        val oh = geometryOutSize[1].toFloat().coerceAtLeast(1f)
        val h = (w * oh / ow).toInt().coerceIn(16, 512)
        val buf = ByteArray(w * h * 4)
        if (!Native.engineRenderRegion(engine, params, w, h, 0f, 0f, 1f, 1f, buf)) return
        val hist = IntArray(256 * 3)
        var i = 0
        while (i < buf.size) {
            hist[buf[i].toInt() and 0xFF]++
            hist[256 + (buf[i + 1].toInt() and 0xFF)]++
            hist[512 + (buf[i + 2].toInt() and 0xFF)]++
            i += 4
        }
        _histogram.value = hist
        // renderRegion used the shared target; redraw the screen next frame
        requestRender()
    }
}

package app.rawline.core.studio.render

import app.rawline.core.nativelib.StudioNative

/** The compositor calls the session needs. Every call must be on the GL thread that owns the context ([GpuExecutor.post] runs blocks there). */
interface StudioGpu {
    fun setLayerImage(slot: Int, rgba: ByteArray, w: Int, h: Int): Boolean
    fun updateRegion(slot: Int, x: Int, y: Int, w: Int, h: Int, rgba: ByteArray): Boolean
    fun removeLayer(slot: Int)
    fun beginStroke(slot: Int, r: Float, g: Float, b: Float, opacity: Float, erase: Boolean, hardness: Float, flow: Float): Boolean
    fun addStamps(xyr: FloatArray, count: Int): Boolean
    fun readStroke(x: Int, y: Int, w: Int, h: Int, coverage: FloatArray): Boolean
    fun endStroke()
    fun textureBytes(): Long
    /** Straight RGBA8 of the view `(vx, vy)` at [zoom] into [out] (outW * outH * 4 bytes, row 0 at the top). False on a GL error. */
    fun render(layers: FloatArray, vx: Float, vy: Float, zoom: Float, outW: Int, outH: Int, out: ByteArray): Boolean
}

/** [StudioGpu] on the native compositor (jni_studio.cpp). [handle] is the object made by `StudioNative.create` on the GL thread. */
class NativeStudioGpu(private val handle: Long) : StudioGpu {
    override fun setLayerImage(slot: Int, rgba: ByteArray, w: Int, h: Int) = StudioNative.setLayerImage(handle, slot, rgba, w, h)
    override fun updateRegion(slot: Int, x: Int, y: Int, w: Int, h: Int, rgba: ByteArray) = StudioNative.updateLayerRegion(handle, slot, x, y, w, h, rgba)
    override fun removeLayer(slot: Int) = StudioNative.removeLayer(handle, slot)
    override fun beginStroke(slot: Int, r: Float, g: Float, b: Float, opacity: Float, erase: Boolean, hardness: Float, flow: Float) = StudioNative.beginStroke(handle, slot, r, g, b, opacity, erase, hardness, flow)
    override fun addStamps(xyr: FloatArray, count: Int) = StudioNative.addStamps(handle, xyr, count)
    override fun readStroke(x: Int, y: Int, w: Int, h: Int, coverage: FloatArray) = StudioNative.readStroke(handle, x, y, w, h, coverage)
    override fun endStroke() = StudioNative.endStroke(handle)
    override fun textureBytes() = StudioNative.textureBytes(handle)
    override fun render(layers: FloatArray, vx: Float, vy: Float, zoom: Float, outW: Int, outH: Int, out: ByteArray) = StudioNative.render(handle, layers, vx, vy, zoom, outW, outH, out)
}

/** What one frame shows: visible layers bottom to top as 6 floats each (slot, x, y, scale, opacity 0..1, blend id), and the view. Immutable, so the GL thread can read the latest one without a lock. */
class FrameSpec(val layers: FloatArray, val vx: Float, val vy: Float, val zoom: Float, val docW: Int, val docH: Int)

/** Told on the GL thread; must not block. */
interface SurfaceListener {
    fun onSurfaceSize(w: Int, h: Int)
    /** A new GL context was made after an earlier one was lost: every texture is gone. Not called for the first context. */
    fun onContextRestored()
}

/** The GL thread as the session sees it (same rule as EditorSession.post: queued until the context exists). */
interface GpuExecutor {
    /** Runs [block] on the GL thread once the context exists, in order. [onDrop] runs instead when the surface is gone for good. Never blocks the caller. */
    fun post(onDrop: (() -> Unit)? = null, block: (StudioGpu) -> Unit)
    fun setFrame(frame: FrameSpec?)
    fun requestRender()
    var listener: SurfaceListener?
    /** True while the app is in the background or the screen is off (the view is paused): GPU jobs wait until it resumes. Read by the exporter. */
    val isPaused: Boolean get() = false
}

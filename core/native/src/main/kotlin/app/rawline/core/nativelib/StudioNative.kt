package app.rawline.core.nativelib

/** JNI for the Studio compositor (jni_studio.cpp). Every call except [create] and [abandon] must be on the GL thread that owns the context. */
object StudioNative {
    init {
        System.loadLibrary("raw")
        System.loadLibrary("rawline_jni")
    }

    external fun create(): Long
    /** null on success, else the GL error text. */
    external fun init(h: Long): String?
    external fun destroy(h: Long)
    /** The GL context is gone: free the object without any GL call. */
    external fun abandon(h: Long)
    /** Straight RGBA8, row 0 at the top. On a GL error the slot keeps its previous image and false is returned. */
    external fun setLayerImage(h: Long, slot: Int, rgba: ByteArray, w: Int, hgt: Int): Boolean
    external fun updateLayerRegion(h: Long, slot: Int, x: Int, y: Int, w: Int, hgt: Int, rgba: ByteArray): Boolean
    external fun removeLayer(h: Long, slot: Int)
    /** [layers]: 6 floats per layer, bottom to top: slot, x, y, scale, opacity (0 to 1), blend mode id. [out]: outW * outH * 4 bytes of straight RGBA8, row 0 at the top. */
    external fun render(h: Long, layers: FloatArray, vx: Float, vy: Float, zoom: Float, outW: Int, outH: Int, out: ByteArray): Boolean
    /** Starts a live stroke on [slot]: clears its coverage buffer. [r], [g], [b] are straight 0 to 1 in the document's blend space. */
    external fun beginStroke(h: Long, slot: Int, r: Float, g: Float, b: Float, opacity: Float, erase: Boolean, hardness: Float, flow: Float): Boolean
    /** [xyr]: count * 3 floats, x, y and radius of each stamp in layer pixels (from `StrokeWalker`). */
    external fun addStamps(h: Long, xyr: FloatArray, count: Int): Boolean
    /** Coverage 0 to 1 of the rectangle, w * h floats, for the commit (`StrokeReference.commit`). */
    external fun readStroke(h: Long, x: Int, y: Int, w: Int, hgt: Int, coverage: FloatArray): Boolean
    external fun endStroke(h: Long)
    /** GPU bytes held by layer textures and the ping-pong pair (for the Copy report). */
    external fun textureBytes(h: Long): Long
}

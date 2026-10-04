package app.rawline.core.nativelib

object Native {
    init {
        System.loadLibrary("raw")
        System.loadLibrary("rawline_jni")
    }

    external fun librawVersion(): String

    /** Returns [offset, length, tiffOrientation] of the biggest embedded JPEG, or null. */
    external fun findPreview(fd: Int): LongArray?

    /** pread() of exactly [len] bytes at [off]; null if the file is shorter. */
    external fun readBytes(fd: Int, off: Long, len: Int): ByteArray?

    // ---- Raw decode (any thread). Returns a native handle, 0 on failure. ----
    external fun decodeRaw(fd: Int, half: Boolean): Long
    external fun rawFromRgba(px: ByteArray, w: Int, h: Int): Long   // 8 bit sRGB image to the working space
    external fun rawInfo(handle: Long): IntArray      // width, height, tiff orientation
    external fun freeRaw(handle: Long)

    // ---- GPU engine: every call must happen on the GL thread that owns the context ----
    external fun engineCreate(): Long
    external fun engineInit(h: Long): String?          // null on success, else the GL error text
    external fun engineDestroy(h: Long)
    external fun engineSetSource(h: Long, raw: Long): Boolean
    external fun engineSetBaseCurve(h: Long, enabled: Boolean)
    external fun engineSetLayer(h: Long, index: Int, alpha: ByteArray, w: Int, hgt: Int)
    external fun engineSetOverlay(h: Long, rgbaHalf: ShortArray?, w: Int, hgt: Int)
    external fun engineOutputSize(h: Long, params: FloatArray): IntArray
    external fun engineRender(h: Long, params: FloatArray, vx: Int, vy: Int, vw: Int, vh: Int, x: Float, y: Float, w: Float, hgt: Float)
    external fun engineRenderRegion(h: Long, params: FloatArray, pw: Int, ph: Int, x: Float, y: Float, w: Float, hgt: Float, out: ByteArray): Boolean
    external fun engineRenderRegionHalf(h: Long, params: FloatArray, pw: Int, ph: Int, x: Float, y: Float, w: Float, hgt: Float, out: ShortArray): Boolean
    external fun engineInvalidate(h: Long)
    external fun engineSetOutputSpace(h: Long, space: Int)   // 0 sRGB, 1 Display P3
    external fun engineUpdateOverlay(h: Long, x: Int, y: Int, w: Int, hgt: Int, rgbaHalf: ShortArray)
    external fun baseCurve(): FloatArray
    /** Linear working-space rgb of a rectangle of a decoded raw (3 floats per pixel, edges clamped). */
    external fun rawRead(handle: Long, x: Int, y: Int, w: Int, h: Int): FloatArray
    external fun rawWrite(handle: Long, x: Int, y: Int, w: Int, h: Int, rgb: FloatArray)
    external fun paramFloats(): Int
}

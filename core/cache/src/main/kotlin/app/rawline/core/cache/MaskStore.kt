package app.rawline.core.cache

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer

/** 8 bit alpha masks (AI results) kept as PNG files so edits stay small and masks survive restarts. */
class MaskStore(context: Context) {
    val dir = File(context.filesDir, "masks").apply { mkdirs() }

    fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png")

    fun save(key: String, alpha: ByteArray, w: Int, h: Int) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        b.copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
        file(key).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    /** Returns (alpha, w, h) or null. */
    fun load(key: String): Triple<ByteArray, Int, Int>? {
        val f = file(key)
        if (!f.exists()) return null
        val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ALPHA_8 }
        val b = android.graphics.BitmapFactory.decodeFile(f.path, opts) ?: return null
        val src = if (b.config == Bitmap.Config.ALPHA_8) b else b.copy(Bitmap.Config.ALPHA_8, false)
        val out = ByteArray(src.byteCount)
        src.copyPixelsToBuffer(ByteBuffer.wrap(out))
        return Triple(out, src.width, src.height)
    }

    fun delete(key: String) { file(key).delete() }
    fun all(): List<File> = dir.listFiles()?.toList() ?: emptyList()
}

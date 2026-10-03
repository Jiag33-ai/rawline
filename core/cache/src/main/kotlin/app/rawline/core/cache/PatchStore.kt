package app.rawline.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Repair patches (RGBA, straight alpha) kept as PNG files next to the edit. */
class PatchStore(context: Context) {
    val dir = File(context.filesDir, "heals").apply { mkdirs() }
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png")

    fun save(key: String, pixels: IntArray, w: Int, h: Int) {
        val b = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        file(key).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Returns (pixels, w, h) with straight alpha, or null. */
    fun load(key: String): Triple<IntArray, Int, Int>? {
        val f = file(key)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inPremultiplied = false }
        val b = BitmapFactory.decodeFile(f.path, o) ?: return null
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Triple(px, b.width, b.height)
    }

    fun delete(key: String) { file(key).delete() }
    fun all(): List<File> = dir.listFiles()?.toList() ?: emptyList()
}

package app.rawline.core.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.rawline.core.cache.PatchStore
import app.rawline.core.cache.PerfLog
import app.rawline.core.model.BrushStroke
import app.rawline.core.model.HealOp
import app.rawline.core.render.EditorSession
import app.rawline.core.render.Geo
import app.rawline.core.render.HealOverlay
import app.rawline.core.render.RenderParams
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Repairs: AI remove (LaMa), heal and clone. Each repair becomes a small RGBA patch in source space that is laid over the raw
 * before any adjustment. Strokes are stored in the masks' frame; the patch is placed using the geometry at the time of the stroke.
 */
class Healer(
    private val context: Context,
    private val session: EditorSession,
    private val models: ModelStore,
    private val patches: PatchStore,
) {
    private var overlay: HealOverlay? = null
    private var applied: List<String?> = emptyList()
    private var appliedLook = -1
    private val lama = lazy { TfModel(context, models.file("lama_dilated.tflite"), "lama") }
    val busy = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Frees the model and sends the overlay to the GPU again after a lost GL context. */
    fun release() { if (lama.isInitialized()) lama.value.release() }
    fun resendOverlay() { overlay?.resend() }

    private fun ensureOverlay(): HealOverlay {
        overlay?.let { return it }
        return HealOverlay(session, session.sourceWidth, session.sourceHeight, session.currentRecipe.lookVersion, useBase = session.usesBaseCurve).also { overlay = it; session.setOverlayActive(true) }
    }

    /**
     * Rebuilds the overlay when the list of repairs changed (undo, redo, snapshot, opening a photo) or when the edit moved to another look
     * version (Update look and its undo): the patches are stored as display values, and the overlay holds them converted with the look's
     * base curve, so it is made again from the stored patches under the new look.
     */
    fun sync(heals: List<HealOp>, look: Int) {
        val keys = heals.map { it.patchKey }
        if (keys == applied && look == appliedLook) return
        if (look != appliedLook) overlay = null
        applied = keys; appliedLook = look
        if (heals.isEmpty()) { overlay?.clear(); session.setOverlayActive(false); return }
        val o = ensureOverlay()
        o.clear()
        heals.forEach { op ->
            val key = op.patchKey ?: return@forEach
            val (px, w, h) = patches.load(key) ?: return@forEach
            if (op.region.size == 4) o.apply(op.region, px, w, h)
        }
        session.setOverlayActive(true)
    }

    private data class Region(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * @param stroke stroke in the frame (size is a fraction of frame height)
     * @param source for clone and heal, the point in the frame to copy from
     */
    suspend fun add(kind: String, stroke: BrushStroke, source: Pair<Float, Float>?): HealOp? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { addOffMain(kind, stroke, source) }

    private suspend fun addOffMain(kind: String, stroke: BrushStroke, source: Pair<Float, Float>?): HealOp? {
        val s = session.currentRecipe
        val sw = session.sourceWidth; val sh = session.sourceHeight
        if (sw <= 1) return null
        if (kind == "remove" && !models.ensure(Models.LAMA)) return null
        busy.value = if (kind == "remove") "Removing (AI)" else "Repairing"
        try {
            val t0 = System.nanoTime()
            val fh = Geo.frameHeightPx(s.geometry, session.orientationValue, sw, sh)
            // the shader warps the frame through the lens profile polynomial before it samples the source, so strokes must be placed the same way
            val lensDist = RenderParams.lensDistFor(s, session.lens)
            val pts = HealGeometry.strokeToSource(stroke.points, s, session.orientationValue, sw, sh, lensDist)
            if (pts.isEmpty() || pts.first()[2] < 0.5f) return null
            val radius = stroke.size * fh / 2f   // source px
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            pts.forEach { minX = min(minX, it[0]); maxX = max(maxX, it[0]); minY = min(minY, it[1]); maxY = max(maxY, it[1]) }
            val padX = radius * (1f + stroke.feather) * 1.4f / sw; val padY = radius * (1f + stroke.feather) * 1.4f / sh
            minX -= padX; maxX += padX; minY -= padY; maxY += padY
            val bw = (maxX - minX) * sw; val bh = (maxY - minY) * sh

            val region: Region
            val pw: Int; val ph: Int
            if (kind == "remove") {
                var side = max(bw, bh) * 2.2f + 96f
                side = side.coerceIn(min(256f, min(sw, sh).toFloat()), min(sw, sh).toFloat())
                val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
                val rw = side / sw; val rh = side / sh
                region = Region((cx - rw / 2).coerceIn(0f, 1f - rw), (cy - rh / 2).coerceIn(0f, 1f - rh), rw, rh)
                pw = 512; ph = 512
            } else {
                val rw = (maxX - minX).coerceIn(8f / sw, 1f); val rh = (maxY - minY).coerceIn(8f / sh, 1f)
                region = Region(minX.coerceIn(0f, 1f - rw), minY.coerceIn(0f, 1f - rh), rw, rh)
                val scale = min(1f, 1024f / max(rw * sw, rh * sh))
                pw = max(16, (rw * sw * scale).toInt()); ph = max(16, (rh * sh * scale).toInt())
            }
            val orig = session.renderSource(region.x, region.y, region.w, region.h, pw, ph) ?: return null
            val mask = rasterMask(pts, region, pw, ph, radius, stroke.feather, sw, sh)

            val patch: IntArray = when (kind) {
                "remove" -> aiPatch(orig, mask, pw, ph)
                else -> {
                    val src = source ?: return null
                    val sp = Geo.frameToSource(src.first, src.second, s.geometry, s.optics, session.orientationValue, sw, sh, lensDist)
                    val dx = sp[0] - pts[0][0]; val dy = sp[1] - pts[0][1]
                    val rx = (region.x + dx).coerceIn(0f, 1f - region.w); val ry = (region.y + dy).coerceIn(0f, 1f - region.h)
                    val srcImg = session.renderSource(rx, ry, region.w, region.h, pw, ph) ?: return null
                    clonePatch(orig, srcImg, mask, pw, ph, matchColour = kind == "heal")
                }
            }
            val key = "heal_${System.currentTimeMillis().toString(36)}"
            patches.save(key, patch, pw, ph)
            ensureOverlay().apply(listOf(region.x, region.y, region.w, region.h), patch, pw, ph)
            session.setOverlayActive(true)
            PerfLog.record("heal_${kind}_ms", (System.nanoTime() - t0) / 1_000_000)
            val op = HealOp(kind, stroke, source?.first ?: 0f, source?.second ?: 0f, key, listOf(region.x, region.y, region.w, region.h))
            applied = applied + key
            return op
        } finally { busy.value = null }
    }

    private fun rasterMask(pts: List<FloatArray>, r: Region, pw: Int, ph: Int, radiusPx: Float, feather: Float, sw: Int, sh: Int): FloatArray {
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val rpx = radiusPx * pw / (r.w * sw)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = rpx * 2f; color = 0xFF000000.toInt()
            if (feather > 0.05f) maskFilter = BlurMaskFilter((rpx * feather * 0.6f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
        val path = Path()
        pts.forEachIndexed { i, q ->
            val x = (q[0] - r.x) / r.w * pw; val y = (q[1] - r.y) / r.h * ph
            if (i == 0) { path.moveTo(x, y); if (pts.size == 1) c.drawPoint(x, y, p) } else path.lineTo(x, y)
        }
        if (pts.size > 1) c.drawPath(path, p)
        val bytes = ByteArray(pw * ph)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        return FloatArray(pw * ph) { (bytes[it].toInt() and 255) / 255f }
    }

    private fun clonePatch(orig: Bitmap, src: Bitmap, mask: FloatArray, w: Int, h: Int, matchColour: Boolean): IntArray {
        val o = IntArray(w * h); orig.getPixels(o, 0, w, 0, 0, w, h)
        val s = IntArray(w * h); src.getPixels(s, 0, w, 0, 0, w, h)
        var dr = 0f; var dg = 0f; var db = 0f
        if (matchColour) {
            // Compare a ring just outside the stroke so the copied texture takes on the surrounding tone.
            var n = 0; var ar = 0f; var ag = 0f; var ab = 0f
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (mask[i] > 0.05f) continue
                var near = false
                for (k in -3..3 step 3) { if (mask[(y + k).coerceIn(0, h - 1) * w + x] > 0.3f || mask[y * w + (x + k).coerceIn(0, w - 1)] > 0.3f) near = true }
                if (!near) continue
                ar += (o[i] shr 16 and 255) - (s[i] shr 16 and 255); ag += (o[i] shr 8 and 255) - (s[i] shr 8 and 255); ab += (o[i] and 255) - (s[i] and 255); n++
            }
            if (n > 0) { dr = ar / n; dg = ag / n; db = ab / n }
        }
        return IntArray(w * h) { i ->
            val a = (mask[i].coerceIn(0f, 1f) * 255f).toInt()
            val r = ((s[i] shr 16 and 255) + dr).toInt().coerceIn(0, 255); val g = ((s[i] shr 8 and 255) + dg).toInt().coerceIn(0, 255); val b = ((s[i] and 255) + db).toInt().coerceIn(0, 255)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun aiPatch(orig: Bitmap, mask: FloatArray, w: Int, h: Int): IntArray {
        val o = IntArray(w * h); orig.getPixels(o, 0, w, 0, 0, w, h)
        val img = TfModel.floats(w * h * 3); val fi = img.asFloatBuffer()
        for (c in o) { fi.put((c shr 16 and 255) / 255f); fi.put((c shr 8 and 255) / 255f); fi.put((c and 255) / 255f) }
        // Binary mask, grown a little so the model sees the whole object edge
        val hard = FloatArray(w * h) { if (mask[it] > 0.15f) 1f else 0f }
        val grown = grow(hard, w, h, 5)
        val mk = TfModel.floats(w * h); mk.asFloatBuffer().put(grown)
        val out = TfModel.floats(w * h * 3)
        val m = lama.value
        val ii = m.inputIndex("image", 0); val im = m.inputIndex("mask", 1)
        val ins = arrayOfNulls<Any>(2); ins[ii] = img; ins[im] = mk
        m.run(ins.requireNoNulls(), mapOf(0 to out))
        out.rewind()
        val f = out.asFloatBuffer()
        val painted = FloatArray(w * h * 3); f.get(painted)
        // Blend the paint in over the grown mask with a soft edge
        val soft = GuidedFilter.box(grown, w, h, 3)
        return IntArray(w * h) { i ->
            val a = max(soft[i], mask[i]).coerceIn(0f, 1f)
            val r = (painted[i * 3].coerceIn(0f, 1f) * 255f).toInt(); val g = (painted[i * 3 + 1].coerceIn(0f, 1f) * 255f).toInt(); val b = (painted[i * 3 + 2].coerceIn(0f, 1f) * 255f).toInt()
            ((a * 255f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun grow(m: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val b = GuidedFilter.box(m, w, h, r)
        return FloatArray(w * h) { if (b[it] > 0.02f) 1f else 0f }
    }
}

/** Frame to source mapping for repair strokes: the same warp the shader applies, so a stroke lands on the object the user touched. */
internal object HealGeometry {
    /** One (u, v, inside) triple per point of [points] (x, y pairs in the frame). */
    fun strokeToSource(points: List<Float>, recipe: app.rawline.core.model.EditRecipe, orientation: Int, srcW: Int, srcH: Int, lensDist: FloatArray?): List<FloatArray> {
        val out = ArrayList<FloatArray>(points.size / 2)
        var i = 0
        while (i + 1 < points.size) { out.add(Geo.frameToSource(points[i], points[i + 1], recipe.geometry, recipe.optics, orientation, srcW, srcH, lensDist)); i += 2 }
        return out
    }
}

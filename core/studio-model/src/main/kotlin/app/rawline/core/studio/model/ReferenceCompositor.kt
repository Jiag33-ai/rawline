package app.rawline.core.studio.model

import kotlin.math.floor

/** Straight RGBA8 pixels of one layer, row 0 at the top. */
class RefImage(val w: Int, val h: Int, val rgba: ByteArray) {
    init { require(rgba.size == w * h * 4) { "pixel buffer is ${rgba.size} bytes, expected ${w * h * 4}" } }
}

/** One visible layer as the compositor sees it. [x], [y] are the document position of the top left corner; the layer covers w*scale by h*scale document pixels. */
class RefLayer(val image: RefImage, val x: Float, val y: Float, val scale: Float, val opacity: Float, val mode: BlendMode)

/**
 * The slow, exact CPU compositor of spec 4.5. The GPU path (studio_composite.frag) is compared with it in the golden scene, and the
 * golden expectation itself comes from the independent Python reference (tools/studio/studio_ref.py), which shares test vectors
 * with this class through blend_vectors.tsv. Sampling is bilinear on alpha weighted straight colour, in pixel centres, clamped to the
 * layer edge, and alpha is 0 outside the layer rectangle.
 */
object ReferenceCompositor {
    fun sample(l: RefLayer, dx: Float, dy: Float): Rgba {
        val img = l.image
        val rw = img.w * l.scale; val rh = img.h * l.scale
        val lx = (dx - l.x) / rw * img.w
        val ly = (dy - l.y) / rh * img.h
        if (!(lx >= 0f && lx < img.w && ly >= 0f && ly < img.h)) return Rgba.CLEAR
        val u = lx - 0.5f; val v = ly - 0.5f
        val i0 = floor(u).toInt(); val j0 = floor(v).toInt()
        val fx = u - i0; val fy = v - j0
        var r = 0f; var g = 0f; var b = 0f; var asum = 0f
        for (jj in 0..1) for (ii in 0..1) {
            val cx = (i0 + ii).coerceIn(0, img.w - 1); val cy = (j0 + jj).coerceIn(0, img.h - 1)
            val o = (cy * img.w + cx) * 4
            val a = (img.rgba[o + 3].toInt() and 255) / 255f
            val w = (if (ii == 0) 1 - fx else fx) * (if (jj == 0) 1 - fy else fy) * a
            r += w * (img.rgba[o].toInt() and 255) / 255f
            g += w * (img.rgba[o + 1].toInt() and 255) / 255f
            b += w * (img.rgba[o + 2].toInt() and 255) / 255f
            asum += w
        }
        return if (asum <= 0f) Rgba.CLEAR else Rgba(r / asum, g / asum, b / asum, asum)
    }

    /** Renders the view (top left [vx], [vy] in document pixels, [zoom] screen pixels per document pixel) into straight RGBA8, rounded to nearest. Layers bottom to top. */
    fun render(layers: List<RefLayer>, vx: Float, vy: Float, zoom: Float, outW: Int, outH: Int): ByteArray {
        val out = ByteArray(outW * outH * 4)
        for (y in 0 until outH) for (x in 0 until outW) {
            val dx = vx + (x + 0.5f) / zoom; val dy = vy + (y + 0.5f) / zoom
            var acc = Rgba.CLEAR
            for (l in layers) {
                val s = sample(l, dx, dy)
                if (s.a > 0f) acc = Blend.over(acc, s, l.mode, l.opacity)
            }
            val o = (y * outW + x) * 4
            out[o] = q(acc.r); out[o + 1] = q(acc.g); out[o + 2] = q(acc.b); out[o + 3] = q(acc.a)
        }
        return out
    }

    private fun q(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** The visible layers of [doc] for which [pixels] returns an image, as compositor input. */
    fun layersOf(doc: Document, pixels: (Layer.Pixel) -> RefImage?): List<RefLayer> = doc.layers.mapNotNull { l ->
        val p = l as? Layer.Pixel ?: return@mapNotNull null
        if (!p.common.visible) return@mapNotNull null
        val img = pixels(p) ?: return@mapNotNull null
        RefLayer(img, p.common.x.toFloat(), p.common.y.toFloat(), p.common.scale, p.common.opacity / 100f, p.common.blend)
    }
}

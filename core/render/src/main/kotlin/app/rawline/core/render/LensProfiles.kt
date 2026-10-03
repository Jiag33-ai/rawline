package app.rawline.core.render

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs
import kotlin.math.ln

/** What the shader needs to undo a lens's distortion, lateral chromatic aberration and vignetting. */
class LensCorrection(
    val name: String,
    val dist: FloatArray?,      // p0..p4 of r_src = r * (p0 + p1 r + ... + p4 r^4), r = radius over half the short side
    val tca: FloatArray?,       // red v, c, b then blue v, c, b
    val vig: FloatArray?,       // k1 k2 k3
)

/**
 * Lens profiles from the lensfun database (data only, parsed here; the lensfun library itself is not used). The shipped file
 * holds the L-mount lenses (Lumix S, Sigma, Leica). Calibrations are interpolated by focal length.
 */
class LensProfiles private constructor(private val lenses: List<Lens>) {
    private class Dist(val focal: Float, val p: FloatArray)
    private class Tca(val focal: Float, val v: FloatArray)
    private class Vig(val focal: Float, val aperture: Float, val distance: Float, val k: FloatArray)
    private class Lens(val maker: String, val model: String, val dist: List<Dist>, val tca: List<Tca>, val vig: List<Vig>)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun find(exifLens: String?, focal: Float, aperture: Float): LensCorrection? {
        if (exifLens.isNullOrBlank() || focal <= 0f) return null
        val e = norm(exifLens)
        val l = lenses.firstOrNull { norm(it.model) == e || norm(it.maker + it.model) == e }
            ?: lenses.firstOrNull { val m = norm(it.model); m.length > 8 && (e.contains(m) || m.contains(e)) }
            ?: return null
        return LensCorrection(l.model, interpDist(l.dist, focal), interpTca(l.tca, focal), nearestVig(l.vig, focal, aperture))
    }

    private fun lerpArr(a: FloatArray, b: FloatArray, t: Float) = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    private fun <T> bracket(items: List<T>, focal: Float, f: (T) -> Float): Triple<T, T, Float>? {
        if (items.isEmpty()) return null
        val s = items.sortedBy(f)
        val lo = s.lastOrNull { f(it) <= focal } ?: return Triple(s.first(), s.first(), 0f)
        val hi = s.firstOrNull { f(it) >= focal } ?: return Triple(s.last(), s.last(), 0f)
        val span = f(hi) - f(lo)
        return Triple(lo, hi, if (span <= 1e-6f) 0f else (focal - f(lo)) / span)
    }

    private fun interpDist(d: List<Dist>, focal: Float): FloatArray? = bracket(d, focal) { it.focal }?.let { (a, b, t) -> lerpArr(a.p, b.p, t) }
    private fun interpTca(d: List<Tca>, focal: Float): FloatArray? = bracket(d, focal) { it.focal }?.let { (a, b, t) -> lerpArr(a.v, b.v, t) }

    private fun nearestVig(v: List<Vig>, focal: Float, aperture: Float): FloatArray? {
        if (v.isEmpty()) return null
        val far = v.filter { it.distance >= 100f }.ifEmpty { v }
        return far.minByOrNull { abs(ln(focal / it.focal.coerceAtLeast(1f))) * 2f + abs(ln(aperture.coerceAtLeast(1f) / it.aperture.coerceAtLeast(1f))) }?.k
    }

    companion object {
        @Volatile private var instance: LensProfiles? = null

        fun get(context: Context): LensProfiles = instance ?: synchronized(this) {
            instance ?: runCatching { context.assets.open("lensfun/lenses_lmount.xml").use { parse(it) } }.getOrDefault(LensProfiles(emptyList())).also { instance = it }
        }

        fun parse(input: java.io.InputStream): LensProfiles {
            val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(input, "UTF-8")
            val lenses = ArrayList<Lens>()
            var maker = ""; var model = ""
            val dist = ArrayList<Dist>(); val tca = ArrayList<Tca>(); val vig = ArrayList<Vig>()
            var text = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        text = ""
                        when (p.name) {
                            "lens" -> { maker = ""; model = ""; dist.clear(); tca.clear(); vig.clear() }
                            "distortion" -> parseDist(p)?.let { dist.add(it) }
                            "tca" -> parseTca(p)?.let { tca.add(it) }
                            "vignetting" -> parseVig(p)?.let { vig.add(it) }
                        }
                    }
                    XmlPullParser.TEXT -> text = p.text.trim()
                    XmlPullParser.END_TAG -> when (p.name) {
                        "maker" -> maker = text
                        "model" -> if (model.isEmpty()) model = text
                        "lens" -> lenses.add(Lens(maker, model, dist.toList(), tca.toList(), vig.toList()))
                    }
                }
                ev = p.next()
            }
            return LensProfiles(lenses)
        }

        private fun f(p: XmlPullParser, n: String, d: Float = 0f) = p.getAttributeValue(null, n)?.toFloatOrNull() ?: d

        private fun parseDist(p: XmlPullParser): Dist? {
            val focal = f(p, "focal", -1f)
            if (focal <= 0f) return null
            return when (p.getAttributeValue(null, "model")) {
                "ptlens" -> { val a = f(p, "a"); val b = f(p, "b"); val c = f(p, "c"); Dist(focal, floatArrayOf(1f - a - b - c, c, b, a, 0f)) }
                "poly3" -> { val k = f(p, "k1"); Dist(focal, floatArrayOf(1f - k, 0f, k, 0f, 0f)) }
                "poly5" -> Dist(focal, floatArrayOf(1f, 0f, f(p, "k1"), 0f, f(p, "k2")))
                else -> null
            }
        }

        private fun parseTca(p: XmlPullParser): Tca? {
            val focal = f(p, "focal", -1f)
            if (focal <= 0f) return null
            return when (p.getAttributeValue(null, "model")) {
                "poly3" -> Tca(focal, floatArrayOf(f(p, "vr", 1f), f(p, "cr"), f(p, "br"), f(p, "vb", 1f), f(p, "cb"), f(p, "bb")))
                "linear" -> Tca(focal, floatArrayOf(f(p, "kr", 1f), 0f, 0f, f(p, "kb", 1f), 0f, 0f))
                else -> null
            }
        }

        private fun parseVig(p: XmlPullParser): Vig? {
            if (p.getAttributeValue(null, "model") != "pa") return null
            return Vig(f(p, "focal"), f(p, "aperture", 8f), f(p, "distance", 1000f), floatArrayOf(f(p, "k1"), f(p, "k2"), f(p, "k3")))
        }
    }
}

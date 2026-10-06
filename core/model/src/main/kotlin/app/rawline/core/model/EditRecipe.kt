package app.rawline.core.model

import org.json.JSONArray
import org.json.JSONObject

/** A point on a tone curve, both axes 0..1. */
data class CurvePoint(val x: Float, val y: Float)

data class Curves(
    val master: List<CurvePoint> = emptyList(),
    val red: List<CurvePoint> = emptyList(),
    val green: List<CurvePoint> = emptyList(),
    val blue: List<CurvePoint> = emptyList(),
    /** Parametric curve: highlights, lights, darks, shadows (-100..100). */
    val parametric: List<Float> = listOf(0f, 0f, 0f, 0f),
) {
    val isIdentity get() = master.isEmpty() && red.isEmpty() && green.isEmpty() && blue.isEmpty() && parametric.all { it == 0f }
}

data class Hsl(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f)

data class Grading(
    val shadows: Hsl = Hsl(), val mid: Hsl = Hsl(), val highlights: Hsl = Hsl(), val global: Hsl = Hsl(),
    val blending: Float = 50f, val balance: Float = 0f,
)

/** Everything that can differ per region: used for the whole photo and inside each mask. */
data class Adjust(
    val exposure: Float = 0f, val contrast: Float = 0f, val highlights: Float = 0f, val shadows: Float = 0f,
    val whites: Float = 0f, val blacks: Float = 0f,
    val temp: Float = 0f, val tint: Float = 0f, val vibrance: Float = 0f, val saturation: Float = 0f,
    val texture: Float = 0f, val clarity: Float = 0f, val dehaze: Float = 0f,
    val mixHue: List<Float> = List(8) { 0f }, val mixSat: List<Float> = List(8) { 0f }, val mixLum: List<Float> = List(8) { 0f },
    val grading: Grading = Grading(),
    val curves: Curves = Curves(),
) {
    val isIdentity get() = this == Adjust()
}

data class Detail(
    val sharpen: Float = 0f, val radius: Float = 1f, val detail: Float = 25f, val masking: Float = 0f,
    val nrLuminance: Float = 0f, val nrColor: Float = 0f, val aiDenoise: Boolean = false, val aiDenoiseAmount: Float = 50f,
)

data class Effects(
    val vignetteAmount: Float = 0f, val vignetteMidpoint: Float = 50f, val vignetteRoundness: Float = 0f, val vignetteFeather: Float = 50f,
    val grainAmount: Float = 0f, val grainSize: Float = 25f, val grainRoughness: Float = 50f,
)

data class Optics(val lensCorrection: Boolean = true, val removeCa: Boolean = true, val distortion: Float = 0f, val vignetting: Float = 0f)

data class Geometry(
    val cropX: Float = 0f, val cropY: Float = 0f, val cropW: Float = 1f, val cropH: Float = 1f,
    val angle: Float = 0f, val rotate90: Int = 0, val flipH: Boolean = false, val flipV: Boolean = false,
    val keystoneV: Float = 0f, val keystoneH: Float = 0f, val aspect: String = "original",
)

enum class MaskType(val code: Int) { LINEAR(1), RADIAL(2), BITMAP(3), COLOR(4), LUMINANCE(5) }
enum class MaskOp(val code: Int) { ADD(0), SUBTRACT(1), INTERSECT(2) }

/**
 * One piece of a mask. [params] meaning depends on [type]:
 * LINEAR: x1 y1 x2 y2 (normalised). RADIAL: cx cy rx ry angle feather. COLOR: r g b range softness. LUMINANCE: lo hi falloff.
 * BITMAP: [layerKey] names the cached alpha (brush strokes, AI results).
 */
data class MaskComponent(
    val type: MaskType, val op: MaskOp = MaskOp.ADD, val invert: Boolean = false,
    val params: List<Float> = emptyList(), val layerKey: String? = null,
    val strokes: List<BrushStroke> = emptyList(), val label: String = "",
)

data class BrushStroke(val points: List<Float>, val size: Float, val feather: Float, val flow: Float, val erase: Boolean, val autoMask: Boolean = false)

data class Mask(
    val id: String, val name: String, val components: List<MaskComponent>, val adjust: Adjust = Adjust(),
    val amount: Float = 1f, val invert: Boolean = false, val visible: Boolean = true,
)

/** Heal, clone and AI remove results are kept as a list of patches rendered into an overlay. */
/** [region] is x, y, w, h of the patch in the source image, normalised. Strokes are in the masks' frame. */
data class HealOp(val kind: String, val stroke: BrushStroke, val sourceX: Float = 0f, val sourceY: Float = 0f, val patchKey: String? = null, val region: List<Float> = emptyList())

/**
 * Look versions. V1 is the rendering every edit made before this field existed was judged under: LibRaw lowers its white point to the frame's
 * brightest pixel when that pixel is between 75 and 100 percent of white (up to +0.4 EV), white balance dims the picture by min(AsShotNeutral), and the
 * base curve has no shoulder at white. V2 removes those three effects (see docs/COLOUR.md). A saved edit keeps its version; only an explicit
 * "Update look" (or a reset, which is a new edit) moves it.
 */
object Look {
    const val V1 = 1
    const val V2 = 2
    const val CURRENT = V2
    /** A version this build cannot render (written by a newer build) is shown with the newest look it knows rather than refused. */
    fun supported(v: Int) = v.coerceIn(V1, CURRENT)

    /** The Copy report line: how many saved edits are on each look, counted from their stored JSON (a row that cannot be read is counted apart). */
    fun report(storedJson: List<String>): String {
        val counts = IntArray(CURRENT + 1)
        var unreadable = 0
        for (j in storedJson) {
            val v = try { supported(org.json.JSONObject(j).optInt("lookVersion", V1)) } catch (e: Exception) { 0 }
            if (v == 0) unreadable++ else counts[v]++
        }
        return "Edits by look: " + (V1..CURRENT).joinToString(", ") { "look $it ${counts[it]}" } + if (unreadable > 0) ", unreadable $unreadable" else ""
    }
}

data class EditRecipe(
    val schemaVersion: Int = 1,
    /** Which tone and white point behaviour renders this edit (see Look). Edits saved before the field existed read as [Look.V1] and keep their exact old rendering until the user updates them. */
    val lookVersion: Int = Look.CURRENT,
    val adjust: Adjust = Adjust(),
    val detail: Detail = Detail(),
    val effects: Effects = Effects(),
    val optics: Optics = Optics(),
    val geometry: Geometry = Geometry(),
    val masks: List<Mask> = emptyList(),
    val heals: List<HealOp> = emptyList(),
) {
    val isDefault get() = this == EditRecipe()

    /** The same edit under the current look (what "Update look" does). Not a default recipe's concern: only recipes that were saved under an older look differ. */
    fun withCurrentLook() = if (lookVersion == Look.CURRENT) this else copy(lookVersion = Look.CURRENT)

    fun toJson(): String = RecipeJson.write(this)

    companion object {
        fun fromJson(s: String): EditRecipe = RecipeJson.read(s)
    }
}

/** Hand written JSON so the format is stable and testable without extra libraries. */
object RecipeJson {
    private fun fl(a: List<Float>) = JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
    private fun floats(j: JSONArray?, n: Int = 0, default: Float = 0f): List<Float> =
        if (j == null) List(n) { default } else List(j.length()) { j.getDouble(it).toFloat() }

    private fun pts(l: List<CurvePoint>) = JSONArray().also { j -> l.forEach { j.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } }
    private fun readPts(j: JSONArray?) = if (j == null) emptyList() else List(j.length()) { val p = j.getJSONArray(it); CurvePoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) }

    private fun hsl(h: Hsl) = JSONArray().put(h.hue.toDouble()).put(h.sat.toDouble()).put(h.lum.toDouble())
    private fun readHsl(j: JSONArray?) = if (j == null) Hsl() else Hsl(j.getDouble(0).toFloat(), j.getDouble(1).toFloat(), j.getDouble(2).toFloat())

    fun adjustToJson(a: Adjust) = JSONObject().apply {
        put("exposure", a.exposure.toDouble()); put("contrast", a.contrast.toDouble()); put("highlights", a.highlights.toDouble())
        put("shadows", a.shadows.toDouble()); put("whites", a.whites.toDouble()); put("blacks", a.blacks.toDouble())
        put("temp", a.temp.toDouble()); put("tint", a.tint.toDouble()); put("vibrance", a.vibrance.toDouble())
        put("saturation", a.saturation.toDouble()); put("texture", a.texture.toDouble()); put("clarity", a.clarity.toDouble())
        put("dehaze", a.dehaze.toDouble())
        put("mixHue", fl(a.mixHue)); put("mixSat", fl(a.mixSat)); put("mixLum", fl(a.mixLum))
        put("grading", JSONObject().apply {
            put("shadows", hsl(a.grading.shadows)); put("mid", hsl(a.grading.mid)); put("highlights", hsl(a.grading.highlights))
            put("global", hsl(a.grading.global)); put("blending", a.grading.blending.toDouble()); put("balance", a.grading.balance.toDouble())
        })
        put("curves", JSONObject().apply {
            put("master", pts(a.curves.master)); put("red", pts(a.curves.red)); put("green", pts(a.curves.green)); put("blue", pts(a.curves.blue))
            put("parametric", fl(a.curves.parametric))
        })
    }

    fun adjustFromJson(o: JSONObject): Adjust {
        val g = o.optJSONObject("grading")
        val c = o.optJSONObject("curves")
        return Adjust(
            o.optDouble("exposure", 0.0).toFloat(), o.optDouble("contrast", 0.0).toFloat(), o.optDouble("highlights", 0.0).toFloat(),
            o.optDouble("shadows", 0.0).toFloat(), o.optDouble("whites", 0.0).toFloat(), o.optDouble("blacks", 0.0).toFloat(),
            o.optDouble("temp", 0.0).toFloat(), o.optDouble("tint", 0.0).toFloat(), o.optDouble("vibrance", 0.0).toFloat(),
            o.optDouble("saturation", 0.0).toFloat(), o.optDouble("texture", 0.0).toFloat(), o.optDouble("clarity", 0.0).toFloat(),
            o.optDouble("dehaze", 0.0).toFloat(),
            floats(o.optJSONArray("mixHue"), 8), floats(o.optJSONArray("mixSat"), 8), floats(o.optJSONArray("mixLum"), 8),
            if (g == null) Grading() else Grading(
                readHsl(g.optJSONArray("shadows")), readHsl(g.optJSONArray("mid")), readHsl(g.optJSONArray("highlights")),
                readHsl(g.optJSONArray("global")), g.optDouble("blending", 50.0).toFloat(), g.optDouble("balance", 0.0).toFloat(),
            ),
            if (c == null) Curves() else Curves(
                readPts(c.optJSONArray("master")), readPts(c.optJSONArray("red")), readPts(c.optJSONArray("green")), readPts(c.optJSONArray("blue")),
                floats(c.optJSONArray("parametric"), 4),
            ),
        )
    }

    private fun stroke(s: BrushStroke) = JSONObject().put("points", fl(s.points)).put("size", s.size.toDouble()).put("feather", s.feather.toDouble())
        .put("flow", s.flow.toDouble()).put("erase", s.erase).put("auto", s.autoMask)
    private fun readStroke(o: JSONObject) = BrushStroke(floats(o.getJSONArray("points")), o.getDouble("size").toFloat(), o.getDouble("feather").toFloat(),
        o.getDouble("flow").toFloat(), o.getBoolean("erase"), o.optBoolean("auto"))

    fun write(r: EditRecipe): String = JSONObject().apply {
        put("schemaVersion", r.schemaVersion)
        put("lookVersion", r.lookVersion)
        put("adjust", adjustToJson(r.adjust))
        put("detail", JSONObject().apply {
            put("sharpen", r.detail.sharpen.toDouble()); put("radius", r.detail.radius.toDouble()); put("detail", r.detail.detail.toDouble())
            put("masking", r.detail.masking.toDouble()); put("nrLum", r.detail.nrLuminance.toDouble()); put("nrColor", r.detail.nrColor.toDouble())
            put("aiDenoise", r.detail.aiDenoise); put("aiDenoiseAmount", r.detail.aiDenoiseAmount.toDouble())
        })
        put("effects", JSONObject().apply {
            put("vigAmount", r.effects.vignetteAmount.toDouble()); put("vigMid", r.effects.vignetteMidpoint.toDouble())
            put("vigRound", r.effects.vignetteRoundness.toDouble()); put("vigFeather", r.effects.vignetteFeather.toDouble())
            put("grain", r.effects.grainAmount.toDouble()); put("grainSize", r.effects.grainSize.toDouble()); put("grainRough", r.effects.grainRoughness.toDouble())
        })
        put("optics", JSONObject().apply {
            put("lens", r.optics.lensCorrection); put("ca", r.optics.removeCa); put("lensv", 1)
            put("distortion", r.optics.distortion.toDouble()); put("vignetting", r.optics.vignetting.toDouble())
        })
        put("geometry", JSONObject().apply {
            val g = r.geometry
            put("x", g.cropX.toDouble()); put("y", g.cropY.toDouble()); put("w", g.cropW.toDouble()); put("h", g.cropH.toDouble())
            put("angle", g.angle.toDouble()); put("rot", g.rotate90); put("flipH", g.flipH); put("flipV", g.flipV)
            put("ksV", g.keystoneV.toDouble()); put("ksH", g.keystoneH.toDouble()); put("aspect", g.aspect)
        })
        put("masks", JSONArray().also { arr ->
            r.masks.forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id); put("name", m.name); put("amount", m.amount.toDouble()); put("invert", m.invert); put("visible", m.visible)
                    put("adjust", adjustToJson(m.adjust))
                    put("components", JSONArray().also { ca ->
                        m.components.forEach { c ->
                            ca.put(JSONObject().apply {
                                put("type", c.type.name); put("op", c.op.name); put("invert", c.invert); put("params", fl(c.params))
                                put("layer", c.layerKey ?: JSONObject.NULL); put("label", c.label)
                                put("strokes", JSONArray().also { sa -> c.strokes.forEach { sa.put(stroke(it)) } })
                            })
                        }
                    })
                })
            }
        })
        put("heals", JSONArray().also { arr ->
            r.heals.forEach { h ->
                arr.put(JSONObject().put("kind", h.kind).put("stroke", stroke(h.stroke)).put("sx", h.sourceX.toDouble()).put("sy", h.sourceY.toDouble())
                    .put("patch", h.patchKey ?: JSONObject.NULL).put("region", fl(h.region)))
            }
        })
    }.toString()

    fun read(s: String): EditRecipe {
        val o = JSONObject(s)
        val d = o.optJSONObject("detail")
        val e = o.optJSONObject("effects")
        val op = o.optJSONObject("optics")
        val g = o.optJSONObject("geometry")
        val masks = o.optJSONArray("masks")
        val heals = o.optJSONArray("heals")
        return EditRecipe(
            o.optInt("schemaVersion", 1),
            Look.supported(o.optInt("lookVersion", Look.V1)),   // a recipe without the key was made under the first look
            o.optJSONObject("adjust")?.let(::adjustFromJson) ?: Adjust(),
            if (d == null) Detail() else Detail(
                d.optDouble("sharpen", 0.0).toFloat(), d.optDouble("radius", 1.0).toFloat(), d.optDouble("detail", 25.0).toFloat(),
                d.optDouble("masking", 0.0).toFloat(), d.optDouble("nrLum", 0.0).toFloat(), d.optDouble("nrColor", 0.0).toFloat(),
                d.optBoolean("aiDenoise"), d.optDouble("aiDenoiseAmount", 50.0).toFloat(),
            ),
            if (e == null) Effects() else Effects(
                e.optDouble("vigAmount", 0.0).toFloat(), e.optDouble("vigMid", 50.0).toFloat(), e.optDouble("vigRound", 0.0).toFloat(),
                e.optDouble("vigFeather", 50.0).toFloat(), e.optDouble("grain", 0.0).toFloat(), e.optDouble("grainSize", 25.0).toFloat(),
                e.optDouble("grainRough", 50.0).toFloat(),
            ),
            if (op == null) Optics() else // recipes saved before profiles were automatic (no "lensv") get the new default of on
                Optics(if (op.has("lensv")) op.optBoolean("lens") else true, if (op.has("lensv")) op.optBoolean("ca") else true, op.optDouble("distortion", 0.0).toFloat(), op.optDouble("vignetting", 0.0).toFloat()),
            if (g == null) Geometry() else Geometry(
                g.optDouble("x", 0.0).toFloat(), g.optDouble("y", 0.0).toFloat(), g.optDouble("w", 1.0).toFloat(), g.optDouble("h", 1.0).toFloat(),
                g.optDouble("angle", 0.0).toFloat(), g.optInt("rot", 0), g.optBoolean("flipH"), g.optBoolean("flipV"),
                g.optDouble("ksV", 0.0).toFloat(), g.optDouble("ksH", 0.0).toFloat(), g.optString("aspect", "original"),
            ),
            if (masks == null) emptyList() else List(masks.length()) { i ->
                val m = masks.getJSONObject(i)
                val ca = m.getJSONArray("components")
                Mask(
                    m.getString("id"), m.getString("name"),
                    List(ca.length()) { j ->
                        val c = ca.getJSONObject(j)
                        val sa = c.optJSONArray("strokes")
                        MaskComponent(
                            MaskType.valueOf(c.getString("type")), MaskOp.valueOf(c.getString("op")), c.optBoolean("invert"),
                            floats(c.optJSONArray("params")), if (c.isNull("layer")) null else c.getString("layer"),
                            if (sa == null) emptyList() else List(sa.length()) { readStroke(sa.getJSONObject(it)) }, c.optString("label"),
                        )
                    },
                    adjustFromJson(m.getJSONObject("adjust")), m.optDouble("amount", 1.0).toFloat(), m.optBoolean("invert"), m.optBoolean("visible", true),
                )
            },
            if (heals == null) emptyList() else List(heals.length()) { i ->
                val h = heals.getJSONObject(i)
                HealOp(h.getString("kind"), readStroke(h.getJSONObject("stroke")), h.optDouble("sx", 0.0).toFloat(), h.optDouble("sy", 0.0).toFloat(),
                    if (h.isNull("patch")) null else h.getString("patch"), floats(h.optJSONArray("region")))
            },
        )
    }
}

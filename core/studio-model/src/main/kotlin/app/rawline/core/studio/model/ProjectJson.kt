package app.rawline.core.studio.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The file is damaged or not a Studio project. The message is safe to show. */
class ProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The file was written by a newer app. Open it read-only and never write it back (the Develop rule for newer recipes). */
class NewerSchemaException(val version: Int) : Exception("This project was made by a newer version of Rawline (format $version).")

/**
 * project.json, schema version 2 (spec 2.15, S1 subset: one lossless pixel container file per pixel layer instead of tiles).
 *
 * Rules that keep old files readable: readers ignore unknown keys; writers always write `schemaVersion`; a changed meaning needs a
 * new version and a migration `v(n) -> v(n+1)` in [Migrations] with a test over a stored sample file. The writer is hand rolled so
 * the key order is stable (a diff of two saves shows only what changed) and the output does not depend on the JSON library.
 */
object ProjectJson {
    const val SCHEMA_VERSION = 2

    fun write(d: Document, appVersion: String): String {
        val sb = StringBuilder(512 + d.layers.size * 256)
        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
        sb.append("  \"appVersion\": ").append(q(appVersion)).append(",\n")
        sb.append("  \"id\": ").append(q(d.id)).append(",\n")
        sb.append("  \"name\": ").append(q(d.name)).append(",\n")
        sb.append("  \"created\": ").append(d.created).append(",\n")
        sb.append("  \"modified\": ").append(d.modified).append(",\n")
        sb.append("  \"canvas\": {\"width\": ").append(d.width).append(", \"height\": ").append(d.height).append("},\n")
        sb.append("  \"colourSpace\": ").append(q(d.colourSpace.key)).append(",\n")
        sb.append("  \"blendSpace\": ").append(q(d.blendSpace.key)).append(",\n")
        d.selection?.let { sb.append("  \"selection\": {\"dir\": ").append(q(it.dir)).append("},\n") }
        sb.append("  \"layers\": [")
        d.layers.forEachIndexed { i, l ->
            sb.append(if (i == 0) "\n" else ",\n")
            val c = l.common
            when (l) {
                is Layer.Pixel -> {
                    sb.append("    {\"kind\": \"pixel\", \"id\": ").append(q(c.id)).append(", \"name\": ").append(q(c.name))
                    sb.append(", \"visible\": ").append(c.visible).append(", \"locked\": ").append(c.locked)
                    sb.append(", \"opacity\": ").append(c.opacity).append(", \"blend\": ").append(q(c.blend.key))
                    sb.append(", \"x\": ").append(c.x).append(", \"y\": ").append(c.y).append(", \"scale\": ").append(c.scale)
                    sb.append(", \"width\": ").append(l.width).append(", \"height\": ").append(l.height)
                    sb.append(", \"pixels\": ").append(if (l.pixelsFile == null) "null" else q(l.pixelsFile))
                    c.mask?.let { sb.append(", \"mask\": {\"dir\": ").append(q(it.dir)).append(", \"enabled\": ").append(it.enabled).append(", \"inverted\": ").append(it.inverted).append(", \"linked\": ").append(it.linked).append("}") }
                    c.origin?.let { sb.append(", \"origin\": ").append(q(it)) }
                    sb.append("}")
                }
            }
        }
        sb.append(if (d.layers.isEmpty()) "]\n" else "\n  ]\n").append("}\n")
        return sb.toString()
    }

    /** Parses [text], migrating older versions. Throws [NewerSchemaException] for a newer file and [ProjectFormatException] for anything damaged. */
    fun read(text: String): Document {
        try {
            val root = Migrations.migrate(JSONObject(text))
            val canvas = root.getJSONObject("canvas")
            val layers = root.getJSONArray("layers")
            val list = ArrayList<Layer>(layers.length())
            for (i in 0 until layers.length()) list.add(readLayer(layers.getJSONObject(i)))
            return Document(
                id = root.getString("id"), name = root.getString("name"),
                width = canvas.getInt("width"), height = canvas.getInt("height"),
                colourSpace = ColourSpace.entries.firstOrNull { it.key == root.optString("colourSpace") } ?: throw ProjectFormatException("unknown colour space"),
                blendSpace = BlendSpace.entries.firstOrNull { it.key == root.optString("blendSpace") } ?: throw ProjectFormatException("unknown blend space"),
                layers = list, created = root.optLong("created", 0L), modified = root.optLong("modified", 0L),
                selection = root.optJSONObject("selection")?.let { SelectionRef(safeDir(it.getString("dir"))) },
            )
        } catch (e: JSONException) {
            throw ProjectFormatException("The project file is damaged (${e.message}).", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("The project file is not valid (${e.message}).", e)
        }
    }

    private fun readLayer(o: JSONObject): Layer {
        val kind = o.getString("kind")
        if (kind != "pixel") throw ProjectFormatException("unsupported layer kind \"$kind\"")
        val common = LayerCommon(
            id = o.getString("id"), name = o.getString("name"),
            visible = o.optBoolean("visible", true), locked = o.optBoolean("locked", false),
            opacity = o.optInt("opacity", 100).coerceIn(0, 100),
            blend = BlendMode.fromKey(o.optString("blend", "normal")) ?: throw ProjectFormatException("unknown blend mode \"${o.optString("blend")}\""),
            x = o.optInt("x", 0), y = o.optInt("y", 0), scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.25f, 4f),
            mask = o.optJSONObject("mask")?.let { MaskRef(safeDir(it.getString("dir")), it.optBoolean("enabled", true), it.optBoolean("inverted", false), it.optBoolean("linked", true)) },
            origin = if (o.isNull("origin")) null else o.optString("origin").ifEmpty { null },
        )
        return Layer.Pixel(common, o.getInt("width"), o.getInt("height"), if (o.isNull("pixels")) null else o.getString("pixels"))
    }

    /** A tile directory is a relative path under the project folder; anything that could leave it is refused. */
    private fun safeDir(d: String): String {
        if (d.isEmpty() || d.startsWith("/") || d.split('/').any { it == ".." || it.isEmpty() }) throw ProjectFormatException("unsafe tile directory \"$d\"")
        return d
    }

    private fun q(s: String) = JSONObject.quote(s)
}

/** Pure functions v(n) to v(n+1), applied in order until the current version. Each needs a stored sample file in src/test/resources and a test. */
object Migrations {
    fun migrate(root: JSONObject): JSONObject {
        val v = try { root.getInt("schemaVersion") } catch (e: JSONException) { throw ProjectFormatException("The file has no schemaVersion.", e) }
        if (v > ProjectJson.SCHEMA_VERSION) throw NewerSchemaException(v)
        if (v < 1) throw ProjectFormatException("The file has an invalid schemaVersion ($v).")
        var cur = root; var ver = v
        while (ver < ProjectJson.SCHEMA_VERSION) { cur = steps.getValue(ver)(cur); ver++ }
        return cur
    }

    /** Version 1 to 2: masks, the saved selection and `origin` are new optional keys, so only the number changes (v1 files have none of them). */
    fun v1ToV2(root: JSONObject): JSONObject = root.put("schemaVersion", 2)

    /** Index n holds the step from version n to n+1. */
    private val steps: Map<Int, (JSONObject) -> JSONObject> = mapOf(1 to ::v1ToV2)
}

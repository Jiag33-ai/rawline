package app.rawline.core.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class BackupException(message: String) : IOException(message)

/** One restored image file waiting in the staging folder until the database part has committed. */
class StagedFile(val kind: String, val name: String, val file: File)

/** Everything parsed and validated from a backup zip. Nothing here has touched the catalogue yet. */
class BackupContents(
    val edits: List<EditEntity>,
    val snapshots: List<SnapshotEntity>,
    val presets: List<PresetEntity>,
    val metas: List<MetaEntity>,
    val staged: List<StagedFile>,
)

/**
 * Reads a backup zip safely: only known entry names, each entry and the whole zip are size capped while streaming
 * (a zip bomb stops at the cap, it is never held whole in memory), JSON is parsed and image files are checked for a PNG
 * header with sane dimensions before anything is kept. Pure Kotlin so it is unit tested on the host.
 */
object BackupReader {
    const val MAX_JSON_BYTES = 64L shl 20
    const val MAX_IMAGE_BYTES = 64L shl 20
    const val MAX_TOTAL_BYTES = 512L shl 20
    const val MAX_ENTRIES = 50_000
    const val MAX_IMAGE_SIDE = 16_384
    const val MAX_IMAGE_PIXELS = 64L * 1024 * 1024
    private val JSON_NAMES = setOf("version.json", "edits.json", "snapshots.json", "presets.json", "meta.json")
    private val IMAGE_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,200}\\.png")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Returns the stored image file name for a "masks/..." or "heals/..." entry, or null if the name is not allowed. */
    fun imageName(entry: String): Pair<String, String>? {
        val kind = when { entry.startsWith("masks/") -> "masks"; entry.startsWith("heals/") -> "heals"; else -> return null }
        val rest = entry.substring(kind.length + 1)
        return if (IMAGE_NAME.matches(rest)) kind to rest else null
    }

    /** Checks the PNG signature and the IHDR size claim (the first chunk). Does not decode pixels. */
    fun validPng(head: ByteArray, length: Long): Boolean {
        if (length < 33 || head.size < 24) return false
        for (i in 0 until 8) if (head[i] != PNG_SIGNATURE[i]) return false
        if (!(head[12] == 'I'.code.toByte() && head[13] == 'H'.code.toByte() && head[14] == 'D'.code.toByte() && head[15] == 'R'.code.toByte())) return false
        fun be(o: Int) = ((head[o].toLong() and 0xFF) shl 24) or ((head[o + 1].toLong() and 0xFF) shl 16) or ((head[o + 2].toLong() and 0xFF) shl 8) or (head[o + 3].toLong() and 0xFF)
        val w = be(16); val h = be(20)
        return w in 1..MAX_IMAGE_SIDE && h in 1..MAX_IMAGE_SIDE && w * h <= MAX_IMAGE_PIXELS
    }

    private class Capped(private val src: InputStream, private val limit: Long, private val what: String) {
        var read = 0L
        fun readAll(sink: (ByteArray, Int) -> Unit) {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                read += n
                if (read > limit) throw BackupException("$what is larger than allowed")
                sink(buf, n)
            }
        }
    }

    /** [staging] must be an empty folder this call may fill; the caller deletes it afterwards. */
    fun read(input: InputStream, staging: File): BackupContents {
        val json = HashMap<String, String>()
        val staged = ArrayList<StagedFile>()
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (++entries > MAX_ENTRIES) throw BackupException("Too many files in the backup")
                val name = e.name
                val image = imageName(name)
                if (name in JSON_NAMES) {
                    if (name in json) throw BackupException("Duplicate $name")
                    val out = java.io.ByteArrayOutputStream()
                    val c = Capped(z, minOf(MAX_JSON_BYTES, MAX_TOTAL_BYTES - total), name)
                    c.readAll { b, n -> out.write(b, 0, n) }
                    total += c.read
                    json[name] = out.toString(Charsets.UTF_8.name())
                } else if (image != null) {
                    val (kind, fileName) = image
                    if (staged.any { it.kind == kind && it.name == fileName }) throw BackupException("Duplicate $name")
                    val dir = File(staging, kind).apply { mkdirs() }
                    val f = File(dir, fileName)
                    val head = ByteArray(24); var have = 0
                    val c = Capped(z, minOf(MAX_IMAGE_BYTES, MAX_TOTAL_BYTES - total), name)
                    f.outputStream().use { o ->
                        c.readAll { b, n ->
                            if (have < head.size) { val k = minOf(n, head.size - have); System.arraycopy(b, 0, head, have, k); have += k }
                            o.write(b, 0, n)
                        }
                    }
                    total += c.read
                    if (!validPng(head, c.read)) { f.delete(); throw BackupException("$name is not a valid image") }
                    staged.add(StagedFile(kind, fileName, f))
                } // anything else (unknown names, path tricks like ../) is ignored, never written
            }
        }
        return BackupContents(
            edits = parse("edits.json", json["edits.json"]) { o -> EditEntity(o.getString("key"), requireJson(o.getString("json")), o.optLong("t")) },
            snapshots = parse("snapshots.json", json["snapshots.json"]) { o -> SnapshotEntity(0, o.getString("key"), o.getString("name"), requireJson(o.getString("json")), o.optLong("t")) },
            presets = parse("presets.json", json["presets.json"]) { o -> PresetEntity(0, o.getString("name"), requireJson(o.getString("json")), o.optLong("t")) },
            metas = parse("meta.json", json["meta.json"]) { o -> MetaEntity(o.getString("key"), o.getInt("rating").coerceIn(0, 5), o.getInt("flag").coerceIn(-1, 1), o.getInt("label").coerceIn(0, 5), o.optLong("t")) },
            staged = staged,
        )
    }

    private fun requireJson(s: String): String { JSONObject(s); return s }

    private fun <T> parse(name: String, text: String?, f: (JSONObject) -> T): List<T> {
        if (text == null) return emptyList()
        try {
            val a = JSONArray(text)
            return List(a.length()) { f(a.getJSONObject(it)) }
        } catch (e: org.json.JSONException) { throw BackupException("$name is damaged") }
    }
}

/** Newer wins merges for restore. A backup never overwrites something changed on this phone since. */
object BackupMerge {
    /** Edits and meta rows: take the incoming row only if there is none, or it is strictly newer. Rows without times (0) never replace. */
    fun newerEdits(existing: Map<String, EditEntity>, incoming: List<EditEntity>) = incoming.filter { (existing[it.key]?.updatedAt ?: -1L) < it.updatedAt || it.key !in existing }
    fun newerMetas(existing: Map<String, MetaEntity>, incoming: List<MetaEntity>) = incoming.filter { it.key !in existing || (existing.getValue(it.key).updatedAt < it.updatedAt) }
}

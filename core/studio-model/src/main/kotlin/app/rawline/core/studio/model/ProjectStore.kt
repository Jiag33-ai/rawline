package app.rawline.core.studio.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The file system as the store needs it. [rename] is atomic and replaces the target (POSIX rename); [write] is not atomic: a process that dies in the middle leaves a torn file. */
interface Fs {
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray?
    fun write(path: String, data: ByteArray)
    fun rename(from: String, to: String)
    fun delete(path: String)
    /** File names (not paths) directly inside [dir]. */
    fun list(dir: String): List<String>
    /** Names of the sub directories directly inside [dir]. */
    fun dirs(dir: String): List<String>
    /** Size in bytes, 0 when missing. */
    fun size(path: String): Long
    /** Deletes [path] and everything under it. */
    fun deleteTree(path: String)
}

/** Straight RGBA8 pixels of one layer. */
class RawPixels(val w: Int, val h: Int, val rgba: ByteArray) {
    init { require(rgba.size == w * h * 4) { "pixel buffer is ${rgba.size} bytes, expected ${w * h * 4}" } }
}

/**
 * Layer pixel container used in S1: "RLPX", version 1, width, height (big endian ints), then the straight RGBA8 bytes deflated. Lossless by
 * construction (no premultiplication, which Android bitmap codecs apply and which loses colour at low alpha); sparse paint layers shrink to
 * almost nothing. S2 replaces it by lossless WebP tiles written through libwebp, after a device test proves straight alpha survives.
 */
object PixelContainer {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), 'X'.code.toByte())

    fun encode(p: RawPixels): ByteArray {
        val out = ByteArrayOutputStream(1024 + p.rgba.size / 8)
        out.write(MAGIC); out.write(1)
        out.write(ByteBuffer.allocate(8).putInt(p.w).putInt(p.h).array())
        val d = Deflater(1)
        d.setInput(p.rgba); d.finish()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** Throws [ProjectFormatException] for anything that is not a complete container. */
    fun decode(bytes: ByteArray): RawPixels {
        if (bytes.size < 13 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4].toInt() != 1) throw ProjectFormatException("not a layer pixel file")
        val bb = ByteBuffer.wrap(bytes, 5, 8)
        val w = bb.getInt(); val h = bb.getInt()
        if (w !in 1..Document.MAX_EDGE || h !in 1..Document.MAX_EDGE || w.toLong() * h > Document.MAX_PIXELS_S1) throw ProjectFormatException("layer pixel file has an invalid size")
        val out = ByteArray(w * h * 4)
        val inf = Inflater()
        try {
            inf.setInput(bytes, 13, bytes.size - 13)
            var n = 0
            while (n < out.size) {
                val k = inf.inflate(out, n, out.size - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            if (n != out.size) throw ProjectFormatException("layer pixel file is cut short")
        } catch (e: DataFormatException) { throw ProjectFormatException("layer pixel file is damaged", e) }
        finally { inf.end() }
        return RawPixels(w, h, out)
    }
}

/** Which of the three generations of project.json was opened. BACKUP means the newest one was unreadable: show "Recovered from autosave". */
enum class OpenedFrom { NEW, CURRENT, BACKUP }

class OpenResult(val document: Document, val from: OpenedFrom) { val recovered get() = from == OpenedFrom.BACKUP }

/**
 * Project directory (spec 2.15, S1 form): `project.json` (+ `.new` while committing, `.bak` the generation before), and
 * `layers/{layerId}-{hash12}.rlpx`, named by content so a file a previous generation refers to is never overwritten.
 *
 * Save protocol, every step safe to be killed after:
 *  1. each changed layer: write `layers/x.tmp`, rename to its hashed name (skipped when that file already exists);
 *  2. write `project.json.tmp`, rename to `project.json.new`  (the new generation is complete and visible);
 *  3. rename `project.json` to `project.json.bak`;
 *  4. rename `project.json.new` to `project.json`;
 *  5. delete layer files neither generation refers to, and stray `.tmp` files.
 * Open picks the newest complete generation: `.new`, `project.json`, `.bak` in that order of preference among those that parse and whose
 * layer files all exist and decode; a damaged newest generation falls back to the one before and reports it.
 */
class ProjectStore(private val fs: Fs, private val root: String, private val appVersion: String = "0") {
    private val json = "$root/project.json"
    private val jsonNew = "$root/project.json.new"
    private val jsonBak = "$root/project.json.bak"

    /** Writes [doc] and returns it with the pixel file names filled in for the layers in [changed] (their pixels come from [pixels]). Layers not in [changed] keep their file. */
    fun save(doc: Document, pixels: (Layer.Pixel) -> RawPixels?, changed: Set<String>): Document {
        val layers = doc.layers.map { l ->
            val p = l as? Layer.Pixel ?: return@map l
            if (p.common.id !in changed && p.pixelsFile != null) return@map l
            val px = pixels(p)
            if (px == null) return@map p.copy(pixelsFile = null)
            val bytes = PixelContainer.encode(px)
            val name = "layers/${p.common.id}-${hash12(bytes)}.rlpx"
            if (!fs.exists("$root/$name")) {
                fs.write("$root/$name.tmp", bytes)
                fs.rename("$root/$name.tmp", "$root/$name")
            }
            p.copy(pixelsFile = name)
        }
        val saved = doc.copy(layers = layers)
        fs.write("$json.tmp", ProjectJson.write(saved, appVersion).toByteArray(Charsets.UTF_8))
        fs.rename("$json.tmp", jsonNew)
        if (fs.exists(json)) fs.rename(json, jsonBak)
        fs.rename(jsonNew, json)
        collect(saved)
        return saved
    }

    /** Opens the newest complete generation. Throws [ProjectFormatException] when none is usable and [NewerSchemaException] for a project from a newer app (open read-only, never save it back). */
    fun open(): OpenResult {
        var newer: NewerSchemaException? = null
        var firstError: ProjectFormatException? = null
        for ((path, from) in listOf(jsonNew to OpenedFrom.NEW, json to OpenedFrom.CURRENT, jsonBak to OpenedFrom.BACKUP)) {
            val bytes = fs.read(path) ?: continue
            try {
                val doc = ProjectJson.read(String(bytes, Charsets.UTF_8))
                verify(doc)
                // a candidate that is older than a damaged newer one is still the best there is; a valid .new always wins (it is complete before it becomes visible)
                return OpenResult(doc, from)
            } catch (e: NewerSchemaException) { newer = e; break }
            catch (e: ProjectFormatException) { if (firstError == null) firstError = e }
        }
        newer?.let { throw it }
        throw firstError ?: ProjectFormatException("There is no project here.")
    }

    /** The pixels of a layer, or null for a layer that is still transparent. */
    fun load(layer: Layer.Pixel): RawPixels? {
        val f = layer.pixelsFile ?: return null
        val px = PixelContainer.decode(fs.read("$root/$f") ?: throw ProjectFormatException("layer file $f is missing"))
        if (px.w != layer.width || px.h != layer.height) throw ProjectFormatException("layer file $f has the wrong size")
        return px
    }

    private fun verify(doc: Document) {
        for (l in doc.layers) if (l is Layer.Pixel) load(l)
    }

    private fun collect(current: Document) {
        val keep = HashSet<String>()
        keep += current.layers.mapNotNull { (it as? Layer.Pixel)?.pixelsFile?.substringAfter("layers/") }
        fs.read(jsonBak)?.let { b -> try { ProjectJson.read(String(b, Charsets.UTF_8)).layers.forEach { l -> (l as? Layer.Pixel)?.pixelsFile?.let { keep += it.substringAfter("layers/") } } } catch (e: Exception) { /* an unreadable .bak refers to nothing */ } }
        for (n in fs.list("$root/layers")) if (n !in keep) fs.delete("$root/layers/$n")
        if (fs.exists("$json.tmp")) fs.delete("$json.tmp")
    }

    private fun hash12(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)
}

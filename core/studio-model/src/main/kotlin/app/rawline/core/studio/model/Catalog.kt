package app.rawline.core.studio.model

import java.util.Random

/** One row of the Studio home grid and of studio.db. Everything here can be rebuilt from the project directories. */
data class ProjectRow(val id: String, val name: String, val width: Int, val height: Int, val modified: Long, val layerCount: Int, val sizeBytes: Long, val hasThumb: Boolean)

class ScanResult(val rows: List<ProjectRow>, val damaged: List<String>)

/**
 * The project directories under [root] (`files/studio`) are the source of truth (spec 3.6); this reads them. Every operation works
 * on an [Fs], so the same code runs on the phone and in the host tests.
 */
object ProjectCatalog {
    const val ROOT = "files/studio"

    /** Newest first. A directory that does not open (damaged, newer format) is reported by id, never crashes the home screen. */
    fun scan(fs: Fs, root: String = ROOT): ScanResult {
        val rows = ArrayList<ProjectRow>(); val bad = ArrayList<String>()
        for (id in fs.dirs(root).sorted()) {
            try { rows += row(fs, "$root/$id", id) } catch (e: ProjectFormatException) { bad += id } catch (e: NewerSchemaException) { bad += id }
        }
        rows.sortWith(compareByDescending<ProjectRow> { it.modified }.thenBy { it.id })
        return ScanResult(rows, bad)
    }

    private fun row(fs: Fs, dir: String, id: String): ProjectRow {
        val doc = ProjectStore(fs, dir).open().document
        val size = fs.list(dir).sumOf { fs.size("$dir/$it") } + fs.list("$dir/layers").sumOf { fs.size("$dir/layers/$it") }
        return ProjectRow(id, doc.name, doc.width, doc.height, doc.modified, doc.layers.size, size, fs.exists("$dir/thumb.jpg"))
    }

    /** "p" + time in base 36 + 4 random base 36 characters: unique enough on one phone, sortable by creation, safe as a directory name. */
    fun newId(nowMs: Long, rnd: Random = Random()): String = "p" + nowMs.toString(36) + (0 until 4).map { "0123456789abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(36)] }.joinToString("")

    /** Copies every file (layer files are named by content, so they are shared by value), then rewrites the copy's id and name. */
    fun duplicate(fs: Fs, root: String, id: String, newId: String, nowMs: Long): ProjectRow {
        require(!fs.exists("$root/$newId/project.json") && fs.dirs(root).none { it == newId }) { "project $newId exists" }
        val src = "$root/$id"; val dst = "$root/$newId"
        for (n in fs.list(src)) if (!n.endsWith(".tmp")) fs.write("$dst/$n", fs.read("$src/$n")!!)
        for (n in fs.list("$src/layers")) if (!n.endsWith(".tmp")) fs.write("$dst/layers/$n", fs.read("$src/layers/$n")!!)
        val store = ProjectStore(fs, dst)
        val doc = store.open().document
        store.save(doc.copy(id = newId, name = (doc.name + " copy").take(60), modified = nowMs), { null }, emptySet())
        return row(fs, dst, newId)
    }

    fun rename(fs: Fs, root: String, id: String, name: String, nowMs: Long) {
        val store = ProjectStore(fs, "$root/$id")
        val doc = store.open().document
        store.save(doc.copy(name = name.trim().take(60).ifEmpty { doc.name }, modified = nowMs), { null }, emptySet())
    }

    fun delete(fs: Fs, root: String, id: String) { require(id.isNotEmpty() && !id.contains('/') && !id.contains("..")) { "bad id" }; fs.deleteTree("$root/$id") }
}

/** New project choices (spec 2.16 S1: blank presets and one photo). The canvas is capped at 12 MP and 8192 on a side. */
object NewProject {
    class Preset(val label: String, val width: Int, val height: Int)
    val presets = listOf(Preset("Square 1080", 1080, 1080), Preset("Portrait 1080 x 1350", 1080, 1350), Preset("Landscape 1920 x 1080", 1920, 1080), Preset("12 MP 4000 x 3000", 4000, 3000))

    /** Largest size with the same shape that fits the S1 caps. */
    fun fit(w: Int, h: Int): Pair<Int, Int> {
        require(w > 0 && h > 0)
        var s = 1.0
        if (w.toLong() * h > Document.MAX_PIXELS_S1) s = minOf(s, Math.sqrt(Document.MAX_PIXELS_S1.toDouble() / (w.toDouble() * h)))
        if (maxOf(w, h) > Document.MAX_EDGE) s = minOf(s, Document.MAX_EDGE.toDouble() / maxOf(w, h))
        var fw = maxOf(1, Math.floor(w * s).toInt()); var fh = maxOf(1, Math.floor(h * s).toInt())
        while (fw.toLong() * fh > Document.MAX_PIXELS_S1) { if (fw >= fh) fw-- else fh-- }
        return fw to fh
    }

    /** A new document with one empty (transparent) layer; the photo case replaces that layer's pixels with the scaled picture. */
    fun blank(id: String, name: String, w: Int, h: Int, nowMs: Long): Document {
        val (fw, fh) = fit(w, h)
        return Document(id, name, fw, fh, layers = listOf(Layer.Pixel(LayerCommon("l1", "Layer 1"), fw, fh)), created = nowMs, modified = nowMs)
    }
}

/** What to change in studio.db so that it matches a scan of the directories (the directories win, the index is only a fast first paint). */
class IndexDiff(val upserts: List<ProjectRow>, val deletes: List<String>) {
    val isEmpty get() = upserts.isEmpty() && deletes.isEmpty()
    companion object {
        fun compute(indexed: List<ProjectRow>, scanned: List<ProjectRow>): IndexDiff {
            val old = indexed.associateBy { it.id }; val now = scanned.associateBy { it.id }
            return IndexDiff(scanned.filter { old[it.id] != it }, indexed.filter { it.id !in now }.map { it.id })
        }
    }
}

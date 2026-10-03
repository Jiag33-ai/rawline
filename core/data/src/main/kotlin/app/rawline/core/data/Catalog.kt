package app.rawline.core.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Edits, ratings, snapshots, presets and backup. Everything is keyed by [Photo.key] so it survives re-indexing. */
class Catalog(private val context: Context, private val db: RawlineDb, private val maskStore: app.rawline.core.cache.MaskStore? = null, private val patchStore: app.rawline.core.cache.PatchStore? = null) {
    private val photos = db.photos()
    private val edits = db.edits()

    suspend fun loadRecipe(p: Photo): EditRecipe? = edits.get(p.key)?.let { runCatching { EditRecipe.fromJson(it.json) }.getOrNull() }

    suspend fun saveRecipe(p: Photo, r: EditRecipe) {
        if (r.isDefault) { edits.delete(p.key); photos.setEdited(p.id, false) }
        else { edits.put(EditEntity(p.key, r.toJson(), System.currentTimeMillis())); photos.setEdited(p.id, true) }
    }

    suspend fun setRating(list: List<Photo>, rating: Int) { list.chunked(400).forEach { photos.setRating(it.map { p -> p.id }, rating) }; saveMeta(list) { it.copy(rating = rating) } }
    suspend fun setFlag(list: List<Photo>, flag: Int) { list.chunked(400).forEach { photos.setFlag(it.map { p -> p.id }, flag) }; saveMeta(list) { it.copy(flag = flag) } }
    suspend fun setLabel(list: List<Photo>, label: Int) { list.chunked(400).forEach { photos.setLabel(it.map { p -> p.id }, label) }; saveMeta(list) { it.copy(label = label) } }

    private suspend fun saveMeta(list: List<Photo>, f: (MetaEntity) -> MetaEntity) {
        val old = list.chunked(400).flatMap { c -> edits.metaFor(c.map { it.key }) }.associateBy { it.key }
        edits.putMeta(list.map { f(old[it.key] ?: MetaEntity(it.key, it.rating, it.flag, it.label)) })
        // XMP sidecar is best effort and only when the user turned it on
        if (xmpEnabled()) list.forEach { p -> val m = edits.metaFor(listOf(p.key)).firstOrNull(); if (m != null) runCatching { Xmp.write(context, p, m.rating, m.label, edits.get(p.key)?.json) } }
    }

    private fun xmpEnabled() = context.getSharedPreferences("rawline", Context.MODE_PRIVATE).getBoolean("xmp", false)

    suspend fun snapshots(p: Photo) = edits.snapshots(p.key)
    suspend fun addSnapshot(p: Photo, name: String, r: EditRecipe): Long {
        edits.addSnapshot(SnapshotEntity(key = p.key, name = name, json = r.toJson(), createdAt = System.currentTimeMillis()))
        return edits.snapshots(p.key).firstOrNull()?.id ?: 0
    }

    suspend fun presets() = edits.presets()
    suspend fun addPreset(name: String, r: EditRecipe) = edits.addPreset(PresetEntity(name = name, json = r.toJson(), createdAt = System.currentTimeMillis()))
    suspend fun deletePreset(id: Long) = edits.deletePreset(id)

    // ---------------- Backup ----------------

    /** One zip with edits, snapshots, presets and ratings as JSON. Photos themselves are never included. */
    suspend fun writeBackup(out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun entry(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            entry("version.json", JSONObject().put("format", 1).put("created", System.currentTimeMillis()).toString())
            entry("edits.json", JSONArray().also { a -> edits.all().forEach { a.put(JSONObject().put("key", it.key).put("json", it.json).put("t", it.updatedAt)) } }.toString())
            entry("snapshots.json", JSONArray().also { a -> edits.allSnapshots().forEach { a.put(JSONObject().put("key", it.key).put("name", it.name).put("json", it.json).put("t", it.createdAt)) } }.toString())
            entry("presets.json", JSONArray().also { a -> edits.presets().forEach { a.put(JSONObject().put("name", it.name).put("json", it.json).put("t", it.createdAt)) } }.toString())
            // AI mask images and repair patches are not in the recipes, so they travel in the zip too
            fun files(prefix: String, dir: java.io.File?) { dir?.listFiles()?.forEach { f -> z.putNextEntry(ZipEntry(prefix + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            files("masks/", maskStore?.dir); files("heals/", patchStore?.dir)
            entry("meta.json", JSONArray().also { a -> edits.allMeta().forEach { a.put(JSONObject().put("key", it.key).put("rating", it.rating).put("flag", it.flag).put("label", it.label)) } }.toString())
        }
    }

    suspend fun readBackup(input: InputStream): Int {
        var count = 0
        val files = HashMap<String, String>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = e.name
                val bytes = z.readBytes()
                val dir = when { name.startsWith("masks/") -> maskStore?.dir; name.startsWith("heals/") -> patchStore?.dir; else -> null }
                if (dir != null) { val f = java.io.File(dir, name.substringAfter('/').replace("/", "_")); if (!f.exists()) f.writeBytes(bytes) }
                else files[name] = bytes.toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        files["edits.json"]?.let { s ->
            val a = JSONArray(s)
            val existing = edits.all().associateBy { it.key }
            // keep whichever edit is newer, so an old backup never overwrites newer work
            edits.putEdits(List(a.length()) { val o = a.getJSONObject(it); EditEntity(o.getString("key"), o.getString("json"), o.optLong("t")) }.filter { e -> (existing[e.key]?.updatedAt ?: -1L) < e.updatedAt })
            count += a.length()
        }
        files["snapshots.json"]?.let { s ->
            val a = JSONArray(s)
            val have = edits.allSnapshots().map { it.key to it.createdAt }.toSet()
            edits.putSnapshots(List(a.length()) { val o = a.getJSONObject(it); SnapshotEntity(0, o.getString("key"), o.getString("name"), o.getString("json"), o.optLong("t")) }.filter { (it.key to it.createdAt) !in have })
        }
        files["presets.json"]?.let { s ->
            val a = JSONArray(s)
            val have = edits.presets().map { it.name to it.createdAt }.toSet()
            edits.putPresets(List(a.length()) { val o = a.getJSONObject(it); PresetEntity(0, o.getString("name"), o.getString("json"), o.optLong("t")) }.filter { (it.name to it.createdAt) !in have })
        }
        val metas = files["meta.json"]?.let { s ->
            val a = JSONArray(s)
            List(a.length()) { val o = a.getJSONObject(it); MetaEntity(o.getString("key"), o.getInt("rating"), o.getInt("flag"), o.getInt("label")) }
        } ?: emptyList()
        edits.putMeta(metas)
        // apply to photos already in the catalogue
        val byKey = (edits.allMeta()).associateBy { it.key }
        val editKeys = edits.all().map { it.key }.toSet()
        photos.all().forEach { p ->
            val k = Photo(p.id, p.folderUri, p.uri, p.name, p.size, p.modified, app.rawline.core.model.Kind.RAW, true).key
            val m = byKey[k]
            if (m != null || k in editKeys) photos.restoreMeta(p.uri, m?.rating ?: p.rating, m?.flag ?: p.flag, m?.label ?: p.label, k in editKeys)
        }
        return count
    }

    /** After a re-scan, give new rows their saved ratings and edit marks back. */
    suspend fun reapply(folderKey: String) {
        val rows = photos.known(folderKey)
        if (rows.isEmpty()) return
        val all = photos.all().filter { it.folderUri == folderKey }
        val keys = all.map { Photo(it.id, it.folderUri, it.uri, it.name, it.size, it.modified, app.rawline.core.model.Kind.RAW, true).key }
        val meta = HashMap<String, MetaEntity>()
        keys.chunked(500).forEach { c -> edits.metaFor(c).forEach { meta[it.key] = it } }
        val edited = HashSet<String>()
        keys.chunked(500).forEach { c -> c.forEach { k -> if (edits.get(k) != null) edited.add(k) } }
        all.forEachIndexed { i, p ->
            val k = keys[i]; val m = meta[k]
            if ((m != null && (m.rating != p.rating || m.flag != p.flag || m.label != p.label)) || (k in edited) != p.edited)
                photos.restoreMeta(p.uri, m?.rating ?: p.rating, m?.flag ?: p.flag, m?.label ?: p.label, k in edited)
        }
    }
}

/** Minimal XMP sidecar: rating, label and a few basic develop settings. Existing sidecars are read for rating and label. */
object Xmp {
    fun sidecarName(name: String) = name.substringBeforeLast('.') + ".xmp"

    fun build(rating: Int, label: Int, recipeJson: String?): String {
        val r = recipeJson?.let { runCatching { EditRecipe.fromJson(it) }.getOrNull() }
        val labels = listOf("", "Red", "Yellow", "Green", "Blue", "Purple")
        return buildString {
            append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n<rdf:Description rdf:about=\"\"\n")
            append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:rawline=\"https://rawline.app/ns/1.0/\"\n")
            append(" xmp:Rating=\"$rating\"")
            if (label in 1..5) append(" xmp:Label=\"${labels[label]}\"")
            if (r != null) {
                append("\n rawline:Exposure=\"${r.adjust.exposure}\" rawline:Contrast=\"${r.adjust.contrast}\" rawline:Highlights=\"${r.adjust.highlights}\"")
                append(" rawline:Shadows=\"${r.adjust.shadows}\" rawline:Temperature=\"${r.adjust.temp}\" rawline:Saturation=\"${r.adjust.saturation}\"")
            }
            append("/>\n</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>\n")
        }
    }

    fun parse(xml: String): Pair<Int, Int>? {
        val rating = Regex("xmp:Rating=\"(-?\\d)\"").find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val label = Regex("xmp:Label=\"([A-Za-z]+)\"").find(xml)?.groupValues?.get(1)
        val idx = listOf("", "Red", "Yellow", "Green", "Blue", "Purple").indexOf(label ?: "").coerceAtLeast(0)
        return rating.coerceIn(0, 5) to idx
    }

    /** Writes next to the photo through the folder permission. Returns false if the folder is read only. */
    fun write(context: Context, p: Photo, rating: Int, label: Int, json: String?): Boolean {
        val doc = Uri.parse(p.uri)
        val tree = Uri.parse(p.folderUri)
        val docId = DocumentsContract.getDocumentId(doc)
        val parentId = docId.substringBeforeLast('/', "")
        if (parentId.isEmpty()) return false
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val name = sidecarName(p.name)
        val existing = DocumentsContract.buildDocumentUriUsingTree(tree, "$parentId/$name")
        val target = runCatching { context.contentResolver.openOutputStream(existing, "wt")?.also { } }.getOrNull()
            ?: DocumentsContract.createDocument(context.contentResolver, parent, "application/rdf+xml", name)?.let { context.contentResolver.openOutputStream(it, "wt") }
            ?: return false
        target.use { it.write(build(rating, label, json).toByteArray()) }
        return true
    }
}

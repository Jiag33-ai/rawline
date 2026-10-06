package app.rawline.core.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.rawline.core.model.EditRecipe
import app.rawline.core.model.Photo
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Edits, ratings, snapshots, presets and backup. Everything is keyed by [Photo.key] so it survives re-indexing. */
class Catalog(private val context: Context, private val db: RawlineDb, private val maskStore: app.rawline.core.cache.MaskStore? = null, private val patchStore: app.rawline.core.cache.PatchStore? = null) {
    private val photos = db.photos()
    private val edits = db.edits()

    suspend fun loadRecipe(p: Photo): EditRecipe? = (readRecipe(p) as? RecipeRead.Ok)?.recipe

    /** Like [loadRecipe] but tells "no edit saved" apart from "saved but unreadable". Use this wherever the result is acted on. */
    suspend fun readRecipe(p: Photo): RecipeRead = RecipeRead.parse(edits.get(p.key)?.json)

    suspend fun saveRecipe(p: Photo, r: EditRecipe) {
        when (saveAction(r.isDefault, if (r.isDefault) readRecipe(p) else RecipeRead.Missing)) {   // only a default save needs the stored row
            SaveAction.KEEP_UNREADABLE -> return
            SaveAction.DELETE -> { edits.delete(p.key); photos.setEdited(p.id, false) }
            SaveAction.PUT -> { edits.put(EditEntity(p.key, r.toJson(), System.currentTimeMillis())); photos.setEdited(p.id, true) }
        }
    }

    suspend fun setRating(list: List<Photo>, rating: Int): SidecarResult { list.chunked(400).forEach { photos.setRating(it.map { p -> p.id }, rating) }; return saveMeta(list) { it.copy(rating = rating) } }
    suspend fun setFlag(list: List<Photo>, flag: Int): SidecarResult { list.chunked(400).forEach { photos.setFlag(it.map { p -> p.id }, flag) }; return saveMeta(list, sidecar = false) { it.copy(flag = flag) } }   // a flag is not stored in the sidecar
    suspend fun setLabel(list: List<Photo>, label: Int): SidecarResult { list.chunked(400).forEach { photos.setLabel(it.map { p -> p.id }, label) }; return saveMeta(list) { it.copy(label = label) } }

    /**
     * The database write comes first (the grid updates from it). The XMP sidecar step is SAF file I/O with several binder calls per
     * photo, so it runs on the IO dispatcher whichever thread called, and reports what it could not do instead of staying silent.
     */
    private suspend fun saveMeta(list: List<Photo>, sidecar: Boolean = true, f: (MetaEntity) -> MetaEntity): SidecarResult {
        val old = list.chunked(400).flatMap { c -> edits.metaFor(c.map { it.key }) }.associateBy { it.key }
        val now = System.currentTimeMillis()
        val updated = list.map { f(old[it.key] ?: MetaEntity(it.key, it.rating, it.flag, it.label)).copy(updatedAt = now) }
        edits.putMeta(updated)
        if (!sidecar || !xmpEnabled()) return SidecarResult.NONE
        return withContext(Dispatchers.IO) {
            val byKey = updated.associateBy { it.key }
            var written = 0; var unsupported = 0; var failed = 0
            for (p in list) {
                if (!Xmp.supports(p)) { unsupported++; continue }
                val m = byKey[p.key] ?: continue
                val ok = runCatching { Xmp.write(context, p, m.rating, m.label, edits.get(p.key)?.json) }.getOrDefault(false)
                if (ok) written++ else failed++
            }
            SidecarResult(written, unsupported, failed)
        }
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
            fun files(prefix: String, dir: java.io.File?) { dir?.listFiles()?.filter { BackupReader.imageName(prefix + it.name) != null }?.forEach { f -> z.putNextEntry(ZipEntry(prefix + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            files("masks/", maskStore?.dir); files("heals/", patchStore?.dir)
            entry("meta.json", JSONArray().also { a -> edits.allMeta().forEach { a.put(JSONObject().put("key", it.key).put("rating", it.rating).put("flag", it.flag).put("label", it.label).put("t", it.updatedAt)) } }.toString())
        }
    }

    /**
     * Restores a backup. The zip is read and validated completely first (see [BackupReader]); the database part is one
     * transaction, so a failure leaves the catalogue exactly as it was. Image files are only moved into place after it commits.
     * Ratings, flags and labels follow the same newer wins rule as edits. Returns how many edits the backup held.
     */
    suspend fun readBackup(input: InputStream): Int {
        val staging = java.io.File(context.cacheDir, "restore-" + System.nanoTime())
        try {
            val c = BackupReader.read(input, staging)
            db.withTransaction {
                edits.putEdits(BackupMerge.newerEdits(edits.all().associateBy { it.key }, c.edits))
                val have = edits.allSnapshots().map { it.key to it.createdAt }.toSet()
                edits.putSnapshots(c.snapshots.filter { (it.key to it.createdAt) !in have })
                val havePresets = edits.presets().map { it.name to it.createdAt }.toSet()
                edits.putPresets(c.presets.filter { (it.name to it.createdAt) !in havePresets })
                edits.putMeta(BackupMerge.newerMetas(edits.allMeta().associateBy { it.key }, c.metas))
                applyToPhotos()
            }
            // image files only after the database committed; an existing file is never overwritten
            c.staged.forEach { s ->
                val dir = if (s.kind == "masks") maskStore?.dir else patchStore?.dir
                if (dir != null) { val f = java.io.File(dir, s.name); if (!f.exists()) runCatching { s.file.copyTo(f) } }
            }
            return c.edits.size
        } finally { staging.deleteRecursively() }
    }

    private suspend fun applyToPhotos() {
        // apply to photos already in the catalogue
        val byKey = (edits.allMeta()).associateBy { it.key }
        val editKeys = edits.all().map { it.key }.toSet()
        photos.all().forEach { p ->
            val k = Photo.keyOf(p.name, p.size, p.modified)
            val m = byKey[k]
            if (m != null || k in editKeys) photos.restoreMeta(p.uri, m?.rating ?: p.rating, m?.flag ?: p.flag, m?.label ?: p.label, k in editKeys)
        }
    }

    /**
     * After a re-scan, give new rows their saved ratings and edit marks back. One query for the folder's rows, one per 500 keys
     * for ratings and one per 500 keys for edits (it used to be one query per photo), and the writes share one transaction.
     */
    suspend fun reapply(folderKey: String) {
        val like = folderKey.endsWith("%")
        val all = if (like) photos.inFolderLike(folderKey) else photos.inFolder(folderKey)
        if (all.isEmpty()) return
        val keys = all.map { Photo.keyOf(it.name, it.size, it.modified) }
        val meta = HashMap<String, MetaEntity>()
        val edited = HashSet<String>()
        keys.chunked(500).forEach { c -> edits.metaFor(c).forEach { meta[it.key] = it }; edited.addAll(edits.editedAmong(c)) }
        val plan = Reapply.plan(all, meta, edited)
        if (plan.isEmpty()) return
        db.withTransaction { plan.forEach { photos.restoreMeta(it.uri, it.rating, it.flag, it.label, it.edited) } }
    }
}

/** What the sidecar step did for one rating, flag or label change: [unsupported] photos have no folder to write into, [failed] could not be written. */
data class SidecarResult(val written: Int, val unsupported: Int, val failed: Int) {
    companion object { val NONE = SidecarResult(0, 0, 0) }
}

/** Minimal XMP sidecar: rating, label and a few basic develop settings. Existing sidecars are read for rating and label. */
object Xmp {
    /**
     * Sidecars are written through a folder the user added (a SAF tree). Camera roll photos ("device:...") and picked files
     * ("imported") have no tree to write into, so for them nothing is written and the app says so.
     */
    fun supports(folderUri: String) = !folderUri.startsWith("device:") && folderUri != "imported" && folderUri.isNotEmpty()
    fun supports(p: Photo) = supports(p.folderUri)

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
        // A sidecar from Lightroom or another editor holds develop settings we do not write: leave it alone.
        val old = runCatching { context.contentResolver.openInputStream(existing)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        if (old != null && !old.contains("rawline:")) return false
        val target = runCatching { context.contentResolver.openOutputStream(existing, "wt")?.also { } }.getOrNull()
            ?: DocumentsContract.createDocument(context.contentResolver, parent, "application/rdf+xml", name)?.let { context.contentResolver.openOutputStream(it, "wt") }
            ?: return false
        target.use { it.write(build(rating, label, json).toByteArray()) }
        return true
    }
}

package app.rawline.core.data

/**
 * Decides which known rows a device scan may delete. A listing that is empty or much smaller than what we know usually means
 * a partial grant, a slow MediaStore or an SD card still mounting, not that most photos were deleted. Deleting rows loses
 * indexed data and breaks export queue references, so a big drop is only believed when two scans in a row agree on it.
 */
object ScanPrune {
    const val MIN_KNOWN = 20

    /** @param suspicious the listing size to remember for the next scan, or null. */
    class Result(val gone: List<KnownRow>, val suspicious: Int?)

    fun decide(known: Collection<KnownRow>, seen: Set<String>, lastSuspicious: Int?): Result {
        if (seen.isEmpty()) return Result(emptyList(), null)
        val gone = known.filter { it.uri !in seen }
        val bigDrop = known.size >= MIN_KNOWN && seen.size * 2 < known.size
        if (!bigDrop) return Result(gone, null)
        return if (lastSuspicious == seen.size) Result(gone, null) else Result(emptyList(), seen.size)
    }
}

/** Which rows need their saved rating, flag, label or edited mark put back after a re-scan. */
object Reapply {
    class Update(val uri: String, val rating: Int, val flag: Int, val label: Int, val edited: Boolean)

    fun plan(photos: List<PhotoEntity>, meta: Map<String, app.rawline.core.data.MetaEntity>, editedKeys: Set<String>): List<Update> = photos.mapNotNull { p ->
        val k = app.rawline.core.model.Photo.keyOf(p.name, p.size, p.modified)
        val m = meta[k]
        val isEdited = k in editedKeys
        if ((m != null && (m.rating != p.rating || m.flag != p.flag || m.label != p.label)) || isEdited != p.edited)
            Update(p.uri, m?.rating ?: p.rating, m?.flag ?: p.flag, m?.label ?: p.label, isEdited)
        else null
    }
}

/** What saveRecipe does with the stored row. */
enum class SaveAction { KEEP_UNREADABLE, DELETE, PUT }

fun saveAction(isDefault: Boolean, stored: RecipeRead): SaveAction = when {
    // a stored recipe we cannot read (newer build) must not be deleted because the editor opened with defaults
    isDefault && stored == RecipeRead.Unreadable -> SaveAction.KEEP_UNREADABLE
    isDefault -> SaveAction.DELETE
    else -> SaveAction.PUT
}

/** One file found while listing a folder. */
internal class ScanDoc(val uri: String, val name: String, val size: Long, val modified: Long, val raw: Boolean, val dir: String)

/** What to store for one directory's worth of files: new rows, and old rows whose size or time changed (deleted first, then re-added). */
object FolderScan {
    class Plan(val fresh: List<PhotoEntity>, val stale: List<Long>)

    internal fun plan(folderKey: String, batch: List<ScanDoc>, byUri: Map<String, KnownRow>): Plan {
        val fresh = ArrayList<PhotoEntity>()
        val stale = ArrayList<Long>()
        for (d in batch) {
            val k = byUri[d.uri]
            val row = { PhotoEntity(folderUri = folderKey, uri = d.uri, name = d.name, size = d.size, modified = d.modified, isRaw = d.raw) }
            if (k == null) fresh.add(row())
            else if (k.modified != d.modified || k.size != d.size) { stale.add(k.id); fresh.add(row()) }
        }
        return Plan(fresh, stale)
    }
}

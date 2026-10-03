package app.rawline.core.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.rawline.core.cache.PerfLog
import app.rawline.core.model.FileTypes
import app.rawline.core.model.Kind

/**
 * Photos that are already on the phone (the camera roll and other albums, via MediaStore) and files the user imports.
 * Sources are keys in the photos table: "device:<album>" for MediaStore albums and "imported" for picked files.
 * No pixel is decoded here, so a library of tens of thousands lists in a moment; thumbnails are made when a tile is shown.
 */
class DeviceScanner(private val context: Context, private val dao: PhotoDao, private val catalog: Catalog?) {

    /** @return true when the MediaStore listing completed. */
    suspend fun scanDevice(): Boolean {
        val t0 = System.nanoTime()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        )
        val rows = ArrayList<PhotoEntity>()
        val cursor = runCatching { context.contentResolver.query(uri, proj, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC") }.getOrNull() ?: return false
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val name = c.getString(1) ?: continue
                val kind = FileTypes.kindOf(name) ?: Kind.IMAGE
                val modified = c.getLong(3) * 1000L
                val taken = c.getLong(4)
                val bucket = c.getString(5) ?: "Other"
                rows.add(PhotoEntity(
                    folderUri = "device:$bucket", uri = ContentUris.withAppendedId(uri, id).toString(), name = name, size = c.getLong(2), modified = modified,
                    isRaw = kind == Kind.RAW, indexed = false, takenAt = if (taken > 0) taken else modified, width = c.getInt(6), height = c.getInt(7),
                ))
            }
        }
        val known = dao.knownLike("device:%").associateBy { it.uri }
        val seen = HashSet<String>(rows.size)
        val fresh = ArrayList<PhotoEntity>()
        rows.forEach { r -> seen.add(r.uri); if (known[r.uri] == null) fresh.add(r) }
        val gone = known.values.filter { it.uri !in seen }.map { it.id }
        gone.chunked(500).forEach { dao.delete(it) }
        fresh.chunked(300).forEach { dao.insertAll(it) }
        catalog?.reapply("device:%")
        PerfLog.record("device_scan_ms (n=${rows.size})", (System.nanoTime() - t0) / 1_000_000)
        return true
    }

    /** Adds files picked from the document picker to the "imported" source. The picker's read permission is kept. */
    suspend fun importFiles(uris: List<Uri>): Int {
        val fresh = ArrayList<PhotoEntity>()
        val known = dao.knownLike("imported").map { it.uri }.toSet()
        for (u in uris) {
            if (u.toString() in known) continue
            runCatching { context.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = "photo"; var size = 0L; var modified = System.currentTimeMillis()
            context.contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { if (!c.isNull(it)) modified = c.getLong(it) }
                }
            }
            val kind = FileTypes.kindOf(name) ?: continue
            fresh.add(PhotoEntity(folderUri = "imported", uri = u.toString(), name = name, size = size, modified = modified, isRaw = kind == Kind.RAW, takenAt = modified))
        }
        fresh.chunked(300).forEach { dao.insertAll(it) }
        catalog?.reapply("imported")
        return fresh.size
    }
}

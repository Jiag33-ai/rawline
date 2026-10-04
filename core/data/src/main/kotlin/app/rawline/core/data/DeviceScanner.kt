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
        val imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // The Files table also lists RAW files that the phone did not classify as images (RW2 often is not), when All files access is on.
        val filesUri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val exts = listOf("rw2", "dng", "orf", "cr2", "nef", "arw", "raf")
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
            exts.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.$it'" }
        val rows = ArrayList<PhotoEntity>()
        val cursor = runCatching { context.contentResolver.query(filesUri, proj, sel, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC") }.getOrNull() ?: return false
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val name = c.getString(1) ?: continue
                val kind = FileTypes.kindOf(name) ?: if (c.getInt(8) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) Kind.IMAGE else continue
                val modified = c.getLong(3) * 1000L
                val taken = c.getLong(4)
                val bucket = c.getString(5) ?: "Other"
                // keep the Images uri for images (thumbnails and earlier rows), the Files uri for RAW the phone calls "other"
                val base = if (c.getInt(8) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) imagesUri else filesUri
                rows.add(PhotoEntity(
                    folderUri = "device:$bucket", uri = ContentUris.withAppendedId(base, id).toString(), name = name, size = c.getLong(2), modified = modified,
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

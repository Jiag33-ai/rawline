package app.rawline.core.model

enum class Kind { RAW, IMAGE }

/** One catalogued picture. Immutable snapshot of a Room row. */
data class Photo(
    val id: Long,
    val folderUri: String,
    val uri: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val kind: Kind,
    val indexed: Boolean,
    val takenAt: Long = 0,
    val camera: String? = null,
    val lens: String? = null,
    val iso: Int = 0,
    val shutter: Double = 0.0,
    val aperture: Double = 0.0,
    val focal: Double = 0.0,
    val orientation: Int = 1,
    val previewOffset: Long = 0,
    val previewLength: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rating: Int = 0,
    val flag: Int = 0,
    val label: Int = 0,
    val edited: Boolean = false,
) {
    /** Stable across re-indexing: file name, size and modified time. */
    val key: String get() = "$name|$size|$modified"
}

object FileTypes {
    private val raw = setOf("rw2", "dng", "orf", "cr2", "nef", "arw", "raf")
    private val image = setOf("jpg", "jpeg", "png", "heic", "heif", "hif", "hsp", "avif", "webp")

    fun kindOf(name: String): Kind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in raw -> Kind.RAW
            in image -> Kind.IMAGE
            else -> null
        }
    }
}

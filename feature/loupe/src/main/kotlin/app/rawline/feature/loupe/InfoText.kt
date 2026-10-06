package app.rawline.feature.loupe

/** Text for the viewer's info sheet. Unknown values are left out instead of showing "0 x 0 px  0 MB". */
object InfoText {
    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 * 1024 -> "${maxOf(1, bytes / 1024)} KB"
        else -> "${bytes / 1024 / 1024} MB"
    }

    fun facts(width: Int, height: Int, bytes: Long): String {
        val parts = ArrayList<String>(2)
        if (width > 0 && height > 0) parts += "$width x $height px"
        size(bytes).let { if (it.isNotEmpty()) parts += it }
        return parts.joinToString("   ")
    }
}

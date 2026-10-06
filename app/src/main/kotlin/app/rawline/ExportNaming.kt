package app.rawline

import app.rawline.core.model.Photo
import app.rawline.core.render.ExportSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the exported file name from the user's pattern. Pure, so it is tested on the host. */
object ExportNaming {
    /** Most file systems allow 255 bytes per name; stay well inside it even with multi byte characters. */
    const val MAX_BASE = 120

    fun fileName(p: Photo, s: ExportSettings, n: Int): String {
        val base = p.name.substringBeforeLast('.')
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(if (p.takenAt > 0) p.takenAt else p.modified))
        var name = s.pattern.ifBlank { "{name}" }
            .replace("{name}", base).replace("{date}", date).replace("{n}", n.toString().padStart(3, '0'))
            .replace("{rating}", p.rating.toString()).replace("{camera}", (p.camera ?: "camera").replace(Regex("[^A-Za-z0-9]+"), "-"))
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
        if (name.length > MAX_BASE) name = name.take(MAX_BASE)
        if (name.isEmpty()) name = "photo"
        return "$name.${s.format.ext}"
    }
}

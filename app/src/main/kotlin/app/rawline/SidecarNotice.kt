package app.rawline

import app.rawline.core.data.SidecarResult
import app.rawline.core.ui.Plurals

/** The honest message when "Write XMP sidecars" is on but not every photo got one. */
object SidecarNotice {
    fun text(r: SidecarResult): String? = when {
        r.unsupported > 0 && r.failed > 0 ->
            "Saved. XMP sidecars are only written for folders you added: ${Plurals.photos(r.unsupported)} (camera roll or imported) skipped and ${Plurals.photos(r.failed)} could not be written."
        r.unsupported > 0 -> "Saved. XMP sidecars are only written for folders you added, so ${Plurals.photos(r.unsupported)} (camera roll or imported) got none."
        r.failed > 0 -> "Saved, but ${Plurals.photos(r.failed)} could not get an XMP sidecar (read only folder, or a sidecar from another editor)."
        else -> null
    }
}

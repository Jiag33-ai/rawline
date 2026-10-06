package app.rawline

import app.rawline.core.ui.Plurals

/** What a finished batch says in the notification. Failures are never silent. */
object ExportNotice {
    class Text(val title: String, val body: String)

    /** @return null when nothing was saved or failed (everything was cancelled), so no notification is posted. */
    fun finished(saved: Int, failed: Int): Text? = when {
        saved == 0 && failed == 0 -> null
        failed == 0 -> Text("Export finished", "${Plurals.photos(saved)} saved")
        saved == 0 -> Text("Export failed", "${Plurals.photos(failed)} could not be saved. Open the queue for the reasons.")
        else -> Text("Export finished with problems", "$saved saved, $failed failed. Open the queue for the reasons.")
    }
}

/** Plain sentences for export failures, so the queue never shows a class name or a raw provider message. The detail stays in the log. */
object ExportErrors {
    fun plain(e: Throwable): String {
        val m = e.message.orEmpty()
        val l = m.lowercase()
        return when {
            "no place to save" in l || "failed to create document" in l || "no such file" in l || "enoent" in l || "securityexception" in e.javaClass.simpleName.lowercase() ->
                "The save folder is no longer available. Choose a folder in Export settings."
            "no space" in l || "enospc" in l || "not enough space" in l -> "The phone is out of storage space."
            "gallery" in l -> m
            m.isBlank() || "exception" in l || "java." in l || "android." in l -> "Something went wrong while saving this photo."
            else -> m
        }
    }
}

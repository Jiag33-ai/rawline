package app.rawline.core.studio.model

/**
 * BK-503: Studio on a nearly full phone. One place for the free space rule and the save retry schedule, so the session, the home and the export say the same thing.
 */
object SpaceCheck {
    /** Kept free beyond what an operation is estimated to write (the system and the other apps need room too). */
    const val MARGIN_BYTES = 200L * 1024 * 1024
    /** Autosave writes deflated layers into an existing project, so it only needs a small margin. */
    const val SAVE_MARGIN_BYTES = 16L * 1024 * 1024

    /** The message for the user, or null when [freeBytes] covers [estimateBytes] plus the margin. */
    fun problem(freeBytes: Long, estimateBytes: Long, margin: Long = MARGIN_BYTES): String? {
        val need = estimateBytes + margin
        if (freeBytes >= need) return null
        val mb = ((need - freeBytes) / (1024 * 1024)).coerceAtLeast(1)
        return "Not enough space. Free about $mb MB and try again."
    }

    /** What autosave needs for [rawBytes] of changed layer pixels (deflate makes them smaller, a quarter is a safe estimate for paint, photos can be more). */
    fun saveEstimate(rawBytes: Long): Long = rawBytes / 4

    const val SAVE_FAILED_FULL = "Not saved: the phone is almost full. Free some space or Export, Studio keeps your work open and tries again."
    const val SAVE_FAILED_OTHER = "Could not save. Studio keeps your work open and tries again. Export is safe."

    /** Retry schedule after the [failures]th failed save in a row (1 based): 5, 10, 20, 40, then 60 seconds. */
    fun retryDelayMs(failures: Int): Long = (5_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(60_000L)

    /** After this many failures in a row the retries stop until the user edits again, pauses the app or frees space (a new try on the next edit). */
    const val MAX_TRIES = 10
}

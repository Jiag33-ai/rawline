package app.rawline.core.studio.model

import java.util.Locale

/** Studio S1d (BK-504 to BK-507): the small rules around reopening, RAW files, start failures and thumbnails. Pure, so the host tests run them. */

/** The project that was open when the last session ended. Cleared by a normal Close, so what is left after a kill or a crash is "continue this". */
class OpenMark(private val kv: KeyValue) {
    fun open(id: String) = kv.putString(KEY, id)
    fun close() = kv.putString(KEY, "")
    fun id(): String? = kv.getString(KEY)?.takeIf { it.isNotEmpty() }
    companion object { const val KEY = "studio_open_project" }
}

object ContinueRule {
    /** The row to offer as "Continue <name>": the marked project, if it is still in the list. */
    fun card(openId: String?, rows: List<ProjectRow>): ProjectRow? = openId?.let { id -> rows.firstOrNull { it.id == id } }
    fun label(row: ProjectRow): String = "Continue ${row.name.take(30).trimEnd()}"
}

/** How the previous process ended, as the app's exit reader reports it (mapped from ApplicationExitInfo in the app module). */
enum class ExitKind { CRASH, CRASH_NATIVE, ANR, INITIALIZATION_FAILURE, LOW_MEMORY, SIGNALED, USER_REQUESTED, OTHER;
    companion object {
        /** From ApplicationExitInfo.getReason() (the numbers are the REASON_* constants, so this stays free of Android): 2 signaled, 3 low memory, 4 crash, 5 native crash, 6 ANR, 7 failed to start, 10 and 11 the user. */
        fun fromReason(reason: Int): ExitKind = when (reason) {
            4 -> CRASH; 5 -> CRASH_NATIVE; 6 -> ANR; 7 -> INITIALIZATION_FAILURE; 3 -> LOW_MEMORY; 2 -> SIGNALED; 10, 11 -> USER_REQUESTED; else -> OTHER
        }
    }
}
class ExitInfo(val kind: ExitKind, val endedMs: Long)

object StartFailure {
    /** A Studio start that never drew the home is a failure when the process crashed, stopped responding, failed to start, or hung for over 10 s; a swipe away or force stop is not. */
    fun counts(exit: ExitInfo?, startedMs: Long): Boolean {
        if (exit == null) return false                       // nothing known: do not punish the user for it
        return when (exit.kind) {
            ExitKind.CRASH, ExitKind.CRASH_NATIVE, ExitKind.ANR, ExitKind.INITIALIZATION_FAILURE -> true
            ExitKind.USER_REQUESTED -> false
            ExitKind.LOW_MEMORY, ExitKind.SIGNALED, ExitKind.OTHER -> exit.endedMs - startedMs > StartGuard.HANG_MS
        }
    }
}

/** BK-505: what a picked file is and what to say about it. */
object RawPick {
    private val RAW_EXT = setOf("rw2", "dng", "orf", "cr2", "cr3", "nef", "arw", "raf")
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isDng(name: String, mime: String?) = ext(name) == "dng" || mime == "image/x-adobe-dng" || mime == "image/dng"
    fun isRaw(name: String, mime: String?) = ext(name) in RAW_EXT || (mime?.startsWith("image/x-") == true && mime.contains("raw"))

    const val HOME_NOTE = "RAW photos open from Develop."
    const val DNG_NOTICE = "Rendered by Android, so colours can differ from Develop."
    const val RAW_REFUSED = "RAW photos open from Develop. Use Open in Studio there."

    /** What to do after the decoder answered: [decoded] false means it could not read the file. */
    fun outcome(name: String, mime: String?, decoded: Boolean): Outcome = when {
        decoded && isDng(name, mime) -> Outcome(true, DNG_NOTICE)
        decoded -> Outcome(true, null)
        isRaw(name, mime) -> Outcome(false, RAW_REFUSED)
        else -> Outcome(false, null)
    }
    class Outcome(val ok: Boolean, val message: String?)

    /** What to say when the picture could not be read: the RAW line for a RAW file, else the cause the decoder gave. */
    fun failure(name: String, mime: String?, code: Int?, tooLarge: Boolean): String = outcome(name, mime, decoded = false).message ?: DecodeFailure.message(code, tooLarge)
}

/** BK-505: a cause for "Could not read that picture". [code] is ImageDecoder.DecodeException.getError(): 1 source exception, 2 incomplete, 3 source error. */
object DecodeFailure {
    fun message(code: Int?, tooLarge: Boolean): String = when {
        tooLarge -> "That picture is too large for a canvas of 12 megapixels."
        code == 2 -> "That file is incomplete or damaged."
        code == 3 -> "That file is damaged, or Android cannot open this kind of picture."
        code == 1 -> "That file could not be read. Check that it is still on the phone."
        else -> "Could not read that picture."
    }
}

/**
 * BK-507: when the home's project thumbnail is written. After a quiet [quietMs] since the last edit, at most once per [minGapMs], and only if something changed since the last one.
 * Closing never waits for it: if it is not due, the home keeps the previous thumbnail.
 */
class ThumbScheduler(private val quietMs: Long = 2_000, private val minGapMs: Long = 60_000) {
    private var lastEdit = Long.MIN_VALUE / 2
    private var lastWrite = Long.MIN_VALUE / 2
    private var dirty = false
    fun onEdit(now: Long) { lastEdit = now; dirty = true }
    fun due(now: Long): Boolean = dirty && now - lastEdit >= quietMs && now - lastWrite >= minGapMs
    /** [now] is when the write began. An edit at that moment or later may not be in the picture, so it keeps the thumbnail due. */
    fun onWritten(now: Long) { lastWrite = now; if (lastEdit < now) dirty = false }
    fun needsWrite(): Boolean = dirty
    /** Milliseconds until [due] can be true (0 when it is due now), or null when nothing changed. The session arms one timer with it. */
    fun waitMs(now: Long): Long? = if (!dirty) null else maxOf(0L, quietMs - (now - lastEdit), minGapMs - (now - lastWrite))
}

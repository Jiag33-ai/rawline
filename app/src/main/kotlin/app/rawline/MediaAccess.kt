package app.rawline

/** What the library should offer for photo access right now. */
enum class MediaPrompt {
    /** Access is available (Photos permission or All files access). */
    GRANTED,
    /** The system dialog can still be shown. */
    ASK,
    /** Android will not show the dialog again; the only way back is the app's page in system Settings. */
    OPEN_SETTINGS,
}

/**
 * The photo permission state machine, kept free of Android types so it can be tested on the host.
 * Android stops showing the dialog after two denials, and from then on `launch` returns "denied" at once with no dialog,
 * so a button that only re-requests does nothing. Before the first request [rationale] is also false, hence [askedBefore].
 */
object MediaAccess {
    fun prompt(hasMedia: Boolean, hasAllFiles: Boolean, askedBefore: Boolean, rationale: Boolean): MediaPrompt = when {
        hasMedia || hasAllFiles -> MediaPrompt.GRANTED
        !askedBefore -> MediaPrompt.ASK
        rationale -> MediaPrompt.ASK
        else -> MediaPrompt.OPEN_SETTINGS
    }

    /** Only the very first launch asks by itself; later the library shows its own button, so a rotation or restart never re-prompts. */
    fun autoAsk(prompt: MediaPrompt, askedBefore: Boolean, autoAskedThisRun: Boolean): Boolean =
        prompt == MediaPrompt.ASK && !askedBefore && !autoAskedThisRun
}

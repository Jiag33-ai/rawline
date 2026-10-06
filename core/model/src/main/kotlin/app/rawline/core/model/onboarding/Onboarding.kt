package app.rawline.core.model.onboarding

/** The first-run flow as a pure state machine (BK-191, BK-392). The Compose screens only render [Onboarding.current] and send events. */

/** WELCOME to GESTURES in order. STUDIO is only part of the flow in a build that has Studio. */
enum class Step { WELCOME, PHOTOS, RAW_FILES, WHERE_FROM, STUDIO, GESTURES }

/** What the phone says right now; read again every time the app returns from a system screen. */
data class Perms(val photosGranted: Boolean, val allFilesGranted: Boolean)

/** Small persistent store (SharedPreferences in the app, a map in tests). Reads and writes must not throw. */
interface Store {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBool(key: String, default: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
}

/** What the app does at start with the flow (see [Onboarding.entry]). */
enum class Entry { NONE, WAIT, SHOW, MARK_DONE }

/**
 * Rules:
 * - Every screen can be skipped; a skip of the whole flow marks it done and it is never shown again unless reset from Settings > Help.
 * - PHOTOS is not shown when photos are already allowed, RAW_FILES not when All files access is already on (the flow re-reads [Perms] each time).
 * - STUDIO is shown only when [studio] is true (the build has Studio).
 * - A denied photo permission never blocks: the flow moves on and the library shows its own "No photos yet" message.
 * - The position is saved after every move, so a process death resumes at the same screen; finishing writes `done`.
 * - The notification question is not part of the flow: it is asked once at the first export (app/platform NotificationRule).
 */
class Onboarding(private val store: Store, private val studio: Boolean = false, private val perms: () -> Perms) {
    var current: Step = start()
        private set
    val done: Boolean get() = store.getBool(DONE, false)
    var rawSkipped = false
        private set

    /** True once the person has moved past the first screen, so a restart resumes the flow instead of treating the phone as a returning user's. */
    val started: Boolean get() = store.getInt(POS, 0) > 0

    private fun applies(s: Step, p: Perms): Boolean = when (s) {
        Step.PHOTOS -> !p.photosGranted
        Step.RAW_FILES -> !p.allFilesGranted
        Step.STUDIO -> studio
        else -> true
    }

    private fun start(): Step {
        if (store.getBool(DONE, false)) return Step.GESTURES
        val saved = store.getInt(POS, 0).coerceIn(0, Step.values().size - 1)
        return normalise(Step.values()[saved])
    }

    /** Moves forward over screens that do not apply. GESTURES always applies, so this ends. */
    private fun normalise(s: Step): Step {
        var x = s
        val p = perms()
        while (!applies(x, p)) x = Step.values()[x.ordinal + 1]
        return x
    }

    private fun go(s: Step) { current = s; store.putInt(POS, s.ordinal) }

    /** The screens this person will pass through from here, in order (for a progress indicator). */
    fun visibleSteps(): List<Step> { val p = perms(); return Step.values().filter { applies(it, p) || it == current } }

    fun next() {
        if (current == Step.GESTURES) { finish(); return }
        go(normalise(Step.values()[current.ordinal + 1]))
    }

    /** "Skip for now" on the RAW screen: keeps going and remembers to show the note once. */
    fun skipRaw() { if (current == Step.RAW_FILES) { rawSkipped = true; next() } }

    /** The note after "Skip for now" is shown once. */
    fun noteShown() { rawSkipped = false }

    fun back() {
        val p = perms()
        var i = current.ordinal - 1
        while (i >= 0) {
            val s = Step.values()[i]
            if (applies(s, p)) { go(s); return }
            i--
        }
    }

    /** The system screen returned: a granted permission moves past its own screen. */
    fun permissionsChanged() { go(normalise(current)) }

    fun skipAll() = finish()
    private fun finish() { store.putBool(DONE, true); current = Step.GESTURES }

    /** Settings > Help > Show the welcome screens again. */
    fun reset() { store.putBool(DONE, false); store.putInt(POS, 0); rawSkipped = false; current = normalise(Step.WELCOME) }

    companion object {
        const val DONE = "onboarding.done"
        const val POS = "onboarding.pos"

        /**
         * What the app does at start. A returning person (photos already in the library, flow never started) is marked done without seeing it;
         * a flow that was started always resumes, even after the photo permission made the library fill up. Nothing is decided until the library has loaded,
         * so the photo grid is never held back for this.
         */
        fun entry(done: Boolean, started: Boolean, libraryLoaded: Boolean, photoCount: Int): Entry = when {
            done -> Entry.NONE
            started -> Entry.SHOW
            !libraryLoaded -> Entry.WAIT
            photoCount > 0 -> Entry.MARK_DONE
            else -> Entry.SHOW
        }
    }
}

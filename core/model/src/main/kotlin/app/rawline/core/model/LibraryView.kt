package app.rawline.core.model

/** W29: the first impression of the library (BK-497 and BK-498). Pure, no Android. */

/** What the user chose with the chip. Stored as "raw" or "all"; null means no choice yet. */
enum class ViewChoice(val key: String) {
    RAW("raw"), ALL("all");
    companion object { fun parse(s: String?): ViewChoice? = values().firstOrNull { it.key == s } }
}

object DefaultView {
    /**
     * Whether the grid shows RAW photos only. An explicit choice always wins. With no choice yet the grid shows RAW photos when the phone has at least one RAW file,
     * and everything otherwise (a phone with no RAW files, or All files access not granted yet, keeps the normal view and the "RAW files hidden" hint of BK-450).
     * Called after a scan and when the source changes, never while the user is scrolling, so the view cannot flip under a thumb.
     */
    fun rawOnly(choice: ViewChoice?, rawCount: Int): Boolean = when (choice) { ViewChoice.RAW -> true; ViewChoice.ALL -> false; null -> rawCount > 0 }
}

/** The one time note after an update that changes the default view (BK-498). */
object WhatsNew {
    const val NOTE_VERSION = 1
    /** Shown once, only when the new default is actually in effect (RAW only and no explicit choice made before). */
    fun shouldShow(seenVersion: Int, rawOnlyByDefault: Boolean, explicitChoice: ViewChoice?): Boolean = seenVersion < NOTE_VERSION && rawOnlyByDefault && explicitChoice == null
}

/**
 * BK-497: the order the grid shows. The full sorted list changes while indexing reads capture times; the grid must not move under the user.
 * While the user is active (scrolling, a selection, a touch in the last [holdMs]) the displayed order keeps its relative order: removals apply at once, new photos wait.
 * When the user has been idle for [holdMs] (counted from the moment the touch ended) the displayed list becomes the sorted list. If the grid is not on screen (a photo is open) the caller passes `onScreen = false` and the update applies at once.
 */
class OrderGate(private val holdMs: Long = 1500) {
    private var displayed: List<Long> = emptyList()
    private var lastActive = Long.MIN_VALUE / 2
    private var wasActive = false
    /** How many times the relative order of photos already on screen changed. The Copy report prints this as grid_resort_count. */
    var resorts = 0
        private set

    @Synchronized fun update(sorted: List<Long>, active: Boolean, nowMs: Long, onScreen: Boolean = true): List<Long> {
        // the quiet time starts when the touch ends: the call that reports the end is the start of the wait
        if (active || wasActive) lastActive = nowMs
        wasActive = active
        if (displayed.isEmpty()) { displayed = sorted; return displayed }
        if (sorted == displayed) return displayed
        val keep = sorted.toHashSet()
        // nothing in common (another source): this is a different list, not the same grid changing order
        if (displayed.none { it in keep }) { displayed = sorted; return displayed }
        if (!onScreen || (!active && nowMs - lastActive >= holdMs)) { apply(sorted); return displayed }
        displayed = displayed.filter { it in keep }
        return displayed
    }

    /** True while the displayed order differs from the last sorted list handed to [update] (new photos or a new order are waiting for the user to be idle). */
    @Synchronized fun holding(sorted: List<Long>): Boolean = displayed != sorted

    /** Pull to refresh or a source change: show the sorted list now. */
    @Synchronized fun refresh(sorted: List<Long>): List<Long> { apply(sorted); return displayed }

    private fun apply(sorted: List<Long>) {
        val old = displayed.toHashSet(); val now = sorted.toHashSet()
        val before = displayed.filter { it in now }; val after = sorted.filter { it in old }
        if (before != after) resorts++
        displayed = sorted
    }
}

/**
 * One step of the grid's list pipeline (the view model runs it for every new list, touch change or timer tick).
 * [key] stands for the source and the filter: when it changes the sorted list is shown at once (a new place or a new filter is not the grid moving under the user).
 * [list] is the sorted list itself (compared by identity): when neither it nor the held ids changed, nothing needs to be emitted.
 */
class HeldOrder(val gate: OrderGate = OrderGate()) {
    private var lastKey: Any? = null
    private var lastList: Any? = null
    private var lastIds: List<Long>? = null
    class Step(val ids: List<Long>, val changed: Boolean, val waiting: Boolean)

    fun step(list: Any, key: Any, sortedIds: List<Long>, busy: Boolean, visible: Boolean, nowMs: Long): Step {
        val ids = if (key != lastKey) gate.refresh(sortedIds) else gate.update(sortedIds, busy, nowMs, onScreen = visible)
        lastKey = key
        val waiting = gate.holding(sortedIds)
        val changed = !(lastList === list && ids == lastIds)
        lastList = list; lastIds = ids
        return Step(ids, changed, waiting)
    }
}

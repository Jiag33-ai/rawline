package app.rawline.core.studio.model

/** The two products inside the app (spec 3.5). */
enum class AppMode(val key: String) { DEVELOP("develop"), STUDIO("studio");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: DEVELOP }
}

/** The few persisted values the mode switch needs. SharedPreferences on Android, a map in tests. */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

class MapKeyValue : KeyValue {
    val map = HashMap<String, Any>()
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
}

/**
 * Which mode the app opens in and when the switch is shown (spec 3.5). With the flag off Studio does not exist: the stored mode is ignored, the
 * switch is never shown. The switch is shown only on the two home screens, never while editing or painting.
 */
class ModeState(private val studioEnabled: Boolean, private val kv: KeyValue, private val clock: () -> Long = System::currentTimeMillis, private val resumeWindowMs: Long = RESUME_WINDOW_MS) {
    private val guard = StartGuard(kv)
    var notice: String? = null
        private set

    /** The mode to start in. A Studio start that crashed twice in a row falls back to Develop and says so once. */
    fun startMode(): AppMode {
        if (!studioEnabled) return AppMode.DEVELOP
        var wanted = AppMode.fromKey(kv.getString(KEY))
        // BK-504: Studio comes back only when it was in use recently; otherwise the app opens in Develop, its main job
        if (wanted == AppMode.STUDIO && !studioRecentlyUsed()) { kv.putString(KEY, AppMode.DEVELOP.key); wanted = AppMode.DEVELOP }
        if (wanted == AppMode.STUDIO) {
            if (guard.shouldFallBack()) { kv.putString(KEY, AppMode.DEVELOP.key); kv.putInt(LAST_FALLBACK, 1); notice = "Studio did not start twice, so Rawline opened Develop. Switch to Studio again when you like."; return AppMode.DEVELOP }
            beginStart()
        }
        return wanted
    }

    /** True when Studio was last used less than [resumeWindowMs] ago. A stored mode with no time stamp (an older install) counts as not recent. */
    fun studioRecentlyUsed(): Boolean { val t = kv.getString(LAST_ACTIVE)?.toLongOrNull() ?: return false; val d = clock() - t; return d in 0 until resumeWindowMs }

    /** Call from Studio's home and canvas on pause, on close and when a project opens: keeps the "used recently" time current. */
    fun studioActive() { if (studioEnabled) kv.putString(LAST_ACTIVE, clock().toString()) }

    private fun beginStart() { kv.putString(START_MS, clock().toString()); guard.beginStudioStart() }

    /** BK-506: call once at app start, before [startMode], with how the previous process ended (null when Android has no record). A swipe away is forgiven, a crash is not. */
    fun judgeLastStart(lastExit: ExitInfo?) = guard.forgiveIfNotAFailure(lastExit, kv.getString(START_MS)?.toLongOrNull() ?: Long.MAX_VALUE)

    /** True when a Studio start was begun and never reached its first frame: the only time the previous exit needs to be looked at. */
    fun startPending(): Boolean = kv.getInt(StartGuard.PENDING, 0) > 0

    /** Call when the Studio home has drawn its first frame. */
    fun studioReady() = guard.studioReady()

    fun switchVisible(onHomeScreen: Boolean) = studioEnabled && onHomeScreen

    fun switchTo(mode: AppMode): AppMode {
        if (!studioEnabled) return AppMode.DEVELOP
        kv.putString(KEY, mode.key)
        if (mode == AppMode.STUDIO) studioActive()
        if (mode == AppMode.STUDIO) { kv.putInt(LAST_FALLBACK, 0); beginStart() } else guard.studioReady()
        return mode
    }

    fun clearNotice() { notice = null }

    /** For the Copy report: true when the last Studio start fell back to Develop and the user has not chosen Studio again since. */
    fun lastStartFellBack() = kv.getInt(LAST_FALLBACK, 0) == 1

    companion object { const val KEY = "mode"; const val LAST_FALLBACK = "studio_last_fallback"; const val LAST_ACTIVE = "studio_last_active_ms"; const val START_MS = "studio_start_ms"; const val RESUME_WINDOW_MS = 30L * 60 * 1000 }
}

/** Counts Studio starts that did not reach the first frame. [limit] in a row means the next start goes to Develop (BK-409). */
class StartGuard(private val kv: KeyValue, private val limit: Int = 2) {
    fun beginStudioStart() = kv.putInt(PENDING, kv.getInt(PENDING, 0) + 1)

    /**
     * BK-506: before counting a start that never drew the home, look at how the previous process ended. A user's own swipe away does not count; a crash, a not responding
     * stop or a hang of more than [HANG_MS] does. [lastExit] is null when Android has no record, which counts only if the start was slow (unknown).
     */
    fun forgiveIfNotAFailure(lastExit: ExitInfo?, startedMs: Long) {
        if (kv.getInt(PENDING, 0) == 0) return
        if (!StartFailure.counts(lastExit, startedMs)) kv.putInt(PENDING, (kv.getInt(PENDING, 0) - 1).coerceAtLeast(0))
    }
    fun studioReady() = kv.putInt(PENDING, 0)
    fun shouldFallBack(): Boolean { val n = kv.getInt(PENDING, 0); if (n >= limit) { kv.putInt(PENDING, 0); return true }; return false }
    companion object { const val PENDING = "studio_pending_starts"; const val HANG_MS = 10_000L }
}

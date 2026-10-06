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
class ModeState(private val studioEnabled: Boolean, private val kv: KeyValue) {
    private val guard = StartGuard(kv)
    var notice: String? = null
        private set

    /** The mode to start in. A Studio start that crashed twice in a row falls back to Develop and says so once. */
    fun startMode(): AppMode {
        if (!studioEnabled) return AppMode.DEVELOP
        val wanted = AppMode.fromKey(kv.getString(KEY))
        if (wanted == AppMode.STUDIO) {
            if (guard.shouldFallBack()) { kv.putString(KEY, AppMode.DEVELOP.key); kv.putInt(LAST_FALLBACK, 1); notice = "Studio did not start twice, so Rawline opened Develop. Switch to Studio again when you like."; return AppMode.DEVELOP }
            guard.beginStudioStart()
        }
        return wanted
    }

    /** Call when the Studio home has drawn its first frame. */
    fun studioReady() = guard.studioReady()

    fun switchVisible(onHomeScreen: Boolean) = studioEnabled && onHomeScreen

    fun switchTo(mode: AppMode): AppMode {
        if (!studioEnabled) return AppMode.DEVELOP
        kv.putString(KEY, mode.key)
        if (mode == AppMode.STUDIO) { kv.putInt(LAST_FALLBACK, 0); guard.beginStudioStart() } else guard.studioReady()
        return mode
    }

    fun clearNotice() { notice = null }

    /** For the Copy report: true when the last Studio start fell back to Develop and the user has not chosen Studio again since. */
    fun lastStartFellBack() = kv.getInt(LAST_FALLBACK, 0) == 1

    companion object { const val KEY = "mode"; const val LAST_FALLBACK = "studio_last_fallback" }
}

/** Counts Studio starts that did not reach the first frame. [limit] in a row means the next start goes to Develop (BK-409). */
class StartGuard(private val kv: KeyValue, private val limit: Int = 2) {
    fun beginStudioStart() = kv.putInt(PENDING, kv.getInt(PENDING, 0) + 1)
    fun studioReady() = kv.putInt(PENDING, 0)
    fun shouldFallBack(): Boolean { val n = kv.getInt(PENDING, 0); if (n >= limit) { kv.putInt(PENDING, 0); return true }; return false }
    companion object { const val PENDING = "studio_pending_starts" }
}

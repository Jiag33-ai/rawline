package app.rawline.platform

import org.json.JSONObject
import app.rawline.core.model.EditedFilter
import app.rawline.core.model.FlagFilter
import app.rawline.core.model.LibraryFilter
import app.rawline.core.model.SortOrder

/** W16: pure pieces of the platform task. No Android imports, so host tests run them. */

/** BK-285: the notification question is asked once, when the first export is queued, on Android 13 and later, if it is not granted. */
object NotificationRule {
    fun shouldAsk(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean = sdk >= 33 && !granted && !askedBefore
}

/**
 * BK-120: the library's filter and sort survive a cold start. Stored as JSON in the preferences. Unknown keys are ignored, a missing key takes the default
 * and an unknown enum name takes the default, so a later task can add a field (W29 adds `rawOnly`) without breaking a stored value.
 */
object LibraryFilterJson {
    fun write(f: LibraryFilter): String = JSONObject()
        .put("minRating", f.minRating).put("flag", f.flag.name).put("edited", f.edited.name).put("sort", f.sort.name)
        .also { if (f.camera != null) it.put("camera", f.camera) }.toString()

    fun read(s: String?): LibraryFilter {
        if (s.isNullOrBlank()) return LibraryFilter()
        return try {
            val o = JSONObject(s)
            LibraryFilter(
                minRating = o.optInt("minRating", 0).coerceIn(0, 5),
                flag = enumOr(o.optString("flag", ""), FlagFilter.ANY),
                edited = enumOr(o.optString("edited", ""), EditedFilter.ANY),
                camera = if (o.has("camera") && !o.isNull("camera")) o.getString("camera") else null,
                sort = enumOr(o.optString("sort", ""), SortOrder.NEWEST),
            )
        } catch (e: Exception) { LibraryFilter() }
    }
    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default
}

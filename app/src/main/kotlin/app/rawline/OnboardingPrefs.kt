package app.rawline

import android.content.SharedPreferences
import app.rawline.core.model.onboarding.Store

/**
 * The first-run flow's small store over the app's one SharedPreferences (already loaded at start, so no extra disk read before the first frame).
 * apply(), never commit(): a lost write only shows the welcome screens once more. Nothing here can throw into the UI.
 */
class PrefsStore(private val p: SharedPreferences) : Store {
    override fun getInt(key: String, default: Int) = runCatching { p.getInt(key, default) }.getOrDefault(default)
    override fun putInt(key: String, value: Int) { runCatching { p.edit().putInt(key, value).apply() } }
    override fun getBool(key: String, default: Boolean) = runCatching { p.getBoolean(key, default) }.getOrDefault(default)
    override fun putBool(key: String, value: Boolean) { runCatching { p.edit().putBoolean(key, value).apply() } }
}

/** Preference keys of the help features. */
object HelpPrefs {
    /** "Show explanations": the glossary on a long press of a slider name. On by default. */
    const val EXPLANATIONS = "showExplanations"
    /** The one-time hint about the glossary has been shown. */
    const val EXPLANATIONS_HINT = "explanationsHint"
}

package app.rawline.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The screens that have a help sheet (BK-393). Words are in strings_help.xml; Settings > Help lists all of them. */
enum class HelpTopic(val title: Int, val items: Int) {
    LIBRARY(R.string.title_help_library, R.array.help_library),
    VIEWER(R.string.title_help_viewer, R.array.help_viewer),
    EDITOR(R.string.title_help_editor, R.array.help_editor),
    MASKS(R.string.title_help_masks, R.array.help_masks),
    REMOVE(R.string.title_help_remove, R.array.help_remove),
    EXPORT(R.string.title_help_export, R.array.help_export),
}

/** A bottom sheet with the short tips of one screen. Swipe down, tap outside or press back to close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(topic: HelpTopic, onDismiss: () -> Unit) {
    val title = stringResource(topic.title)
    val items = stringArrayResource(topic.items)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lr.Surface2) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Lr.TextPrimary, modifier = Modifier.semantics { heading() })
            for (item in items) Row {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = Lr.TextSecondary)
                Spacer(Modifier.width(12.dp))
                Text(item, style = MaterialTheme.typography.bodyLarge, color = Lr.TextPrimary)
            }
        }
    }
}

/**
 * The glossary (BK-394): a long press on a slider name shows one plain line about it. The app provides [LocalGlossary] only while "Show explanations" is on,
 * so with it off (or in a screen that never provides it) the label behaves exactly as before.
 */
object Glossary {
    private val IDS: Map<String, Int> = mapOf(
        "Exposure" to R.string.help_gloss_exposure, "Highlights" to R.string.help_gloss_highlights, "Shadows" to R.string.help_gloss_shadows,
        "Whites" to R.string.help_gloss_whites_blacks, "Blacks" to R.string.help_gloss_whites_blacks, "Texture" to R.string.help_gloss_texture,
        "Clarity" to R.string.help_gloss_clarity, "Dehaze" to R.string.help_gloss_dehaze, "Vibrance" to R.string.help_gloss_vibrance,
        "Saturation" to R.string.help_gloss_saturation, "Grain" to R.string.help_gloss_grain,
    )

    /** The string resource that explains a slider name, or null when there is none. */
    fun idFor(label: String): Int? = IDS[label.trim()]
}

/** Shows a glossary line (the app decides how: a toast). */
class GlossaryHost(val show: (String) -> Unit)

val LocalGlossary = staticCompositionLocalOf<GlossaryHost?> { null }

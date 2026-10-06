package app.rawline.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rawline.core.model.onboarding.Step
import app.rawline.core.ui.Lr
import app.rawline.core.ui.LrIcon
import app.rawline.core.ui.LrIconView
import app.rawline.core.ui.LrTextButton
import app.rawline.core.ui.PrimaryButton
import app.rawline.core.ui.R
import app.rawline.core.ui.SecondaryButton

/**
 * The first-run screens (BK-392, docs/COPY.md). One screen per [Step], full screen over the app, every one skippable, none needing a network.
 * Shown by the app only while the flow is not done; the photo grid underneath is already loading and is never held back. Content scrolls, so it fits at font scale 1.3 and in landscape.
 * Back goes to the previous screen; on the first screen it closes the welcome screens (the same as Skip), so nobody is trapped and the grid underneath is one back press away.
 */
@Composable
fun OnboardingHost(state: OnboardingState, studio: StudioText?, actions: OnboardingActions, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val step = state.step
    // the flow ends when it is marked done (skip, or the last button); the host leaves the screen by itself
    if (state.finished) { LaunchedEffect(Unit) { onDone() }; return }
    // always on, so a back press never reaches the screens underneath: it goes one screen back, and on the first screen it closes the welcome screens like Skip
    BackHandler { if (step == Step.WELCOME) state.skipAll() else state.back() }
    Column(modifier.fillMaxSize().background(Lr.Canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Dots(state.steps.indexOf(step), state.steps.size, Modifier.weight(1f).padding(start = 12.dp))
            val skip = stringResource(R.string.button_skip)
            LrTextButton(onClick = { state.skipAll() }, Modifier.semantics { contentDescription = skip }) { Text(skip, color = Lr.TextSecondary) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            when (step) {
                Step.WELCOME -> WelcomePage()
                Step.PHOTOS -> PhotosPage()
                Step.RAW_FILES -> RawFilesPage()
                Step.WHERE_FROM -> WherePage(state.rawSkipped, actions, onChoice = { state.next() })
                Step.STUDIO -> StudioPage(studio)
                Step.GESTURES -> GesturesPage()
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                Step.WELCOME -> Primary(R.string.button_get_started) { state.next() }
                Step.PHOTOS -> {
                    Primary(R.string.button_allow_photos) { actions.onAllowPhotos() }
                    Secondary(R.string.button_skip_for_now) { state.next() }
                }
                Step.RAW_FILES -> {
                    Primary(R.string.button_open_settings) { actions.onOpenAllFilesSettings() }
                    Secondary(R.string.button_skip_for_now) { state.skipRaw() }
                }
                Step.WHERE_FROM -> Secondary(R.string.button_continue) { state.next() }
                Step.STUDIO -> Primary(R.string.button_continue) { state.next() }
                Step.GESTURES -> Primary(R.string.button_open_my_photos) { state.next() }
            }
            if (step != Step.WELCOME) {
                val back = stringResource(R.string.button_back)
                LrTextButton(onClick = { state.back() }, Modifier.semantics { contentDescription = back }) { Text(back, color = Lr.TextSecondary) }
            }
        }
    }
}

@Composable
private fun Primary(text: Int, onClick: () -> Unit) {
    val t = stringResource(text)
    PrimaryButton(t, onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = t })
}

@Composable
private fun Secondary(text: Int, onClick: () -> Unit) {
    val t = stringResource(text)
    SecondaryButton(t, onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = t })
}

@Composable
private fun Dots(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == index) Lr.Accent else Lr.SurfaceSelected))
    }
}

@Composable
private fun Title(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Lr.TextPrimary, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Lr.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun WelcomePage() {
    Title(stringResource(R.string.title_welcome))
    Body(stringResource(R.string.body_welcome))
}

@Composable
private fun PhotosPage() {
    var why by rememberSaveable { mutableStateOf(false) }
    Title(stringResource(R.string.title_photos))
    Body(stringResource(R.string.body_photos))
    val link = stringResource(R.string.link_why_photos)
    Text(
        link, style = MaterialTheme.typography.bodyLarge, color = Lr.Accent,
        modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { why = !why }.padding(vertical = 12.dp).semantics { contentDescription = link },
    )
    if (why) Body(stringResource(R.string.body_why_photos))
}

@Composable
private fun RawFilesPage() {
    Title(stringResource(R.string.title_raw_files))
    Body(stringResource(R.string.body_raw_files))
}

@Composable
private fun WherePage(rawSkipped: Boolean, actions: OnboardingActions, onChoice: () -> Unit) {
    Title(stringResource(R.string.title_where_from))
    if (rawSkipped) Body(stringResource(R.string.note_raw_skipped))
    Choice(LrIcon.PHOTOS, stringResource(R.string.choice_camera_roll)) { onChoice() }
    Choice(LrIcon.FOLDER, stringResource(R.string.choice_folder_or_card)) { actions.onPickFolder(); onChoice() }
    Choice(LrIcon.IMPORT, stringResource(R.string.choice_import_files)) { actions.onImportFiles(); onChoice() }
    Spacer(Modifier.height(12.dp))
    Body(stringResource(R.string.note_where_from))
}

@Composable
private fun Choice(icon: LrIcon, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Lr.BorderDefault, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp).semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LrIconView(icon, Lr.IconPrimary, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Lr.TextPrimary)
    }
}

@Composable
private fun StudioPage(studio: StudioText?) {
    // the app only reaches this screen in a build with Studio, which supplies the words; a missing text shows nothing rather than a blank title
    if (studio == null) return
    Title(studio.title)
    Body(studio.body)
    Body(studio.note)
}

@Composable
private fun GesturesPage() {
    Title(stringResource(R.string.title_gestures))
    Gesture(LrIcon.SELECT, stringResource(R.string.body_gesture_select))
    Gesture(LrIcon.ORIGINAL, stringResource(R.string.body_gesture_original))
    Gesture(LrIcon.RESET, stringResource(R.string.body_gesture_reset))
}

@Composable
private fun Gesture(icon: LrIcon, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LrIconView(icon, Lr.IconPrimary, size = 28.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Lr.TextPrimary)
    }
}

package app.rawline.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.rawline.core.model.onboarding.Onboarding
import app.rawline.core.model.onboarding.Step

/** The words of the Studio screen. They come from the app (a build without Studio has none), so this module carries no Studio text. */
data class StudioText(val title: String, val body: String, val note: String)

/** What the first-run screens ask the app to do. Each one is a one line call into code that already exists (the permission launcher, the pickers, Settings). */
class OnboardingActions(
    val onAllowPhotos: () -> Unit,
    val onOpenAllFilesSettings: () -> Unit,
    val onPickFolder: () -> Unit,
    val onImportFiles: () -> Unit,
)

/** Compose view of the pure [Onboarding] flow: every event goes through the flow (the rules are tested on the host), then the screen state is read back. */
class OnboardingState(private val flow: Onboarding) {
    var step by mutableStateOf(flow.current)
        private set
    var rawSkipped by mutableStateOf(flow.rawSkipped)
        private set
    var finished by mutableStateOf(flow.done)
        private set
    var steps by mutableStateOf(flow.visibleSteps())
        private set

    private fun sync() { step = flow.current; rawSkipped = flow.rawSkipped; finished = flow.done; steps = flow.visibleSteps() }

    fun next() { if (step == Step.WHERE_FROM) flow.noteShown(); flow.next(); sync() }
    fun back() { flow.back(); sync() }
    fun skipAll() { flow.skipAll(); sync() }
    fun skipRaw() { flow.skipRaw(); sync() }
    fun permissionsChanged() { flow.permissionsChanged(); sync() }

    /** Settings > Help > Show the welcome screens again. */
    fun restart() { flow.reset(); sync() }
}

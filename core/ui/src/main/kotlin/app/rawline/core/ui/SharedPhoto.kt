package app.rawline.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Marks the picture of one photo as a shared element, so the library thumbnail, the loupe and the editor
 * morph into each other (420 ms) when moving between them. Outside a navigation host it adds nothing.
 */
val LocalSharedPhoto = staticCompositionLocalOf<@Composable (Long) -> Modifier> { { Modifier } }

package app.rawline.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutral grey surfaces so colour judgement is not skewed; one restrained accent.
private val Scheme = darkColorScheme(
    primary = Lr.Accent,
    onPrimary = Color(0xFF00121F),
    background = Lr.Background,
    onBackground = Lr.Text,
    surface = Lr.Panel,
    onSurface = Lr.Text,
    surfaceVariant = Lr.Surface,
    onSurfaceVariant = Lr.TextDim,
    error = Color(0xFFF28B82),
)

@Composable
fun RawlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

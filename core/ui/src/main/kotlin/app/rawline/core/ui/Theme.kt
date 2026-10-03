package app.rawline.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutral grey surfaces so colour judgement is not skewed; one restrained accent.
private val Scheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1B33),
    background = Color(0xFF2A2A2A),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF333333),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF3D3D3D),
    onSurfaceVariant = Color(0xFFB8B8B8),
    error = Color(0xFFF28B82),
)

@Composable
fun RawlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

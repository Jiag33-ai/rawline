package app.rawline.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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

private val base = androidx.compose.material3.Typography()
// Lightroom uses light, regular weight type; Material defaults are heavier
private val LrType = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Normal, fontSize = 22.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontWeight = FontWeight.Normal, fontSize = 15.sp, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Normal),
)

@Composable
fun RawlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = LrType, content = content)
}

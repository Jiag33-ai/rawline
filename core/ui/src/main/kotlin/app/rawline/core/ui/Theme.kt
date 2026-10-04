package app.rawline.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Roboto is bundled so the type does not drift into the phone maker's own font. */
val Roboto = FontFamily(Font(R.font.roboto_regular, FontWeight.Normal), Font(R.font.roboto_medium, FontWeight.Medium))

private fun t(size: Int, line: Int, w: FontWeight) = TextStyle(fontFamily = Roboto, fontSize = size.sp, lineHeight = line.sp, fontWeight = w, letterSpacing = 0.sp)

private val LrType = Typography(
    headlineLarge = t(18, 24, FontWeight.Medium), headlineMedium = t(18, 24, FontWeight.Medium), headlineSmall = t(18, 24, FontWeight.Medium),
    titleLarge = t(18, 24, FontWeight.Medium),      // screen / workflow title
    titleMedium = t(16, 22, FontWeight.Medium),     // major section title
    titleSmall = t(15, 20, FontWeight.Medium),      // panel title
    bodyLarge = t(14, 20, FontWeight.Normal), bodyMedium = t(14, 20, FontWeight.Normal), bodySmall = t(13, 18, FontWeight.Normal),
    labelLarge = t(14, 18, FontWeight.Medium),      // button
    labelMedium = t(11, 14, FontWeight.Normal),     // bottom tool label
    labelSmall = t(11, 15, FontWeight.Normal),      // caption
)

/** Adjustment values: tabular numerals so digits do not jitter while dragging. */
val ValueStyle = t(13, 18, FontWeight.Normal).copy(fontFeatureSettings = "tnum")

private val LrShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp), extraLarge = RoundedCornerShape(8.dp),
)

private val Scheme = darkColorScheme(
    primary = Lr.Accent, onPrimary = Color.White, primaryContainer = Lr.AccentPressed, onPrimaryContainer = Color.White,
    secondary = Lr.TextSecondary, onSecondary = Color.Black,
    background = Lr.Canvas, onBackground = Lr.TextPrimary,
    surface = Lr.Surface1, onSurface = Lr.TextPrimary, surfaceVariant = Lr.Surface2, onSurfaceVariant = Lr.TextMuted,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Lr.Canvas, surfaceContainerLow = Lr.Surface1, surfaceContainer = Lr.Surface3, surfaceContainerHigh = Lr.Modal, surfaceContainerHighest = Lr.SurfaceSelected,
    outline = Lr.FunctionBorder, outlineVariant = Lr.BorderDefault,
    error = Lr.Error, onError = Color.Black,
)

@Composable
fun RawlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = LrType, shapes = LrShapes, content = content)
}

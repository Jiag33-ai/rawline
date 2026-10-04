package app.rawline.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens of the locked UI specification (docs/UI_SPEC.md). Everything visual comes from here:
 * black canvas, #1C1C1C control surfaces, one blue accent, 0 to 6 dp radii, short direct motion.
 */
object Lr {
    // surfaces
    val Canvas = Color(0xFF000000)
    val AppBg = Color(0xFF0A0A0A)
    val Surface1 = Color(0xFF1C1C1C)
    val Surface2 = Color(0xFF202020)
    val Surface3 = Color(0xFF262626)
    val SurfaceSelected = Color(0xFF303030)
    val SurfaceHover = Color(0xFF292929)
    val SurfacePressed = Color(0xFF363636)
    val Input = Color(0xFF242424)
    val Modal = Color(0xFF242424)
    val Button = Color(0xFF181818)
    // lines
    val BorderSubtle = Color(0x12FFFFFF)
    val BorderDefault = Color(0x21FFFFFF)
    val BorderStrong = Color(0x40FFFFFF)
    val Divider = Color(0xFF393939)
    val FunctionBorder = Color(0xFF515151)
    // text and icons
    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFFD2D2D2)
    val TextMuted = Color(0xFF9A9A9A)
    val TextDisabled = Color(0xFF686868)
    val IconPrimary = Color(0xFFE2E2E2)
    val IconSecondary = Color(0xFF9E9E9E)
    // accent and states
    val Accent = Color(0xFF437EE4)
    val AccentHover = Color(0xFF4D87EB)
    val AccentPressed = Color(0xFF386FCB)
    val AccentSoft = Color(0x2E437EE4)
    val Success = Color(0xFF55A86A)
    val Warning = Color(0xFFD2A24A)
    val Error = Color(0xFFDF6464)
    val Focus = Color(0xFF78A7FF)
    // sliders
    val SliderTrack = Color(0xFF7A7A7A)
    val SliderTrackStrong = Color(0xFFB6B6B6)
    val SliderThumb = Color(0xFFEEEEEE)
    val ToggleOff = Color(0xFF4A4A4A)
    // overlays
    val Overlay = Color(0x8F000000)
    val OverlayHeavy = Color(0xB8000000)
    val ValuePill = Color(0xF0141414)
    // named literals that used to sit inline
    val IconDisabled = Color(0xFF666666)
    val ControlPressed = Color(0xFF2E2E2E)
    val ButtonBorder = Color(0xFF555555)
    val PressOverlay = Color(0x17FFFFFF)
    val CircleButton = Color(0xFF2A2A2A)
    val CircleButtonBorder = Color(0xFF414141)
    val InputBorder = Color(0xFF454545)
    val ScrollThumb = Color(0xFF717171)
    val DebugText = Color(0xFF9EE493)

    // names used before the specification; they point at the spec values
    val Black = Canvas
    val Background = Surface1
    val Panel = Surface1
    val PanelOverlay = Surface1
    val Surface = SurfaceSelected
    val Separator = Divider
    val AccentDim = AccentSoft
    val Text = TextPrimary
    val TextDim = TextMuted
    val TrackOff = ToggleOff
    val Star = Color(0xFFFFFFFF)
}

object LrSpace { val s1 = 2.dp; val s2 = 4.dp; val s3 = 6.dp; val s4 = 8.dp; val s5 = 12.dp; val s6 = 16.dp; val s7 = 20.dp; val s8 = 24.dp; val s9 = 32.dp; val s10 = 40.dp }

object LrRadius { val none = 0.dp; val xs = 2.dp; val sm = 4.dp; val md = 6.dp; val lg = 8.dp }

/** Control sizes in dp. */
object LrDim {
    val topBar = 52.dp; val libraryHeader = 48.dp; val idleDock = 66.dp; val masterRail = 54.dp; val categoryRail = 64.dp
    val tabBar = 44.dp; val button = 40.dp; val smallButton = 34.dp; val hit = 44.dp; val menuRow = 40.dp; val presetRow = 58.dp
    val sliderBlock = 46.dp; val bottomNav = 56.dp; val confirmBar = 56.dp; val pill = 28.dp; val activeTile = 42.dp
    val dockMargin = 28.dp
}

/** Durations in ms and easings. No springs anywhere. */
object LrMotion {
    const val instant = 80; const val fast = 120; const val normal = 180; const val panel = 220; const val page = 320; const val shared = 420; const val slow = 520
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val enter = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

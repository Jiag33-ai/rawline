package app.rawline.core.ui

import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay

/**
 * Parameter panel scrollbar (spec section 11): a 2 dp thumb in #717171, no track, hidden while idle.
 * Put it before verticalScroll in the chain so it draws against the viewport, not the content.
 */
fun Modifier.lrScrollbar(state: ScrollState): Modifier = composed {
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) active = true else { delay(700); active = false }
    }
    val alpha by animateFloatAsState(if (active) 1f else 0f, tween(if (active) LrMotion.instant else LrMotion.panel), label = "scrollbar")
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (alpha > 0f && max > 0) {
            val view = size.height
            val thumb = (view * view / (view + max)).coerceAtLeast(24.dp.toPx())
            val top = state.value.toFloat() / max * (view - thumb)
            val w = 2.dp.toPx()
            drawRoundRect(Lr.ScrollThumb, Offset(size.width - w - 2.dp.toPx(), top), Size(w, thumb), CornerRadius(w / 2f), alpha = alpha)
        }
    }
}

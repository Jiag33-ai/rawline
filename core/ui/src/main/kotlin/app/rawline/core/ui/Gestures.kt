package app.rawline.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tap and double tap without the usual wait: [onTap] fires the moment the first tap lifts, and [onDoubleTap] fires when a second
 * tap lands within the system double tap time and 48 dp of the first. So a double tap runs both, which suits "select, then reset
 * what was just selected" (colour chips, swatches, curve channels) without a 300 ms lag on the plain tap.
 * A drag or a scroll that takes over (the pointer is consumed, or it moved past the touch slop) cancels the tap. Both callbacks are read fresh each time.
 */
fun Modifier.tapOrDoubleTap(onTap: () -> Unit = {}, onDoubleTap: () -> Unit = {}): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    // pointerInput alone exposes no click action, so TalkBack, Switch Access and Voice Access could not operate these controls
    // (only here, where the tap has no position; a position based tap has no meaning for an assistive click)
    Modifier.semantics { onClick { tap(); true } }.tapOrDoubleTapAt({ onTap() }, { onDoubleTap() })
}

/** [tapOrDoubleTap] that also reports where the (first, for a tap; second, for a double tap) finger landed, in this element's pixels. */
fun Modifier.tapOrDoubleTapAt(onTap: (Offset) -> Unit = {}, onDoubleTap: (Offset) -> Unit = {}): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val double by rememberUpdatedState(onDoubleTap)
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            // a finger that travelled further than the touch slop was a drag (a flick, a slow pan), not a tap
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            up.consume()
            tap(down.position)
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() } ?: return@awaitEachGesture
            if ((second.position - down.position).getDistance() > 48.dp.toPx()) return@awaitEachGesture
            second.consume()
            val up2 = waitForUpOrCancellation() ?: return@awaitEachGesture
            if ((up2.position - second.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            up2.consume()
            double(second.position)
        }
    }
}

/**
 * Makes this surface swallow every touch that lands on it, so a tap in a gap between its controls never reaches a photo, graph or
 * other layer drawn underneath (a pointer handler stops siblings below it from also being hit).
 */
fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
}

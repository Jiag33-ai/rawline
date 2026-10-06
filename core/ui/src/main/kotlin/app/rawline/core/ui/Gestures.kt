package app.rawline.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tap and double tap without the usual wait: [onTap] fires the moment the first tap lifts, and [onDoubleTap] fires when a second
 * tap lands within the system double tap time and 48 dp of the first. So a double tap runs both, which suits "select, then reset
 * what was just selected" (colour chips, swatches, curve channels) without a 300 ms lag on the plain tap.
 * A drag or a scroll that takes over (the pointer is consumed) cancels the tap. Both callbacks are read fresh each time.
 */
fun Modifier.tapOrDoubleTap(onTap: () -> Unit = {}, onDoubleTap: () -> Unit = {}): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val double by rememberUpdatedState(onDoubleTap)
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            up.consume()
            tap()
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() } ?: return@awaitEachGesture
            if ((second.position - down.position).getDistance() > 48.dp.toPx()) return@awaitEachGesture
            second.consume()
            val up2 = waitForUpOrCancellation() ?: return@awaitEachGesture
            up2.consume()
            double()
        }
    }
}

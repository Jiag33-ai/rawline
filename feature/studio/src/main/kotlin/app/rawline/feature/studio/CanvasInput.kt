package app.rawline.feature.studio

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import app.rawline.core.studio.model.InputEvent
import app.rawline.core.studio.model.InputSamples
import app.rawline.core.studio.model.Phase
import app.rawline.core.studio.model.PointerKind
import app.rawline.core.studio.model.Sample
import app.rawline.core.studio.render.StudioSession

/**
 * Turns Compose pointer events into the session's [InputEvent]s (screen pixels): a stylus draws with its pressure, a finger with pressure 1 (spec 2.4),
 * a move carries the historical positions the system batched (a pen samples faster than the display) with pressure interpolated by time.
 *
 * The session is read through rememberUpdatedState and the pointer block is keyed on Unit, so a recomposition never restarts it in the middle of a stroke
 * and the block never calls an old session. If the block is cancelled (the system cancelled the touch, or the screen left composition) every pointer still down is cancelled,
 * which rolls the stroke back without a history entry.
 */
@Composable
fun Modifier.canvasInput(session: StudioSession): Modifier {
    val current by rememberUpdatedState(session)
    return pointerInput(Unit) {
        val kinds = HashMap<Int, PointerKind>()
        val lastPos = HashMap<Int, FloatArray>()
        val lastPressure = HashMap<Int, Float>()
        val lastTime = HashMap<Int, Long>()
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    for (c in event.changes) {
                        val id = c.id.value.toInt()
                        val kind = if (c.type == PointerType.Stylus || c.type == PointerType.Eraser) PointerKind.STYLUS else PointerKind.FINGER
                        val pressure = if (kind == PointerKind.STYLUS) c.pressure.coerceIn(0f, 1f) else 1f
                        // Pen hover (not touching): fingers are taken for a palm while the pen is near and for a short grace after it leaves (BK-481).
                        if (kind == PointerKind.STYLUS && !c.pressed) current.onHover(event.type != PointerEventType.Exit, c.uptimeMillis)
                        when {
                            c.changedToDown() -> {
                                kinds[id] = kind; lastPos[id] = floatArrayOf(c.position.x, c.position.y); lastPressure[id] = pressure; lastTime[id] = c.uptimeMillis
                                current.onInput(InputEvent(Phase.DOWN, id, c.position.x, c.position.y, pressure, kind, c.uptimeMillis))
                                c.consume()
                            }
                            c.changedToUp() -> {
                                if (kinds.containsKey(id)) current.onInput(InputEvent(Phase.UP, id, c.position.x, c.position.y, pressure, kinds[id] ?: kind, c.uptimeMillis))
                                kinds.remove(id); lastPos.remove(id); lastPressure.remove(id); lastTime.remove(id)
                                c.consume()
                            }
                            c.pressed && c.positionChanged() && kinds.containsKey(id) -> {
                                val k = kinds[id] ?: kind
                                val hist = c.historical.map { Sample(it.position.x, it.position.y, 0f, it.uptimeMillis) }
                                val samples = InputSamples.expand(lastPressure[id] ?: pressure, lastTime[id] ?: c.uptimeMillis, hist, Sample(c.position.x, c.position.y, pressure, c.uptimeMillis))
                                for (s in samples) current.onInput(InputEvent(Phase.MOVE, id, s.x, s.y, s.pressure, k, s.timeMs))
                                lastPos[id] = floatArrayOf(c.position.x, c.position.y); lastPressure[id] = pressure; lastTime[id] = c.uptimeMillis
                                c.consume()
                            }
                        }
                    }
                }
            }
        } finally {
            val now = SystemClock.uptimeMillis()
            for ((id, k) in kinds) {
                val p = lastPos[id] ?: floatArrayOf(0f, 0f)
                current.onInput(InputEvent(Phase.CANCEL, id, p[0], p[1], 0f, k, now))
            }
        }
    }
}

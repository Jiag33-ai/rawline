package app.rawline.core.studio.model

import kotlin.math.hypot

enum class PointerKind { FINGER, STYLUS }
enum class Phase { DOWN, MOVE, UP, CANCEL }

/** One pointer event in screen pixels, already split per pointer (the Compose layer turns a MotionEvent and its historical samples into these). */
class InputEvent(val phase: Phase, val pointerId: Int, val x: Float, val y: Float, val pressure: Float, val kind: PointerKind, val timeMs: Long = 0)

/** What the canvas must do. Strokes use screen coordinates; the session converts them to layer pixels with the current view. */
sealed class Action {
    class StrokeStart(val x: Float, val y: Float, val pressure: Float, val kind: PointerKind) : Action()
    class StrokeMove(val x: Float, val y: Float, val pressure: Float) : Action()
    object StrokeEnd : Action()
    /** Roll the stroke back without a history entry (a second finger landed, the system cancelled). */
    object StrokeCancel : Action()
    class GestureStart(val cx: Float, val cy: Float) : Action()
    /** Pan by (dx, dy) screen pixels of the centre, zoom by [scale] about ([cx], [cy]). */
    class GestureUpdate(val dx: Float, val dy: Float, val scale: Float, val cx: Float, val cy: Float) : Action()
    object GestureEnd : Action()
}

/**
 * Pointer routing of the canvas (spec 2.14 S1 subset), a pure state machine over [InputEvent]:
 * - one pointer draws (finger or pen); a second FINGER landing cancels a stroke begun by a finger and starts a two finger pan and zoom;
 * - palm rejection: while a stylus draws, finger touches are ignored; a finger never starts a stroke while a stylus is down;
 * - during and after a two finger gesture the remaining finger does nothing until every pointer is up (no stray stroke at the end of a pinch);
 * - a stylus landing during a gesture is ignored until the gesture ends.
 */
class InputRouter {
    private enum class State { IDLE, STROKING, GESTURE, WAIT_FOR_UP }
    private var state = State.IDLE
    private var stroke = -1
    private var strokeKind = PointerKind.FINGER
    private val pos = LinkedHashMap<Int, FloatArray>()   // pointers of the gesture, by id
    private val down = HashSet<Int>()
    private var lastCx = 0f; private var lastCy = 0f; private var lastDist = 1f

    fun onEvent(e: InputEvent): List<Action> {
        val out = ArrayList<Action>(2)
        when (e.phase) {
            Phase.DOWN -> {
                down.add(e.pointerId)
                when (state) {
                    State.IDLE -> { state = State.STROKING; stroke = e.pointerId; strokeKind = e.kind; out += Action.StrokeStart(e.x, e.y, e.pressure, e.kind) }
                    State.STROKING -> {
                        if (strokeKind == PointerKind.STYLUS && e.kind == PointerKind.FINGER) return out    // palm
                        if (e.kind == PointerKind.STYLUS) return out
                        out += Action.StrokeCancel
                        val first = strokePos ?: floatArrayOf(e.x, e.y)
                        pos.clear(); pos[stroke] = first; pos[e.pointerId] = floatArrayOf(e.x, e.y)
                        state = State.GESTURE; beginGesture(); out += Action.GestureStart(lastCx, lastCy)
                    }
                    State.GESTURE, State.WAIT_FOR_UP -> {}
                }
            }
            Phase.MOVE -> when (state) {
                State.STROKING -> if (e.pointerId == stroke) { strokePos = floatArrayOf(e.x, e.y); out += Action.StrokeMove(e.x, e.y, e.pressure) }
                State.GESTURE -> if (pos.containsKey(e.pointerId)) { pos[e.pointerId] = floatArrayOf(e.x, e.y); update(out) }
                else -> {}
            }
            Phase.UP, Phase.CANCEL -> {
                down.remove(e.pointerId)
                when (state) {
                    State.STROKING -> if (e.pointerId == stroke) { out += if (e.phase == Phase.UP) Action.StrokeEnd else Action.StrokeCancel; state = State.IDLE; strokePos = null }
                    State.GESTURE -> if (pos.containsKey(e.pointerId)) { out += Action.GestureEnd; pos.clear(); state = if (down.isEmpty()) State.IDLE else State.WAIT_FOR_UP }
                    else -> {}
                }
                if (down.isEmpty() && state == State.WAIT_FOR_UP) state = State.IDLE
            }
        }
        if (e.phase == Phase.DOWN && state == State.STROKING && e.pointerId == stroke) strokePos = floatArrayOf(e.x, e.y)
        return out
    }

    private var strokePos: FloatArray? = null

    private fun beginGesture() {
        val (cx, cy, d) = measure(); lastCx = cx; lastCy = cy; lastDist = d.coerceAtLeast(1f)
    }

    private fun update(out: MutableList<Action>) {
        val (cx, cy, d) = measure()
        val dist = d.coerceAtLeast(1f)
        out += Action.GestureUpdate(cx - lastCx, cy - lastCy, dist / lastDist, cx, cy)
        lastCx = cx; lastCy = cy; lastDist = dist
    }

    private fun measure(): Triple<Float, Float, Float> {
        val p = pos.values.toList()
        val a = p[0]; val b = p[1]
        return Triple((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, hypot(a[0] - b[0], a[1] - b[1]))
    }
}

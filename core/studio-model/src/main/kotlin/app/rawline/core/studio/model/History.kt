package app.rawline.core.studio.model

/** Before and after pixels of one rectangle of one layer (straight RGBA8, row 0 top, tightly packed rect rows). */
class PixelDelta(val x: Int, val y: Int, val w: Int, val h: Int, val before: ByteArray, val after: ByteArray) {
    val bytes: Long get() = (before.size + after.size).toLong()

    /** Writes [src] (the before or the after bytes) into [layer] (layerW wide). */
    fun apply(layer: ByteArray, layerW: Int, src: ByteArray) {
        for (r in 0 until h) System.arraycopy(src, r * w * 4, layer, ((y + r) * layerW + x) * 4, w * 4)
    }

    companion object {
        /** Cuts the rectangle out of [layer]. */
        fun cut(layer: ByteArray, layerW: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
            val out = ByteArray(w * h * 4)
            for (r in 0 until h) System.arraycopy(layer, ((y + r) * layerW + x) * 4, out, r * w * 4, w * 4)
            return out
        }
    }
}

/** What the session must do for an undo or a redo. */
sealed class Step {
    /** Replace the document (layer stack operations). The pixels of layers that are unchanged stay as they are. */
    class SetDocument(val document: Document) : Step()
    /** Write [bytes] (the delta's before or after) into the rectangle of the layer, in the CPU copy and in the GPU texture. */
    class SetPixels(val layerId: String, val delta: PixelDelta, val bytes: ByteArray) : Step()
}

/**
 * One linear history for layer operations and strokes (spec 2.13 in the S1 form): document states for layer operations, pixel deltas for
 * strokes. Bounded by [maxEntries] and by [maxBytes] of pixel deltas (the oldest entries go first). A new edit after an undo drops the redo tail.
 */
class StudioHistory(initial: Document, private val maxEntries: Int = 100, private val maxBytes: Long = 200L * 1024 * 1024) {
    private sealed class Entry {
        class Doc(val before: Document, val after: Document) : Entry()
        class Stroke(val layerId: String, val delta: PixelDelta) : Entry()
    }
    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()
    var document: Document = initial
        private set
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    var deltaBytes = 0L
        private set

    /** Records a layer operation result. An identical document is not recorded. */
    fun commitDocument(next: Document) {
        if (next == document) return
        push(Entry.Doc(document, next)); document = next
    }

    /** Records a finished stroke. The pixels are already in place; only the delta is kept. */
    fun commitStroke(layerId: String, delta: PixelDelta) { push(Entry.Stroke(layerId, delta)); deltaBytes += delta.bytes; trim() }

    /** Replaces the current document without a history entry (the storage layer filling in pixel file names after a save). */
    fun replaceCurrent(doc: Document) { document = doc }

    fun undo(): Step? {
        val e = undo.removeLastOrNull() ?: return null
        redo.addLast(e)
        return when (e) {
            is Entry.Doc -> { document = e.before; Step.SetDocument(e.before) }
            is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.before)
        }
    }

    fun redo(): Step? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(e)
        return when (e) {
            is Entry.Doc -> { document = e.after; Step.SetDocument(e.after) }
            is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.after)
        }
    }

    /** Drops the oldest entries until the pixel deltas take at most [maxBytes] (memory pressure). Keeps at least the newest entry. Returns true when anything was dropped. */
    fun trimBytes(maxBytes: Long): Boolean {
        var dropped = false
        while (deltaBytes > maxBytes && undo.size > 1) {
            val e = undo.removeFirst(); dropped = true
            if (e is Entry.Stroke) deltaBytes -= e.delta.bytes
        }
        return dropped
    }

    private fun push(e: Entry) {
        undo.addLast(e)
        dropRedo()
        trim()
    }

    private fun dropRedo() { for (e in redo) if (e is Entry.Stroke) deltaBytes -= e.delta.bytes; redo.clear() }

    private fun trim() {
        while (undo.size > maxEntries || (deltaBytes > maxBytes && undo.size > 1)) {
            val e = undo.removeFirst()
            if (e is Entry.Stroke) deltaBytes -= e.delta.bytes
        }
    }
}

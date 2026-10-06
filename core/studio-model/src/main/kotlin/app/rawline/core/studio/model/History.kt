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
    /** A stroke that touched several tiles: write every part (before bytes for an undo, after bytes for a redo). */
    class SetPixelsMany(val layerId: String, val parts: List<PixelDelta>, val useAfter: Boolean) : Step() {
        fun apply(layer: ByteArray, layerW: Int) { for (d in parts) d.apply(layer, layerW, if (useAfter) d.after else d.before) }
    }
    /** Selection or mask tiles (S2). */
    class SetPlane(val edit: PlaneStep) : Step()
}

/** What the session must do for an undo or redo of a tile edit of the selection or of a layer mask (decision D8): write the before or after tiles, and restore the selection bounds. */
class PlaneStep(val target: PlaneTarget, val parts: List<PlaneDelta>, val useAfter: Boolean, val bounds: IRect?)

/**
 * One linear history for layer operations and strokes (spec 2.13 in the S1 form): document states for layer operations, pixel deltas for
 * strokes. Bounded by [maxEntries] and by [maxBytes] of pixel deltas (the oldest entries go first). A new edit after an undo drops the redo tail.
 */
class StudioHistory(initial: Document, private val maxEntries: Int = 100, private val maxBytes: Long = 200L * 1024 * 1024) {
    private sealed class Entry {
        class Doc(val before: Document, val after: Document) : Entry()
        class Stroke(val layerId: String, val delta: PixelDelta) : Entry()
        class Strokes(val layerId: String, val parts: List<PixelDelta>) : Entry()
        class Plane(val target: PlaneTarget, val parts: List<PlaneDelta>, val boundsBefore: IRect?, val boundsAfter: IRect?) : Entry()
    }
    private fun bytesOf(e: Entry): Long = when (e) { is Entry.Stroke -> e.delta.bytes; is Entry.Strokes -> e.parts.sumOf { it.bytes }; is Entry.Doc -> 0L; is Entry.Plane -> e.parts.sumOf { it.bytes } }
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

    /** One entry for a stroke that changed several tiles (one undo step, bytes proportional to the painted tiles, not to a bounding box). */
    fun commitStrokeTiles(layerId: String, parts: List<PixelDelta>) {
        if (parts.isEmpty()) return
        val e = Entry.Strokes(layerId, parts.toList()); push(e); deltaBytes += bytesOf(e); trim()
    }

    /** A selection or mask edit that changed tiles (D8). Nothing is recorded when no tile changed and the bounds did not either. */
    fun commitPlane(target: PlaneTarget, parts: List<PlaneDelta>, boundsBefore: IRect?, boundsAfter: IRect?) {
        if (parts.isEmpty() && boundsBefore == boundsAfter) return
        val e = Entry.Plane(target, parts.toList(), boundsBefore, boundsAfter); push(e); deltaBytes += bytesOf(e); trim()
    }

    /**
     * Layers whose pixels an undo or a redo can still need to bring back: those that leave the stack in a recorded layer operation (undo of a delete) or enter it
     * in an undone one (redo of an add). The session keeps the encoded pixels of exactly these layers and may drop every other graveyard entry.
     */
    fun restorableLayerIds(): Set<String> {
        val out = HashSet<String>()
        fun ids(d: Document) = d.layers.map { it.common.id }.toSet()
        for (e in undo) if (e is Entry.Doc) out += ids(e.before) - ids(e.after)
        for (e in redo) if (e is Entry.Doc) out += ids(e.after) - ids(e.before)
        return out
    }

    /** Replaces the current document without a history entry (the storage layer filling in pixel file names after a save). */
    fun replaceCurrent(doc: Document) { document = doc }

    fun undo(): Step? {
        val e = undo.removeLastOrNull() ?: return null
        redo.addLast(e)
        return when (e) {
            is Entry.Doc -> { document = e.before; Step.SetDocument(e.before) }
            is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.before)
            is Entry.Strokes -> Step.SetPixelsMany(e.layerId, e.parts, useAfter = false)
            is Entry.Plane -> Step.SetPlane(PlaneStep(e.target, e.parts, false, e.boundsBefore))
        }
    }

    fun redo(): Step? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(e)
        return when (e) {
            is Entry.Doc -> { document = e.after; Step.SetDocument(e.after) }
            is Entry.Stroke -> Step.SetPixels(e.layerId, e.delta, e.delta.after)
            is Entry.Strokes -> Step.SetPixelsMany(e.layerId, e.parts, useAfter = true)
            is Entry.Plane -> Step.SetPlane(PlaneStep(e.target, e.parts, true, e.boundsAfter))
        }
    }

    /** Drops the oldest entries until the pixel deltas take at most [maxBytes] (memory pressure). Keeps at least the newest entry. Returns true when anything was dropped. */
    fun trimBytes(maxBytes: Long): Boolean {
        var dropped = false
        while (deltaBytes > maxBytes && undo.size > 1) {
            val e = undo.removeFirst(); dropped = true
            deltaBytes -= bytesOf(e)
        }
        return dropped
    }

    private fun push(e: Entry) {
        undo.addLast(e)
        dropRedo()
        trim()
    }

    private fun dropRedo() { for (e in redo) deltaBytes -= bytesOf(e); redo.clear() }

    private fun trim() {
        while (undo.size > maxEntries || (deltaBytes > maxBytes && undo.size > 1)) {
            val e = undo.removeFirst()
            deltaBytes -= bytesOf(e)
        }
    }
}

package app.rawline.core.studio.model

enum class ColourSpace(val key: String) { SRGB("srgb"), DISPLAY_P3("display-p3") }

/** GAMMA runs the blend formulas on sRGB encoded values (what Photoshop users expect); LINEAR runs them on linear light. */
enum class BlendSpace(val key: String) { GAMMA("gamma"), LINEAR("linear") }

/**
 * What every layer has (spec 2.1, the S1 subset). Pixels live outside the model: [pixelsFile] names the lossless WebP of a pixel
 * layer inside the project directory, or is null for a layer that is still fully transparent. Position and size on the canvas
 * are [x], [y] (document pixels of the top left corner) and [scale] (1 = one layer pixel per document pixel); S2 replaces
 * these three by the 3x3 matrix of the spec without changing the file's meaning.
 */
data class LayerCommon(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    /** 0..100, as the slider shows it. */
    val opacity: Int = 100,
    val blend: BlendMode = BlendMode.NORMAL,
    val x: Int = 0,
    val y: Int = 0,
    val scale: Float = 1f,
)

/** Layer kinds. S1 has pixel layers only; the sealed type is here so S3 onward adds kinds without touching callers that `when` over it. */
sealed class Layer {
    abstract val common: LayerCommon
    abstract fun with(common: LayerCommon): Layer

    data class Pixel(override val common: LayerCommon, val width: Int, val height: Int, val pixelsFile: String? = null) : Layer() {
        override fun with(common: LayerCommon): Layer = copy(common = common)
    }
}

/**
 * A Studio document. [layers] run bottom to top. Immutable: every operation returns a new document, which is what makes undo of
 * layer operations trivial (see [LayerHistory]).
 */
data class Document(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val colourSpace: ColourSpace = ColourSpace.SRGB,
    val blendSpace: BlendSpace = BlendSpace.GAMMA,
    val layers: List<Layer> = emptyList(),
    val created: Long = 0L,
    val modified: Long = 0L,
) {
    init {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE) { "canvas ${width}x$height is outside 1..$MAX_EDGE" }
        require(width.toLong() * height <= MAX_PIXELS_S1) { "canvas ${width}x$height is over the S1 cap of $MAX_PIXELS_S1 pixels" }
        require(layers.map { it.common.id }.toSet().size == layers.size) { "duplicate layer ids" }
    }

    fun layer(id: String): Layer? = layers.firstOrNull { it.common.id == id }
    fun indexOf(id: String): Int = layers.indexOfFirst { it.common.id == id }

    companion object {
        /** S1 canvas cap (spec milestone S1): 12 megapixels, shown in the new project dialog. S2 lifts it with tiles. */
        const val MAX_PIXELS_S1 = 12_000_000L
        const val MAX_EDGE = 8192
        /** S1 layer cap. The spec's 64 arrives with tiles in S2. */
        const val MAX_LAYERS_S1 = 10
    }
}

/** Pure layer operations. Each returns the new document, or throws [IllegalArgumentException] with a message the UI can show. */
object LayerOps {
    private fun edit(d: Document, id: String, f: (LayerCommon) -> LayerCommon): Document {
        val i = d.indexOf(id)
        require(i >= 0) { "no layer $id" }
        val l = d.layers[i]
        return d.copy(layers = d.layers.toMutableList().also { it[i] = l.with(f(l.common)) })
    }

    fun add(d: Document, layer: Layer, at: Int = d.layers.size): Document {
        require(d.layers.size < Document.MAX_LAYERS_S1) { "A project can have ${Document.MAX_LAYERS_S1} layers for now. Delete one to add another." }
        return d.copy(layers = d.layers.toMutableList().also { it.add(at.coerceIn(0, it.size), layer) })
    }

    fun delete(d: Document, id: String): Document {
        require(d.layers.size > 1) { "A project needs at least one layer." }
        require(d.indexOf(id) >= 0) { "no layer $id" }
        return d.copy(layers = d.layers.filter { it.common.id != id })
    }

    /** The copy sits just above the original, named "name copy", with [newId]. Pixels are shared by file name until either is painted on (the storage layer copies on write). */
    fun duplicate(d: Document, id: String, newId: String): Document {
        val i = d.indexOf(id)
        require(i >= 0) { "no layer $id" }
        val src = d.layers[i]
        val copy = src.with(src.common.copy(id = newId, name = src.common.name + " copy"))
        return add(d, copy, i + 1)
    }

    /** Moves a layer to index [to] (0 = bottom). */
    fun move(d: Document, id: String, to: Int): Document {
        val from = d.indexOf(id)
        require(from >= 0) { "no layer $id" }
        val list = d.layers.toMutableList()
        val l = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), l)
        return d.copy(layers = list)
    }

    fun setVisible(d: Document, id: String, visible: Boolean) = edit(d, id) { it.copy(visible = visible) }
    fun setLocked(d: Document, id: String, locked: Boolean) = edit(d, id) { it.copy(locked = locked) }
    fun setOpacity(d: Document, id: String, opacity: Int) = edit(d, id) { it.copy(opacity = opacity.coerceIn(0, 100)) }
    fun setBlend(d: Document, id: String, mode: BlendMode) = edit(d, id) { it.copy(blend = mode) }
    fun rename(d: Document, id: String, name: String) = edit(d, id) { it.copy(name = name.trim().take(40).ifEmpty { it.name }) }
    fun setPlacement(d: Document, id: String, x: Int, y: Int, scale: Float) = edit(d, id) { it.copy(x = x, y = y, scale = scale.coerceIn(0.25f, 4f)) }
}

/** Undo and redo of document states (layer operations; strokes are history entries of the paint layer, S1b). */
class LayerHistory(initial: Document, private val limit: Int = 50) {
    private val undo = ArrayDeque<Document>()
    private val redo = ArrayDeque<Document>()
    var current: Document = initial
        private set

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Records [next] as the new state. Identical state is not recorded (a drag that ended where it started). */
    fun commit(next: Document) {
        if (next == current) return
        undo.addLast(current)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
        current = next
    }

    fun undo(): Document? { val p = undo.removeLastOrNull() ?: return null; redo.addLast(current); current = p; return p }
    fun redo(): Document? { val n = redo.removeLastOrNull() ?: return null; undo.addLast(current); current = n; return n }
}

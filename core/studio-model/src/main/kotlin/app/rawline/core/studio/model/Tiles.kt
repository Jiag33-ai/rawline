package app.rawline.core.studio.model

/** Tile addressing (spec 4.1, 4.4). S1 stores one full-canvas texture per layer; the helpers are here so S2 and the dirty tracking tests share one definition. */
object Tiles {
    const val SIZE = 256

    fun countX(width: Int) = (width + SIZE - 1) / SIZE
    fun countY(height: Int) = (height + SIZE - 1) / SIZE

    /** Pixel rectangle of tile (tx, ty) clipped to the layer: x, y, w, h. */
    fun rect(tx: Int, ty: Int, width: Int, height: Int): IntArray {
        val x = tx * SIZE; val y = ty * SIZE
        return intArrayOf(x, y, minOf(SIZE, width - x), minOf(SIZE, height - y))
    }

    /** Tiles (as tx, ty pairs packed `ty * countX + tx`) that a pixel rectangle touches. */
    fun covering(x: Int, y: Int, w: Int, h: Int, width: Int, height: Int): List<Int> {
        if (w <= 0 || h <= 0) return emptyList()
        val x0 = maxOf(0, x) / SIZE; val y0 = maxOf(0, y) / SIZE
        val x1 = minOf(width - 1, x + w - 1) / SIZE; val y1 = minOf(height - 1, y + h - 1) / SIZE
        if (x1 < x0 || y1 < y0) return emptyList()
        val cx = countX(width)
        return (y0..y1).flatMap { ty -> (x0..x1).map { tx -> ty * cx + tx } }
    }
}

package com.example.soundwaterfall.dsp

/**
 * The waterfall's pixel history, as a single-producer / single-consumer ring
 * (spec §3.1).
 *
 * The audio thread calls [appendRow], which colourises the row and then
 * publishes [newestRow]. The main thread reads [newestRow] once per draw and
 * blits [pixels] as two contiguous slices. There are no locks: the only possible
 * interleaving is the renderer catching the single row currently being written,
 * which smears that one row for one frame and is invisible in practice.
 *
 * Rows are written with a DECREMENTING index so that history ages downward
 * through the array, which is what lets the renderer avoid a vertical flip.
 */
class WaterfallBuffer(
    val width: Int,
    val height: Int,
    private val colorMap: ColorMap,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
    }

    /** Raw ARGB pixels in ring order. The renderer reads this; nothing else writes it. */
    val pixels = IntArray(width * height) { colorMap.floorColor }

    /** Index of the newest row. Published after that row is fully written. */
    @Volatile
    var newestRow: Int = 0
        private set

    /** Rows from [newestRow] to the end of the array, drawn at destination y = 0. */
    val topSliceHeight: Int get() = height - newestRow

    /**
     * Colourises [columns] and stores it as the newest row.
     *
     * @param columns at least [width] dB values, already reduced to pixel columns
     */
    fun appendRow(columns: FloatArray, dbFloor: Float) {
        require(columns.size >= width) {
            "columns must hold at least $width values, had ${columns.size}"
        }
        val row = if (newestRow == 0) height - 1 else newestRow - 1
        val base = row * width
        for (x in 0 until width) {
            pixels[base + x] = colorMap.color(columns[x], dbFloor)
        }
        // Publish only once the row is complete.
        newestRow = row
    }

    fun clear() {
        pixels.fill(colorMap.floorColor)
        newestRow = 0
    }
}

package com.example.soundwaterfall.dsp

import java.util.concurrent.atomic.AtomicLong

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

    /**
     * Monotonic count of rows written, so the renderer can skip a vsync when
     * nothing new has arrived. Measured on the API 21 device: blitting on every
     * vsync regardless put 100% of frames over the 16.7 ms budget.
     *
     * Atomic rather than merely volatile because [clear] is a SECOND writer, on
     * the main thread: a plain `+=` there races the audio thread's increment,
     * and a lost update would stop the renderer ever seeing the full-upload
     * trigger, leaving rows colourised under the previous dB floor on screen —
     * the silent mixing of two mappings that spec §6.5 forbids.
     */
    private val rowCounter = AtomicLong(0)

    val rowsAppended: Long get() = rowCounter.get()

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
        rowCounter.incrementAndGet()
    }

    /**
     * Called on the main thread while the audio thread may be inside
     * [appendRow]. The counter is advanced by a full screen — atomically — so a
     * renderer doing delta uploads sees more new rows than it has height for and
     * falls back to a full upload, which is what guarantees no row survives the
     * clear still coloured by the old dB floor.
     */
    fun clear() {
        pixels.fill(colorMap.floorColor)
        newestRow = 0
        rowCounter.addAndGet(height.toLong())
    }
}

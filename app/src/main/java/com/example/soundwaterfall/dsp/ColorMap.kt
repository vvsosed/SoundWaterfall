package com.example.soundwaterfall.dsp

/**
 * dB -> packed ARGB, through a precomputed 256-entry lookup table.
 *
 * Default stops are Inferno (spec §6.4), chosen because its luminance rises
 * monotonically — equal dB steps look like equal brightness steps — and because
 * its near-black floor separates "nothing there" from "something faint there".
 *
 * The LUT is built once; [color] is a divide, a cast and an array read, which is
 * what makes it cheap enough to run on the audio thread for every pixel of every
 * row (spec §3.1).
 */
class ColorMap(stops: IntArray = INFERNO_STOPS) {

    private val lut = IntArray(SIZE)

    init {
        require(stops.size >= 2) { "need at least two stops, got ${stops.size}" }
        val last = stops.size - 1
        for (i in 0 until SIZE) {
            val t = i.toFloat() / (SIZE - 1) * last
            val lo = t.toInt().coerceAtMost(stops.size - 2)
            val f = t - lo
            val a = stops[lo]
            val b = stops[lo + 1]
            val r = lerp((a shr 16) and 0xFF, (b shr 16) and 0xFF, f)
            val g = lerp((a shr 8) and 0xFF, (b shr 8) and 0xFF, f)
            val bl = lerp(a and 0xFF, b and 0xFF, f)
            lut[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    val floorColor: Int get() = lut[0]

    fun colorAt(index: Int): Int = lut[index.coerceIn(0, SIZE - 1)]

    /**
     * Maps [db] onto a colour, with [dbFloor] at index 0 and 0 dBFS at the top.
     *
     * Hardened against the values that actually arrive from a silent microphone:
     * NaN and -Infinity both cast to a clamped index rather than throwing.
     */
    fun color(db: Float, dbFloor: Float): Int {
        val range = -dbFloor
        if (range <= 0f) return lut[0]
        // Float.NaN.toInt() == 0 and (-Inf).toInt() == Int.MIN_VALUE, so the
        // coerceIn below turns every non-finite input into a valid index.
        val index = ((db - dbFloor) / range * (SIZE - 1)).toInt()
        return lut[index.coerceIn(0, SIZE - 1)]
    }

    private fun lerp(a: Int, b: Int, f: Float): Int =
        (a + (b - a) * f).toInt().coerceIn(0, 255)

    companion object {
        const val SIZE = 256

        /** Inferno, spec §6.4. */
        val INFERNO_STOPS = intArrayOf(
            0xFF000004.toInt(), 0xFF1B0C41.toInt(), 0xFF4A0C6B.toInt(), 0xFF781C6D.toInt(),
            0xFFA52C60.toInt(), 0xFFCF4446.toInt(), 0xFFED6925.toInt(), 0xFFFB9B06.toInt(),
            0xFFF7D13D.toInt(), 0xFFFCFFA4.toInt(),
        )
    }
}

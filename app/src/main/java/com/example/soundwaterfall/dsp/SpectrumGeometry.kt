package com.example.soundwaterfall.dsp

/**
 * The spectrum chart's vertical mapping, kept out of the View so it can be
 * tested on the JVM.
 */
object SpectrumGeometry {

    /**
     * dB spacing between horizontal gridlines (spec §6.2). A fixed step cannot
     * keep labels round across a floor that ranges from -40 to -120, so the step
     * widens for the larger ranges.
     */
    fun gridlineStepDb(dbFloor: Float): Int = if (-dbFloor <= 60f) 10 else 20

    /**
     * Maps a dB value onto a pixel row: 0 dBFS at the top, [dbFloor] at the
     * bottom. Hardened against a zero height before first layout, and against
     * the NaN / -Infinity that a silent microphone produces upstream.
     */
    fun dbToY(db: Float, dbFloor: Float, heightPx: Int): Float {
        if (heightPx <= 0) return 0f
        val range = -dbFloor
        if (range <= 0f) return heightPx.toFloat()
        if (db.isNaN()) return heightPx.toFloat()
        val t = ((db - dbFloor) / range).coerceIn(0f, 1f)
        return (1f - t) * heightPx
    }
}

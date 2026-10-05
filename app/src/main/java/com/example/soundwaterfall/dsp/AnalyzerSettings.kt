package com.example.soundwaterfall.dsp

/**
 * The three runtime knobs (spec §6.5), as one immutable value so the audio
 * thread can swap them atomically at a frame boundary.
 */
data class AnalyzerSettings(
    val fftSize: Int,
    val window: WindowFunction,
    val dbFloor: Float,
) {
    /** Fixed 50% overlap (spec §3.2). */
    val hop: Int get() = fftSize / 2

    val binCount: Int get() = fftSize / 2 + 1

    init {
        require(fftSize in FFT_SIZES) { "fftSize must be one of ${FFT_SIZES.toList()}, was $fftSize" }
        require(dbFloor in DB_FLOOR_MIN..DB_FLOOR_MAX) {
            "dbFloor must be in $DB_FLOOR_MIN..$DB_FLOOR_MAX, was $dbFloor"
        }
    }

    companion object {
        val FFT_SIZES = intArrayOf(1024, 2048, 4096)
        const val DB_FLOOR_MIN = -120f
        const val DB_FLOOR_MAX = -40f
        const val DB_FLOOR_STEP = 10f

        val DEFAULT = AnalyzerSettings(
            fftSize = 2048,
            window = WindowFunction.HANN,
            dbFloor = -90f,
        )
    }
}

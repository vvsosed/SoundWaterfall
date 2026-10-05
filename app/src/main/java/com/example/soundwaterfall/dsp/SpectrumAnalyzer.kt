package com.example.soundwaterfall.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * frame -> windowed -> transformed -> normalized -> dBFS.
 *
 * Normalization (spec §5.4) divides magnitudes by `(N/2) * coherentGain`, which
 * makes a full-scale sine read 0 dBFS for every window and every FFT size. DC
 * and Nyquist are single-sided and are halved first so they land on the same
 * scale as the sinusoidal bins.
 *
 * Allocates nothing per frame: every buffer is a field. Not thread-safe; owned
 * by the audio thread.
 */
class SpectrumAnalyzer(
    private val engine: FftEngine,
    window: WindowFunction,
    var dbFloor: Float,
) {
    val binCount: Int = engine.binCount

    private val work = DoubleArray(engine.size)
    private val mags = FloatArray(binCount)

    private var window: WindowFunction = window
    private var coefficients: FloatArray = window.coefficients(engine.size)

    /** Swaps the coefficient table. No reallocation of the FFT or work buffers. */
    fun setWindow(w: WindowFunction) {
        window = w
        coefficients = w.coefficients(engine.size)
    }

    fun analyze(frame: ShortArray, out: FloatArray) {
        require(frame.size == engine.size) {
            "frame must be ${engine.size} samples, was ${frame.size}"
        }
        require(out.size == binCount) { "out must be $binCount bins, was ${out.size}" }

        for (n in work.indices) {
            work[n] = frame[n] / 32768.0 * coefficients[n]
        }

        engine.magnitudes(work, mags)

        // DC and Nyquist are single-sided: halve them onto the sinusoidal scale.
        mags[0] *= 0.5f
        mags[binCount - 1] *= 0.5f

        val norm = (engine.size / 2.0f) * window.coherentGain
        val floor = dbFloor
        for (k in 0 until binCount) {
            val mag = mags[k] / norm
            val db = 20.0f * log10(max(mag, 1e-10f))
            out[k] = if (db < floor) floor else if (db > 0f) 0f else db
        }
    }
}

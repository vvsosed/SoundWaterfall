package com.example.soundwaterfall.dsp

import kotlin.math.PI
import kotlin.math.cos

/**
 * Analysis windows in their periodic (DFT-even) form, i.e. the argument is
 * 2*pi*n/N rather than 2*pi*n/(N-1). The periodic form is the correct one for
 * spectral analysis, and it is what makes [coherentGain] come out as the exact
 * constants below.
 *
 * [coherentGain] is the mean of the coefficients. Spec §5.4 divides magnitudes
 * by (N/2) * coherentGain so that a full-scale sine reads 0 dBFS regardless of
 * which window is selected.
 */
enum class WindowFunction(val label: String, val coherentGain: Float) {
    RECTANGULAR("Rectangular", 1.00f),
    HANN("Hann", 0.50f),
    HAMMING("Hamming", 0.54f),
    BLACKMAN("Blackman", 0.42f);

    fun coefficients(size: Int): FloatArray {
        require(size > 0) { "window size must be positive, was $size" }
        val out = FloatArray(size)
        val t = 2.0 * PI / size
        for (n in 0 until size) {
            out[n] = when (this) {
                RECTANGULAR -> 1.0f
                HANN -> (0.5 - 0.5 * cos(t * n)).toFloat()
                HAMMING -> (0.54 - 0.46 * cos(t * n)).toFloat()
                BLACKMAN -> (0.42 - 0.5 * cos(t * n) + 0.08 * cos(2.0 * t * n)).toFloat()
            }
        }
        return out
    }
}

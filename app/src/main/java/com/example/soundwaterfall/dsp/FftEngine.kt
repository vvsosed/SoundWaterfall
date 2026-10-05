package com.example.soundwaterfall.dsp

/**
 * A real-input forward FFT, reduced to the one operation this app needs.
 *
 * The interface exists so the library choice stays a swappable detail (spec §11)
 * and so the magnitude contract can be tested without reference to any
 * particular library's packed output layout.
 */
interface FftEngine {

    /** Transform length. Always a power of two. */
    val size: Int

    /** Number of usable bins, `size / 2 + 1`, covering DC through Nyquist. */
    val binCount: Int

    /**
     * Reads [work] and writes [binCount] raw, un-normalized magnitudes into
     * [out]. Implementations are permitted to destroy [work]'s contents — the
     * current one does not, but callers must refill it before every call rather
     * than relying on that.
     *
     * Allocates nothing: both arrays are supplied by the caller.
     *
     * @param work exactly [size] samples, already windowed
     * @param out exactly [binCount] elements
     */
    fun magnitudes(work: DoubleArray, out: FloatArray)
}

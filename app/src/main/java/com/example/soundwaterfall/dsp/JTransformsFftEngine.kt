package com.example.soundwaterfall.dsp

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [FftEngine] backed by JTransforms.
 *
 * `realForward` packs its result into the input array. For even N:
 *
 *     work[0]         = Re[0]        (DC)
 *     work[1]         = Re[N/2]      (Nyquist — NOT at the end of the array)
 *     work[2k]        = Re[k]
 *     work[2k + 1]    = Im[k]        for 1 <= k < N/2
 *
 * That Nyquist placement is the detail worth the dedicated test in FftEngineTest.
 */
class JTransformsFftEngine(override val size: Int) : FftEngine {

    init {
        require(size >= 2 && size and (size - 1) == 0) {
            "size must be a power of two >= 2, was $size"
        }
    }

    override val binCount: Int = size / 2 + 1

    // DoubleFFT_1D's constructor takes a long; Kotlin does not widen Int implicitly.
    private val fft = DoubleFFT_1D(size.toLong())

    override fun magnitudes(work: DoubleArray, out: FloatArray) {
        require(work.size == size) { "work must be $size samples, was ${work.size}" }
        require(out.size == binCount) { "out must be $binCount bins, was ${out.size}" }

        fft.realForward(work)

        val half = size / 2
        out[0] = abs(work[0]).toFloat()
        out[half] = abs(work[1]).toFloat()
        for (k in 1 until half) {
            val re = work[2 * k]
            val im = work[2 * k + 1]
            out[k] = sqrt(re * re + im * im).toFloat()
        }
    }
}

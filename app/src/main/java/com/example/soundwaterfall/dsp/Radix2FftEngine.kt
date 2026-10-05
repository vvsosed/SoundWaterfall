package com.example.soundwaterfall.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Iterative radix-2 Cooley–Tukey FFT.
 *
 * This replaces JTransforms, which spec §11 flagged as a risk and Task 0 of the
 * implementation plan rejected on measured evidence: JTransforms depends on
 * JLargeArrays, which references `sun.misc.Cleaner` and
 * `com.sun.xml.internal.ws.encoding.soap.SerializationException` — classes that
 * do not exist on Android — and drags in commons-math3, taking the debug APK to
 * 74,649 method references across six dex files. That multidex APK could not
 * load its own classes on the API 21 test device.
 *
 * The real input is transformed as a length-N complex FFT with zero imaginary
 * parts. That is twice the arithmetic of a packed real transform, but it is a
 * few hundred microseconds at N = 4096 and removes every one of the problems
 * above. Magnitudes are symmetric, so the sign convention of the twiddle factors
 * does not affect the result.
 *
 * Allocates nothing per call: the scratch arrays, the twiddle tables and the
 * bit-reversal permutation are all precomputed fields.
 */
class Radix2FftEngine(override val size: Int) : FftEngine {

    init {
        require(size >= 2 && size and (size - 1) == 0) {
            "size must be a power of two >= 2, was $size"
        }
    }

    override val binCount: Int = size / 2 + 1

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /** Twiddle factors for e^(-i*2*pi*k/size), k in 0 until size/2. */
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)

    /** Bit-reversed index permutation, applied while loading the input. */
    private val reversed = IntArray(size)

    init {
        for (k in 0 until size / 2) {
            val angle = 2.0 * PI * k / size
            cosTable[k] = cos(angle)
            sinTable[k] = sin(angle)
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            reversed[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    override fun magnitudes(work: DoubleArray, out: FloatArray) {
        require(work.size == size) { "work must be $size samples, was ${work.size}" }
        require(out.size == binCount) { "out must be $binCount bins, was ${out.size}" }

        for (i in 0 until size) {
            re[i] = work[reversed[i]]
            im[i] = 0.0
        }

        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var base = 0
            while (base < size) {
                var k = 0
                for (j in base until base + half) {
                    val c = cosTable[k]
                    val s = -sinTable[k]
                    val pr = re[j + half]
                    val pi = im[j + half]
                    val tr = pr * c - pi * s
                    val ti = pr * s + pi * c
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                base += len
            }
            len = len shl 1
        }

        for (b in 0 until binCount) {
            out[b] = sqrt(re[b] * re[b] + im[b] * im[b]).toFloat()
        }
    }
}

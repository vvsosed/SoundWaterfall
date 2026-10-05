package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class FftEngineTest {

    private fun engine(size: Int): FftEngine = Radix2FftEngine(size)

    @Test
    fun binCountIsHalfSizePlusOne() {
        assertEquals(1025, engine(2048).binCount)
        assertEquals(513, engine(1024).binCount)
        assertEquals(2049, engine(4096).binCount)
    }

    /** A constant signal puts all of its energy in bin 0, with magnitude N. */
    @Test
    fun dcGoesEntirelyIntoBinZero() {
        val n = 1024
        val e = engine(n)
        val work = DoubleArray(n) { 1.0 }
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        assertEquals(n.toFloat(), out[0], 1e-2f)
        for (k in 1 until e.binCount) {
            assertEquals("bin $k should be empty", 0.0f, out[k], 1e-2f)
        }
    }

    /** A unit impulse has a flat spectrum: every bin has magnitude 1. */
    @Test
    fun impulseGivesFlatMagnitude() {
        val n = 256
        val e = engine(n)
        val work = DoubleArray(n)
        work[0] = 1.0
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        for (k in 0 until e.binCount) {
            assertEquals("bin $k", 1.0f, out[k], 1e-4f)
        }
    }

    /** A cosine at an exact bin centre lands in that bin with magnitude N/2. */
    @Test
    fun cosineAtBinCentreLandsInThatBin() {
        val n = 2048
        val bin = 43
        val e = engine(n)
        val work = DoubleArray(n) { cos(2.0 * PI * bin * it / n) }
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        assertEquals((n / 2).toFloat(), out[bin], 1.0f)
        assertEquals("bin below the peak", 0.0f, out[bin - 1], 1.0f)
        assertEquals("bin above the peak", 0.0f, out[bin + 1], 1.0f)
    }

    /**
     * The Nyquist bin is the one most likely to be mis-unpacked, because the
     * library stores Re[N/2] in work[1] rather than at the end of the array.
     */
    @Test
    fun nyquistGoesIntoTheLastBin() {
        val n = 512
        val e = engine(n)
        val work = DoubleArray(n) { cos(PI * it) }   // alternating +1, -1
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        assertEquals(n.toFloat(), out[n / 2], 1e-2f)
        for (k in 0 until n / 2) {
            assertEquals("bin $k should be empty", 0.0f, out[k], 1e-2f)
        }
    }

    @Test
    fun silenceGivesZeroEverywhere() {
        val e = engine(256)
        val out = FloatArray(e.binCount) { 123f }
        e.magnitudes(DoubleArray(256), out)
        out.forEach { assertEquals(0.0f, it, 1e-9f) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwoSize() {
        engine(1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWorkArrayOfTheWrongLength() {
        val e = engine(256)
        e.magnitudes(DoubleArray(128), FloatArray(e.binCount))
    }
}

package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowFunctionTest {

    @Test
    fun rectangularIsAllOnes() {
        val c = WindowFunction.RECTANGULAR.coefficients(8)
        assertEquals(8, c.size)
        c.forEach { assertEquals(1.0f, it, 1e-6f) }
    }

    @Test
    fun hannStartsAtZeroAndPeaksAtOne() {
        val c = WindowFunction.HANN.coefficients(1024)
        assertEquals(0.0f, c[0], 1e-6f)
        assertEquals(1.0f, c[512], 1e-6f)
    }

    @Test
    fun hammingStartsAtPedestalAndPeaksAtOne() {
        val c = WindowFunction.HAMMING.coefficients(1024)
        assertEquals(0.08f, c[0], 1e-6f)
        assertEquals(1.0f, c[512], 1e-6f)
    }

    @Test
    fun blackmanStartsAtZeroAndPeaksAtOne() {
        val c = WindowFunction.BLACKMAN.coefficients(1024)
        assertEquals(0.0f, c[0], 1e-6f)
        assertEquals(1.0f, c[512], 1e-6f)
    }

    /**
     * Spec §5.3. The declared coherentGain must equal the actual mean of the
     * coefficients, because spec §5.4's normalization divides by it. If these
     * ever disagree, the "0 dBFS on every window" invariant breaks silently.
     */
    @Test
    fun declaredCoherentGainEqualsActualMean() {
        for (w in WindowFunction.entries) {
            val c = w.coefficients(2048)
            val mean = c.fold(0.0) { acc, v -> acc + v } / c.size
            assertEquals("coherentGain for $w", w.coherentGain.toDouble(), mean, 1e-6)
        }
    }
}

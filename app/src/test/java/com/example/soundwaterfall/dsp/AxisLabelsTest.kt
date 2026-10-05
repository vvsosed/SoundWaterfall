package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class AxisLabelsTest {

    @Test
    fun dcIsPlainZero() {
        assertEquals("0", AxisLabels.format(0))
    }

    @Test
    fun wholeKilohertzDropTheDecimal() {
        assertEquals("4k", AxisLabels.format(4000))
        assertEquals("12k", AxisLabels.format(12000))
        assertEquals("24k", AxisLabels.format(24000))
    }

    @Test
    fun subKilohertzKeepsTheHertzValue() {
        assertEquals("500", AxisLabels.format(500))
        assertEquals("999", AxisLabels.format(999))
    }

    /** 44.1 kHz capture puts Nyquist at 22050, which is not a whole kilohertz. */
    @Test
    fun fractionalKilohertzGetsOneDecimal() {
        assertEquals("22.1k", AxisLabels.format(22050))
        assertEquals("1.5k", AxisLabels.format(1500))
    }

    @Test
    fun negativeInputIsClampedToZero() {
        assertEquals("0", AxisLabels.format(-100))
    }
}

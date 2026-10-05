package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumGeometryTest {

    /**
     * Spec §6.2: the step must keep labels on round numbers across the whole
     * adjustable floor range of -120..-40, which a fixed step cannot do.
     */
    @Test
    fun gridlineStepKeepsLabelsRoundAcrossTheWholeFloorRange() {
        assertEquals(10, SpectrumGeometry.gridlineStepDb(-40f))
        assertEquals(10, SpectrumGeometry.gridlineStepDb(-50f))
        assertEquals(10, SpectrumGeometry.gridlineStepDb(-60f))
        assertEquals(20, SpectrumGeometry.gridlineStepDb(-70f))
        assertEquals(20, SpectrumGeometry.gridlineStepDb(-90f))
        assertEquals(20, SpectrumGeometry.gridlineStepDb(-120f))
    }

    @Test
    fun everyFloorValueYieldsAtLeastThreeGridlines() {
        var floor = -120f
        while (floor <= -40f) {
            val step = SpectrumGeometry.gridlineStepDb(floor)
            val lines = (-floor / step).toInt()
            assertTrue("floor $floor gave only $lines gridlines", lines >= 3)
            floor += 10f
        }
    }

    @Test
    fun zeroDbfsIsAtTheTopAndTheFloorIsAtTheBottom() {
        assertEquals(0f, SpectrumGeometry.dbToY(0f, -90f, 400), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(-90f, -90f, 400), 1e-4f)
    }

    @Test
    fun midScaleIsHalfway() {
        assertEquals(200f, SpectrumGeometry.dbToY(-45f, -90f, 400), 1e-4f)
    }

    @Test
    fun followsTheConfiguredFloor() {
        assertEquals(200f, SpectrumGeometry.dbToY(-30f, -60f, 400), 1e-4f)
    }

    @Test
    fun clampsValuesBeyondTheRange() {
        assertEquals(0f, SpectrumGeometry.dbToY(20f, -90f, 400), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(-500f, -90f, 400), 1e-4f)
    }

    /** REVIEW FOCUS 3 and 4: pre-layout height, and non-finite dB from silence. */
    @Test
    fun zeroHeightAndNonFiniteInputsDoNotThrow() {
        assertEquals(0f, SpectrumGeometry.dbToY(-45f, -90f, 0), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(Float.NEGATIVE_INFINITY, -90f, 400), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(Float.NaN, -90f, 400), 1e-4f)
    }

    @Test
    fun nonNegativeFloorDoesNotDivideByZero() {
        assertEquals(400f, SpectrumGeometry.dbToY(-10f, 0f, 400), 1e-4f)
    }
}

package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMapTest {

    private val map = ColorMap()

    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * The reason Inferno was chosen over jet/turbo (spec §6.4): equal dB steps
     * must look like equal brightness steps. A palette whose luminance dips and
     * rises invents contour bands that are not in the signal.
     */
    @Test
    fun luminanceRisesMonotonically() {
        for (i in 1 until ColorMap.SIZE) {
            val prev = luminance(map.colorAt(i - 1))
            val cur = luminance(map.colorAt(i))
            assertTrue(
                "luminance dropped between index ${i - 1} ($prev) and $i ($cur)",
                cur >= prev - 1e-6,
            )
        }
    }

    @Test
    fun floorIsNearBlackAndCeilingIsBright() {
        assertTrue("floor luminance ${luminance(map.colorAt(0))}", luminance(map.colorAt(0)) < 5.0)
        assertTrue("top luminance ${luminance(map.colorAt(255))}", luminance(map.colorAt(255)) > 200.0)
    }

    @Test
    fun everyEntryIsFullyOpaque() {
        for (i in 0 until ColorMap.SIZE) {
            assertEquals("alpha at $i", 0xFF, (map.colorAt(i) shr 24) and 0xFF)
        }
    }

    @Test
    fun mapsTheFloorToIndexZeroAndZeroDbToTheTop() {
        assertEquals(map.colorAt(0), map.color(-90f, -90f))
        assertEquals(map.colorAt(255), map.color(0f, -90f))
    }

    @Test
    fun followsTheConfiguredFloor() {
        // The same dB value sits at different points depending on the floor.
        assertNotEquals(map.color(-45f, -90f), map.color(-45f, -60f))
        assertEquals(map.colorAt(0), map.color(-60f, -60f))
        assertEquals(map.colorAt(0), map.color(-120f, -120f))
    }

    @Test
    fun clampsValuesOutsideTheRange() {
        assertEquals(map.colorAt(0), map.color(-500f, -90f))
        assertEquals(map.colorAt(255), map.color(50f, -90f))
    }

    /**
     * REVIEW FOCUS 4. Silence makes log10(0) = -Infinity upstream. Whatever
     * arrives here must become a valid index, never an exception.
     */
    @Test
    fun negativeInfinityNaNAndInfinityAllProduceValidColours() {
        assertEquals(map.colorAt(0), map.color(Float.NEGATIVE_INFINITY, -90f))
        assertEquals(map.colorAt(0), map.color(Float.NaN, -90f))
        assertEquals(map.colorAt(255), map.color(Float.POSITIVE_INFINITY, -90f))
    }

    /**
     * REVIEW FOCUS 4. dbFloor of 0 would make the index divide by zero. The
     * settings type forbids it, but nothing stops a caller from passing it.
     */
    @Test
    fun zeroOrPositiveFloorDoesNotDivideByZero() {
        assertEquals(map.colorAt(0), map.color(-10f, 0f))
        assertEquals(map.colorAt(0), map.color(-10f, 5f))
    }

    @Test
    fun colorAtClampsOutOfRangeIndices() {
        assertEquals(map.colorAt(0), map.colorAt(-7))
        assertEquals(map.colorAt(255), map.colorAt(9999))
    }

    @Test
    fun floorColorMatchesIndexZero() {
        assertEquals(map.colorAt(0), map.floorColor)
    }

    @Test
    fun interpolatesBetweenStopsRatherThanBanding() {
        // 256 entries from 10 stops must produce far more than 10 distinct colours.
        val distinct = (0 until ColorMap.SIZE).map { map.colorAt(it) }.toSet()
        assertTrue("only ${distinct.size} distinct colours", distinct.size > 200)
    }
}

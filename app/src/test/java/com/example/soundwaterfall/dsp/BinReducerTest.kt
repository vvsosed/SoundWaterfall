package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinReducerTest {

    /**
     * Spec §6.2: when bins outnumber columns the reduction takes the MAXIMUM,
     * not the mean. A narrow peak occupying one bin out of 1025 must survive
     * being squeezed into 720 columns — averaging would bury it.
     */
    @Test
    fun narrowPeakSurvivesDownsampling() {
        val db = FloatArray(1025) { -90f }
        db[500] = -10f
        val out = FloatArray(720)

        BinReducer.reduce(db, out)

        assertEquals("peak must be preserved exactly", -10f, out.max(), 1e-4f)
        assertEquals("only one column should hold it", 1, out.count { it > -89f })
    }

    @Test
    fun downsamplingCoversEveryBinWithNoGaps() {
        // Every bin is a distinct peak; every one must appear in some column.
        val db = FloatArray(16) { it.toFloat() }
        val out = FloatArray(4)
        BinReducer.reduce(db, out)
        // 16 bins into 4 columns: maxima of [0..3], [4..7], [8..11], [12..15]
        assertEquals(3f, out[0], 1e-4f)
        assertEquals(7f, out[1], 1e-4f)
        assertEquals(11f, out[2], 1e-4f)
        assertEquals(15f, out[3], 1e-4f)
    }

    /**
     * REVIEW FOCUS 1. At N=1024 there are 513 bins, and the test device is
     * 720 px wide. More columns than bins is therefore the DEFAULT case on the
     * real hardware, not an edge case, so the interpolation path must be right.
     */
    @Test
    fun interpolatesWhenColumnsOutnumberBins() {
        val db = FloatArray(513) { it.toFloat() }
        val out = FloatArray(720)

        BinReducer.reduce(db, out)

        assertEquals("first column is the first bin", 0f, out[0], 1e-4f)
        assertEquals("last column is the last bin", 512f, out[719], 1e-3f)
        for (c in 1 until out.size) {
            assertTrue("column $c must not decrease", out[c] >= out[c - 1] - 1e-4f)
        }
    }

    @Test
    fun interpolationIsLinearBetweenNeighbouringBins() {
        val db = floatArrayOf(0f, 10f)
        val out = FloatArray(3)
        BinReducer.reduce(db, out)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(5f, out[1], 1e-4f)
        assertEquals(10f, out[2], 1e-4f)
    }

    @Test
    fun equalSizesCopyThroughUnchanged() {
        val db = FloatArray(8) { it * -3f }
        val out = FloatArray(8)
        BinReducer.reduce(db, out)
        for (i in db.indices) assertEquals(db[i], out[i], 1e-5f)
    }

    /** REVIEW FOCUS 3: pre-layout views report zero width. */
    @Test
    fun zeroLengthArraysDoNothingAndDoNotThrow() {
        BinReducer.reduce(FloatArray(0), FloatArray(0))
        BinReducer.reduce(FloatArray(1025) { -90f }, FloatArray(0))
        BinReducer.reduce(FloatArray(0), FloatArray(720))
    }

    @Test
    fun singleColumnTakesTheMaximumOfEverything() {
        val db = FloatArray(1025) { -90f }
        db[77] = -5f
        val out = FloatArray(1)
        BinReducer.reduce(db, out)
        assertEquals(-5f, out[0], 1e-4f)
    }

    @Test
    fun singleBinFillsEveryColumn() {
        val out = FloatArray(5)
        BinReducer.reduce(floatArrayOf(-42f), out)
        out.forEach { assertEquals(-42f, it, 1e-4f) }
    }
}

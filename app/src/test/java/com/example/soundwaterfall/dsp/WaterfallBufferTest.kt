package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterfallBufferTest {

    private val colorMap = ColorMap()

    private fun buffer(width: Int = 4, height: Int = 4) =
        WaterfallBuffer(width, height, colorMap)

    private fun row(buf: WaterfallBuffer, y: Int): IntArray =
        IntArray(buf.width) { buf.pixels[y * buf.width + it] }

    @Test
    fun startsEntirelyAtTheFloorColour() {
        val buf = buffer()
        buf.pixels.forEach { assertEquals(colorMap.floorColor, it) }
    }

    /** The first row written must be the LAST array row, so the index can decrement. */
    @Test
    fun firstAppendLandsAtTheBottomOfTheArray() {
        val buf = buffer(height = 4)
        buf.appendRow(FloatArray(4) { 0f }, -90f)
        assertEquals(3, buf.newestRow)
    }

    @Test
    fun successiveAppendsDecrementTheIndex() {
        val buf = buffer(height = 4)
        val cols = FloatArray(4) { 0f }
        buf.appendRow(cols, -90f); assertEquals(3, buf.newestRow)
        buf.appendRow(cols, -90f); assertEquals(2, buf.newestRow)
        buf.appendRow(cols, -90f); assertEquals(1, buf.newestRow)
        buf.appendRow(cols, -90f); assertEquals(0, buf.newestRow)
    }

    @Test
    fun indexWrapsAroundAfterAFullHeight() {
        val buf = buffer(height = 4)
        val cols = FloatArray(4) { 0f }
        repeat(4) { buf.appendRow(cols, -90f) }
        assertEquals(0, buf.newestRow)
        buf.appendRow(cols, -90f)
        assertEquals("must wrap back to the bottom", 3, buf.newestRow)
        buf.appendRow(cols, -90f)
        assertEquals(2, buf.newestRow)
    }

    @Test
    fun writesTheColourisedRowAtTheNewestIndex() {
        val buf = buffer(width = 3, height = 4)
        buf.appendRow(floatArrayOf(0f, -45f, -90f), -90f)

        assertEquals(3, buf.newestRow)
        val written = row(buf, 3)
        assertEquals(colorMap.color(0f, -90f), written[0])
        assertEquals(colorMap.color(-45f, -90f), written[1])
        assertEquals(colorMap.color(-90f, -90f), written[2])
    }

    @Test
    fun olderRowsAreLeftUntouched() {
        val buf = buffer(width = 2, height = 4)
        buf.appendRow(floatArrayOf(0f, 0f), -90f)       // -> row 3
        buf.appendRow(floatArrayOf(-90f, -90f), -90f)   // -> row 2

        val old = row(buf, 3)
        assertEquals(colorMap.color(0f, -90f), old[0])
        assertEquals(colorMap.color(0f, -90f), old[1])
    }

    @Test
    fun theTwoRenderSlicesCoverTheWholeHeightExactly() {
        val buf = buffer(height = 8)
        val cols = FloatArray(4) { 0f }
        repeat(5) { buf.appendRow(cols, -90f) }
        assertEquals(8 - buf.newestRow, buf.topSliceHeight)
        assertEquals(8, buf.topSliceHeight + buf.newestRow)
    }

    /**
     * The renderer skips a vsync when no new row has arrived; measured on the
     * API 21 device, blitting every vsync regardless put 100% of frames over
     * the 16.7 ms budget.
     */
    @Test
    fun rowsAppendedCountsEveryAppendSoTheRendererCanSkipIdleFrames() {
        val buf = buffer(height = 4)
        val cols = FloatArray(4) { 0f }
        assertEquals(0L, buf.rowsAppended)
        buf.appendRow(cols, -90f)
        assertEquals(1L, buf.rowsAppended)
        repeat(9) { buf.appendRow(cols, -90f) }
        assertEquals("must keep counting across a wrap", 10L, buf.rowsAppended)
    }

    @Test
    fun clearResetsTheRowCountSoTheRendererRedrawsTheClearedBuffer() {
        val buf = buffer(height = 4)
        buf.appendRow(FloatArray(4) { 0f }, -90f)
        val before = buf.rowsAppended
        buf.clear()
        assertNotEquals("clear must invalidate the renderer's cached count", before, buf.rowsAppended)
    }

    @Test
    fun clearResetsEverythingToTheFloorColourAndTheIndex() {
        val buf = buffer(height = 4)
        buf.appendRow(FloatArray(4) { 0f }, -90f)
        buf.appendRow(FloatArray(4) { 0f }, -90f)
        buf.clear()
        assertEquals(0, buf.newestRow)
        buf.pixels.forEach { assertEquals(colorMap.floorColor, it) }
    }

    @Test
    fun honoursTheConfiguredFloorWhenColourising() {
        val buf = buffer(width = 1, height = 2)
        buf.appendRow(floatArrayOf(-60f), -60f)
        assertEquals(colorMap.floorColor, row(buf, 1)[0])
    }

    /** REVIEW FOCUS 3: a view reports width/height 0 before it has been laid out. */
    @Test
    fun rejectsZeroOrNegativeDimensions() {
        for (dims in listOf(0 to 4, 4 to 0, 0 to 0, -1 to 4, 4 to -1)) {
            try {
                WaterfallBuffer(dims.first, dims.second, colorMap)
                throw AssertionError("expected rejection for $dims")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAColumnArrayShorterThanTheWidth() {
        buffer(width = 8, height = 2).appendRow(FloatArray(4), -90f)
    }

    @Test
    fun acceptsSilenceWithoutProducingInvalidPixels() {
        val buf = buffer(width = 4, height = 2)
        buf.appendRow(FloatArray(4) { Float.NEGATIVE_INFINITY }, -90f)
        row(buf, 1).forEach { assertEquals(colorMap.floorColor, it) }
    }
}

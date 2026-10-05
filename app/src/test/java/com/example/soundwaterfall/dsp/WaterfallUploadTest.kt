package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delta-upload arithmetic from WaterfallView, extracted so the torn-read
 * case can be tested on the JVM rather than argued about.
 */
class WaterfallUploadTest {

    private fun plan(newest: Int, rows: Long, uploaded: Long, height: Int) =
        WaterfallUpload.plan(newest, rows, uploaded, height)

    @Test
    fun firstDrawUploadsEverything() {
        val p = plan(newest = 0, rows = 0, uploaded = -1, height = 8)
        assertTrue(p.full)
    }

    @Test
    fun noNewRowsUploadsNothing() {
        val p = plan(newest = 3, rows = 5, uploaded = 5, height = 8)
        assertTrue(!p.full)
        assertEquals(0, p.headCount)
        assertEquals(0, p.tailCount)
    }

    @Test
    fun oneNewRowUploadsJustThatRow() {
        val p = plan(newest = 3, rows = 6, uploaded = 5, height = 8)
        assertEquals(3, p.headStart)
        assertEquals(1, p.headCount)
        assertEquals(0, p.tailCount)
    }

    @Test
    fun aSpanThatWrapsSplitsIntoTwoUploads() {
        // newest=6, 4 new rows -> rows 6,7 then wrap to 0,1
        val p = plan(newest = 6, rows = 10, uploaded = 6, height = 8)
        assertEquals(6, p.headStart)
        assertEquals(2, p.headCount)
        assertEquals(2, p.tailCount)
        assertEquals(p.headCount + p.tailCount, 4)
    }

    @Test
    fun aSpanAsLargeAsTheHeightBecomesAFullUpload() {
        assertTrue(plan(newest = 2, rows = 108, uploaded = 100, height = 8).full)
        assertTrue(plan(newest = 2, rows = 200, uploaded = 100, height = 8).full)
    }

    @Test
    fun aClearThatRewindsTheCounterFallsBackToAFullUpload() {
        // Defensive: a negative delta must never produce a negative span.
        assertTrue(plan(newest = 0, rows = 3, uploaded = 9, height = 8).full)
    }

    /**
     * THE BUG THIS CLASS EXISTS FOR. The renderer reads newestRow and
     * rowsAppended as two separate volatile reads. If the audio thread appends
     * between them, the span is computed from the NEW count but anchored at the
     * OLD index: it re-uploads rows that were already correct and skips exactly
     * the newest ones, which then stay stale in the bitmap until they scroll off
     * a full screen later — the waterfall lying about when something happened.
     *
     * The plan must therefore be built from a re-validated pair, and a changed
     * counter must force a full upload.
     */
    @Test
    fun aTornReadOfTheCounterPairForcesAFullUpload() {
        val before = 20L
        val after = 22L   // two rows landed between the two reads
        val p = WaterfallUpload.planValidated(
            newest = 4, rowsBefore = before, rowsAfter = after, uploaded = 19L, height = 8,
        )
        assertTrue("a torn pair must not be trusted for a partial upload", p.full)
    }

    @Test
    fun anUntornPairUploadsOnlyTheDelta() {
        val p = WaterfallUpload.planValidated(
            newest = 4, rowsBefore = 22L, rowsAfter = 22L, uploaded = 20L, height = 8,
        )
        assertTrue(!p.full)
        assertEquals(4, p.headStart)
        assertEquals(2, p.headCount)
    }

    /**
     * Walks a full ring several times over and asserts that the union of every
     * uploaded span equals every row the producer wrote — i.e. no row is ever
     * skipped, which is the property the torn read broke.
     */
    @Test
    fun acrossRepeatedWrapsEveryWrittenRowGetsUploadedAtLeastOnce() {
        val height = 8
        var newest = 0
        var rows = 0L
        var uploaded = -1L
        val uploadedAt = IntArray(height) { -1 }   // last row-ordinal uploaded per slot
        val writtenAt = IntArray(height) { -1 }

        repeat(60) { step ->
            // producer appends one row, decrementing index
            newest = if (newest == 0) height - 1 else newest - 1
            rows++
            writtenAt[newest] = rows.toInt()

            // renderer draws every other step, so deltas of 1 and 2 both occur
            if (step % 2 == 1) {
                val p = WaterfallUpload.planValidated(newest, rows, rows, uploaded, height)
                if (p.full) {
                    for (r in 0 until height) uploadedAt[r] = writtenAt[r]
                } else {
                    for (i in 0 until p.headCount) uploadedAt[p.headStart + i] = writtenAt[p.headStart + i]
                    for (i in 0 until p.tailCount) uploadedAt[i] = writtenAt[i]
                }
                uploaded = rows
                for (r in 0 until height) {
                    assertEquals("row $r stale after step $step", writtenAt[r], uploadedAt[r])
                }
            }
        }
    }
}

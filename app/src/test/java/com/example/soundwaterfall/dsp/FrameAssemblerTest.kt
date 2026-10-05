package com.example.soundwaterfall.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    private fun collect(
        frameSize: Int,
        hop: Int,
        chunkSize: Int,
        total: Int,
    ): List<ShortArray> {
        val assembler = FrameAssembler(frameSize, hop)
        val frames = mutableListOf<ShortArray>()
        var next = 0
        while (next < total) {
            val n = minOf(chunkSize, total - next)
            val chunk = ShortArray(n) { (next + it).toShort() }
            next += n
            // copyOf(): onFrame hands out a reused buffer, so the test must snapshot it.
            assembler.append(chunk, n) { frames += it.copyOf() }
        }
        return frames
    }

    @Test
    fun emitsOverlappingFramesFromOneChunk() {
        val frames = collect(frameSize = 8, hop = 4, chunkSize = 16, total = 16)
        assertEquals(3, frames.size)
        assertArrayEquals(ShortArray(8) { it.toShort() }, frames[0])
        assertArrayEquals(ShortArray(8) { (it + 4).toShort() }, frames[1])
        assertArrayEquals(ShortArray(8) { (it + 8).toShort() }, frames[2])
    }

    /**
     * Spec §5.2: AudioRecord chunk sizes are whatever the platform returns and
     * are not assumed to divide the frame size or the hop. Chunking must not
     * change the output at all.
     */
    @Test
    fun chunkingDoesNotChangeTheOutput() {
        val reference = collect(frameSize = 64, hop = 32, chunkSize = 1024, total = 1024)
        for (chunkSize in intArrayOf(1, 3, 7, 31, 33, 63, 65, 100)) {
            val frames = collect(frameSize = 64, hop = 32, chunkSize = chunkSize, total = 1024)
            assertEquals("chunkSize=$chunkSize frame count", reference.size, frames.size)
            for (i in reference.indices) {
                assertArrayEquals("chunkSize=$chunkSize frame $i", reference[i], frames[i])
            }
        }
    }

    @Test
    fun framesAdvanceByExactlyOneHop() {
        val frames = collect(frameSize = 32, hop = 16, chunkSize = 5, total = 512)
        assertTrue(frames.size > 2)
        for (i in 1 until frames.size) {
            assertEquals(
                "frame $i must start one hop after frame ${i - 1}",
                (frames[i - 1][0] + 16).toShort(),
                frames[i][0],
            )
        }
    }

    @Test
    fun losesNoSamplesAcrossChunkBoundaries() {
        val frames = collect(frameSize = 16, hop = 8, chunkSize = 3, total = 128)
        // Reassemble the signal from the non-overlapping half of each frame.
        val rebuilt = mutableListOf<Short>()
        rebuilt.addAll(frames[0].take(8))
        for (f in frames) rebuilt.addAll(f.drop(8))
        val expected = (0 until rebuilt.size).map { it.toShort() }
        assertEquals(expected, rebuilt)
    }

    @Test
    fun emitsNothingBeforeTheFirstFullFrame() {
        val assembler = FrameAssembler(frameSize = 16, hop = 8)
        var count = 0
        assembler.append(ShortArray(15), 15) { count++ }
        assertEquals(0, count)
        assembler.append(ShortArray(1), 1) { count++ }
        assertEquals(1, count)
    }

    @Test
    fun resetDiscardsPartialState() {
        val assembler = FrameAssembler(frameSize = 8, hop = 4)
        assembler.append(ShortArray(7) { 99 }, 7) { }
        assembler.reset()
        var emitted: ShortArray? = null
        assembler.append(ShortArray(8) { it.toShort() }, 8) { emitted = it.copyOf() }
        assertArrayEquals(ShortArray(8) { it.toShort() }, emitted)
    }

    @Test
    fun honoursTheLengthArgumentAndIgnoresTrailingGarbage() {
        val assembler = FrameAssembler(frameSize = 4, hop = 4)
        val chunk = shortArrayOf(1, 2, 3, 4, 99, 99, 99, 99)
        var emitted: ShortArray? = null
        assembler.append(chunk, 4) { emitted = it.copyOf() }
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted)
    }
}

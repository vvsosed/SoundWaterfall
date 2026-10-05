package com.example.soundwaterfall.dsp

/**
 * Turns the arbitrarily-sized chunks AudioRecord hands back into fixed-size
 * overlapping analysis frames.
 *
 * A ring buffer of exactly [frameSize] samples holds the sliding window; a frame
 * is emitted every [hop] samples once the window has filled for the first time.
 * Incoming chunk sizes need not divide either value.
 *
 * Not thread-safe: it is owned by the audio thread.
 */
class FrameAssembler(private val frameSize: Int, private val hop: Int) {

    init {
        require(frameSize > 0) { "frameSize must be positive, was $frameSize" }
        require(hop in 1..frameSize) { "hop must be in 1..$frameSize, was $hop" }
    }

    private val ring = ShortArray(frameSize)
    private val frame = ShortArray(frameSize)

    /** Index of the oldest sample in [ring], and of the next slot to overwrite. */
    private var writePos = 0
    private var filled = 0
    private var sinceEmit = 0

    fun reset() {
        writePos = 0
        filled = 0
        sinceEmit = 0
    }

    /**
     * Appends the first [length] samples of [chunk], invoking [onFrame] once per
     * completed frame. The array passed to [onFrame] is reused between calls and
     * must be consumed, not retained.
     */
    inline fun append(chunk: ShortArray, length: Int, onFrame: (ShortArray) -> Unit) {
        for (i in 0 until length) {
            pushSample(chunk[i])
            if (isFrameReady()) onFrame(buildFrame())
        }
    }

    @PublishedApi
    internal fun pushSample(sample: Short) {
        ring[writePos] = sample
        writePos = if (writePos + 1 == frameSize) 0 else writePos + 1
        if (filled < frameSize) filled++
        sinceEmit++
    }

    @PublishedApi
    internal fun isFrameReady(): Boolean = filled == frameSize && sinceEmit >= hop

    /** Copies the ring into [frame] in chronological order, oldest first. */
    @PublishedApi
    internal fun buildFrame(): ShortArray {
        sinceEmit = 0
        val tail = frameSize - writePos
        System.arraycopy(ring, writePos, frame, 0, tail)
        if (writePos > 0) System.arraycopy(ring, 0, frame, tail, writePos)
        return frame
    }
}

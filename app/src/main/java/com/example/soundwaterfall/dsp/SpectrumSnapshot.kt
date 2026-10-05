package com.example.soundwaterfall.dsp

/**
 * Hands the newest spectrum frame from the audio thread to the renderer
 * (spec §3.1).
 *
 * The spectrum chart only ever wants the latest frame, so this is a double
 * buffer rather than a queue: the producer writes the buffer the consumer is not
 * reading, then publishes the index. Dropped intermediate frames are correct
 * behaviour here — unlike the waterfall, which needs every one.
 *
 * [readInto] copies rather than lending the array out, which keeps the window in
 * which a producer could overtake a consumer down to the duration of a memcpy.
 */
class SpectrumSnapshot(val binCount: Int) {

    init {
        require(binCount > 0) { "binCount must be positive, was $binCount" }
    }

    private val buffers = arrayOf(FloatArray(binCount), FloatArray(binCount))

    /** Index of the buffer holding a complete frame, or -1 before the first publish. */
    @Volatile
    private var frontIndex = -1

    /** Producer-only. */
    private var writeIndex = 0

    /** Called on the audio thread. */
    fun publish(source: FloatArray) {
        require(source.size == binCount) {
            "frame must be $binCount bins, was ${source.size}"
        }
        val target = buffers[writeIndex]
        System.arraycopy(source, 0, target, 0, binCount)
        frontIndex = writeIndex
        writeIndex = 1 - writeIndex
    }

    /**
     * Called on the main thread.
     *
     * @return true if a frame was copied into [dest], false if nothing has been
     *   published yet, in which case [dest] is untouched.
     */
    fun readInto(dest: FloatArray): Boolean {
        require(dest.size == binCount) {
            "dest must be $binCount bins, was ${dest.size}"
        }
        val index = frontIndex
        if (index < 0) return false
        System.arraycopy(buffers[index], 0, dest, 0, binCount)
        return true
    }

    /** Visible for testing the buffer alternation only. */
    internal fun frontBufferForTest(): FloatArray? =
        frontIndex.let { if (it < 0) null else buffers[it] }
}

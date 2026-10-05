package com.example.soundwaterfall.dsp

/**
 * Hands the newest spectrum frame from the audio thread to the renderer
 * (spec §3.1).
 *
 * The spectrum chart only ever wants the latest frame, so this is a double
 * buffer rather than a queue: dropped intermediate frames are correct behaviour
 * here, unlike the waterfall, which needs every one.
 *
 * Alternating two buffers is NOT enough on its own. The producer returns to the
 * consumer's buffer after only one further publish, so a consumer still copying
 * can be overwritten mid-copy and hand back a frame stitched from two different
 * analyses. `SpectrumSnapshotTest.survivesConcurrentPublishAndRead` reproduces
 * exactly that.
 *
 * So the buffers are guarded by a sequence counter (a seqlock): the producer
 * makes [seq] odd before touching a buffer and even again afterwards, and the
 * consumer accepts a copy only if [seq] was even before it started and is
 * unchanged when it finishes. A consumer that loses the race retries, and after
 * [MAX_READ_ATTEMPTS] gives up and reports no new frame — the renderer then
 * keeps the frame it already had, which is the right fallback for a display that
 * only ever wants the newest value.
 */
class SpectrumSnapshot(val binCount: Int) {

    init {
        require(binCount > 0) { "binCount must be positive, was $binCount" }
    }

    private val buffers = arrayOf(FloatArray(binCount), FloatArray(binCount))

    /** Even = no write in progress, odd = producer is inside a buffer. */
    @Volatile
    private var seq = 0

    /** Index of the buffer holding the newest frame, or -1 before the first publish. */
    @Volatile
    private var frontIndex = -1

    /** Producer-only; needs no synchronisation because there is a single writer. */
    private var writeIndex = 0

    /**
     * Called on the audio thread. There is exactly one producer, so the
     * non-atomic increments of [seq] are safe.
     */
    fun publish(source: FloatArray) {
        require(source.size == binCount) {
            "frame must be $binCount bins, was ${source.size}"
        }
        val target = writeIndex
        seq++                                   // odd: a write is in progress
        System.arraycopy(source, 0, buffers[target], 0, binCount)
        frontIndex = target
        writeIndex = 1 - target
        seq++                                   // even: the buffer is stable again
    }

    /**
     * Called on the main thread.
     *
     * @return true if a complete, untorn frame was copied into [dest]; false if
     *   nothing has been published yet or the read lost the race repeatedly, in
     *   which case [dest] is left as it was.
     */
    fun readInto(dest: FloatArray): Boolean {
        require(dest.size == binCount) {
            "dest must be $binCount bins, was ${dest.size}"
        }
        var attempt = 0
        while (attempt++ < MAX_READ_ATTEMPTS) {
            val before = seq
            if (before and 1 != 0) continue     // producer is mid-write
            val index = frontIndex
            if (index < 0) return false         // nothing published yet
            System.arraycopy(buffers[index], 0, dest, 0, binCount)
            if (seq == before) return true      // no write overlapped the copy
        }
        return false
    }

    /** Visible for testing the buffer alternation only. */
    internal fun frontBufferForTest(): FloatArray? =
        frontIndex.let { if (it < 0) null else buffers[it] }

    private companion object {
        /**
         * At 47 frames/s the producer writes for ~1 us every ~21 ms, so a retry
         * is already almost unheard of and a second one effectively impossible.
         */
        const val MAX_READ_ATTEMPTS = 8
    }
}

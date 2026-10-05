package com.example.soundwaterfall.dsp

/**
 * Works out which rows of a [WaterfallBuffer] the renderer still has to push
 * into its Bitmap.
 *
 * This lives outside the View so the arithmetic — especially the torn-read case
 * below — is testable on the JVM instead of only on a device.
 *
 * Rows are written with a DECREASING index, so the `n` rows appended since the
 * last upload occupy `newest, newest+1, ... newest+n-1`, wrapping at most once.
 */
object WaterfallUpload {

    /**
     * @param full upload the whole buffer and ignore the spans
     * @param headStart first row of the contiguous span at or after [headStart]
     * @param headCount rows in that span
     * @param tailCount rows wrapped around to index 0
     */
    data class Plan(
        val full: Boolean,
        val headStart: Int = 0,
        val headCount: Int = 0,
        val tailCount: Int = 0,
    )

    private val FULL = Plan(full = true)
    private val NOTHING = Plan(full = false)

    /**
     * The renderer must sample `rowsAppended` on both sides of its read of
     * `newestRow`. If the producer appended in between, the span would be
     * computed from the new count but anchored at the old index — re-uploading
     * rows that were already correct and skipping exactly the newest ones, which
     * then stay stale until they scroll off a full screen later. A changed
     * counter is therefore not trusted for a partial upload.
     */
    fun planValidated(
        newest: Int,
        rowsBefore: Long,
        rowsAfter: Long,
        uploaded: Long,
        height: Int,
    ): Plan = if (rowsBefore != rowsAfter) FULL else plan(newest, rowsAfter, uploaded, height)

    fun plan(newest: Int, rows: Long, uploaded: Long, height: Int): Plan {
        if (height <= 0) return FULL
        val added = rows - uploaded
        // uploaded < 0 is the first draw; added < 0 means the counter rewound;
        // added >= height means more changed than the buffer holds.
        if (uploaded < 0L || added < 0L || added >= height) return FULL
        if (added == 0L) return NOTHING

        val span = added.toInt()
        val head = minOf(span, height - newest)
        return Plan(full = false, headStart = newest, headCount = head, tailCount = span - head)
    }
}

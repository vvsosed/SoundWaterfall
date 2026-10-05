package com.example.soundwaterfall.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.example.soundwaterfall.dsp.BinReducer
import com.example.soundwaterfall.dsp.ColorMap
import com.example.soundwaterfall.dsp.WaterfallBuffer

/**
 * The scrolling spectrogram (spec §6.3).
 *
 * One pixel row per analysis frame, newest at the top so it sits against the
 * spectrum above it. History depth is therefore the view's pixel height: about
 * 10 s at N=2048 on the 720x1280 test device, about 20 s on a 1080x2400 phone.
 *
 * [submitFrame] is called on the AUDIO thread and does the bin-to-column
 * reduction and the colourising there, exactly as spec §3.1 requires, so the
 * main thread only has to blit.
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Buffer and its matching column scratch array, swapped together. */
    private class Target(val buffer: WaterfallBuffer, val columns: FloatArray)

    private val colorMap = ColorMap()
    private val blitPaint = Paint().apply { isFilterBitmap = false }
    private val srcRect = Rect()
    private val dstRect = Rect()

    @Volatile
    private var target: Target? = null

    private var bitmap: Bitmap? = null

    /** Row count at the last requested redraw; -1 forces the first one. */
    private var lastDrawnRowCount = -1L

    /** Row count at the last bitmap upload; -1 forces a full upload. */
    private var uploadedRowCount = -1L

    /**
     * Called once per vsync. Skips the invalidate entirely when no new row has
     * arrived — at N=4096 the analysis produces 23 rows/s against a 60 Hz
     * vsync, so most redraws would re-upload an unchanged 1.35 MB bitmap.
     *
     * The check belongs here and not in [onDraw]: returning early from onDraw
     * would leave the view's display list empty and blank the waterfall.
     */
    fun refresh() {
        val t = target ?: return
        val rows = t.buffer.rowsAppended
        if (rows == lastDrawnRowCount) return
        lastDrawnRowCount = rows
        invalidate()
    }

    fun clear() {
        target?.buffer?.clear()
        invalidate()
    }

    /**
     * Called on the audio thread, once per analysis frame.
     *
     * @param db one value per FFT bin
     */
    fun submitFrame(db: FloatArray, dbFloor: Float) {
        val t = target ?: return
        BinReducer.reduce(db, t.columns)
        t.buffer.appendRow(t.columns, dbFloor)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // REVIEW FOCUS 3: WaterfallBuffer rejects non-positive dimensions, so no
        // buffer exists until the view has a real size. History clears on resize
        // and on rotation, as spec §6.3 specifies.
        if (w <= 0 || h <= 0) {
            target = null
            bitmap?.recycle()
            bitmap = null
            return
        }
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        target = Target(WaterfallBuffer(w, h, colorMap), FloatArray(w))
        lastDrawnRowCount = -1L
        uploadedRowCount = -1L
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        target = null
        bitmap?.recycle()
        bitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        val t = target ?: return
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return

        val buffer = t.buffer
        val w = buffer.width
        val h = buffer.height

        // One volatile read; both slices must agree on the same split point.
        val newest = buffer.newestRow
        val topHeight = h - newest

        // Upload only the rows that changed. A full 720x470 upload is ~1.35 MB;
        // at 47 rows/s only a handful of rows are new per draw.
        val rows = buffer.rowsAppended
        val added = rows - uploadedRowCount
        if (uploadedRowCount < 0L || added < 0L || added >= h) {
            bmp.setPixels(buffer.pixels, 0, w, 0, 0, w, h)
        } else if (added > 0L) {
            // Rows are written with a DECREASING index, so the new ones occupy
            // newest, newest+1, ... newest+added-1, wrapping once at the end.
            val span = added.toInt()
            val head = minOf(span, h - newest)
            bmp.setPixels(buffer.pixels, newest * w, w, 0, newest, w, head)
            val tail = span - head
            if (tail > 0) bmp.setPixels(buffer.pixels, 0, w, 0, 0, w, tail)
        }
        uploadedRowCount = rows

        if (topHeight > 0) {
            srcRect.set(0, newest, w, h)
            dstRect.set(0, 0, w, topHeight)
            canvas.drawBitmap(bmp, srcRect, dstRect, blitPaint)
        }
        if (newest > 0) {
            srcRect.set(0, 0, w, newest)
            dstRect.set(0, topHeight, w, h)
            canvas.drawBitmap(bmp, srcRect, dstRect, blitPaint)
        }
    }
}

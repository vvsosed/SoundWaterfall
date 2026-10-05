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

    fun refresh() {
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

        bmp.setPixels(buffer.pixels, 0, w, 0, 0, w, h)

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

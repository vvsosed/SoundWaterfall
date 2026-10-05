package com.example.soundwaterfall.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.soundwaterfall.R
import com.example.soundwaterfall.dsp.AxisLabels
import com.example.soundwaterfall.dsp.FrequencyScale

/**
 * The single shared frequency scale, drawn below the waterfall (spec §6.1,
 * layout C). It labels both charts because all three views derive their x
 * mapping from the same [FrequencyScale].
 */
class FrequencyAxisView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_axis_background)
    }
    private val tickPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_grid)
        strokeWidth = 1f
    }
    private val labelPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_label)
        textSize = resources.displayMetrics.density * 9f
        isAntiAlias = true
    }

    private var scale: FrequencyScale? = null

    fun bind(scale: FrequencyScale) {
        this.scale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
        if (w <= 0 || h <= 0) return

        val freq = scale ?: return
        val tickHeight = h * 0.3f
        val baseline = h * 0.82f

        // The unit sits hard against the right edge, so the gridline labels must
        // not run into it — at 48 kHz the Nyquist label lands exactly there.
        val hz = "Hz"
        val hzWidth = labelPaint.measureText(hz)
        val labelLimit = (w - hzWidth - UNIT_GAP * resources.displayMetrics.density)
            .coerceAtLeast(0f)

        for (f in freq.gridlineFrequencies(GRID_STEP_HZ)) {
            val x = freq.xOf(f.toFloat(), w)
            canvas.drawLine(x, 0f, x, tickHeight, tickPaint)

            val label = AxisLabels.format(f)
            val textWidth = labelPaint.measureText(label)
            // Keep the first and last labels inside the view, clear of the unit.
            val tx = (x - textWidth / 2f)
                .coerceIn(0f, (labelLimit - textWidth).coerceAtLeast(0f))
            canvas.drawText(label, tx, baseline, labelPaint)
        }

        canvas.drawText(hz, w - hzWidth - 1f, baseline, labelPaint)
    }

    private companion object {
        const val GRID_STEP_HZ = 4000
        const val UNIT_GAP = 4f
    }
}

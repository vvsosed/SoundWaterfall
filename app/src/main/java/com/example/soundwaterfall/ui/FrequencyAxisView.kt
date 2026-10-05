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

        for (f in freq.gridlineFrequencies(GRID_STEP_HZ)) {
            val x = freq.xOf(f.toFloat(), w)
            canvas.drawLine(x, 0f, x, tickHeight, tickPaint)

            val label = AxisLabels.format(f)
            val textWidth = labelPaint.measureText(label)
            // Keep the first and last labels inside the view.
            val tx = (x - textWidth / 2f).coerceIn(0f, (w - textWidth).coerceAtLeast(0f))
            canvas.drawText(label, tx, baseline, labelPaint)
        }

        val hz = "Hz"
        canvas.drawText(hz, w - labelPaint.measureText(hz) - 1f, labelPaint.textSize, labelPaint)
    }

    private companion object {
        const val GRID_STEP_HZ = 4000
    }
}

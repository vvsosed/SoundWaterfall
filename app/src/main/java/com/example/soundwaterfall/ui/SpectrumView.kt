package com.example.soundwaterfall.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.soundwaterfall.R
import com.example.soundwaterfall.dsp.AnalyzerSettings
import com.example.soundwaterfall.dsp.BinReducer
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.SpectrumGeometry
import com.example.soundwaterfall.dsp.SpectrumSnapshot

/**
 * The instantaneous spectrum (spec §6.2).
 *
 * Allocates nothing in [onDraw]: the bin buffer, the column buffer and both
 * Paths are fields, reallocated only when the bin count or the view size
 * changes.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val gridPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_grid)
        strokeWidth = 1f
        isAntiAlias = false
    }
    private val labelPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_label)
        textSize = resources.displayMetrics.density * 9f
        isAntiAlias = true
    }
    private val tracePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.spectrum_trace)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.spectrum_fill)
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_background)
    }

    private val tracePath = Path()
    private val fillPath = Path()

    private var snapshot: SpectrumSnapshot? = null
    private var scale: FrequencyScale? = null

    /** Publish count at the last requested redraw; -1 forces the first one. */
    private var lastDrawnPublishCount = -1L

    // Spec §6.2 requires onDraw to allocate nothing. Gridline positions and
    // label strings change only when the scale, the size or dbFloor changes, so
    // they are computed once there and only read while drawing.
    private val gridHz = IntArray(MAX_GRIDLINES)
    private var gridCount = 0
    private val gridX = FloatArray(MAX_GRIDLINES)
    private var dbLabels: Array<String> = emptyArray()
    private var dbLabelY = FloatArray(0)

    private var bins: FloatArray = FloatArray(0)
    private var columns: FloatArray = FloatArray(0)

    var dbFloor: Float = AnalyzerSettings.DEFAULT.dbFloor
        set(value) {
            field = value
            rebuildGrid()
            invalidate()
        }

    /** Called on the main thread whenever the pipeline is (re)built. */
    fun bind(snapshot: SpectrumSnapshot, scale: FrequencyScale) {
        this.snapshot = snapshot
        this.scale = scale
        if (bins.size != snapshot.binCount) bins = FloatArray(snapshot.binCount)
        lastDrawnPublishCount = -1L
        rebuildGrid()
        invalidate()
    }

    /**
     * Called once per vsync by the Choreographer loop. Skips the invalidate when
     * no new frame has been published, which at N=4096 removes about 60% of
     * redraws of two anti-aliased 720-segment paths.
     *
     * The check belongs here, not in [onDraw]: returning early from onDraw would
     * leave the display list empty and blank the chart.
     */
    fun refresh() {
        val snap = snapshot ?: return
        val published = snap.publishCount
        if (published == lastDrawnPublishCount) return
        lastDrawnPublishCount = published
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && columns.size != w) columns = FloatArray(w)
        rebuildGrid()
    }

    /** Recomputes everything the grid needs, so onDraw only reads. */
    private fun rebuildGrid() {
        val freq = scale
        gridCount = if (freq == null) 0 else freq.gridlineFrequenciesInto(GRID_STEP_HZ, gridHz)
        if (freq != null) {
            for (i in 0 until gridCount) gridX[i] = freq.xOf(gridHz[i].toFloat(), width)
        }

        val step = SpectrumGeometry.gridlineStepDb(dbFloor)
        val count = (-dbFloor / step).toInt() + 1
        if (dbLabels.size != count) {
            dbLabels = Array(count) { "" }
            dbLabelY = FloatArray(count)
        }
        for (i in 0 until count) {
            val db = -i * step
            dbLabels[i] = if (db == 0) "0 dBFS" else db.toString()
            dbLabelY[i] = SpectrumGeometry.dbToY(db.toFloat(), dbFloor, height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
        // REVIEW FOCUS 3: nothing to draw before the first layout pass.
        if (w <= 0 || h <= 0) return

        drawGrid(canvas, w, h)

        val snap = snapshot ?: return
        if (columns.size != w || bins.size != snap.binCount) return
        if (!snap.readInto(bins)) return

        BinReducer.reduce(bins, columns)

        tracePath.rewind()
        fillPath.rewind()
        val y0 = SpectrumGeometry.dbToY(columns[0], dbFloor, h)
        tracePath.moveTo(0f, y0)
        fillPath.moveTo(0f, h.toFloat())
        fillPath.lineTo(0f, y0)
        for (x in 1 until w) {
            val y = SpectrumGeometry.dbToY(columns[x], dbFloor, h)
            tracePath.lineTo(x.toFloat(), y)
            fillPath.lineTo(x.toFloat(), y)
        }
        fillPath.lineTo((w - 1).toFloat(), h.toFloat())
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(tracePath, tracePaint)
    }

    /** Reads only precomputed state; allocates nothing (spec §6.2). */
    private fun drawGrid(canvas: Canvas, w: Int, h: Int) {
        val inset = LABEL_INSET * resources.displayMetrics.density
        for (i in dbLabels.indices) {
            val y = dbLabelY[i]
            canvas.drawLine(0f, y, w.toFloat(), y, gridPaint)
            val ty = if (i == 0) labelPaint.textSize else y - 2f
            canvas.drawText(dbLabels[i], inset, ty, labelPaint)
        }
        for (i in 0 until gridCount) {
            if (gridHz[i] == 0) continue
            canvas.drawLine(gridX[i], 0f, gridX[i], h.toFloat(), gridPaint)
        }
    }

    private companion object {
        const val GRID_STEP_HZ = 4000
        const val LABEL_INSET = 3f

        /** 0 Hz through 24 kHz at 4 kHz steps is 7; a little headroom. */
        const val MAX_GRIDLINES = 16
    }
}

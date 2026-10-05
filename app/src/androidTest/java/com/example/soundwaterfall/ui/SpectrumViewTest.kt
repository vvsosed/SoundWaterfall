package com.example.soundwaterfall.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.SpectrumSnapshot
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REVIEW FOCUS 3. A View is measured and can be asked to draw before it has a
 * real size. These tests exist to prove that path does not crash; the visual
 * correctness of the chart is checked by eye against a known tone (spec §9).
 */
@RunWith(AndroidJUnit4::class)
class SpectrumViewTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun draw(view: View, w: Int, h: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        if (w > 0 && h > 0) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        } else {
            // A zero-size view still gets asked to draw into a parent's canvas.
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        }
    }

    @Test
    fun drawsAtZeroSizeWithoutData() {
        draw(SpectrumView(context), 0, 0)
    }

    @Test
    fun drawsAtZeroSizeWithDataBound() {
        val view = SpectrumView(context)
        val snap = SpectrumSnapshot(1025)
        snap.publish(FloatArray(1025) { -90f })
        view.bind(snap, FrequencyScale(48000, 1025))
        draw(view, 0, 0)
    }

    @Test
    fun drawsAtOnePixelWide() {
        val view = SpectrumView(context)
        val snap = SpectrumSnapshot(1025)
        snap.publish(FloatArray(1025) { -45f })
        view.bind(snap, FrequencyScale(48000, 1025))
        draw(view, 1, 1)
    }

    @Test
    fun drawsAtDeviceWidthBeforeAnyFrameIsPublished() {
        val view = SpectrumView(context)
        view.bind(SpectrumSnapshot(1025), FrequencyScale(48000, 1025))
        draw(view, 720, 400)
    }

    @Test
    fun drawsSilenceAndFullScaleAtEveryFftSize() {
        for (binCount in intArrayOf(513, 1025, 2049)) {
            for (value in floatArrayOf(-90f, 0f, Float.NEGATIVE_INFINITY)) {
                val view = SpectrumView(context)
                val snap = SpectrumSnapshot(binCount)
                snap.publish(FloatArray(binCount) { value })
                view.bind(snap, FrequencyScale(48000, binCount))
                draw(view, 720, 400)
            }
        }
    }
}

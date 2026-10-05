package com.example.soundwaterfall.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * REVIEW FOCUS 3, plus the producer/consumer interleaving from spec §3.1.
 * Visual correctness is checked by eye against a known tone (spec §9).
 */
@RunWith(AndroidJUnit4::class)
class WaterfallViewTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun laidOut(w: Int, h: Int): WaterfallView {
        val view = WaterfallView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        return view
    }

    private fun draw(view: View, w: Int, h: Int) {
        val bitmap = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    @Test
    fun submitBeforeLayoutIsIgnoredRatherThanCrashing() {
        val view = WaterfallView(context)
        view.submitFrame(FloatArray(1025) { -45f }, -90f)
        draw(view, 1, 1)
    }

    @Test
    fun drawsAtZeroSize() {
        draw(laidOut(0, 0), 1, 1)
    }

    @Test
    fun clearBeforeLayoutIsSafe() {
        WaterfallView(context).clear()
    }

    @Test
    fun acceptsEveryFftSizeAtTheDeviceWidth() {
        val view = laidOut(720, 400)
        for (binCount in intArrayOf(513, 1025, 2049)) {
            view.submitFrame(FloatArray(binCount) { -30f }, -90f)
            draw(view, 720, 400)
        }
    }

    @Test
    fun scrollsThroughAFullWrapWithoutCrashing() {
        val view = laidOut(720, 64)
        repeat(200) { i ->
            view.submitFrame(FloatArray(1025) { -(i % 90).toFloat() }, -90f)
        }
        draw(view, 720, 64)
    }

    @Test
    fun acceptsSilenceAndNonFiniteValues() {
        val view = laidOut(720, 64)
        view.submitFrame(FloatArray(1025) { Float.NEGATIVE_INFINITY }, -90f)
        view.submitFrame(FloatArray(1025) { Float.NaN }, -90f)
        draw(view, 720, 64)
    }

    /** The real interleaving: a producer thread appending while the UI blits. */
    @Test
    fun survivesConcurrentSubmitAndDraw() {
        val view = laidOut(720, 128)
        val done = CountDownLatch(1)
        var failure: Throwable? = null

        val producer = Thread {
            try {
                val db = FloatArray(1025)
                for (i in 0 until 3_000) {
                    db.fill(-(i % 90).toFloat())
                    view.submitFrame(db, -90f)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        producer.start()
        while (done.count > 0L) {
            draw(view, 720, 128)
        }
        done.await(10, TimeUnit.SECONDS)
        producer.join(10_000)
        failure?.let { throw it }
    }

    @Test
    fun relayoutReplacesTheBufferAndDoesNotCrash() {
        val view = laidOut(720, 128)
        view.submitFrame(FloatArray(1025) { -10f }, -90f)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 480, 64)
        view.submitFrame(FloatArray(1025) { -20f }, -90f)
        draw(view, 480, 64)
    }
}

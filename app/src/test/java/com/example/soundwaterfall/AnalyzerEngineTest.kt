package com.example.soundwaterfall

import com.example.soundwaterfall.audio.SampleSource
import com.example.soundwaterfall.audio.SourceResult
import com.example.soundwaterfall.dsp.AnalyzerSettings
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.WindowFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

class AnalyzerEngineTest {

    /** A tone generator standing in for the microphone. */
    private class FakeSource(
        override val sampleRate: Int = 48000,
        override val sourceLabel: String = "FAKE",
        private val toneBin: Double = 128.0,
        private val fftSize: Int = 2048,
        private val errorAfter: Int = -1,
        private val emptyForever: Boolean = false,
    ) : SampleSource {
        private var phase = 0
        val reads = AtomicInteger(0)

        @Volatile
        var closed = false

        override fun read(dest: ShortArray): Int {
            val n = reads.incrementAndGet()
            if (errorAfter in 1..n) return -3
            if (emptyForever) return 0
            for (i in dest.indices) {
                dest[i] = (cos(2.0 * PI * toneBin * phase / fftSize) * 32767.0)
                    .roundToInt().toShort()
                phase++
            }
            return dest.size
        }

        override fun close() {
            closed = true
        }
    }

    private open class RecordingSink : AnalyzerEngine.Sink {
        val frames = AtomicInteger(0)
        val pipelineChanges = AtomicInteger(0)
        val started = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val lastScale = AtomicReference<FrequencyScale?>(null)
        val startedLabel = AtomicReference<String?>(null)

        override fun onStarted(sourceLabel: String, sampleRate: Int, scale: FrequencyScale) {
            startedLabel.set(sourceLabel)
            lastScale.set(scale)
            started.countDown()
        }

        override fun onFrame(db: FloatArray, scale: FrequencyScale) {
            lastScale.set(scale)
            frames.incrementAndGet()
        }

        override fun onPipelineChanged(scale: FrequencyScale) {
            lastScale.set(scale)
            pipelineChanges.incrementAndGet()
        }

        override fun onError(message: String) {
            error.set(message)
        }
    }

    private fun engine(
        source: SampleSource,
        sink: AnalyzerEngine.Sink,
        settings: AnalyzerSettings = AnalyzerSettings.DEFAULT,
    ) = AnalyzerEngine({ SourceResult.Ok(source) }, sink, settings)

    @Test
    fun reportsTheSourceAndRateItStartedWith() {
        val sink = RecordingSink()
        val e = engine(FakeSource(sampleRate = 44100, sourceLabel = "VOICE_RECOGNITION"), sink)
        e.start()
        assertTrue(sink.started.await(5, TimeUnit.SECONDS))
        e.stop()

        assertEquals("VOICE_RECOGNITION", sink.startedLabel.get())
        assertEquals(44100, sink.lastScale.get()!!.sampleRate)
        assertEquals(22050f, sink.lastScale.get()!!.nyquist, 1e-3f)
    }

    @Test
    fun deliversFramesWhileRunningAndStopsWhenStopped() {
        val sink = RecordingSink()
        val source = FakeSource()
        val e = engine(source, sink)
        e.start()
        assertTrue(sink.started.await(5, TimeUnit.SECONDS))
        waitUntil { sink.frames.get() > 10 }
        e.stop()

        val atStop = sink.frames.get()
        Thread.sleep(100)
        assertEquals("no frames may arrive after stop()", atStop, sink.frames.get())
        assertTrue("source must be closed", source.closed)
    }

    @Test
    fun surfacesAReadErrorAndStops() {
        val sink = RecordingSink()
        val e = engine(FakeSource(errorAfter = 3), sink)
        e.start()
        waitUntil { sink.error.get() != null }
        e.stop()
        assertTrue(sink.error.get()!!.contains("-3"))
    }

    @Test
    fun surfacesAFailedOpenWithoutStartingAThread() {
        val sink = RecordingSink()
        val e = AnalyzerEngine({ SourceResult.Failed("no microphone") }, sink, AnalyzerSettings.DEFAULT)
        e.start()
        waitUntil { sink.error.get() != null }
        e.stop()
        assertEquals("no microphone", sink.error.get())
        assertEquals(0, sink.frames.get())
    }

    /** A mic that returns 0 forever must not spin the CPU; it must give up. */
    @Test
    fun givesUpWhenTheSourceDeliversNothing() {
        val sink = RecordingSink()
        val e = engine(FakeSource(emptyForever = true), sink)
        e.start()
        waitUntil(timeoutMs = 10_000) { sink.error.get() != null }
        e.stop()
        assertTrue(sink.error.get()!!.contains("no audio"))
    }

    @Test
    fun changingOnlyTheWindowDoesNotRebuildThePipeline() {
        val sink = RecordingSink()
        val e = engine(FakeSource(), sink)
        e.start()
        assertTrue(sink.started.await(5, TimeUnit.SECONDS))
        waitUntil { sink.frames.get() > 5 }

        e.updateSettings(AnalyzerSettings.DEFAULT.copy(window = WindowFunction.BLACKMAN))
        waitUntil { e.settings.window == WindowFunction.BLACKMAN }
        val changes = sink.pipelineChanges.get()
        waitUntil { sink.frames.get() > 20 }
        e.stop()

        assertEquals("window-only change must not rebuild", changes, sink.pipelineChanges.get())
        assertEquals(1025, sink.lastScale.get()!!.binCount)
    }

    @Test
    fun changingFftSizeRebuildsThePipelineAndTheScale() {
        val sink = RecordingSink()
        val e = engine(FakeSource(), sink)
        e.start()
        assertTrue(sink.started.await(5, TimeUnit.SECONDS))
        waitUntil { sink.frames.get() > 5 }

        e.updateSettings(AnalyzerSettings.DEFAULT.copy(fftSize = 4096))
        waitUntil { sink.pipelineChanges.get() > 0 }
        waitUntil { sink.lastScale.get()!!.binCount == 2049 }
        e.stop()

        assertEquals(2049, sink.lastScale.get()!!.binCount)
    }

    /**
     * REVIEW FOCUS 2. The UI thread changes settings whenever the user touches a
     * control; the audio thread is mid-pipeline. A torn read of fftSize would
     * hand a consumer a frame whose length disagrees with the scale beside it.
     * Settings must therefore be applied only between reads, never mid-frame.
     */
    @Test
    fun settingsHammeredFromAnotherThreadNeverProduceAMismatchedFrame() {
        val mismatches = AtomicInteger(0)
        val sink = object : RecordingSink() {
            override fun onFrame(db: FloatArray, scale: FrequencyScale) {
                if (db.size != scale.binCount) mismatches.incrementAndGet()
                super.onFrame(db, scale)
            }
        }
        val e = engine(FakeSource(), sink)
        e.start()
        assertTrue(sink.started.await(5, TimeUnit.SECONDS))

        val sizes = AnalyzerSettings.FFT_SIZES
        val windows = WindowFunction.entries
        val hammer = Thread {
            for (i in 0 until 400) {
                e.updateSettings(
                    AnalyzerSettings(
                        fftSize = sizes[i % sizes.size],
                        window = windows[i % windows.size],
                        dbFloor = -(40 + (i % 9) * 10).toFloat(),
                    )
                )
            }
        }
        hammer.start()
        hammer.join(10_000)
        waitUntil { sink.frames.get() > 50 }
        e.stop()

        assertEquals("frame length disagreed with its scale", 0, mismatches.get())
        assertEquals("engine must not have errored", null, sink.error.get())
    }

    @Test
    fun stopIsIdempotentAndSafeBeforeStart() {
        val sink = RecordingSink()
        val e = engine(FakeSource(), sink)
        e.stop()
        e.start()
        assertTrue(sink.started.await(5, TimeUnit.SECONDS))
        e.stop()
        e.stop()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}

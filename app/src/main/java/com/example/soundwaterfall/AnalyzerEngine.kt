package com.example.soundwaterfall

import com.example.soundwaterfall.audio.SampleSource
import com.example.soundwaterfall.audio.SourceResult
import com.example.soundwaterfall.dsp.AnalyzerSettings
import com.example.soundwaterfall.dsp.FrameAssembler
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.Radix2FftEngine
import com.example.soundwaterfall.dsp.SpectrumAnalyzer
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the audio thread and drives capture -> framing -> windowing -> FFT -> dB.
 *
 * Every [Sink] callback arrives ON THE AUDIO THREAD. Consumers must marshal to
 * the main thread before touching a View.
 *
 * Settings are applied only BETWEEN reads, never inside a frame (spec §3.1).
 * That is what guarantees a frame's length always agrees with the
 * [FrequencyScale] delivered alongside it, no matter how fast the user drags a
 * control.
 */
class AnalyzerEngine(
    private val openSource: () -> SourceResult,
    private val sink: Sink,
    initialSettings: AnalyzerSettings = AnalyzerSettings.DEFAULT,
) {
    interface Sink {
        fun onStarted(sourceLabel: String, sampleRate: Int, scale: FrequencyScale)
        fun onFrame(db: FloatArray, scale: FrequencyScale)
        fun onPipelineChanged(scale: FrequencyScale)
        fun onError(message: String)
    }

    @Volatile
    var settings: AnalyzerSettings = initialSettings
        private set

    private val pending = AtomicReference<AnalyzerSettings?>(null)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Queues a settings change; the audio thread picks it up at the next read boundary. */
    fun updateSettings(next: AnalyzerSettings) {
        pending.set(next)
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "AnalyzerEngine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    /**
     * Wraps the whole audio-thread body. Without this, anything thrown here —
     * AudioRecord raising IllegalStateException, a require() tripping after a
     * pipeline rebuild, an OOM allocating an N=4096 buffer — is an uncaught
     * exception, which on Android kills the process. Spec §8 defines a
     * user-visible Retry panel for every failure it lists, and a crash dialog
     * is not that panel.
     */
    private fun loop() {
        try {
            when (val result = openSource()) {
                is SourceResult.Failed -> sink.onError(result.reason)
                is SourceResult.Ok -> {
                    val source = result.source
                    try {
                        pump(source)
                    } finally {
                        source.close()
                    }
                }
            }
        } catch (t: Throwable) {
            sink.onError(t.message ?: t.toString())
        }
    }

    private fun pump(source: SampleSource) {
        var current = settings
        var analyzer = SpectrumAnalyzer(
            Radix2FftEngine(current.fftSize), current.window, current.dbFloor,
        )
        var assembler = FrameAssembler(current.fftSize, current.hop)
        var scale = FrequencyScale(source.sampleRate, analyzer.binCount)
        var out = FloatArray(analyzer.binCount)
        var chunk = ShortArray(current.hop)

        sink.onStarted(source.sourceLabel, source.sampleRate, scale)

        var emptyReads = 0
        while (running) {
            pending.getAndSet(null)?.let { next ->
                if (next.fftSize != current.fftSize) {
                    analyzer = SpectrumAnalyzer(
                        Radix2FftEngine(next.fftSize), next.window, next.dbFloor,
                    )
                    assembler = FrameAssembler(next.fftSize, next.hop)
                    scale = FrequencyScale(source.sampleRate, analyzer.binCount)
                    out = FloatArray(analyzer.binCount)
                    chunk = ShortArray(next.hop)
                    current = next
                    settings = next
                    sink.onPipelineChanged(scale)
                } else {
                    analyzer.setWindow(next.window)
                    analyzer.dbFloor = next.dbFloor
                    current = next
                    settings = next
                }
            }

            val read = source.read(chunk)
            if (read < 0) {
                sink.onError("microphone read failed (code $read)")
                return
            }
            if (read == 0) {
                if (++emptyReads >= MAX_EMPTY_READS) {
                    sink.onError("microphone delivered no audio")
                    return
                }
                Thread.sleep(IDLE_SLEEP_MS)
                continue
            }
            emptyReads = 0

            // Captured locals are safe here because FrameAssembler.append is inline.
            assembler.append(chunk, read) { frame ->
                analyzer.analyze(frame, out)
                sink.onFrame(out, scale)
            }
        }
    }

    private companion object {
        /** About two seconds of empty reads before giving up, rather than spinning. */
        const val MAX_EMPTY_READS = 200
        const val IDLE_SLEEP_MS = 10L
    }
}

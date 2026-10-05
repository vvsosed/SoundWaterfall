package com.example.soundwaterfall.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log

/**
 * Acquires an [AudioRecord] by walking the source chain and the sample-rate
 * fallback (spec §5.1). Acquisition only — the read loop belongs to the engine.
 */
class AudioCapture(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /**
     * @param hop the analysis hop in samples; the buffer is sized to hold
     *   several hops so a slow consumer cannot cause an overrun.
     */
    fun open(hop: Int): SourceResult {
        val candidates = AudioSourceChain.candidates(sdkInt, unprocessedSupported())
        var lastReason = "no audio source was attempted"

        for (candidate in candidates) {
            for (rate in AudioSourceChain.SAMPLE_RATES) {
                val minBuffer = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) {
                    lastReason = "${candidate.label} at $rate Hz: unsupported format " +
                        "(getMinBufferSize returned $minBuffer)"
                    continue
                }

                val bufferSize = maxOf(minBuffer, 4 * hop * BYTES_PER_SAMPLE)
                val record = try {
                    AudioRecord(
                        candidate.source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                    )
                } catch (e: IllegalArgumentException) {
                    lastReason = "${candidate.label} at $rate Hz: ${e.message}"
                    continue
                } catch (e: SecurityException) {
                    return SourceResult.Failed("microphone permission denied: ${e.message}")
                }

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    lastReason = "${candidate.label} at $rate Hz: failed to initialize"
                    continue
                }

                Log.i(TAG, "capturing from ${candidate.label} at $rate Hz, buffer $bufferSize B")
                return SourceResult.Ok(AudioRecordSource(record, rate, candidate.label))
            }
        }
        return SourceResult.Failed(lastReason)
    }

    private fun unprocessedSupported(): Boolean {
        if (sdkInt < 24) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    private class AudioRecordSource(
        private val record: AudioRecord,
        override val sampleRate: Int,
        override val sourceLabel: String,
    ) : SampleSource {

        init {
            record.startRecording()
        }

        override fun read(dest: ShortArray): Int = record.read(dest, 0, dest.size)

        override fun close() {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() failed", e)
            }
            record.release()
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val BYTES_PER_SAMPLE = 2
    }
}

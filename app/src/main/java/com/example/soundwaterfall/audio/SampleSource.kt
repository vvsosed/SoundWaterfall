package com.example.soundwaterfall.audio

/**
 * A blocking source of 16-bit mono PCM.
 *
 * This interface is what lets [com.example.soundwaterfall.AnalyzerEngine] be
 * tested on the JVM with a synthetic signal instead of a microphone.
 */
interface SampleSource {

    /** The rate actually granted by the platform — never assume 48000. */
    val sampleRate: Int

    /** Human-readable name of the source that won the chain, for the app bar. */
    val sourceLabel: String

    /**
     * Blocks until samples are available.
     *
     * @return the number of samples written to [dest], 0 if none were available,
     *   or a negative platform error code.
     */
    fun read(dest: ShortArray): Int

    fun close()
}

/**
 * Outcome of trying to acquire a [SampleSource].
 *
 * Lives here rather than nested inside AudioCapture so that it carries no
 * Android imports, which is what allows AnalyzerEngine to be driven by a fake
 * source in plain JVM unit tests.
 */
sealed interface SourceResult {
    data class Ok(val source: SampleSource) : SourceResult
    data class Failed(val reason: String) : SourceResult
}

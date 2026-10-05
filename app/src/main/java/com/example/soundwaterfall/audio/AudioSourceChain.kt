package com.example.soundwaterfall.audio

/** One candidate audio source, with the label shown in the app bar. */
data class AudioSourceOption(val source: Int, val label: String)

/**
 * Builds the preference-ordered list of audio sources to try (spec §5.1).
 *
 * MIC routes through the platform's AGC, noise suppression and often a high-pass
 * filter, all of which visibly distort the spectrum. UNPROCESSED bypasses them
 * but is API 24+ and optional for OEMs, so the chain degrades gracefully and the
 * UI shows which source actually won.
 *
 * Deliberately free of Android imports — the constants are inlined — so the
 * ordering logic is unit-testable on the JVM across every API level at once.
 */
object AudioSourceChain {

    /** MediaRecorder.AudioSource.MIC */
    const val MIC = 1

    /** MediaRecorder.AudioSource.VOICE_RECOGNITION — usually bypasses AGC. */
    const val VOICE_RECOGNITION = 6

    /** MediaRecorder.AudioSource.UNPROCESSED — API 24+, optional for OEMs. */
    const val UNPROCESSED = 9

    /** Preferred first; 44.1 kHz is the fallback if 48 kHz is refused. */
    val SAMPLE_RATES = intArrayOf(48000, 44100)

    fun candidates(sdkInt: Int, unprocessedSupported: Boolean): List<AudioSourceOption> {
        val out = ArrayList<AudioSourceOption>(3)
        if (sdkInt >= 24 && unprocessedSupported) {
            out += AudioSourceOption(UNPROCESSED, "UNPROCESSED")
        }
        out += AudioSourceOption(VOICE_RECOGNITION, "VOICE_RECOGNITION")
        out += AudioSourceOption(MIC, "MIC")
        return out
    }
}

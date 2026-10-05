package com.example.soundwaterfall.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourceChainTest {

    /**
     * Spec §5.1. The primary test device is a Lenovo A6010 on API 21, so this
     * is the case that actually runs: UNPROCESSED is API 24+ and must not even
     * be attempted.
     */
    @Test
    fun api21OffersVoiceRecognitionThenMic() {
        val c = AudioSourceChain.candidates(sdkInt = 21, unprocessedSupported = false)
        assertEquals(listOf(6, 1), c.map { it.source })
        assertEquals(listOf("VOICE_RECOGNITION", "MIC"), c.map { it.label })
    }

    @Test
    fun api21IgnoresAFalseUnprocessedClaim() {
        // Below API 24 the property cannot be trusted and the constant does not exist.
        val c = AudioSourceChain.candidates(sdkInt = 21, unprocessedSupported = true)
        assertEquals(listOf(6, 1), c.map { it.source })
    }

    @Test
    fun api24WithDeviceSupportPrefersUnprocessed() {
        val c = AudioSourceChain.candidates(sdkInt = 24, unprocessedSupported = true)
        assertEquals(listOf(9, 6, 1), c.map { it.source })
        assertEquals("UNPROCESSED", c.first().label)
    }

    @Test
    fun api24WithoutDeviceSupportSkipsUnprocessed() {
        val c = AudioSourceChain.candidates(sdkInt = 24, unprocessedSupported = false)
        assertEquals(listOf(6, 1), c.map { it.source })
    }

    @Test
    fun micIsAlwaysTheLastResort() {
        for (sdk in intArrayOf(21, 23, 24, 28, 34, 37)) {
            for (supported in booleanArrayOf(true, false)) {
                val c = AudioSourceChain.candidates(sdk, supported)
                assertEquals("sdk=$sdk supported=$supported", 1, c.last().source)
            }
        }
    }

    @Test
    fun fortyEightKilohertzIsTriedBeforeFortyFourPointOne() {
        assertEquals(listOf(48000, 44100), AudioSourceChain.SAMPLE_RATES.toList())
    }
}

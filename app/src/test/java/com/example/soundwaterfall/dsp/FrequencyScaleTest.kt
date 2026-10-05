package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class FrequencyScaleTest {

    @Test
    fun derivesFftSizeAndBinWidthFromBinCount() {
        val s = FrequencyScale(sampleRate = 48000, binCount = 1025)
        assertEquals(2048, s.fftSize)
        assertEquals(24000f, s.nyquist, 1e-3f)
        assertEquals(23.4375f, s.binWidth, 1e-4f)
    }

    /** Spec §9: at N=2048 / 48 kHz a 1 kHz tone belongs in bin 43. */
    @Test
    fun oneKilohertzLandsInBin43AtTheDefaultSettings() {
        val s = FrequencyScale(48000, 1025)
        // Nearest bin, not truncation: 1000 / 23.4375 = 42.67, and bin 43
        // (1007.8 Hz) is 7.8 Hz from 1 kHz while bin 42 (984.4 Hz) is 15.6 Hz away.
        assertEquals(43, (1000f / s.binWidth).roundToInt())
        assertEquals(1007.8125f, s.frequencyOf(43), 1e-3f)
    }

    @Test
    fun binZeroIsDcAndTheLastBinIsNyquist() {
        val s = FrequencyScale(48000, 1025)
        assertEquals(0f, s.frequencyOf(0), 1e-6f)
        assertEquals(s.nyquist, s.frequencyOf(s.binCount - 1), 1e-3f)
    }

    /**
     * REVIEW FOCUS 5. If 48 kHz is refused, every label must follow the granted
     * rate. A hardcoded 48000 anywhere would show up here.
     */
    @Test
    fun everythingFollowsTheGrantedSampleRate() {
        val s = FrequencyScale(sampleRate = 44100, binCount = 1025)
        assertEquals(22050f, s.nyquist, 1e-3f)
        assertEquals(21.533203f, s.binWidth, 1e-4f)
        assertEquals(22050f, s.frequencyOf(1024), 1e-2f)
        assertEquals(listOf(0, 4000, 8000, 12000, 16000, 20000), s.gridlineFrequencies(4000))
    }

    @Test
    fun gridlinesCoverTheRangeAtFortyEightKilohertz() {
        val s = FrequencyScale(48000, 1025)
        assertEquals(listOf(0, 4000, 8000, 12000, 16000, 20000, 24000), s.gridlineFrequencies(4000))
    }

    @Test
    fun mapsFrequencyLinearlyOntoPixels() {
        val s = FrequencyScale(48000, 1025)
        assertEquals(0f, s.xOf(0f, 720), 1e-4f)
        assertEquals(360f, s.xOf(12000f, 720), 1e-3f)
        assertEquals(720f, s.xOf(24000f, 720), 1e-3f)
    }

    /** REVIEW FOCUS 3: a view that has not been laid out yet has width 0. */
    @Test
    fun zeroPixelWidthDoesNotDivideByZero() {
        val s = FrequencyScale(48000, 1025)
        assertEquals(0f, s.xOf(12000f, 0), 1e-6f)
    }

    /**
     * Spec §6.2: "onDraw allocates nothing". gridlineFrequencies() builds an
     * ArrayList and boxes every element, so the views need a variant that fills
     * a buffer they own and allocated once.
     */
    @Test
    fun gridlinesCanBeWrittenIntoACallerOwnedBufferWithoutAllocating() {
        val s = FrequencyScale(48000, 1025)
        val buf = IntArray(16)
        val n = s.gridlineFrequenciesInto(4000, buf)
        assertEquals(7, n)
        assertEquals(listOf(0, 4000, 8000, 12000, 16000, 20000, 24000), buf.take(n))
    }

    @Test
    fun gridlineBufferFillIsTruncatedRatherThanOverrun() {
        val s = FrequencyScale(48000, 1025)
        val tiny = IntArray(3)
        assertEquals(3, s.gridlineFrequenciesInto(4000, tiny))
        assertEquals(listOf(0, 4000, 8000), tiny.toList())
    }

    @Test
    fun gridlineBufferVariantAgreesWithTheListVariant() {
        for (rate in intArrayOf(48000, 44100)) {
            val s = FrequencyScale(rate, 1025)
            val buf = IntArray(32)
            val n = s.gridlineFrequenciesInto(4000, buf)
            assertEquals(s.gridlineFrequencies(4000), buf.take(n))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveSampleRate() {
        FrequencyScale(0, 1025)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBinCountBelowTwo() {
        FrequencyScale(48000, 1)
    }
}

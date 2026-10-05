package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

class SpectrumAnalyzerTest {

    private fun analyzer(
        fftSize: Int,
        window: WindowFunction,
        dbFloor: Float = -90f,
    ) = SpectrumAnalyzer(JTransformsFftEngine(fftSize), window, dbFloor)

    /** Full-scale cosine at an exact bin centre, as 16-bit PCM. */
    private fun cosineFrame(fftSize: Int, bin: Double): ShortArray =
        ShortArray(fftSize) {
            (cos(2.0 * PI * bin * it / fftSize) * 32767.0).roundToInt().toShort()
        }

    /**
     * THE INVARIANT (spec §5.4). A full-scale sine must read 0 dBFS for every
     * window and every FFT size. Without this, switching from rectangular to
     * Blackman would move the whole display by about 7.5 dB and the user could
     * not separate the window's real effect from a global gain change.
     */
    @Test
    fun fullScaleSineReadsZeroDbfsForEveryWindowAndSize() {
        for (fftSize in AnalyzerSettings.FFT_SIZES) {
            for (window in WindowFunction.entries) {
                val a = analyzer(fftSize, window)
                val out = FloatArray(a.binCount)
                val bin = fftSize / 16            // 128 at N=2048, an exact centre
                a.analyze(cosineFrame(fftSize, bin.toDouble()), out)

                assertEquals("peak for $window at N=$fftSize", 0.0f, out[bin], 0.1f)
                assertEquals(
                    "peak bin for $window at N=$fftSize",
                    bin,
                    out.indices.maxByOrNull { out[it] },
                )
            }
        }
    }

    /**
     * DC and Nyquist are single-sided and would otherwise read +6 dB. See the
     * refinement note on this task.
     */
    @Test
    fun fullScaleDcReadsZeroDbfs() {
        for (window in WindowFunction.entries) {
            val a = analyzer(2048, window)
            val out = FloatArray(a.binCount)
            a.analyze(ShortArray(2048) { 32767 }, out)
            assertEquals("DC for $window", 0.0f, out[0], 0.1f)
        }
    }

    @Test
    fun fullScaleNyquistReadsZeroDbfs() {
        val a = analyzer(2048, WindowFunction.RECTANGULAR)
        val out = FloatArray(a.binCount)
        a.analyze(ShortArray(2048) { if (it % 2 == 0) 32767 else -32767 }, out)
        assertEquals(0.0f, out[a.binCount - 1], 0.1f)
    }

    /** REVIEW FOCUS 4: silence must not produce NaN, -Infinity, or anything below the floor. */
    @Test
    fun silenceSitsExactlyAtTheFloorWithNoNaN() {
        val a = analyzer(1024, WindowFunction.HANN, dbFloor = -90f)
        val out = FloatArray(a.binCount)
        a.analyze(ShortArray(1024), out)

        for (k in out.indices) {
            assertTrue("bin $k was NaN", !out[k].isNaN())
            assertTrue("bin $k was infinite", out[k].isFinite())
            assertEquals("bin $k", -90f, out[k], 1e-4f)
        }
    }

    @Test
    fun outputIsAlwaysClampedToTheFloorAndZero() {
        val a = analyzer(1024, WindowFunction.HANN, dbFloor = -60f)
        val out = FloatArray(a.binCount)
        a.analyze(cosineFrame(1024, 64.0), out)
        out.forEach {
            assertTrue("$it below floor", it >= -60f)
            assertTrue("$it above 0 dBFS", it <= 0f)
        }
    }

    /**
     * Spec §9: executable documentation of what the app exists to show. A sine
     * placed BETWEEN bin centres leaks into neighbouring bins, and how far it
     * leaks is the window's defining property.
     */
    @Test
    fun leakageSkirtIsFarHigherForRectangularThanBlackman() {
        val fftSize = 2048
        val bin = 100.5                     // deliberately off-centre
        val probe = 108                     // 7.5 bins from the peak
        val floor = -140f

        val rect = analyzer(fftSize, WindowFunction.RECTANGULAR, floor)
        val blackman = analyzer(fftSize, WindowFunction.BLACKMAN, floor)
        val rectOut = FloatArray(rect.binCount)
        val blackOut = FloatArray(blackman.binCount)

        rect.analyze(cosineFrame(fftSize, bin), rectOut)
        blackman.analyze(cosineFrame(fftSize, bin), blackOut)

        assertTrue(
            "rect skirt ${rectOut[probe]} should be >=20 dB above blackman ${blackOut[probe]}",
            rectOut[probe] > blackOut[probe] + 20f,
        )
    }

    /**
     * Scalloping loss: an off-centre sine reads BELOW 0 dBFS. Rectangular loses
     * the most (about -3.9 dB at the worst case), Blackman the least.
     */
    @Test
    fun offCentreSineShowsScallopingLossWithinKnownBounds() {
        val fftSize = 2048
        val frame = cosineFrame(fftSize, 100.5)

        val rect = analyzer(fftSize, WindowFunction.RECTANGULAR, -140f)
        val rectOut = FloatArray(rect.binCount)
        rect.analyze(frame, rectOut)
        val rectPeak = rectOut.max()
        assertTrue("rect peak $rectPeak should be in -4.5..0 dB", rectPeak in -4.5f..0f)

        val black = analyzer(fftSize, WindowFunction.BLACKMAN, -140f)
        val blackOut = FloatArray(black.binCount)
        black.analyze(frame, blackOut)
        val blackPeak = blackOut.max()
        assertTrue("blackman peak $blackPeak should lose less than rect", blackPeak > rectPeak)
    }

    @Test
    fun changingWindowChangesLeakageWithoutRebuildingTheAnalyzer() {
        val a = analyzer(2048, WindowFunction.RECTANGULAR, -140f)
        val out = FloatArray(a.binCount)
        val frame = cosineFrame(2048, 100.5)

        a.analyze(frame, out)
        val rectSkirt = out[108]

        a.setWindow(WindowFunction.BLACKMAN)
        a.analyze(frame, out)
        val blackSkirt = out[108]

        assertTrue("skirt should drop after switching window", blackSkirt < rectSkirt - 20f)
    }

    @Test
    fun analyzeAllocatesNothingAcrossRepeatedCalls() {
        // A crude but effective smoke test: 2000 frames must not grow the heap
        // by anything resembling per-frame allocation of the work buffers.
        val a = analyzer(2048, WindowFunction.HANN)
        val out = FloatArray(a.binCount)
        val frame = cosineFrame(2048, 128.0)
        repeat(2000) { a.analyze(frame, out) }
        assertEquals(0.0f, out[128], 0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutputArrayOfTheWrongLength() {
        val a = analyzer(1024, WindowFunction.HANN)
        a.analyze(ShortArray(1024), FloatArray(7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFrameOfTheWrongLength() {
        val a = analyzer(1024, WindowFunction.HANN)
        a.analyze(ShortArray(512), FloatArray(a.binCount))
    }
}

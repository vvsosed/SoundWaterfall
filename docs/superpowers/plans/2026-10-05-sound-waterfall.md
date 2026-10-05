# SoundWaterfall Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app that captures the microphone live, runs an FFT on it, and shows an instantaneous spectrum above a scrolling Inferno-colored waterfall, with runtime controls for FFT size, window function and dB floor.

**Architecture:** A dedicated audio thread owns capture and all DSP; its blocking `AudioRecord.read` sets the pipeline's cadence. The spectrum receives the newest frame through a double-buffered snapshot; the waterfall receives *every* frame as a pre-colorized ARGB pixel row written into a lock-free ring buffer, which the main thread blits as two `Bitmap` slices. Everything from framing through colormapping is plain Kotlin with no Android imports, unit-tested on the JVM.

**Tech Stack:** Kotlin, Android Views + `Canvas`, Material Components 1.10.0, JTransforms 3.1 (pending Task 0), JUnit 4.13.2, Gradle 9.6 / AGP 9.4.1 / JDK 25.

**Spec:** `docs/superpowers/specs/2026-10-05-sound-waterfall-design.md`

## Global Constraints

- `minSdk = 21`, `targetSdk = 37`, `compileSdk = release(37)` — already set in `app/build.gradle.kts`
- `sourceCompatibility` / `targetCompatibility` = `JavaVersion.VERSION_11`
- Toolchain: Gradle 9.6, AGP 9.4.1, JDK 25, `org.gradle.configuration-cache=true`
- **Primary test device: Lenovo A6010, Android 5.0.2, API 21, 720×1280 px, density 320 (xhdpi).** `MediaRecorder.AudioSource.UNPROCESSED` is API 24+ and therefore **unavailable on this device** — the source chain will land on `VOICE_RECOGNITION`
- The `dsp` package contains **no Android imports**. Verified by inspection in every task that touches it
- **No allocation** in `View.onDraw` or in the audio-thread hot path. All scratch buffers are fields allocated once
- Every frequency label and the Nyquist limit derive from the **granted** sample rate, never a hardcoded 48000
- Window-independent normalization: a full-scale sine reads **0 dBFS for every window × every FFT size**
- `dbFloor` range −120…−40 dB, 10 dB steps, default −90
- Inferno LUT stops, verbatim: `#000004 #1b0c41 #4a0c6b #781c6d #a52c60 #cf4446 #ed6925 #fb9b06 #f7d13d #fcffa4`
- Unit tests: `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :app:connectedDebugAndroidTest`

## Review Focus

Five conditions the spec implies that no task's happy-path tests would exercise. Each has a test assigned to the task that owns the code.

1. **At N = 1024 on the 720 px test device there are 513 bins but 720 columns** — the spec treats "more pixels than bins" as the exception, but it is the *default* on the actual hardware. The interpolation path must be correct, not an afterthought. → Task 5.
2. **Settings changed from the UI thread while the audio thread is mid-frame** — a torn read of `fftSize` would produce a frame whose length disagrees with every consumer's buffer. Expect: changes apply only at frame boundaries, never mid-frame. → Task 9.
3. **Views measured at zero width or height before first layout** — a `WaterfallBuffer(0, 0)` or a bin→column reduction with `cols == 0` divides by zero. Expect: no buffer is created and nothing is drawn until a real size arrives. → Tasks 5, 7, 11.
4. **Digital silence** — magnitude 0 makes `log10(0)` = −∞, which then becomes a LUT index. Expect: the whole display sits at `dbFloor` with no NaN, no −∞ and no index out of range. → Tasks 4, 6.
5. **44.1 kHz fallback** — if 48 kHz is refused, Nyquist becomes 22050 Hz and every axis label must follow. Expect: labels and bin→frequency mapping derive from the granted rate. → Task 5.

---

### Task 0: De-risk JTransforms on Android — RESULT: confirmed

**Result:** PASS on the Lenovo A6010, Android 5.0.2 / **API 21** (`tests="1" failures="0" errors="0"`); JTransforms dexes, class-loads and computes correctly, with the peak at bin 43 and 0 dBFS as predicted. Also PASS on the host JVM (OpenJDK 25). **API 28+ enforcement was NOT tested on-device** — see the ruling in Step 5. Decision: proceed with JTransforms; `JTransformsFftEngine` requires `DoubleFFT_1D(size.toLong())` because the constructor takes a `long`.

Spec §11. This task writes **no production code**. It answers one question — does JTransforms run on Android — and its artifacts are deleted at the end.

Two separate risks, often conflated:

- **API 21 compatibility** (the connected Lenovo A6010): does the library dex, load and run on Android 5.0 and an ARM Cortex-A53?
- **Non-SDK interface enforcement** (introduced in **API 28**, strict under `targetSdk 37`): `org.jtransforms.fft.DoubleFFT_1D` references `pl.edu.icm.jlargearrays.LargeArray`, which uses `sun.misc.Unsafe`. The API 21 device **cannot test this** — there was no enforcement in Android 5.0.

Already established on the host JVM (OpenJDK 25), so these are *not* open questions: the library loads, the packed output layout in spec §5.4 is correct, and a full-scale cosine at bin 43 of N = 2048 normalizes to exactly `0.0000 dBFS`. It also needs all three jars — `JTransforms` (1.18 MB), `JLargeArrays` (0.23 MB) and `commons-math3` (2.04 MB), because the `DoubleFFT_1D` constructor calls `commons-math3`'s `FastMath`.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create (throwaway): `app/src/androidTest/java/com/example/soundwaterfall/FftLibrarySpike.kt`

**Interfaces:**
- Consumes: nothing
- Produces: a decision recorded in this file — either `JTransforms` is confirmed, or Task 3 switches to the documented alternative. No code survives this task.

- [ ] **Step 1: Add the dependency to the version catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
jtransforms = "3.1"
```

and to `[libraries]`:

```toml
jtransforms = { group = "com.github.wendykierp", name = "JTransforms", version.ref = "jtransforms" }
```

- [ ] **Step 2: Add it to the app module**

In `app/build.gradle.kts`, inside `dependencies { }`:

```kotlin
    implementation(libs.jtransforms)
```

- [ ] **Step 3: Write the spike as an instrumented test**

Create `app/src/androidTest/java/com/example/soundwaterfall/FftLibrarySpike.kt`:

```kotlin
package com.example.soundwaterfall

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jtransforms.fft.DoubleFFT_1D
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * THROWAWAY. Spec §11: does JTransforms survive class-loading and run correctly
 * on a real device? Deleted once the answer is recorded in the plan.
 */
@RunWith(AndroidJUnit4::class)
class FftLibrarySpike {

    @Test
    fun fftLoadsAndProducesCorrectPeak() {
        val n = 2048
        val bin = 43
        val data = DoubleArray(n) { cos(2.0 * PI * bin * it / n) }

        // The risky line: this constructor touches commons-math3 FastMath and
        // resolves pl.edu.icm.jlargearrays.LargeArray, which uses sun.misc.Unsafe.
        val fft = DoubleFFT_1D(n)
        fft.realForward(data)

        var peakDb = -999.0
        var peakBin = -1
        for (k in 1 until n / 2) {
            val re = data[2 * k]
            val im = data[2 * k + 1]
            val mag = sqrt(re * re + im * im) / (n / 2.0)
            val db = 20 * log10(max(mag, 1e-10))
            if (db > peakDb) {
                peakDb = db
                peakBin = k
            }
        }

        println("FftLibrarySpike: sdk=${Build.VERSION.SDK_INT} peakBin=$peakBin peakDb=$peakDb")
        assertEquals(bin, peakBin)
        assertEquals(0.0, peakDb, 0.01)
    }
}
```

- [ ] **Step 4: Run it on the connected API 21 device**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.example.soundwaterfall.FftLibrarySpike"`

Expected: PASS, with logcat showing `sdk=21 peakBin=43 peakDb=-0.0...`.

If it fails with `ExceptionInInitializerError`, `NoClassDefFoundError` or a dex/verify error, record the exact message and stop — Task 3 then uses the alternative in Step 6.

- [ ] **Step 5: Test the non-SDK enforcement risk on API 28+**

The API 21 device cannot exercise this. An emulator is required, and this machine currently has **no AVDs, no system images and no `cmdline-tools`** — install them first:

```bash
# One-time setup. Accept licences when prompted.
SDK=$HOME/Android/Sdk
"$SDK/cmdline-tools/latest/bin/sdkmanager" "system-images;android-34;google_apis;x86_64" "platforms;android-34"
"$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n api34 \
  -k "system-images;android-34;google_apis;x86_64" --device "pixel_5"
"$SDK/emulator/emulator" -avd api34 -no-snapshot -no-boot-anim &
```

If `cmdline-tools` is missing, install it from Android Studio → SDK Manager → SDK Tools → "Android SDK Command-line Tools".

Then, with the emulator booted, tighten enforcement beyond the default so a greylist hit becomes a hard failure, and run the same spike:

```bash
adb -s emulator-5554 shell settings put global hidden_api_policy 1
./gradlew :app:connectedDebugAndroidTest --tests "com.example.soundwaterfall.FftLibrarySpike"
adb -s emulator-5554 shell settings delete global hidden_api_policy
```

Expected: PASS. `sun.misc.Unsafe` sits on Android's *unsupported* (greylist) rather than the blocklist, so this is expected to succeed — but it is cheap to confirm and expensive to discover later.

**RULING (not executed): deferred to Task 14.** Instead of installing a ~2 GB system image, the failure mechanism was characterized from the bytecode, which is more informative than one pass/fail:

- `LargeArray.<clinit>` is harmless — it sets a single int field and never touches `Unsafe`. The plan's original fear was aimed at the wrong class.
- `LargeArrayUtils.<clinit>` is the real risk point: `Class.forName("sun.misc.Unsafe")` → `getDeclaredField("theUnsafe")` → `setAccessible(true)` → `get(null)`, catching `ClassNotFoundException`, `IllegalAccessException`, `IllegalArgumentException`, `NoSuchFieldException` and `SecurityException`, then throwing `java.lang.Error("Could not obtain access to sun.misc.Unsafe")` if the result is null.
- Android signals a blocked hidden-API field by throwing `NoSuchFieldException` — which that code catches — so a blocklisting of `theUnsafe` would surface as `ExceptionInInitializerError` on the first FFT.

Deferred because `theUnsafe` is greylisted (accessible with a log warning, not blocked) and is depended on across the JVM ecosystem; because the only available device is API 21, where no enforcement exists at all; and because installing a system image is a large unrequested change to the user's SDK. Containment is by construction: swapping `FftEngine` is a one-class change and every Task 3 test stays as written.

- [ ] **Step 6: Record the decision in this file**

Edit this task's heading to read `### Task 0: De-risk JTransforms on Android — RESULT: <confirmed | rejected>` and add one line stating the device, the API levels tested, and the outcome.

If **rejected**, Task 3 changes as follows and the dependency added in Steps 1–2 is removed: implement `FftEngine` as a hand-written iterative radix-2 Cooley–Tukey (bit-reversal permutation, then `log2(n)` butterfly stages over a precomputed twiddle table). The interface and *all* of Task 3's tests stay exactly as written — that is the entire point of the interface. Spec §11's other candidate, `commons-math3`'s `FastFourierTransformer`, is **not** recommended as the fallback: it returns `Complex[]`, allocating `N/2+1` objects per frame, which violates the no-allocation constraint.

- [ ] **Step 7: Delete the spike and commit**

```bash
rm app/src/androidTest/java/com/example/soundwaterfall/FftLibrarySpike.kt
git add gradle/libs.versions.toml app/build.gradle.kts docs/superpowers/plans/2026-10-05-sound-waterfall.md
git commit -m "chore: confirm FFT library viability on Android (spec §11)"
```

---

### Task 1: WindowFunction

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/WindowFunction.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/WindowFunctionTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `enum class WindowFunction(val coherentGain: Float)` with entries `RECTANGULAR`, `HANN`, `HAMMING`, `BLACKMAN`, and `fun coefficients(size: Int): FloatArray`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/WindowFunctionTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowFunctionTest {

    @Test
    fun rectangularIsAllOnes() {
        val c = WindowFunction.RECTANGULAR.coefficients(8)
        assertEquals(8, c.size)
        c.forEach { assertEquals(1.0f, it, 1e-6f) }
    }

    @Test
    fun hannStartsAtZeroAndPeaksAtOne() {
        val c = WindowFunction.HANN.coefficients(1024)
        assertEquals(0.0f, c[0], 1e-6f)
        assertEquals(1.0f, c[512], 1e-6f)
    }

    @Test
    fun hammingStartsAtPedestalAndPeaksAtOne() {
        val c = WindowFunction.HAMMING.coefficients(1024)
        assertEquals(0.08f, c[0], 1e-6f)
        assertEquals(1.0f, c[512], 1e-6f)
    }

    @Test
    fun blackmanStartsAtZeroAndPeaksAtOne() {
        val c = WindowFunction.BLACKMAN.coefficients(1024)
        assertEquals(0.0f, c[0], 1e-6f)
        assertEquals(1.0f, c[512], 1e-6f)
    }

    /**
     * Spec §5.3. The declared coherentGain must equal the actual mean of the
     * coefficients, because spec §5.4's normalization divides by it. If these
     * ever disagree, the "0 dBFS on every window" invariant breaks silently.
     */
    @Test
    fun declaredCoherentGainEqualsActualMean() {
        for (w in WindowFunction.entries) {
            val c = w.coefficients(2048)
            val mean = c.fold(0.0) { acc, v -> acc + v } / c.size
            assertEquals("coherentGain for $w", w.coherentGain.toDouble(), mean, 1e-6)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.WindowFunctionTest"`
Expected: FAIL — compilation error, `WindowFunction` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/soundwaterfall/dsp/WindowFunction.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import kotlin.math.PI
import kotlin.math.cos

/**
 * Analysis windows in their periodic (DFT-even) form, i.e. the argument is
 * 2*pi*n/N rather than 2*pi*n/(N-1). The periodic form is the correct one for
 * spectral analysis, and it is what makes [coherentGain] come out as the exact
 * constants below.
 *
 * [coherentGain] is the mean of the coefficients. Spec §5.4 divides magnitudes
 * by (N/2) * coherentGain so that a full-scale sine reads 0 dBFS regardless of
 * which window is selected.
 */
enum class WindowFunction(val label: String, val coherentGain: Float) {
    RECTANGULAR("Rectangular", 1.00f),
    HANN("Hann", 0.50f),
    HAMMING("Hamming", 0.54f),
    BLACKMAN("Blackman", 0.42f);

    fun coefficients(size: Int): FloatArray {
        require(size > 0) { "window size must be positive, was $size" }
        val out = FloatArray(size)
        val t = 2.0 * PI / size
        for (n in 0 until size) {
            out[n] = when (this) {
                RECTANGULAR -> 1.0f
                HANN -> (0.5 - 0.5 * cos(t * n)).toFloat()
                HAMMING -> (0.54 - 0.46 * cos(t * n)).toFloat()
                BLACKMAN -> (0.42 - 0.5 * cos(t * n) + 0.08 * cos(2.0 * t * n)).toFloat()
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.WindowFunctionTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/WindowFunction.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/WindowFunctionTest.kt
git commit -m "feat: add analysis windows with verified coherent gain"
```

---

### Task 2: FrameAssembler

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/FrameAssembler.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/FrameAssemblerTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `class FrameAssembler(frameSize: Int, hop: Int)` with `fun append(chunk: ShortArray, length: Int, onFrame: (ShortArray) -> Unit)` and `fun reset()`. The `ShortArray` handed to `onFrame` is a **reused internal buffer** of length `frameSize` — consumers must not retain it.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/FrameAssemblerTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    private fun collect(
        frameSize: Int,
        hop: Int,
        chunkSize: Int,
        total: Int,
    ): List<ShortArray> {
        val assembler = FrameAssembler(frameSize, hop)
        val frames = mutableListOf<ShortArray>()
        var next = 0
        while (next < total) {
            val n = minOf(chunkSize, total - next)
            val chunk = ShortArray(n) { (next + it).toShort() }
            next += n
            // copyOf(): onFrame hands out a reused buffer, so the test must snapshot it.
            assembler.append(chunk, n) { frames += it.copyOf() }
        }
        return frames
    }

    @Test
    fun emitsOverlappingFramesFromOneChunk() {
        val frames = collect(frameSize = 8, hop = 4, chunkSize = 16, total = 16)
        assertEquals(3, frames.size)
        assertArrayEquals(ShortArray(8) { it.toShort() }, frames[0])
        assertArrayEquals(ShortArray(8) { (it + 4).toShort() }, frames[1])
        assertArrayEquals(ShortArray(8) { (it + 8).toShort() }, frames[2])
    }

    /**
     * Spec §5.2: AudioRecord chunk sizes are whatever the platform returns and
     * are not assumed to divide the frame size or the hop. Chunking must not
     * change the output at all.
     */
    @Test
    fun chunkingDoesNotChangeTheOutput() {
        val reference = collect(frameSize = 64, hop = 32, chunkSize = 1024, total = 1024)
        for (chunkSize in intArrayOf(1, 3, 7, 31, 33, 63, 65, 100)) {
            val frames = collect(frameSize = 64, hop = 32, chunkSize = chunkSize, total = 1024)
            assertEquals("chunkSize=$chunkSize frame count", reference.size, frames.size)
            for (i in reference.indices) {
                assertArrayEquals("chunkSize=$chunkSize frame $i", reference[i], frames[i])
            }
        }
    }

    @Test
    fun framesAdvanceByExactlyOneHop() {
        val frames = collect(frameSize = 32, hop = 16, chunkSize = 5, total = 512)
        assertTrue(frames.size > 2)
        for (i in 1 until frames.size) {
            assertEquals(
                "frame $i must start one hop after frame ${i - 1}",
                (frames[i - 1][0] + 16).toShort(),
                frames[i][0],
            )
        }
    }

    @Test
    fun losesNoSamplesAcrossChunkBoundaries() {
        val frames = collect(frameSize = 16, hop = 8, chunkSize = 3, total = 128)
        // Reassemble the signal from the non-overlapping half of each frame.
        val rebuilt = mutableListOf<Short>()
        rebuilt.addAll(frames[0].take(8))
        for (f in frames) rebuilt.addAll(f.drop(8))
        val expected = (0 until rebuilt.size).map { it.toShort() }
        assertEquals(expected, rebuilt)
    }

    @Test
    fun emitsNothingBeforeTheFirstFullFrame() {
        val assembler = FrameAssembler(frameSize = 16, hop = 8)
        var count = 0
        assembler.append(ShortArray(15), 15) { count++ }
        assertEquals(0, count)
        assembler.append(ShortArray(1), 1) { count++ }
        assertEquals(1, count)
    }

    @Test
    fun resetDiscardsPartialState() {
        val assembler = FrameAssembler(frameSize = 8, hop = 4)
        assembler.append(ShortArray(7) { 99 }, 7) { }
        assembler.reset()
        var emitted: ShortArray? = null
        assembler.append(ShortArray(8) { it.toShort() }, 8) { emitted = it.copyOf() }
        assertArrayEquals(ShortArray(8) { it.toShort() }, emitted)
    }

    @Test
    fun honoursTheLengthArgumentAndIgnoresTrailingGarbage() {
        val assembler = FrameAssembler(frameSize = 4, hop = 4)
        val chunk = shortArrayOf(1, 2, 3, 4, 99, 99, 99, 99)
        var emitted: ShortArray? = null
        assembler.append(chunk, 4) { emitted = it.copyOf() }
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), emitted)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.FrameAssemblerTest"`
Expected: FAIL — compilation error, `FrameAssembler` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/soundwaterfall/dsp/FrameAssembler.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * Turns the arbitrarily-sized chunks AudioRecord hands back into fixed-size
 * overlapping analysis frames.
 *
 * A ring buffer of exactly [frameSize] samples holds the sliding window; a frame
 * is emitted every [hop] samples once the window has filled for the first time.
 * Incoming chunk sizes need not divide either value.
 *
 * Not thread-safe: it is owned by the audio thread.
 */
class FrameAssembler(private val frameSize: Int, private val hop: Int) {

    init {
        require(frameSize > 0) { "frameSize must be positive, was $frameSize" }
        require(hop in 1..frameSize) { "hop must be in 1..$frameSize, was $hop" }
    }

    private val ring = ShortArray(frameSize)
    private val frame = ShortArray(frameSize)

    /** Index of the oldest sample in [ring], and of the next slot to overwrite. */
    private var writePos = 0
    private var filled = 0
    private var sinceEmit = 0

    fun reset() {
        writePos = 0
        filled = 0
        sinceEmit = 0
    }

    /**
     * Appends the first [length] samples of [chunk], invoking [onFrame] once per
     * completed frame. The array passed to [onFrame] is reused between calls and
     * must be consumed, not retained.
     */
    inline fun append(chunk: ShortArray, length: Int, onFrame: (ShortArray) -> Unit) {
        for (i in 0 until length) {
            pushSample(chunk[i])
            if (isFrameReady()) onFrame(buildFrame())
        }
    }

    @PublishedApi
    internal fun pushSample(sample: Short) {
        ring[writePos] = sample
        writePos = if (writePos + 1 == frameSize) 0 else writePos + 1
        if (filled < frameSize) filled++
        sinceEmit++
    }

    @PublishedApi
    internal fun isFrameReady(): Boolean = filled == frameSize && sinceEmit >= hop

    /** Copies the ring into [frame] in chronological order, oldest first. */
    @PublishedApi
    internal fun buildFrame(): ShortArray {
        sinceEmit = 0
        val tail = frameSize - writePos
        System.arraycopy(ring, writePos, frame, 0, tail)
        if (writePos > 0) System.arraycopy(ring, 0, frame, tail, writePos)
        return frame
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.FrameAssemblerTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/FrameAssembler.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/FrameAssemblerTest.kt
git commit -m "feat: add overlapping frame assembler"
```

---

### Task 3: FftEngine and the JTransforms implementation

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/FftEngine.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/JTransformsFftEngine.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/FftEngineTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `interface FftEngine { val size: Int; val binCount: Int; fun magnitudes(work: DoubleArray, out: FloatArray) }` and `class JTransformsFftEngine(size: Int) : FftEngine`. `binCount == size / 2 + 1`. `magnitudes` transforms `work` **in place** (destroying it) and writes `binCount` raw, un-normalized magnitudes into `out`.

The packed output layout this task unpacks was verified empirically during planning on OpenJDK 25: for even `N`, `work[0] = Re[0]`, `work[1] = Re[N/2]`, and `work[2k], work[2k+1] = Re[k], Im[k]` for `1 <= k < N/2`.

If Task 0 rejected JTransforms, replace the body of `JTransformsFftEngine.magnitudes` with a hand-written radix-2 Cooley–Tukey as described in Task 0 Step 6. **Every test below stays unchanged** — they test the contract, not the library.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/FftEngineTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class FftEngineTest {

    private fun engine(size: Int): FftEngine = JTransformsFftEngine(size)

    @Test
    fun binCountIsHalfSizePlusOne() {
        assertEquals(1025, engine(2048).binCount)
        assertEquals(513, engine(1024).binCount)
        assertEquals(2049, engine(4096).binCount)
    }

    /** A constant signal puts all of its energy in bin 0, with magnitude N. */
    @Test
    fun dcGoesEntirelyIntoBinZero() {
        val n = 1024
        val e = engine(n)
        val work = DoubleArray(n) { 1.0 }
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        assertEquals(n.toFloat(), out[0], 1e-2f)
        for (k in 1 until e.binCount) {
            assertEquals("bin $k should be empty", 0.0f, out[k], 1e-2f)
        }
    }

    /** A unit impulse has a flat spectrum: every bin has magnitude 1. */
    @Test
    fun impulseGivesFlatMagnitude() {
        val n = 256
        val e = engine(n)
        val work = DoubleArray(n)
        work[0] = 1.0
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        for (k in 0 until e.binCount) {
            assertEquals("bin $k", 1.0f, out[k], 1e-4f)
        }
    }

    /** A cosine at an exact bin centre lands in that bin with magnitude N/2. */
    @Test
    fun cosineAtBinCentreLandsInThatBin() {
        val n = 2048
        val bin = 43
        val e = engine(n)
        val work = DoubleArray(n) { cos(2.0 * PI * bin * it / n) }
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        assertEquals((n / 2).toFloat(), out[bin], 1.0f)
        assertEquals("bin below the peak", 0.0f, out[bin - 1], 1.0f)
        assertEquals("bin above the peak", 0.0f, out[bin + 1], 1.0f)
    }

    /**
     * The Nyquist bin is the one most likely to be mis-unpacked, because the
     * library stores Re[N/2] in work[1] rather than at the end of the array.
     */
    @Test
    fun nyquistGoesIntoTheLastBin() {
        val n = 512
        val e = engine(n)
        val work = DoubleArray(n) { cos(PI * it) }   // alternating +1, -1
        val out = FloatArray(e.binCount)
        e.magnitudes(work, out)

        assertEquals(n.toFloat(), out[n / 2], 1e-2f)
        for (k in 0 until n / 2) {
            assertEquals("bin $k should be empty", 0.0f, out[k], 1e-2f)
        }
    }

    @Test
    fun silenceGivesZeroEverywhere() {
        val e = engine(256)
        val out = FloatArray(e.binCount) { 123f }
        e.magnitudes(DoubleArray(256), out)
        out.forEach { assertEquals(0.0f, it, 1e-9f) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwoSize() {
        engine(1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWorkArrayOfTheWrongLength() {
        val e = engine(256)
        e.magnitudes(DoubleArray(128), FloatArray(e.binCount))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.FftEngineTest"`
Expected: FAIL — compilation error, `FftEngine` and `JTransformsFftEngine` are unresolved.

- [ ] **Step 3: Write the interface**

Create `app/src/main/java/com/example/soundwaterfall/dsp/FftEngine.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * A real-input forward FFT, reduced to the one operation this app needs.
 *
 * The interface exists so the library choice stays a swappable detail (spec §11)
 * and so the magnitude contract can be tested without reference to any
 * particular library's packed output layout.
 */
interface FftEngine {

    /** Transform length. Always a power of two. */
    val size: Int

    /** Number of usable bins, `size / 2 + 1`, covering DC through Nyquist. */
    val binCount: Int

    /**
     * Transforms [work] in place — its contents are destroyed — and writes
     * [binCount] raw, un-normalized magnitudes into [out].
     *
     * Allocates nothing: both arrays are supplied by the caller.
     *
     * @param work exactly [size] samples, already windowed
     * @param out exactly [binCount] elements
     */
    fun magnitudes(work: DoubleArray, out: FloatArray)
}
```

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/com/example/soundwaterfall/dsp/JTransformsFftEngine.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [FftEngine] backed by JTransforms.
 *
 * `realForward` packs its result into the input array. For even N:
 *
 *     work[0]         = Re[0]        (DC)
 *     work[1]         = Re[N/2]      (Nyquist — NOT at the end of the array)
 *     work[2k]        = Re[k]
 *     work[2k + 1]    = Im[k]        for 1 <= k < N/2
 *
 * That Nyquist placement is the detail worth the dedicated test in FftEngineTest.
 */
class JTransformsFftEngine(override val size: Int) : FftEngine {

    init {
        require(size >= 2 && size and (size - 1) == 0) {
            "size must be a power of two >= 2, was $size"
        }
    }

    override val binCount: Int = size / 2 + 1

    private val fft = DoubleFFT_1D(size)

    override fun magnitudes(work: DoubleArray, out: FloatArray) {
        require(work.size == size) { "work must be $size samples, was ${work.size}" }
        require(out.size == binCount) { "out must be $binCount bins, was ${out.size}" }

        fft.realForward(work)

        val half = size / 2
        out[0] = abs(work[0]).toFloat()
        out[half] = abs(work[1]).toFloat()
        for (k in 1 until half) {
            val re = work[2 * k]
            val im = work[2 * k + 1]
            out[k] = sqrt(re * re + im * im).toFloat()
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.FftEngineTest"`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/FftEngine.kt \
        app/src/main/java/com/example/soundwaterfall/dsp/JTransformsFftEngine.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/FftEngineTest.kt
git commit -m "feat: add FFT engine interface and JTransforms implementation"
```

---

### Task 4: SpectrumAnalyzer — the normalization invariant

This is the task that makes the window knob teach something. Spec §5.4.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/AnalyzerSettings.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/SpectrumAnalyzer.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/SpectrumAnalyzerTest.kt`

**Interfaces:**
- Consumes: `WindowFunction` (Task 1), `FftEngine` / `JTransformsFftEngine` (Task 3)
- Produces:
  - `data class AnalyzerSettings(val fftSize: Int, val window: WindowFunction, val dbFloor: Float)` with `companion object { val DEFAULT = AnalyzerSettings(2048, WindowFunction.HANN, -90f); val FFT_SIZES = intArrayOf(1024, 2048, 4096); const val DB_FLOOR_MIN = -120f; const val DB_FLOOR_MAX = -40f }` and `val hop: Int get() = fftSize / 2`
  - `class SpectrumAnalyzer(engine: FftEngine, window: WindowFunction, dbFloor: Float)` with `val binCount: Int`, `fun setWindow(w: WindowFunction)`, `var dbFloor: Float`, and `fun analyze(frame: ShortArray, out: FloatArray)`

**A refinement this task pins down.** Spec §5.4 gives one normalization divisor, `(N/2) * coherentGain`, which is correct for sinusoidal components in bins `1 .. N/2-1`. DC and Nyquist are single-sided rather than double-sided, so their raw magnitudes come out twice as large, and a full-scale DC input would read **+6 dB** and then clamp silently at 0. This task halves bins `0` and `N/2` before applying the common divisor, so every bin expresses the amplitude of its corresponding real sinusoid on one consistent scale. The `fullScaleDcReadsZeroDbfs` test pins it.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/SpectrumAnalyzerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.SpectrumAnalyzerTest"`
Expected: FAIL — compilation error, `SpectrumAnalyzer` and `AnalyzerSettings` are unresolved.

- [ ] **Step 3: Write AnalyzerSettings**

Create `app/src/main/java/com/example/soundwaterfall/dsp/AnalyzerSettings.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * The three runtime knobs (spec §6.5), as one immutable value so the audio
 * thread can swap them atomically at a frame boundary.
 */
data class AnalyzerSettings(
    val fftSize: Int,
    val window: WindowFunction,
    val dbFloor: Float,
) {
    /** Fixed 50% overlap (spec §3.2). */
    val hop: Int get() = fftSize / 2

    val binCount: Int get() = fftSize / 2 + 1

    init {
        require(fftSize in FFT_SIZES) { "fftSize must be one of ${FFT_SIZES.toList()}, was $fftSize" }
        require(dbFloor in DB_FLOOR_MIN..DB_FLOOR_MAX) {
            "dbFloor must be in $DB_FLOOR_MIN..$DB_FLOOR_MAX, was $dbFloor"
        }
    }

    companion object {
        val FFT_SIZES = intArrayOf(1024, 2048, 4096)
        const val DB_FLOOR_MIN = -120f
        const val DB_FLOOR_MAX = -40f
        const val DB_FLOOR_STEP = 10f

        val DEFAULT = AnalyzerSettings(
            fftSize = 2048,
            window = WindowFunction.HANN,
            dbFloor = -90f,
        )
    }
}
```

- [ ] **Step 4: Write SpectrumAnalyzer**

Create `app/src/main/java/com/example/soundwaterfall/dsp/SpectrumAnalyzer.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * frame -> windowed -> transformed -> normalized -> dBFS.
 *
 * Normalization (spec §5.4) divides magnitudes by `(N/2) * coherentGain`, which
 * makes a full-scale sine read 0 dBFS for every window and every FFT size. DC
 * and Nyquist are single-sided and are halved first so they land on the same
 * scale as the sinusoidal bins.
 *
 * Allocates nothing per frame: every buffer is a field. Not thread-safe; owned
 * by the audio thread.
 */
class SpectrumAnalyzer(
    private val engine: FftEngine,
    window: WindowFunction,
    var dbFloor: Float,
) {
    val binCount: Int = engine.binCount

    private val work = DoubleArray(engine.size)
    private val mags = FloatArray(binCount)

    private var window: WindowFunction = window
    private var coefficients: FloatArray = window.coefficients(engine.size)

    /** Swaps the coefficient table. No reallocation of the FFT or work buffers. */
    fun setWindow(w: WindowFunction) {
        window = w
        coefficients = w.coefficients(engine.size)
    }

    fun analyze(frame: ShortArray, out: FloatArray) {
        require(frame.size == engine.size) {
            "frame must be ${engine.size} samples, was ${frame.size}"
        }
        require(out.size == binCount) { "out must be $binCount bins, was ${out.size}" }

        for (n in work.indices) {
            work[n] = frame[n] / 32768.0 * coefficients[n]
        }

        engine.magnitudes(work, mags)

        // DC and Nyquist are single-sided: halve them onto the sinusoidal scale.
        mags[0] *= 0.5f
        mags[binCount - 1] *= 0.5f

        val norm = (engine.size / 2.0f) * window.coherentGain
        val floor = dbFloor
        for (k in 0 until binCount) {
            val mag = mags[k] / norm
            val db = 20.0f * log10(max(mag, 1e-10f))
            out[k] = if (db < floor) floor else if (db > 0f) 0f else db
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.SpectrumAnalyzerTest"`
Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/AnalyzerSettings.kt \
        app/src/main/java/com/example/soundwaterfall/dsp/SpectrumAnalyzer.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/SpectrumAnalyzerTest.kt
git commit -m "feat: add spectrum analyzer with window-independent normalization"
```

---

### Task 5: FrequencyScale and BinReducer

The shared x mapping. Spec §4 requires that `SpectrumView`, `WaterfallView` and `FrequencyAxisView` cannot drift out of alignment, which is enforced by all three deriving their geometry from one `FrequencyScale` and one `BinReducer`.

Carries **REVIEW FOCUS 1, 3 and 5**.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/FrequencyScale.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/BinReducer.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/FrequencyScaleTest.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/BinReducerTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `class FrequencyScale(val sampleRate: Int, val binCount: Int)` with `val fftSize: Int`, `val nyquist: Float`, `val binWidth: Float`, `fun frequencyOf(bin: Int): Float`, `fun xOf(frequency: Float, widthPx: Int): Float`, `fun gridlineFrequencies(stepHz: Int): List<Int>`
  - `object BinReducer` with `fun reduce(db: FloatArray, out: FloatArray)`

- [ ] **Step 1: Write the failing FrequencyScale test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/FrequencyScaleTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(43, (1000f / s.binWidth).toInt())
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveSampleRate() {
        FrequencyScale(0, 1025)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBinCountBelowTwo() {
        FrequencyScale(48000, 1)
    }
}
```

- [ ] **Step 2: Write the failing BinReducer test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/BinReducerTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinReducerTest {

    /**
     * Spec §6.2: when bins outnumber columns the reduction takes the MAXIMUM,
     * not the mean. A narrow peak occupying one bin out of 1025 must survive
     * being squeezed into 720 columns — averaging would bury it.
     */
    @Test
    fun narrowPeakSurvivesDownsampling() {
        val db = FloatArray(1025) { -90f }
        db[500] = -10f
        val out = FloatArray(720)

        BinReducer.reduce(db, out)

        assertEquals("peak must be preserved exactly", -10f, out.max(), 1e-4f)
        assertEquals("only one column should hold it", 1, out.count { it > -89f })
    }

    @Test
    fun downsamplingCoversEveryBinWithNoGaps() {
        // Every bin is a distinct peak; every one must appear in some column.
        val db = FloatArray(16) { it.toFloat() }
        val out = FloatArray(4)
        BinReducer.reduce(db, out)
        // 16 bins into 4 columns: maxima of [0..3], [4..7], [8..11], [12..15]
        assertEquals(3f, out[0], 1e-4f)
        assertEquals(7f, out[1], 1e-4f)
        assertEquals(11f, out[2], 1e-4f)
        assertEquals(15f, out[3], 1e-4f)
    }

    /**
     * REVIEW FOCUS 1. At N=1024 there are 513 bins, and the test device is
     * 720 px wide. More columns than bins is therefore the DEFAULT case on the
     * real hardware, not an edge case, so the interpolation path must be right.
     */
    @Test
    fun interpolatesWhenColumnsOutnumberBins() {
        val db = FloatArray(513) { it.toFloat() }
        val out = FloatArray(720)

        BinReducer.reduce(db, out)

        assertEquals("first column is the first bin", 0f, out[0], 1e-4f)
        assertEquals("last column is the last bin", 512f, out[719], 1e-3f)
        for (c in 1 until out.size) {
            assertTrue("column $c must not decrease", out[c] >= out[c - 1] - 1e-4f)
        }
    }

    @Test
    fun interpolationIsLinearBetweenNeighbouringBins() {
        val db = floatArrayOf(0f, 10f)
        val out = FloatArray(3)
        BinReducer.reduce(db, out)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(5f, out[1], 1e-4f)
        assertEquals(10f, out[2], 1e-4f)
    }

    @Test
    fun equalSizesCopyThroughUnchanged() {
        val db = FloatArray(8) { it * -3f }
        val out = FloatArray(8)
        BinReducer.reduce(db, out)
        for (i in db.indices) assertEquals(db[i], out[i], 1e-5f)
    }

    /** REVIEW FOCUS 3: pre-layout views report zero width. */
    @Test
    fun zeroLengthArraysDoNothingAndDoNotThrow() {
        BinReducer.reduce(FloatArray(0), FloatArray(0))
        BinReducer.reduce(FloatArray(1025) { -90f }, FloatArray(0))
        BinReducer.reduce(FloatArray(0), FloatArray(720))
    }

    @Test
    fun singleColumnTakesTheMaximumOfEverything() {
        val db = FloatArray(1025) { -90f }
        db[77] = -5f
        val out = FloatArray(1)
        BinReducer.reduce(db, out)
        assertEquals(-5f, out[0], 1e-4f)
    }

    @Test
    fun singleBinFillsEveryColumn() {
        val out = FloatArray(5)
        BinReducer.reduce(floatArrayOf(-42f), out)
        out.forEach { assertEquals(-42f, it, 1e-4f) }
    }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.FrequencyScaleTest" --tests "com.example.soundwaterfall.dsp.BinReducerTest"`
Expected: FAIL — compilation errors, `FrequencyScale` and `BinReducer` are unresolved.

- [ ] **Step 4: Write FrequencyScale**

Create `app/src/main/java/com/example/soundwaterfall/dsp/FrequencyScale.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * The single source of the frequency axis, shared by the spectrum, the waterfall
 * and the axis strip so the three cannot drift out of alignment (spec §4).
 *
 * Everything derives from the GRANTED sample rate. Nothing here may assume
 * 48 kHz: if the device refuses it, capture falls back to 44.1 kHz and every
 * label has to follow (spec §5.1).
 */
class FrequencyScale(val sampleRate: Int, val binCount: Int) {

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(binCount >= 2) { "binCount must be at least 2, was $binCount" }
    }

    /** The transform length that produced [binCount] bins. */
    val fftSize: Int = (binCount - 1) * 2

    val nyquist: Float = sampleRate / 2f

    val binWidth: Float = sampleRate.toFloat() / fftSize

    fun frequencyOf(bin: Int): Float = bin * binWidth

    /** Linear mapping of a frequency onto a pixel column. */
    fun xOf(frequency: Float, widthPx: Int): Float =
        if (widthPx <= 0) 0f else frequency / nyquist * widthPx

    /** Gridline frequencies from DC up to and including Nyquist where it lands on a step. */
    fun gridlineFrequencies(stepHz: Int): List<Int> {
        require(stepHz > 0) { "stepHz must be positive, was $stepHz" }
        val out = ArrayList<Int>(8)
        var f = 0
        while (f <= nyquist) {
            out += f
            f += stepHz
        }
        return out
    }
}
```

- [ ] **Step 5: Write BinReducer**

Create `app/src/main/java/com/example/soundwaterfall/dsp/BinReducer.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * Maps dB bins onto pixel columns. Both charts use this, so their columns line
 * up exactly (spec §6.3).
 *
 * Two regimes, and on the 720 px test device BOTH occur depending on FFT size:
 *
 *  - bins >= columns (N=2048 gives 1025 bins, N=4096 gives 2049): take the
 *    MAXIMUM over each column's bin span. The mean would bury narrow peaks,
 *    which is exactly what this app exists to show.
 *  - bins < columns (N=1024 gives only 513 bins): linearly interpolate between
 *    neighbouring bins. This is the default case at the smallest FFT size, not
 *    an edge case.
 *
 * Allocates nothing. Writes [out].size values.
 */
object BinReducer {

    fun reduce(db: FloatArray, out: FloatArray) {
        val bins = db.size
        val cols = out.size
        if (bins == 0 || cols == 0) return

        if (bins >= cols) {
            for (c in 0 until cols) {
                val lo = (c.toLong() * bins / cols).toInt()
                val hiRaw = ((c + 1).toLong() * bins / cols).toInt()
                val hi = if (hiRaw > lo) minOf(hiRaw, bins) else minOf(lo + 1, bins)
                var m = db[lo]
                for (b in lo + 1 until hi) {
                    if (db[b] > m) m = db[b]
                }
                out[c] = m
            }
        } else {
            val denom = (cols - 1).coerceAtLeast(1)
            val span = (bins - 1).toFloat()
            for (c in 0 until cols) {
                val pos = c * span / denom
                val lo = pos.toInt().coerceIn(0, bins - 1)
                val hi = (lo + 1).coerceAtMost(bins - 1)
                out[c] = db[lo] + (db[hi] - db[lo]) * (pos - lo)
            }
        }
    }
}
```

- [ ] **Step 6: Run both tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.FrequencyScaleTest" --tests "com.example.soundwaterfall.dsp.BinReducerTest"`
Expected: PASS, 9 + 8 = 17 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/FrequencyScale.kt \
        app/src/main/java/com/example/soundwaterfall/dsp/BinReducer.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/FrequencyScaleTest.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/BinReducerTest.kt
git commit -m "feat: add shared frequency scale and bin-to-column reduction"
```

---

### Task 6: ColorMap (Inferno)

Carries **REVIEW FOCUS 4** on the colour side.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/ColorMap.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/ColorMapTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `class ColorMap(stops: IntArray = INFERNO_STOPS)` with `fun color(db: Float, dbFloor: Float): Int`, `fun colorAt(index: Int): Int`, `val floorColor: Int`, and `companion object { const val SIZE = 256; val INFERNO_STOPS: IntArray }`. Colours are packed ARGB `Int`s, directly usable as `Bitmap` pixels.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/ColorMapTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMapTest {

    private val map = ColorMap()

    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * The reason Inferno was chosen over jet/turbo (spec §6.4): equal dB steps
     * must look like equal brightness steps. A palette whose luminance dips and
     * rises invents contour bands that are not in the signal.
     */
    @Test
    fun luminanceRisesMonotonically() {
        for (i in 1 until ColorMap.SIZE) {
            val prev = luminance(map.colorAt(i - 1))
            val cur = luminance(map.colorAt(i))
            assertTrue(
                "luminance dropped between index ${i - 1} ($prev) and $i ($cur)",
                cur >= prev - 1e-6,
            )
        }
    }

    @Test
    fun floorIsNearBlackAndCeilingIsBright() {
        assertTrue("floor luminance ${luminance(map.colorAt(0))}", luminance(map.colorAt(0)) < 5.0)
        assertTrue("top luminance ${luminance(map.colorAt(255))}", luminance(map.colorAt(255)) > 200.0)
    }

    @Test
    fun everyEntryIsFullyOpaque() {
        for (i in 0 until ColorMap.SIZE) {
            assertEquals("alpha at $i", 0xFF, (map.colorAt(i) shr 24) and 0xFF)
        }
    }

    @Test
    fun mapsTheFloorToIndexZeroAndZeroDbToTheTop() {
        assertEquals(map.colorAt(0), map.color(-90f, -90f))
        assertEquals(map.colorAt(255), map.color(0f, -90f))
    }

    @Test
    fun followsTheConfiguredFloor() {
        // The same dB value sits at different points depending on the floor.
        assertNotEquals(map.color(-45f, -90f), map.color(-45f, -60f))
        assertEquals(map.colorAt(0), map.color(-60f, -60f))
        assertEquals(map.colorAt(0), map.color(-120f, -120f))
    }

    @Test
    fun clampsValuesOutsideTheRange() {
        assertEquals(map.colorAt(0), map.color(-500f, -90f))
        assertEquals(map.colorAt(255), map.color(50f, -90f))
    }

    /**
     * REVIEW FOCUS 4. Silence makes log10(0) = -Infinity upstream. Whatever
     * arrives here must become a valid index, never an exception.
     */
    @Test
    fun negativeInfinityNaNAndInfinityAllProduceValidColours() {
        assertEquals(map.colorAt(0), map.color(Float.NEGATIVE_INFINITY, -90f))
        assertEquals(map.colorAt(0), map.color(Float.NaN, -90f))
        assertEquals(map.colorAt(255), map.color(Float.POSITIVE_INFINITY, -90f))
    }

    /**
     * REVIEW FOCUS 4. dbFloor of 0 would make the index divide by zero. The
     * settings type forbids it, but nothing stops a caller from passing it.
     */
    @Test
    fun zeroOrPositiveFloorDoesNotDivideByZero() {
        assertEquals(map.colorAt(0), map.color(-10f, 0f))
        assertEquals(map.colorAt(0), map.color(-10f, 5f))
    }

    @Test
    fun colorAtClampsOutOfRangeIndices() {
        assertEquals(map.colorAt(0), map.colorAt(-7))
        assertEquals(map.colorAt(255), map.colorAt(9999))
    }

    @Test
    fun floorColorMatchesIndexZero() {
        assertEquals(map.colorAt(0), map.floorColor)
    }

    @Test
    fun interpolatesBetweenStopsRatherThanBanding() {
        // 256 entries from 10 stops must produce far more than 10 distinct colours.
        val distinct = (0 until ColorMap.SIZE).map { map.colorAt(it) }.toSet()
        assertTrue("only ${distinct.size} distinct colours", distinct.size > 200)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.ColorMapTest"`
Expected: FAIL — compilation error, `ColorMap` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/soundwaterfall/dsp/ColorMap.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * dB -> packed ARGB, through a precomputed 256-entry lookup table.
 *
 * Default stops are Inferno (spec §6.4), chosen because its luminance rises
 * monotonically — equal dB steps look like equal brightness steps — and because
 * its near-black floor separates "nothing there" from "something faint there".
 *
 * The LUT is built once; [color] is a divide, a cast and an array read, which is
 * what makes it cheap enough to run on the audio thread for every pixel of every
 * row (spec §3.1).
 */
class ColorMap(stops: IntArray = INFERNO_STOPS) {

    private val lut = IntArray(SIZE)

    init {
        require(stops.size >= 2) { "need at least two stops, got ${stops.size}" }
        val last = stops.size - 1
        for (i in 0 until SIZE) {
            val t = i.toFloat() / (SIZE - 1) * last
            val lo = t.toInt().coerceAtMost(stops.size - 2)
            val f = t - lo
            val a = stops[lo]
            val b = stops[lo + 1]
            val r = lerp((a shr 16) and 0xFF, (b shr 16) and 0xFF, f)
            val g = lerp((a shr 8) and 0xFF, (b shr 8) and 0xFF, f)
            val bl = lerp(a and 0xFF, b and 0xFF, f)
            lut[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    val floorColor: Int get() = lut[0]

    fun colorAt(index: Int): Int = lut[index.coerceIn(0, SIZE - 1)]

    /**
     * Maps [db] onto a colour, with [dbFloor] at index 0 and 0 dBFS at the top.
     *
     * Hardened against the values that actually arrive from a silent microphone:
     * NaN and -Infinity both cast to a clamped index rather than throwing.
     */
    fun color(db: Float, dbFloor: Float): Int {
        val range = -dbFloor
        if (range <= 0f) return lut[0]
        // Float.NaN.toInt() == 0 and (-Inf).toInt() == Int.MIN_VALUE, so the
        // coerceIn below turns every non-finite input into a valid index.
        val index = ((db - dbFloor) / range * (SIZE - 1)).toInt()
        return lut[index.coerceIn(0, SIZE - 1)]
    }

    private fun lerp(a: Int, b: Int, f: Float): Int =
        (a + (b - a) * f).toInt().coerceIn(0, 255)

    companion object {
        const val SIZE = 256

        /** Inferno, spec §6.4. */
        val INFERNO_STOPS = intArrayOf(
            0xFF000004.toInt(), 0xFF1B0C41.toInt(), 0xFF4A0C6B.toInt(), 0xFF781C6D.toInt(),
            0xFFA52C60.toInt(), 0xFFCF4446.toInt(), 0xFFED6925.toInt(), 0xFFFB9B06.toInt(),
            0xFFF7D13D.toInt(), 0xFFFCFFA4.toInt(),
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.ColorMapTest"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/ColorMap.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/ColorMapTest.kt
git commit -m "feat: add Inferno colormap with monotonic luminance"
```

---

### Task 7: WaterfallBuffer

The lock-free single-producer / single-consumer ring described in spec §3.1. Carries **REVIEW FOCUS 3** on the buffer side.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/WaterfallBuffer.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/WaterfallBufferTest.kt`

**Interfaces:**
- Consumes: `ColorMap` (Task 6)
- Produces: `class WaterfallBuffer(val width: Int, val height: Int, colorMap: ColorMap)` with `val pixels: IntArray` (read-only use by the renderer), `@Volatile val newestRow: Int`, `fun appendRow(columns: FloatArray, dbFloor: Float)`, `fun clear()`, plus the render contract `val topSliceHeight: Int`.

**The write direction matters.** Rows are written with a **decrementing** index, so the newest row sits at `newestRow` and ages increase downward through the array, wrapping once. That lets the renderer draw the whole history as exactly **two contiguous slices with no vertical flip**:

```
src rows [newestRow .. height-1]  ->  dst y = 0                  (topSliceHeight rows)
src rows [0 .. newestRow-1]       ->  dst y = topSliceHeight      (newestRow rows)
```

An incrementing index would require drawing the ring in reverse, which `drawBitmap` cannot do without a flip matrix.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/WaterfallBufferTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterfallBufferTest {

    private val colorMap = ColorMap()

    private fun buffer(width: Int = 4, height: Int = 4) =
        WaterfallBuffer(width, height, colorMap)

    private fun row(buf: WaterfallBuffer, y: Int): IntArray =
        IntArray(buf.width) { buf.pixels[y * buf.width + it] }

    @Test
    fun startsEntirelyAtTheFloorColour() {
        val buf = buffer()
        buf.pixels.forEach { assertEquals(colorMap.floorColor, it) }
    }

    /** The first row written must be the LAST array row, so the index can decrement. */
    @Test
    fun firstAppendLandsAtTheBottomOfTheArray() {
        val buf = buffer(height = 4)
        buf.appendRow(FloatArray(4) { 0f }, -90f)
        assertEquals(3, buf.newestRow)
    }

    @Test
    fun successiveAppendsDecrementTheIndex() {
        val buf = buffer(height = 4)
        val cols = FloatArray(4) { 0f }
        buf.appendRow(cols, -90f); assertEquals(3, buf.newestRow)
        buf.appendRow(cols, -90f); assertEquals(2, buf.newestRow)
        buf.appendRow(cols, -90f); assertEquals(1, buf.newestRow)
        buf.appendRow(cols, -90f); assertEquals(0, buf.newestRow)
    }

    @Test
    fun indexWrapsAroundAfterAFullHeight() {
        val buf = buffer(height = 4)
        val cols = FloatArray(4) { 0f }
        repeat(4) { buf.appendRow(cols, -90f) }
        assertEquals(0, buf.newestRow)
        buf.appendRow(cols, -90f)
        assertEquals("must wrap back to the bottom", 3, buf.newestRow)
        buf.appendRow(cols, -90f)
        assertEquals(2, buf.newestRow)
    }

    @Test
    fun writesTheColourisedRowAtTheNewestIndex() {
        val buf = buffer(width = 3, height = 4)
        buf.appendRow(floatArrayOf(0f, -45f, -90f), -90f)

        assertEquals(3, buf.newestRow)
        val written = row(buf, 3)
        assertEquals(colorMap.color(0f, -90f), written[0])
        assertEquals(colorMap.color(-45f, -90f), written[1])
        assertEquals(colorMap.color(-90f, -90f), written[2])
    }

    @Test
    fun olderRowsAreLeftUntouched() {
        val buf = buffer(width = 2, height = 4)
        buf.appendRow(floatArrayOf(0f, 0f), -90f)       // -> row 3
        buf.appendRow(floatArrayOf(-90f, -90f), -90f)   // -> row 2

        val old = row(buf, 3)
        assertEquals(colorMap.color(0f, -90f), old[0])
        assertEquals(colorMap.color(0f, -90f), old[1])
    }

    @Test
    fun theTwoRenderSlicesCoverTheWholeHeightExactly() {
        val buf = buffer(height = 8)
        val cols = FloatArray(4) { 0f }
        repeat(5) { buf.appendRow(cols, -90f) }
        assertEquals(8 - buf.newestRow, buf.topSliceHeight)
        assertEquals(8, buf.topSliceHeight + buf.newestRow)
    }

    @Test
    fun clearResetsEverythingToTheFloorColourAndTheIndex() {
        val buf = buffer(height = 4)
        buf.appendRow(FloatArray(4) { 0f }, -90f)
        buf.appendRow(FloatArray(4) { 0f }, -90f)
        buf.clear()
        assertEquals(0, buf.newestRow)
        buf.pixels.forEach { assertEquals(colorMap.floorColor, it) }
    }

    @Test
    fun honoursTheConfiguredFloorWhenColourising() {
        val buf = buffer(width = 1, height = 2)
        buf.appendRow(floatArrayOf(-60f), -60f)
        assertEquals(colorMap.floorColor, row(buf, 1)[0])
    }

    /** REVIEW FOCUS 3: a view reports width/height 0 before it has been laid out. */
    @Test
    fun rejectsZeroOrNegativeDimensions() {
        for (dims in listOf(0 to 4, 4 to 0, 0 to 0, -1 to 4, 4 to -1)) {
            try {
                WaterfallBuffer(dims.first, dims.second, colorMap)
                throw AssertionError("expected rejection for $dims")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAColumnArrayShorterThanTheWidth() {
        buffer(width = 8, height = 2).appendRow(FloatArray(4), -90f)
    }

    @Test
    fun acceptsSilenceWithoutProducingInvalidPixels() {
        val buf = buffer(width = 4, height = 2)
        buf.appendRow(FloatArray(4) { Float.NEGATIVE_INFINITY }, -90f)
        row(buf, 1).forEach { assertEquals(colorMap.floorColor, it) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.WaterfallBufferTest"`
Expected: FAIL — compilation error, `WaterfallBuffer` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/soundwaterfall/dsp/WaterfallBuffer.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * The waterfall's pixel history, as a single-producer / single-consumer ring
 * (spec §3.1).
 *
 * The audio thread calls [appendRow], which colourises the row and then
 * publishes [newestRow]. The main thread reads [newestRow] once per draw and
 * blits [pixels] as two contiguous slices. There are no locks: the only possible
 * interleaving is the renderer catching the single row currently being written,
 * which smears that one row for one frame and is invisible in practice.
 *
 * Rows are written with a DECREMENTING index so that history ages downward
 * through the array, which is what lets the renderer avoid a vertical flip.
 */
class WaterfallBuffer(
    val width: Int,
    val height: Int,
    private val colorMap: ColorMap,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
    }

    /** Raw ARGB pixels in ring order. The renderer reads this; nothing else writes it. */
    val pixels = IntArray(width * height) { colorMap.floorColor }

    /** Index of the newest row. Published after that row is fully written. */
    @Volatile
    var newestRow: Int = 0
        private set

    /** Rows from [newestRow] to the end of the array, drawn at destination y = 0. */
    val topSliceHeight: Int get() = height - newestRow

    /**
     * Colourises [columns] and stores it as the newest row.
     *
     * @param columns at least [width] dB values, already reduced to pixel columns
     */
    fun appendRow(columns: FloatArray, dbFloor: Float) {
        require(columns.size >= width) {
            "columns must hold at least $width values, had ${columns.size}"
        }
        val row = if (newestRow == 0) height - 1 else newestRow - 1
        val base = row * width
        for (x in 0 until width) {
            pixels[base + x] = colorMap.color(columns[x], dbFloor)
        }
        // Publish only once the row is complete.
        newestRow = row
    }

    fun clear() {
        pixels.fill(colorMap.floorColor)
        newestRow = 0
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.WaterfallBufferTest"`
Expected: PASS, 12 tests.

- [ ] **Step 5: Run the whole dsp suite and confirm no Android imports leaked in**

```bash
./gradlew :app:testDebugUnitTest
grep -rn "^import android" app/src/main/java/com/example/soundwaterfall/dsp/ && echo "FAIL: Android import in dsp" || echo "OK: dsp is pure Kotlin"
```

Expected: all unit tests pass; the grep prints `OK: dsp is pure Kotlin`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/WaterfallBuffer.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/WaterfallBufferTest.kt
git commit -m "feat: add lock-free waterfall ring buffer"
```

---

### Task 8: Audio source chain and capture

Spec §5.1. The *selection logic* is pure Kotlin and unit-tested on the JVM; only the `AudioRecord` acquisition needs a device.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/audio/AudioSourceChain.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/audio/SampleSource.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/audio/AudioCapture.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/audio/AudioSourceChainTest.kt`
- Test: `app/src/androidTest/java/com/example/soundwaterfall/audio/AudioCaptureTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class AudioSourceOption(val source: Int, val label: String)`
  - `object AudioSourceChain` with `fun candidates(sdkInt: Int, unprocessedSupported: Boolean): List<AudioSourceOption>` and `val SAMPLE_RATES = intArrayOf(48000, 44100)`
  - `interface SampleSource { val sampleRate: Int; val sourceLabel: String; fun read(dest: ShortArray): Int; fun close() }` — `read` returns the sample count, or a negative value on error
  - `sealed interface SourceResult { data class Ok(val source: SampleSource) : SourceResult; data class Failed(val reason: String) : SourceResult }` — declared in `SampleSource.kt`, deliberately free of Android imports so `AnalyzerEngine` (Task 9) stays testable on the JVM
  - `class AudioCapture(context: Context, sdkInt: Int)` with `fun open(hop: Int): SourceResult`

- [ ] **Step 1: Declare the permission**

In `app/src/main/AndroidManifest.xml`, add immediately before the `<application>` element:

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-feature android:name="android.hardware.microphone" android:required="true" />
```

- [ ] **Step 2: Write the failing source-chain test**

Create `app/src/test/java/com/example/soundwaterfall/audio/AudioSourceChainTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.audio.AudioSourceChainTest"`
Expected: FAIL — compilation error, `AudioSourceChain` is unresolved.

- [ ] **Step 4: Write AudioSourceChain**

Create `app/src/main/java/com/example/soundwaterfall/audio/AudioSourceChain.kt`:

```kotlin
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.audio.AudioSourceChainTest"`
Expected: PASS, 6 tests.

- [ ] **Step 6: Write the SampleSource interface**

Create `app/src/main/java/com/example/soundwaterfall/audio/SampleSource.kt`:

```kotlin
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
```

- [ ] **Step 7: Write AudioCapture**

Create `app/src/main/java/com/example/soundwaterfall/audio/AudioCapture.kt`:

```kotlin
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
```

- [ ] **Step 8: Write the instrumented test that opens the real microphone**

This one runs on the connected API 21 device and records, in its output, which source and rate the real hardware grants.

Create `app/src/androidTest/java/com/example/soundwaterfall/audio/AudioCaptureTest.kt`:

```kotlin
package com.example.soundwaterfall.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioCaptureTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun opensTheMicrophoneAndDeliversSamples() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AudioCapture(context).open(hop = 1024)

        assertTrue("capture failed: $result", result is SourceResult.Ok)
        val source = (result as SourceResult.Ok).source
        try {
            println(
                "AudioCaptureTest: source=${source.sourceLabel} rate=${source.sampleRate}"
            )
            assertTrue(
                "unexpected sample rate ${source.sampleRate}",
                source.sampleRate in AudioSourceChain.SAMPLE_RATES.toList(),
            )

            // Read a few buffers; at least one must deliver samples.
            val buffer = ShortArray(1024)
            var delivered = 0
            repeat(20) {
                val n = source.read(buffer)
                assertTrue("read returned error $n", n >= 0)
                delivered += n
            }
            assertTrue("no samples delivered at all", delivered > 0)
        } finally {
            source.close()
        }
    }
}
```

- [ ] **Step 9: Add the instrumented-test rule dependency**

`GrantPermissionRule` lives in `androidx.test:rules`. Add to `gradle/libs.versions.toml` under `[versions]`:

```toml
androidxTestRules = "1.5.0"
```

under `[libraries]`:

```toml
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRules" }
```

and to `app/build.gradle.kts` in `dependencies { }`:

```kotlin
    androidTestImplementation(libs.androidx.test.rules)
```

- [ ] **Step 10: Run the instrumented test on the device**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.example.soundwaterfall.audio.AudioCaptureTest"`
Expected: PASS. Capture the printed line — on the Lenovo A6010 it should read `source=VOICE_RECOGNITION` with a rate of either 48000 or 44100. Record that rate in the task notes; if it is 44100, REVIEW FOCUS 5 is live on the primary device rather than hypothetical.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/AndroidManifest.xml gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/com/example/soundwaterfall/audio/ \
        app/src/test/java/com/example/soundwaterfall/audio/ \
        app/src/androidTest/java/com/example/soundwaterfall/audio/
git commit -m "feat: add audio source chain and microphone capture"
```

---

### Task 9: SpectrumSnapshot and AnalyzerEngine

The producer loop. Carries **REVIEW FOCUS 2**.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/SpectrumSnapshot.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/AnalyzerEngine.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/SpectrumSnapshotTest.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/AnalyzerEngineTest.kt`

**Interfaces:**
- Consumes: `AnalyzerSettings`, `SpectrumAnalyzer`, `FrameAssembler`, `JTransformsFftEngine`, `FrequencyScale` (Tasks 1–5); `SampleSource`, `SourceResult` (Task 8)
- Produces:
  - `class SpectrumSnapshot(val binCount: Int)` with `fun publish(source: FloatArray)` and `fun readInto(dest: FloatArray): Boolean`
  - `class AnalyzerEngine(openSource: () -> SourceResult, sink: Sink, initialSettings: AnalyzerSettings)` with `fun start()`, `fun stop()`, `fun updateSettings(s: AnalyzerSettings)`, `val settings: AnalyzerSettings`, and nested `interface Sink { fun onStarted(sourceLabel: String, sampleRate: Int, scale: FrequencyScale); fun onFrame(db: FloatArray, scale: FrequencyScale); fun onPipelineChanged(scale: FrequencyScale); fun onError(message: String) }`

**Every `Sink` callback arrives on the audio thread.** Consumers must not touch Views directly.

- [ ] **Step 1: Write the failing SpectrumSnapshot test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/SpectrumSnapshotTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumSnapshotTest {

    @Test
    fun readReportsNoDataBeforeTheFirstPublish() {
        val snap = SpectrumSnapshot(4)
        val dest = FloatArray(4) { 7f }
        assertFalse(snap.readInto(dest))
        assertArrayEquals("dest must be left alone", FloatArray(4) { 7f }, dest, 0f)
    }

    @Test
    fun readReturnsTheMostRecentlyPublishedFrame() {
        val snap = SpectrumSnapshot(3)
        snap.publish(floatArrayOf(-1f, -2f, -3f))
        val dest = FloatArray(3)
        assertTrue(snap.readInto(dest))
        assertArrayEquals(floatArrayOf(-1f, -2f, -3f), dest, 0f)

        snap.publish(floatArrayOf(-9f, -8f, -7f))
        assertTrue(snap.readInto(dest))
        assertArrayEquals(floatArrayOf(-9f, -8f, -7f), dest, 0f)
    }

    @Test
    fun repeatedReadsWithoutAPublishReturnTheSameFrame() {
        val snap = SpectrumSnapshot(2)
        snap.publish(floatArrayOf(-4f, -5f))
        val a = FloatArray(2)
        val b = FloatArray(2)
        snap.readInto(a)
        snap.readInto(b)
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun alternatesBuffersSoAPublishDoesNotOverwriteWhatIsBeingRead() {
        // Publishing twice must use two different backing arrays; if it reused one,
        // the second publish would corrupt a concurrent read of the first.
        val snap = SpectrumSnapshot(1)
        snap.publish(floatArrayOf(1f))
        val first = snap.frontBufferForTest()
        snap.publish(floatArrayOf(2f))
        val second = snap.frontBufferForTest()
        assertFalse("publish must alternate buffers", first === second)
    }

    @Test
    fun survivesConcurrentPublishAndRead() {
        val snap = SpectrumSnapshot(256)
        val producer = Thread {
            val frame = FloatArray(256)
            for (i in 0 until 20_000) {
                frame.fill(-i.toFloat())
                snap.publish(frame)
            }
        }
        var failure: Throwable? = null
        val consumer = Thread {
            val dest = FloatArray(256)
            try {
                for (i in 0 until 20_000) {
                    if (snap.readInto(dest)) {
                        // Every element of a published frame is identical, so a
                        // torn read would show up as a mismatch here.
                        val v = dest[0]
                        for (x in dest) {
                            if (x != v) throw AssertionError("torn frame: $v vs $x")
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            }
        }
        producer.start(); consumer.start()
        producer.join(10_000); consumer.join(10_000)
        failure?.let { throw it }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAPublishOfTheWrongLength() {
        SpectrumSnapshot(4).publish(FloatArray(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAReadIntoTheWrongLength() {
        val snap = SpectrumSnapshot(4)
        snap.publish(FloatArray(4))
        snap.readInto(FloatArray(2))
    }
}
```

- [ ] **Step 2: Write SpectrumSnapshot**

Create `app/src/main/java/com/example/soundwaterfall/dsp/SpectrumSnapshot.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * Hands the newest spectrum frame from the audio thread to the renderer
 * (spec §3.1).
 *
 * The spectrum chart only ever wants the latest frame, so this is a double
 * buffer rather than a queue: the producer writes the buffer the consumer is not
 * reading, then publishes the index. Dropped intermediate frames are correct
 * behaviour here — unlike the waterfall, which needs every one.
 *
 * [readInto] copies rather than lending the array out, which keeps the window in
 * which a producer could overtake a consumer down to the duration of a memcpy.
 */
class SpectrumSnapshot(val binCount: Int) {

    init {
        require(binCount > 0) { "binCount must be positive, was $binCount" }
    }

    private val buffers = arrayOf(FloatArray(binCount), FloatArray(binCount))

    /** Index of the buffer holding a complete frame, or -1 before the first publish. */
    @Volatile
    private var frontIndex = -1

    /** Producer-only. */
    private var writeIndex = 0

    /** Called on the audio thread. */
    fun publish(source: FloatArray) {
        require(source.size == binCount) {
            "frame must be $binCount bins, was ${source.size}"
        }
        val target = buffers[writeIndex]
        System.arraycopy(source, 0, target, 0, binCount)
        frontIndex = writeIndex
        writeIndex = 1 - writeIndex
    }

    /**
     * Called on the main thread.
     *
     * @return true if a frame was copied into [dest], false if nothing has been
     *   published yet, in which case [dest] is untouched.
     */
    fun readInto(dest: FloatArray): Boolean {
        require(dest.size == binCount) {
            "dest must be $binCount bins, was ${dest.size}"
        }
        val index = frontIndex
        if (index < 0) return false
        System.arraycopy(buffers[index], 0, dest, 0, binCount)
        return true
    }

    /** Visible for testing the buffer alternation only. */
    internal fun frontBufferForTest(): FloatArray? =
        frontIndex.let { if (it < 0) null else buffers[it] }
}
```

- [ ] **Step 3: Run the snapshot test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.SpectrumSnapshotTest"`
Expected: PASS, 7 tests.

- [ ] **Step 4: Write the failing AnalyzerEngine test**

Create `app/src/test/java/com/example/soundwaterfall/AnalyzerEngineTest.kt`:

```kotlin
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
        @Volatile var closed = false

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

        override fun close() { closed = true }
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
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.AnalyzerEngineTest"`
Expected: FAIL — compilation error, `AnalyzerEngine` is unresolved.

- [ ] **Step 6: Write AnalyzerEngine**

Create `app/src/main/java/com/example/soundwaterfall/AnalyzerEngine.kt`:

```kotlin
package com.example.soundwaterfall

import com.example.soundwaterfall.audio.SampleSource
import com.example.soundwaterfall.audio.SourceResult
import com.example.soundwaterfall.dsp.AnalyzerSettings
import com.example.soundwaterfall.dsp.FrameAssembler
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.JTransformsFftEngine
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

    private fun loop() {
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
    }

    private fun pump(source: SampleSource) {
        var current = settings
        var analyzer = SpectrumAnalyzer(
            JTransformsFftEngine(current.fftSize), current.window, current.dbFloor,
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
                        JTransformsFftEngine(next.fftSize), next.window, next.dbFloor,
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
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.AnalyzerEngineTest"`
Expected: PASS, 9 tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/SpectrumSnapshot.kt \
        app/src/main/java/com/example/soundwaterfall/AnalyzerEngine.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/SpectrumSnapshotTest.kt \
        app/src/test/java/com/example/soundwaterfall/AnalyzerEngineTest.kt
git commit -m "feat: add analyzer engine with frame-boundary settings application"
```

---

### Task 10: SpectrumGeometry and SpectrumView

Spec §6.2. The pure geometry is extracted so this task has a real JVM test cycle; the drawing itself is verified by eye per spec §9, plus one instrumented test for **REVIEW FOCUS 3**.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/SpectrumGeometry.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/ui/SpectrumView.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/SpectrumGeometryTest.kt`
- Test: `app/src/androidTest/java/com/example/soundwaterfall/ui/SpectrumViewTest.kt`

**Interfaces:**
- Consumes: `SpectrumSnapshot` (Task 9), `BinReducer`, `FrequencyScale` (Task 5)
- Produces:
  - `object SpectrumGeometry` with `fun gridlineStepDb(dbFloor: Float): Int` and `fun dbToY(db: Float, dbFloor: Float, heightPx: Int): Float`
  - `class SpectrumView @JvmOverloads constructor(context, attrs)` with `fun bind(snapshot: SpectrumSnapshot, scale: FrequencyScale)`, `var dbFloor: Float`, `fun refresh()`

- [ ] **Step 1: Write the failing geometry test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/SpectrumGeometryTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumGeometryTest {

    /**
     * Spec §6.2: the step must keep labels on round numbers across the whole
     * adjustable floor range of -120..-40, which a fixed step cannot do.
     */
    @Test
    fun gridlineStepKeepsLabelsRoundAcrossTheWholeFloorRange() {
        assertEquals(10, SpectrumGeometry.gridlineStepDb(-40f))
        assertEquals(10, SpectrumGeometry.gridlineStepDb(-50f))
        assertEquals(10, SpectrumGeometry.gridlineStepDb(-60f))
        assertEquals(20, SpectrumGeometry.gridlineStepDb(-70f))
        assertEquals(20, SpectrumGeometry.gridlineStepDb(-90f))
        assertEquals(20, SpectrumGeometry.gridlineStepDb(-120f))
    }

    @Test
    fun everyFloorValueYieldsAtLeastThreeGridlines() {
        var floor = -120f
        while (floor <= -40f) {
            val step = SpectrumGeometry.gridlineStepDb(floor)
            val lines = (-floor / step).toInt()
            assertTrue("floor $floor gave only $lines gridlines", lines >= 3)
            floor += 10f
        }
    }

    @Test
    fun zeroDbfsIsAtTheTopAndTheFloorIsAtTheBottom() {
        assertEquals(0f, SpectrumGeometry.dbToY(0f, -90f, 400), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(-90f, -90f, 400), 1e-4f)
    }

    @Test
    fun midScaleIsHalfway() {
        assertEquals(200f, SpectrumGeometry.dbToY(-45f, -90f, 400), 1e-4f)
    }

    @Test
    fun followsTheConfiguredFloor() {
        assertEquals(200f, SpectrumGeometry.dbToY(-30f, -60f, 400), 1e-4f)
    }

    @Test
    fun clampsValuesBeyondTheRange() {
        assertEquals(0f, SpectrumGeometry.dbToY(20f, -90f, 400), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(-500f, -90f, 400), 1e-4f)
    }

    /** REVIEW FOCUS 3 and 4: pre-layout height, and non-finite dB from silence. */
    @Test
    fun zeroHeightAndNonFiniteInputsDoNotThrow() {
        assertEquals(0f, SpectrumGeometry.dbToY(-45f, -90f, 0), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(Float.NEGATIVE_INFINITY, -90f, 400), 1e-4f)
        assertEquals(400f, SpectrumGeometry.dbToY(Float.NaN, -90f, 400), 1e-4f)
    }

    @Test
    fun nonNegativeFloorDoesNotDivideByZero() {
        assertEquals(400f, SpectrumGeometry.dbToY(-10f, 0f, 400), 1e-4f)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.SpectrumGeometryTest"`
Expected: FAIL — compilation error, `SpectrumGeometry` is unresolved.

- [ ] **Step 3: Write SpectrumGeometry**

Create `app/src/main/java/com/example/soundwaterfall/dsp/SpectrumGeometry.kt`:

```kotlin
package com.example.soundwaterfall.dsp

/**
 * The spectrum chart's vertical mapping, kept out of the View so it can be
 * tested on the JVM.
 */
object SpectrumGeometry {

    /**
     * dB spacing between horizontal gridlines (spec §6.2). A fixed step cannot
     * keep labels round across a floor that ranges from -40 to -120, so the step
     * widens for the larger ranges.
     */
    fun gridlineStepDb(dbFloor: Float): Int = if (-dbFloor <= 60f) 10 else 20

    /**
     * Maps a dB value onto a pixel row: 0 dBFS at the top, [dbFloor] at the
     * bottom. Hardened against a zero height before first layout, and against
     * the NaN / -Infinity that a silent microphone produces upstream.
     */
    fun dbToY(db: Float, dbFloor: Float, heightPx: Int): Float {
        if (heightPx <= 0) return 0f
        val range = -dbFloor
        if (range <= 0f) return heightPx.toFloat()
        if (db.isNaN()) return heightPx.toFloat()
        val t = ((db - dbFloor) / range).coerceIn(0f, 1f)
        return (1f - t) * heightPx
    }
}
```

- [ ] **Step 4: Run the geometry test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.SpectrumGeometryTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Add the chart colour resources**

In `app/src/main/res/values/colors.xml`, add inside `<resources>`:

```xml
    <color name="chart_background">#FF080C11</color>
    <color name="chart_grid">#FF1A2430</color>
    <color name="chart_axis_background">#FF0D1218</color>
    <color name="chart_label">#FF76859A</color>
    <color name="spectrum_trace">#FF5BB8E8</color>
    <color name="spectrum_fill">#385BB8E8</color>
```

Chart surfaces stay dark in both themes (spec §6.1), so these live in `values/` only and are **not** overridden in `values-night/`.

- [ ] **Step 6: Write SpectrumView**

Create `app/src/main/java/com/example/soundwaterfall/ui/SpectrumView.kt`:

```kotlin
package com.example.soundwaterfall.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.soundwaterfall.R
import com.example.soundwaterfall.dsp.AnalyzerSettings
import com.example.soundwaterfall.dsp.BinReducer
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.SpectrumGeometry
import com.example.soundwaterfall.dsp.SpectrumSnapshot

/**
 * The instantaneous spectrum (spec §6.2).
 *
 * Allocates nothing in [onDraw]: the bin buffer, the column buffer and both
 * Paths are fields, reallocated only when the bin count or the view size
 * changes.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val gridPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_grid)
        strokeWidth = 1f
        isAntiAlias = false
    }
    private val labelPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_label)
        textSize = resources.displayMetrics.density * 9f
        isAntiAlias = true
    }
    private val tracePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.spectrum_trace)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.spectrum_fill)
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_background)
    }

    private val tracePath = Path()
    private val fillPath = Path()

    private var snapshot: SpectrumSnapshot? = null
    private var scale: FrequencyScale? = null

    private var bins: FloatArray = FloatArray(0)
    private var columns: FloatArray = FloatArray(0)

    var dbFloor: Float = AnalyzerSettings.DEFAULT.dbFloor
        set(value) {
            field = value
            invalidate()
        }

    /** Called on the main thread whenever the pipeline is (re)built. */
    fun bind(snapshot: SpectrumSnapshot, scale: FrequencyScale) {
        this.snapshot = snapshot
        this.scale = scale
        if (bins.size != snapshot.binCount) bins = FloatArray(snapshot.binCount)
        invalidate()
    }

    /** Called once per vsync by the Choreographer loop. */
    fun refresh() {
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && columns.size != w) columns = FloatArray(w)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
        // REVIEW FOCUS 3: nothing to draw before the first layout pass.
        if (w <= 0 || h <= 0) return

        drawGrid(canvas, w, h)

        val snap = snapshot ?: return
        if (columns.size != w || bins.size != snap.binCount) return
        if (!snap.readInto(bins)) return

        BinReducer.reduce(bins, columns)

        tracePath.rewind()
        fillPath.rewind()
        val y0 = SpectrumGeometry.dbToY(columns[0], dbFloor, h)
        tracePath.moveTo(0f, y0)
        fillPath.moveTo(0f, h.toFloat())
        fillPath.lineTo(0f, y0)
        for (x in 1 until w) {
            val y = SpectrumGeometry.dbToY(columns[x], dbFloor, h)
            tracePath.lineTo(x.toFloat(), y)
            fillPath.lineTo(x.toFloat(), y)
        }
        fillPath.lineTo((w - 1).toFloat(), h.toFloat())
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(tracePath, tracePaint)
    }

    private fun drawGrid(canvas: Canvas, w: Int, h: Int) {
        val step = SpectrumGeometry.gridlineStepDb(dbFloor)
        var db = 0
        while (db >= dbFloor.toInt()) {
            val y = SpectrumGeometry.dbToY(db.toFloat(), dbFloor, h)
            canvas.drawLine(0f, y, w.toFloat(), y, gridPaint)
            if (db != 0) {
                canvas.drawText("$db", LABEL_INSET * resources.displayMetrics.density, y - 2f, labelPaint)
            } else {
                canvas.drawText(
                    "0 dBFS",
                    LABEL_INSET * resources.displayMetrics.density,
                    labelPaint.textSize,
                    labelPaint,
                )
            }
            db -= step
        }

        scale?.let { freq ->
            for (f in freq.gridlineFrequencies(GRID_STEP_HZ)) {
                if (f == 0) continue
                val x = freq.xOf(f.toFloat(), w)
                canvas.drawLine(x, 0f, x, h.toFloat(), gridPaint)
            }
        }
    }

    private companion object {
        const val GRID_STEP_HZ = 4000
        const val LABEL_INSET = 3f
    }
}
```

- [ ] **Step 7: Write the instrumented zero-size test**

Create `app/src/androidTest/java/com/example/soundwaterfall/ui/SpectrumViewTest.kt`:

```kotlin
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
```

- [ ] **Step 8: Run the instrumented test on the device**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.example.soundwaterfall.ui.SpectrumViewTest"`
Expected: PASS, 5 tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/values/colors.xml \
        app/src/main/java/com/example/soundwaterfall/dsp/SpectrumGeometry.kt \
        app/src/main/java/com/example/soundwaterfall/ui/SpectrumView.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/SpectrumGeometryTest.kt \
        app/src/androidTest/java/com/example/soundwaterfall/ui/SpectrumViewTest.kt
git commit -m "feat: add spectrum chart view"
```

---

### Task 11: AxisLabels, WaterfallView and FrequencyAxisView

Spec §6.3. The waterfall is where the threading design pays off, and where **REVIEW FOCUS 3** bites hardest, because the buffer's size comes from the view's layout.

**Files:**
- Create: `app/src/main/java/com/example/soundwaterfall/dsp/AxisLabels.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/ui/WaterfallView.kt`
- Create: `app/src/main/java/com/example/soundwaterfall/ui/FrequencyAxisView.kt`
- Test: `app/src/test/java/com/example/soundwaterfall/dsp/AxisLabelsTest.kt`
- Test: `app/src/androidTest/java/com/example/soundwaterfall/ui/WaterfallViewTest.kt`

**Interfaces:**
- Consumes: `WaterfallBuffer`, `ColorMap`, `BinReducer`, `FrequencyScale` (Tasks 5–7)
- Produces:
  - `object AxisLabels` with `fun format(hz: Int): String`
  - `class WaterfallView @JvmOverloads constructor(context, attrs)` with `fun submitFrame(db: FloatArray, dbFloor: Float)` (**audio-thread safe**), `fun clear()`, `fun refresh()`
  - `class FrequencyAxisView @JvmOverloads constructor(context, attrs)` with `fun bind(scale: FrequencyScale)`

**How the audio thread reaches the buffer.** The buffer's dimensions come from the view's measured size, which only the main thread knows, but the rows are produced on the audio thread. The view therefore holds an immutable `Target(buffer, columns)` pair behind a single `@Volatile` reference. The main thread replaces the whole pair on a size change; the audio thread reads the reference once per frame. If a replacement lands mid-frame, the producer simply finishes writing into a now-orphaned buffer and the next frame uses the new one — no lock, no torn state, no lost pixels that matter.

- [ ] **Step 1: Write the failing AxisLabels test**

Create `app/src/test/java/com/example/soundwaterfall/dsp/AxisLabelsTest.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class AxisLabelsTest {

    @Test
    fun dcIsPlainZero() {
        assertEquals("0", AxisLabels.format(0))
    }

    @Test
    fun wholeKilohertzDropTheDecimal() {
        assertEquals("4k", AxisLabels.format(4000))
        assertEquals("12k", AxisLabels.format(12000))
        assertEquals("24k", AxisLabels.format(24000))
    }

    @Test
    fun subKilohertzKeepsTheHertzValue() {
        assertEquals("500", AxisLabels.format(500))
        assertEquals("999", AxisLabels.format(999))
    }

    /** 44.1 kHz capture puts Nyquist at 22050, which is not a whole kilohertz. */
    @Test
    fun fractionalKilohertzGetsOneDecimal() {
        assertEquals("22.1k", AxisLabels.format(22050))
        assertEquals("1.5k", AxisLabels.format(1500))
    }

    @Test
    fun negativeInputIsClampedToZero() {
        assertEquals("0", AxisLabels.format(-100))
    }
}
```

- [ ] **Step 2: Write AxisLabels**

Create `app/src/main/java/com/example/soundwaterfall/dsp/AxisLabels.kt`:

```kotlin
package com.example.soundwaterfall.dsp

import kotlin.math.roundToInt

/** Compact frequency labels for the shared axis strip. */
object AxisLabels {

    fun format(hz: Int): String {
        if (hz <= 0) return "0"
        if (hz < 1000) return hz.toString()
        if (hz % 1000 == 0) return "${hz / 1000}k"
        val tenths = (hz / 100f).roundToInt()
        return "${tenths / 10}.${tenths % 10}k"
    }
}
```

- [ ] **Step 3: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.AxisLabelsTest"`
Expected: PASS, 5 tests.

- [ ] **Step 4: Write WaterfallView**

Create `app/src/main/java/com/example/soundwaterfall/ui/WaterfallView.kt`:

```kotlin
package com.example.soundwaterfall.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.example.soundwaterfall.dsp.BinReducer
import com.example.soundwaterfall.dsp.ColorMap
import com.example.soundwaterfall.dsp.WaterfallBuffer

/**
 * The scrolling spectrogram (spec §6.3).
 *
 * One pixel row per analysis frame, newest at the top so it sits against the
 * spectrum above it. History depth is therefore the view's pixel height: about
 * 10 s at N=2048 on the 720x1280 test device, about 20 s on a 1080x2400 phone.
 *
 * [submitFrame] is called on the AUDIO thread and does the bin-to-column
 * reduction and the colourising there, exactly as spec §3.1 requires, so the
 * main thread only has to blit.
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Buffer and its matching column scratch array, swapped together. */
    private class Target(val buffer: WaterfallBuffer, val columns: FloatArray)

    private val colorMap = ColorMap()
    private val blitPaint = Paint().apply { isFilterBitmap = false }
    private val srcRect = Rect()
    private val dstRect = Rect()

    @Volatile
    private var target: Target? = null

    private var bitmap: Bitmap? = null

    fun refresh() {
        invalidate()
    }

    fun clear() {
        target?.buffer?.clear()
        invalidate()
    }

    /**
     * Called on the audio thread, once per analysis frame.
     *
     * @param db one value per FFT bin
     */
    fun submitFrame(db: FloatArray, dbFloor: Float) {
        val t = target ?: return
        BinReducer.reduce(db, t.columns)
        t.buffer.appendRow(t.columns, dbFloor)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // REVIEW FOCUS 3: WaterfallBuffer rejects non-positive dimensions, so no
        // buffer exists until the view has a real size. History clears on resize
        // and on rotation, as spec §6.3 specifies.
        if (w <= 0 || h <= 0) {
            target = null
            bitmap?.recycle()
            bitmap = null
            return
        }
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        target = Target(WaterfallBuffer(w, h, colorMap), FloatArray(w))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        target = null
        bitmap?.recycle()
        bitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        val t = target ?: return
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return

        val buffer = t.buffer
        val w = buffer.width
        val h = buffer.height

        // One volatile read; both slices must agree on the same split point.
        val newest = buffer.newestRow
        val topHeight = h - newest

        bmp.setPixels(buffer.pixels, 0, w, 0, 0, w, h)

        if (topHeight > 0) {
            srcRect.set(0, newest, w, h)
            dstRect.set(0, 0, w, topHeight)
            canvas.drawBitmap(bmp, srcRect, dstRect, blitPaint)
        }
        if (newest > 0) {
            srcRect.set(0, 0, w, newest)
            dstRect.set(0, topHeight, w, h)
            canvas.drawBitmap(bmp, srcRect, dstRect, blitPaint)
        }
    }
}
```

- [ ] **Step 5: Write FrequencyAxisView**

Create `app/src/main/java/com/example/soundwaterfall/ui/FrequencyAxisView.kt`:

```kotlin
package com.example.soundwaterfall.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.soundwaterfall.R
import com.example.soundwaterfall.dsp.AxisLabels
import com.example.soundwaterfall.dsp.FrequencyScale

/**
 * The single shared frequency scale, drawn below the waterfall (spec §6.1,
 * layout C). It labels both charts because all three views derive their x
 * mapping from the same [FrequencyScale].
 */
class FrequencyAxisView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_axis_background)
    }
    private val tickPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_grid)
        strokeWidth = 1f
    }
    private val labelPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.chart_label)
        textSize = resources.displayMetrics.density * 9f
        isAntiAlias = true
    }

    private var scale: FrequencyScale? = null

    fun bind(scale: FrequencyScale) {
        this.scale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
        if (w <= 0 || h <= 0) return

        val freq = scale ?: return
        val tickHeight = h * 0.3f
        val baseline = h * 0.82f

        for (f in freq.gridlineFrequencies(GRID_STEP_HZ)) {
            val x = freq.xOf(f.toFloat(), w)
            canvas.drawLine(x, 0f, x, tickHeight, tickPaint)

            val label = AxisLabels.format(f)
            val textWidth = labelPaint.measureText(label)
            // Keep the first and last labels inside the view.
            val tx = (x - textWidth / 2f).coerceIn(0f, (w - textWidth).coerceAtLeast(0f))
            canvas.drawText(label, tx, baseline, labelPaint)
        }

        val hz = "Hz"
        canvas.drawText(hz, w - labelPaint.measureText(hz) - 1f, labelPaint.textSize, labelPaint)
    }

    private companion object {
        const val GRID_STEP_HZ = 4000
    }
}
```

- [ ] **Step 6: Write the instrumented WaterfallView test**

Create `app/src/androidTest/java/com/example/soundwaterfall/ui/WaterfallViewTest.kt`:

```kotlin
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
```

- [ ] **Step 7: Run the instrumented tests on the device**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.example.soundwaterfall.ui.WaterfallViewTest"`
Expected: PASS, 7 tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/soundwaterfall/dsp/AxisLabels.kt \
        app/src/main/java/com/example/soundwaterfall/ui/WaterfallView.kt \
        app/src/main/java/com/example/soundwaterfall/ui/FrequencyAxisView.kt \
        app/src/test/java/com/example/soundwaterfall/dsp/AxisLabelsTest.kt \
        app/src/androidTest/java/com/example/soundwaterfall/ui/WaterfallViewTest.kt
git commit -m "feat: add waterfall and frequency axis views"
```

---

### Task 12: Layout, permission flow and MainActivity

Spec §6.1 and §7. **At the end of this task the app runs**: charts live on screen with the default settings. Controls arrive in Task 13.

**Files:**
- Modify: `app/build.gradle.kts` (enable view binding)
- Create: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/example/soundwaterfall/MainActivity.kt`

**Interfaces:**
- Consumes: everything from Tasks 8–11
- Produces: `class MainActivity : AppCompatActivity(), AnalyzerEngine.Sink`

**Where each Sink callback runs.** `onFrame` arrives on the audio thread ~47 times a second and **must not post to the main thread** — it writes into `SpectrumSnapshot` and `WaterfallView.submitFrame`, both of which are built for exactly that. A `Choreographer` callback on the main thread invalidates both views once per vsync. Only the infrequent callbacks (`onStarted`, `onPipelineChanged`, `onError`) marshal with `runOnUiThread`.

- [ ] **Step 1: Enable view binding**

In `app/build.gradle.kts`, inside the `android { }` block, after `compileOptions { ... }`:

```kotlin
    buildFeatures {
        viewBinding = true
    }
```

- [ ] **Step 2: Add the strings**

Replace `app/src/main/res/values/strings.xml` with:

```xml
<resources>
    <string name="app_name">SoundWaterfall</string>
    <string name="source_unknown">starting…</string>
    <string name="source_format">%1$s · %2$d Hz</string>
    <string name="permission_title">Microphone access needed</string>
    <string name="permission_body">SoundWaterfall analyses live audio, so it needs permission to use the microphone. Nothing is recorded or saved.</string>
    <string name="permission_grant">Grant access</string>
    <string name="permission_settings">Open settings</string>
    <string name="error_title">Microphone unavailable</string>
    <string name="error_retry">Retry</string>
</resources>
```

- [ ] **Step 3: Add the remaining colours**

In `app/src/main/res/values/colors.xml`, add inside `<resources>`:

```xml
    <color name="app_bar_background">#FF141A22</color>
    <color name="app_bar_text">#FFAAB6C6</color>
    <color name="source_chip_text">#FF7EC98A</color>
    <color name="source_chip_background">#FF1D2A1F</color>
    <color name="panel_scrim">#F2080C11</color>
    <color name="panel_text">#FFD6E3F0</color>
    <color name="panel_body_text">#FF8F9AA8</color>
```

- [ ] **Step 4: Switch to a no-action-bar theme**

The layout draws its own app bar, so the platform one must go. In **both** `app/src/main/res/values/themes.xml` and `app/src/main/res/values-night/themes.xml`, change the parent:

```xml
    <style name="Theme.SoundWaterfall" parent="Theme.MaterialComponents.DayNight.NoActionBar">
```

and in both files replace the status bar line with:

```xml
        <item name="android:statusBarColor">@color/app_bar_background</item>
```

- [ ] **Step 5: Write the layout (spec §6.1, layout C)**

Create `app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/chart_background">

    <LinearLayout
        android:id="@+id/content"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <!-- App bar: name plus the audio source that actually won the chain. -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@color/app_bar_background"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="12dp"
            android:paddingVertical="8dp">

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/app_name"
                android:textColor="@color/app_bar_text"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/sourceChip"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="@color/source_chip_background"
                android:paddingHorizontal="8dp"
                android:paddingVertical="2dp"
                android:text="@string/source_unknown"
                android:textColor="@color/source_chip_text"
                android:textSize="11sp" />
        </LinearLayout>

        <!-- Spectrum, 34% of the remaining height. -->
        <com.example.soundwaterfall.ui.SpectrumView
            android:id="@+id/spectrum"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="34" />

        <!-- Waterfall, 40%, welded directly to the spectrum with no separator so
             the newest row touches it (layout C). -->
        <com.example.soundwaterfall.ui.WaterfallView
            android:id="@+id/waterfall"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="40" />

        <!-- The single shared frequency scale, below both charts. -->
        <com.example.soundwaterfall.ui.FrequencyAxisView
            android:id="@+id/frequencyAxis"
            android:layout_width="match_parent"
            android:layout_height="18dp" />

        <!-- Task 13 inserts the control strip here. -->
        <FrameLayout
            android:id="@+id/controlContainer"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />
    </LinearLayout>

    <!-- Covers the charts whenever there is nothing to show: permission or error. -->
    <LinearLayout
        android:id="@+id/panel"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@color/panel_scrim"
        android:gravity="center"
        android:orientation="vertical"
        android:paddingHorizontal="32dp"
        android:visibility="gone">

        <TextView
            android:id="@+id/panelTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/panel_text"
            android:textSize="18sp" />

        <TextView
            android:id="@+id/panelBody"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:gravity="center"
            android:textColor="@color/panel_body_text"
            android:textSize="13sp" />

        <Button
            android:id="@+id/panelAction"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp" />
    </LinearLayout>
</FrameLayout>
```

- [ ] **Step 6: Point the manifest at the activity**

In `app/src/main/AndroidManifest.xml`, replace the self-closing `<application ... />` tag with an open/close pair containing the activity:

```xml
    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.SoundWaterfall">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
```

- [ ] **Step 7: Write MainActivity**

Create `app/src/main/java/com/example/soundwaterfall/MainActivity.kt`:

```kotlin
package com.example.soundwaterfall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.soundwaterfall.audio.AudioCapture
import com.example.soundwaterfall.databinding.ActivityMainBinding
import com.example.soundwaterfall.dsp.AnalyzerSettings
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.SpectrumSnapshot
import com.example.soundwaterfall.dsp.WindowFunction
import com.example.soundwaterfall.ui.WaterfallView

class MainActivity : AppCompatActivity(), AnalyzerEngine.Sink {

    private lateinit var binding: ActivityMainBinding

    /** Held directly so the audio thread never touches the binding. */
    private lateinit var waterfall: WaterfallView

    private var engine: AnalyzerEngine? = null

    @Volatile
    private var snapshot: SpectrumSnapshot? = null

    @Volatile
    private var dbFloor: Float = AnalyzerSettings.DEFAULT.dbFloor

    private var settings: AnalyzerSettings = AnalyzerSettings.DEFAULT

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            binding.spectrum.refresh()
            waterfall.refresh()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCapture() else showPermissionPanel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        waterfall = binding.waterfall
        binding.root.keepScreenOn = true

        savedInstanceState?.let { state ->
            settings = AnalyzerSettings(
                fftSize = state.getInt(KEY_FFT_SIZE, AnalyzerSettings.DEFAULT.fftSize),
                window = WindowFunction.entries[
                    state.getInt(KEY_WINDOW, AnalyzerSettings.DEFAULT.window.ordinal)
                ],
                dbFloor = state.getFloat(KEY_DB_FLOOR, AnalyzerSettings.DEFAULT.dbFloor),
            )
        }
        applySettingsLocally(settings)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_FFT_SIZE, settings.fftSize)
        outState.putInt(KEY_WINDOW, settings.window.ordinal)
        outState.putFloat(KEY_DB_FLOOR, settings.dbFloor)
    }

    override fun onStart() {
        super.onStart()
        // Re-checked every time: the permission may have been revoked while
        // the app was backgrounded (spec §8).
        if (hasPermission()) {
            startCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        engine?.stop()
        engine = null
        snapshot = null
    }

    // --- settings ------------------------------------------------------------

    /** Updates local state and the views; Task 13 drives this from the controls. */
    private fun updateSettings(next: AnalyzerSettings) {
        val floorChanged = next.dbFloor != settings.dbFloor
        settings = next
        applySettingsLocally(next)
        engine?.updateSettings(next)
        // Existing rows were colourised against the old floor (spec §6.5).
        if (floorChanged) waterfall.clear()
    }

    private fun applySettingsLocally(next: AnalyzerSettings) {
        dbFloor = next.dbFloor
        binding.spectrum.dbFloor = next.dbFloor
    }

    // --- capture lifecycle ---------------------------------------------------

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        hidePanel()
        if (engine != null) return
        val capture = AudioCapture(this)
        val started = AnalyzerEngine(
            openSource = { capture.open(settings.hop) },
            sink = this,
            initialSettings = settings,
        )
        engine = started
        started.start()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // --- AnalyzerEngine.Sink (audio thread unless noted) ---------------------

    override fun onStarted(sourceLabel: String, sampleRate: Int, scale: FrequencyScale) {
        val snap = SpectrumSnapshot(scale.binCount)
        snapshot = snap
        runOnUiThread {
            binding.sourceChip.text = getString(R.string.source_format, sourceLabel, sampleRate)
            bindViews(snap, scale)
        }
    }

    override fun onPipelineChanged(scale: FrequencyScale) {
        val snap = SpectrumSnapshot(scale.binCount)
        snapshot = snap
        runOnUiThread {
            bindViews(snap, scale)
            waterfall.clear()
        }
    }

    /**
     * Audio thread, ~47 times a second. Deliberately does NOT post to the main
     * thread: both sinks are built for cross-thread handoff, and the
     * Choreographer callback handles redrawing.
     */
    override fun onFrame(db: FloatArray, scale: FrequencyScale) {
        snapshot?.publish(db)
        waterfall.submitFrame(db, dbFloor)
    }

    override fun onError(message: String) {
        runOnUiThread { showErrorPanel(message) }
    }

    private fun bindViews(snap: SpectrumSnapshot, scale: FrequencyScale) {
        binding.spectrum.bind(snap, scale)
        binding.frequencyAxis.bind(scale)
    }

    // --- panels --------------------------------------------------------------

    private fun hidePanel() {
        binding.panel.visibility = View.GONE
    }

    private fun showPermissionPanel() {
        // ActivityCompat, not the Activity method: the platform one is API 23+
        // and would throw NoSuchMethodError on the API 21 test device.
        val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.RECORD_AUDIO,
        )
        binding.panelTitle.setText(R.string.permission_title)
        binding.panelBody.setText(R.string.permission_body)
        binding.panelAction.setText(
            if (permanentlyDenied) R.string.permission_settings else R.string.permission_grant
        )
        binding.panelAction.setOnClickListener {
            if (permanentlyDenied) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.panel.visibility = View.VISIBLE
    }

    private fun showErrorPanel(message: String) {
        engine?.stop()
        engine = null
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        binding.panelTitle.setText(R.string.error_title)
        binding.panelBody.text = message
        binding.panelAction.setText(R.string.error_retry)
        binding.panelAction.setOnClickListener { startCapture() }
        binding.panel.visibility = View.VISIBLE
    }

    private companion object {
        const val KEY_FFT_SIZE = "fftSize"
        const val KEY_WINDOW = "window"
        const val KEY_DB_FLOOR = "dbFloor"
    }
}
```

- [ ] **Step 8: Build and install**

```bash
./gradlew :app:installDebug
```

Expected: `BUILD SUCCESSFUL` and the app installed on the Lenovo A6010.

- [ ] **Step 9: Verify the app against a known tone**

Launch it, grant the microphone permission, then play a 1 kHz tone near the phone (any tone-generator app or an online generator).

```bash
~/Android/Sdk/platform-tools/adb shell am start -n com.example.soundwaterfall/.MainActivity
~/Android/Sdk/platform-tools/adb logcat -d -s AudioCapture:I
```

Expected, all four:
1. A sharp peak in the spectrum roughly 4% of the way across (bin 43 of 1025 at 48 kHz — see spec §9)
2. A bright vertical streak in the waterfall at the **same x position** as the spectrum peak
3. The waterfall scrolling downward with the newest row touching the spectrum
4. The app bar chip reading the real source and rate, e.g. `VOICE_RECOGNITION · 48000 Hz`

If the peak and the streak are at different x positions, the shared `FrequencyScale` is not actually shared — stop and fix before continuing.

- [ ] **Step 10: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/ \
        app/src/main/java/com/example/soundwaterfall/MainActivity.kt
git commit -m "feat: add main screen with permission flow and live charts"
```

---

### Task 13: The control strip

Spec §6.5.

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/soundwaterfall/MainActivity.kt`

**Interfaces:**
- Consumes: `AnalyzerSettings`, `WindowFunction`, and `MainActivity.updateSettings` from Task 12
- Produces: no new public types

**A deviation from spec §6.5, deliberately.** The spec calls for a dropdown for the window function. This task uses a second segmented button group instead. The app's entire purpose is A/B comparing windows and watching the leakage skirt change; a dropdown costs two taps and hides the alternatives, while a segmented control costs one tap and keeps all four visible. It also avoids theming an exposed-dropdown or `Spinner` against a dark surface on API 21. Four short labels fit comfortably at 720 px.

- [ ] **Step 1: Add the control strings**

In `app/src/main/res/values/strings.xml`, add inside `<resources>`:

```xml
    <string name="label_fft">FFT</string>
    <string name="label_window">Window</string>
    <string name="label_floor">Floor</string>
    <string name="window_rectangular">Rect</string>
    <string name="window_hann">Hann</string>
    <string name="window_hamming">Hamm</string>
    <string name="window_blackman">Black</string>
    <string name="db_floor_format">%d dB</string>
```

- [ ] **Step 2: Replace the placeholder container with the control strip**

In `app/src/main/res/layout/activity_main.xml`, replace this block:

```xml
        <!-- Task 13 inserts the control strip here. -->
        <FrameLayout
            android:id="@+id/controlContainer"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />
```

with:

```xml
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@color/app_bar_background"
            android:orientation="vertical"
            android:paddingHorizontal="10dp"
            android:paddingVertical="6dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="52dp"
                    android:layout_height="wrap_content"
                    android:text="@string/label_fft"
                    android:textColor="@color/chart_label"
                    android:textSize="11sp" />

                <com.google.android.material.button.MaterialButtonToggleGroup
                    android:id="@+id/fftSizeGroup"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    app:selectionRequired="true"
                    app:singleSelection="true">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/fft1024"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="0dp"
                        android:layout_height="36dp"
                        android:layout_weight="1"
                        android:insetTop="0dp"
                        android:insetBottom="0dp"
                        android:text="1024"
                        android:textSize="11sp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/fft2048"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="0dp"
                        android:layout_height="36dp"
                        android:layout_weight="1"
                        android:insetTop="0dp"
                        android:insetBottom="0dp"
                        android:text="2048"
                        android:textSize="11sp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/fft4096"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="0dp"
                        android:layout_height="36dp"
                        android:layout_weight="1"
                        android:insetTop="0dp"
                        android:insetBottom="0dp"
                        android:text="4096"
                        android:textSize="11sp" />
                </com.google.android.material.button.MaterialButtonToggleGroup>
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="52dp"
                    android:layout_height="wrap_content"
                    android:text="@string/label_window"
                    android:textColor="@color/chart_label"
                    android:textSize="11sp" />

                <com.google.android.material.button.MaterialButtonToggleGroup
                    android:id="@+id/windowGroup"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    app:selectionRequired="true"
                    app:singleSelection="true">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/windowRect"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="0dp"
                        android:layout_height="36dp"
                        android:layout_weight="1"
                        android:insetTop="0dp"
                        android:insetBottom="0dp"
                        android:text="@string/window_rectangular"
                        android:textSize="10sp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/windowHann"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="0dp"
                        android:layout_height="36dp"
                        android:layout_weight="1"
                        android:insetTop="0dp"
                        android:insetBottom="0dp"
                        android:text="@string/window_hann"
                        android:textSize="10sp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/windowHamming"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="0dp"
                        android:layout_height="36dp"
                        android:layout_weight="1"
                        android:insetTop="0dp"
                        android:insetBottom="0dp"
                        android:text="@string/window_hamming"
                        android:textSize="10sp" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/windowBlackman"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="0dp"
                        android:layout_height="36dp"
                        android:layout_weight="1"
                        android:insetTop="0dp"
                        android:insetBottom="0dp"
                        android:text="@string/window_blackman"
                        android:textSize="10sp" />
                </com.google.android.material.button.MaterialButtonToggleGroup>
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    android:layout_width="52dp"
                    android:layout_height="wrap_content"
                    android:text="@string/label_floor"
                    android:textColor="@color/chart_label"
                    android:textSize="11sp" />

                <com.google.android.material.slider.Slider
                    android:id="@+id/dbFloorSlider"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:stepSize="10"
                    android:valueFrom="-120"
                    android:valueTo="-40" />

                <TextView
                    android:id="@+id/dbFloorValue"
                    android:layout_width="56dp"
                    android:layout_height="wrap_content"
                    android:gravity="end"
                    android:textColor="@color/chart_label"
                    android:textSize="11sp" />
            </LinearLayout>
        </LinearLayout>
```

Also add the `app` namespace to the root `<FrameLayout>` tag:

```xml
    xmlns:app="http://schemas.android.com/apk/res-auto"
```

- [ ] **Step 3: Wire the controls in MainActivity**

In `app/src/main/java/com/example/soundwaterfall/MainActivity.kt`, add this call at the end of `onCreate`, after `applySettingsLocally(settings)`:

```kotlin
        wireControls()
```

Then add these three methods to the class:

```kotlin
    /**
     * `isChecked` guards every listener: MaterialButtonToggleGroup fires for the
     * button being cleared as well as the one being checked, and syncControls()
     * programmatically checks buttons, which would otherwise feed back.
     */
    private fun wireControls() {
        syncControls()

        binding.fftSizeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val size = when (checkedId) {
                R.id.fft1024 -> 1024
                R.id.fft4096 -> 4096
                else -> 2048
            }
            if (size != settings.fftSize) updateSettings(settings.copy(fftSize = size))
        }

        binding.windowGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val window = when (checkedId) {
                R.id.windowRect -> WindowFunction.RECTANGULAR
                R.id.windowHamming -> WindowFunction.HAMMING
                R.id.windowBlackman -> WindowFunction.BLACKMAN
                else -> WindowFunction.HANN
            }
            if (window != settings.window) updateSettings(settings.copy(window = window))
        }

        // The label follows the thumb, but the setting is applied on release:
        // every floor change clears the waterfall (spec §6.5), and doing that on
        // each step of a drag would be unusable.
        binding.dbFloorSlider.addOnChangeListener { _, value, _ ->
            binding.dbFloorValue.text = getString(R.string.db_floor_format, value.toInt())
        }
        binding.dbFloorSlider.addOnSliderTouchListener(
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = Unit

                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    val floor = slider.value
                    if (floor != settings.dbFloor) updateSettings(settings.copy(dbFloor = floor))
                }
            }
        )
    }

    /** Reflects [settings] into the controls, e.g. after a rotation. */
    private fun syncControls() {
        binding.fftSizeGroup.check(
            when (settings.fftSize) {
                1024 -> R.id.fft1024
                4096 -> R.id.fft4096
                else -> R.id.fft2048
            }
        )
        binding.windowGroup.check(
            when (settings.window) {
                WindowFunction.RECTANGULAR -> R.id.windowRect
                WindowFunction.HANN -> R.id.windowHann
                WindowFunction.HAMMING -> R.id.windowHamming
                WindowFunction.BLACKMAN -> R.id.windowBlackman
            }
        )
        binding.dbFloorSlider.value = settings.dbFloor
        binding.dbFloorValue.text =
            getString(R.string.db_floor_format, settings.dbFloor.toInt())
    }
```

- [ ] **Step 4: Build and install**

```bash
./gradlew :app:installDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify each control does what the spec says, against a steady tone**

Play a steady 1 kHz tone and check all five:

1. **Window → Rectangular**: the peak keeps its height but grows wide leakage skirts. **→ Blackman**: the skirts collapse and **the peak stays at the same height**. If the whole trace jumps vertically instead, the §5.4 normalization is broken.
2. **Window change does not clear the waterfall** — the existing history stays on screen.
3. **FFT 1024 → 4096**: the peak narrows (finer frequency resolution) and the waterfall scrolls visibly slower (23 rows/s versus 94). History clears on the change.
4. **Floor slider**: the label tracks the thumb during the drag, nothing else changes until release; on release the waterfall clears and the contrast changes.
5. **Rotate the device**: the three control positions survive; the waterfall history clears.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml \
        app/src/main/java/com/example/soundwaterfall/MainActivity.kt
git commit -m "feat: add FFT size, window and dB floor controls"
```

---

### Task 14: Device validation — performance and failure modes

Spec §8 and §12. The primary device is a **2015 budget phone** (Lenovo A6010, Snapdragon 410, Cortex-A53), which is far harsher than the spec's "60 fps" criterion assumes. This task measures rather than guesses, and the measurement decides whether any optimization is warranted at all.

**Files:**
- Modify (only if the measurement demands it): `app/src/main/java/com/example/soundwaterfall/ui/WaterfallView.kt`, `app/src/main/java/com/example/soundwaterfall/dsp/WaterfallBuffer.kt`
- Create: `docs/superpowers/plans/2026-10-05-sound-waterfall-validation.md`

**Interfaces:**
- Consumes: the whole app
- Produces: a validation record; code changes only if a measured threshold is missed

- [ ] **Step 1: Measure frame timing at each FFT size**

For each of 1024, 2048 and 4096, set that FFT size in the app, let it run 30 seconds with audio present, then:

```bash
PKG=com.example.soundwaterfall
ADB=~/Android/Sdk/platform-tools/adb
$ADB shell dumpsys gfxinfo $PKG reset
sleep 30
$ADB shell dumpsys gfxinfo $PKG | sed -n '1,40p'
```

Record, for each size: total frames, janky frame count and percentage, and the 50th/90th/95th/99th percentile frame times.

**Threshold:** janky frames below 10% and the 95th percentile under 16 ms. The known hot spot is `Bitmap.setPixels` plus the full texture re-upload in `WaterfallView.onDraw` — on a 720×~500 surface that is about 1.4 MB per frame.

- [ ] **Step 2: Check CPU cost of the DSP separately**

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB shell top -m 10 -d 2 -n 3 | grep -i soundwaterfall
```

Record total CPU%. The audio thread does the FFT and the colormapping, so a figure above roughly 40% of one core at N=4096 means the DSP, not the rendering, is the bottleneck.

- [ ] **Step 3: If and only if Step 1 missed its threshold, apply these in order**

Stop at the first one that brings it within threshold, and record which was needed.

**(a) Redraw the waterfall only when a new row exists.** Cheapest change, removes roughly 20% of blits at N=2048 and about 75% at N=4096. In `WaterfallBuffer` add a counter the producer increments and the renderer reads:

```kotlin
    /** Monotonic count of appended rows, so the renderer can skip idle frames. */
    @Volatile
    var rowsAppended: Long = 0
        private set
```

increment it at the end of `appendRow` (after `newestRow = row`), and in `WaterfallView` keep `private var lastDrawnRowCount = -1L`, returning early from `onDraw` when `buffer.rowsAppended == lastDrawnRowCount`.

**(b) Upload only the rows that changed.** `setPixels` accepts a sub-rectangle. Track how many rows were appended since the last draw, and upload just that span (it is contiguous in ring order except across one wrap, which needs two calls). This removes nearly all of the per-frame upload cost.

**(c) Halve the vertical resolution.** Allocate the buffer at `height / 2` and let `drawBitmap` scale the slices up. Halves the upload and the history row count; the time axis becomes 2 screen pixels per analysis frame.

Do **not** reach for OpenGL — spec §2 rejected it, and (b) addresses the actual cost.

- [ ] **Step 4: Validate every failure mode from spec §8**

Run each and record the observed behaviour:

```bash
ADB=~/Android/Sdk/platform-tools/adb
PKG=com.example.soundwaterfall

# 1. Permission revoked while backgrounded -> rationale panel on return.
$ADB shell am start -n $PKG/.MainActivity
$ADB shell pm revoke $PKG android.permission.RECORD_AUDIO
$ADB shell input keyevent KEYCODE_HOME
$ADB shell am start -n $PKG/.MainActivity

# 2. Permission permanently denied -> the button must offer Settings, not Grant.
#    Deny twice through the system dialog, then relaunch.

# 3. Microphone stolen by another app -> "Microphone unavailable" plus Retry.
#    Start any voice recorder while SoundWaterfall is in the foreground.

# 4. Backgrounding releases the microphone (nothing should hold it).
$ADB shell am start -n $PKG/.MainActivity
$ADB shell input keyevent KEYCODE_HOME
$ADB shell dumpsys media.audio_flinger | grep -i -A2 "input" | head -20

# 5. Rotation mid-capture -> no crash, history clears, controls survive.
$ADB shell settings put system accelerometer_rotation 1
$ADB shell am start -n $PKG/.MainActivity
# rotate the device by hand, then check:
$ADB logcat -d -s AndroidRuntime:E | tail -20
```

Expected: no entry in the `AndroidRuntime:E` log at any point, and each panel showing the right message and the right button.

- [ ] **Step 5: Confirm the spec's success criteria**

Walk spec §12 and record pass or fail for each:

1. A tone produces a clean, correctly positioned peak on both charts
2. Switching windows changes the leakage skirt while the peak stays at 0 dBFS
3. Switching FFT size trades frequency resolution against scroll speed
4. The active audio source is always visible
5. Rendering keeps up (Step 1's numbers)
6. The `dsp` package is fully unit-tested on the JVM

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
grep -rn "^import android" app/src/main/java/com/example/soundwaterfall/dsp/ \
  && echo "FAIL: Android import in dsp" || echo "OK: dsp is pure Kotlin"
```

Expected: every test green, and `OK: dsp is pure Kotlin`.

- [ ] **Step 6: Verify the release build shrinks correctly**

The release build type enables R8 optimization, and JTransforms pulls in `commons-math3` and `JLargeArrays` — about 3.4 MB of jars before shrinking.

```bash
./gradlew :app:assembleRelease
ls -la app/build/outputs/apk/release/
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release-unsigned.apk 2>&1 | tail -2
```

If R8 strips something JTransforms reaches reflectively, the release build will crash where debug does not. Add to `app/src/main/keepRules/rules.keep` if so:

```
-keep class pl.edu.icm.jlargearrays.** { *; }
-keep class org.jtransforms.** { *; }
-dontwarn sun.misc.Unsafe
```

Record the release APK size either way.

- [ ] **Step 7: Write the validation record**

Create `docs/superpowers/plans/2026-10-05-sound-waterfall-validation.md` holding: the device and API level; Step 1's frame timings for all three FFT sizes; Step 2's CPU figures; which, if any, of Step 3's optimizations were needed; Step 4's observed behaviour per failure mode; Step 5's pass/fail per success criterion; and Step 6's release APK size.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/plans/2026-10-05-sound-waterfall-validation.md
git add -A app/src/main app/src/main/keepRules 2>/dev/null || true
git commit -m "test: validate performance and failure modes on device"
```

---

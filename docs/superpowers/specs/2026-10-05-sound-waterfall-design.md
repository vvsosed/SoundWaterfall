# SoundWaterfall — Design

**Date:** 2026-10-05
**Status:** Approved design, ready for implementation planning

## 1. Purpose and scope

An Android app that captures the microphone live, runs an FFT on it, and displays the result
as two charts on a single screen: an instantaneous spectrum on top and a scrolling waterfall
(spectrogram) below.

The app exists as a **DSP learning and experimentation tool**. Insight matters more than
polish: the display must be an honest representation of what the transform produces, and the
user must be able to change the transform's parameters and see the consequences. This goal
decides several trade-offs later in this document — notably the normalization rule in §5.4,
the colormap choice in §6.4, and the deliberate absence of smoothing in §10.

Success means: point the phone at a whistle or a tone generator and see a clean peak at the
correct frequency on both charts; then change the window function or FFT size and *see*
spectral leakage and frequency resolution change.

### Non-goals

Recording or exporting audio, saving sessions, calibrated SPL measurement, pitch detection,
note naming, multiple screens, settings persistence across app restarts.

## 2. Decisions and rationale

| Decision | Choice | Why |
| --- | --- | --- |
| FFT implementation | Third-party library (JTransforms, pending §11) | The learning focus is the pipeline and the visualization, not writing a radix-2 transform |
| Runtime controls | FFT size, window function, dB floor | Directly serves "see what windowing does" |
| Frequency axis | Linear, 0 → Nyquist, full range | 1:1 with the bins the FFT actually produces; makes resolution and leakage legible |
| UI toolkit | Android Views + `Canvas` | No new UI dependencies, comfortably 60 fps at this data size, keeps complexity in the DSP |
| Module layout | Single `:app` module, strict packages | Simplest build; `dsp` package holds no Android imports by convention |
| Screen layout | Charts adjacent, shared axis below the waterfall | Newest waterfall row touches the spectrum, so a peak and its time-streak read as one shape |
| Colormap | Inferno (monotonic luminance, near-black floor) | Equal dB steps look like equal brightness steps; silence reads as empty so faint tones separate from it |
| Audio source | Best-available chain, displayed in the UI | Platform AGC and noise suppression visibly distort the spectrum; the user must know which source won |
| minSdk | 21 | Already set; requires an API-level guard for `UNPROCESSED` (§5.1) |

## 3. Architecture

Two threads. A dedicated audio thread owns capture and all DSP; its blocking
`AudioRecord.read` sets the cadence of the whole pipeline. The main thread only draws.

```
AudioRecord ──┐  audio thread: blocking read drives the pipeline
              ▼
      FrameAssembler        ring buffer → emits N-sample frames, hop = N/2
              ▼
      WindowFunction        precomputed coefficients, applied in place
              ▼
      FftEngine             interface; real-forward transform on a reused buffer
              ▼
      SpectrumAnalyzer      |X[k]| → normalize → dBFS, clamped to the floor
              ▼
       ┌──────┴───────┐
  SpectrumSnapshot   WaterfallBuffer      handoff; no allocation in either path
   (latest frame)    (every row, ARGB)
       ▼                  ▼
  SpectrumView       WaterfallView        main thread, invalidated on vsync
```

### 3.1 The handoff, and why the two charts differ

The charts have different requirements, and this is the central design constraint:

- The **spectrum** only ever shows the newest frame. Older frames are irrelevant to it.
- The **waterfall** must consume *every* frame. A dropped frame is not a cosmetic glitch —
  it compresses the time axis and makes the display lie about when things happened.

So the two use different handoffs. `SpectrumSnapshot` holds two preallocated `FloatArray`s
and swaps a reference, giving the renderer the most recent complete frame and nothing else.

`WaterfallBuffer` instead applies the dB→ARGB colormap **on the audio thread** and writes
finished pixel rows directly into an `IntArray` ring buffer, advancing a `@Volatile`
write index after each complete row. The renderer reads that index once per frame and blits
two slices of a `Bitmap` to produce endless scrolling without ever shifting pixels. This is a
single-producer / single-consumer ring: no locks, no allocation in the hot path, and a torn
read can at worst smear the one row currently being written.

A consequence worth stating: because the FFT runs on the audio thread rather than the UI
thread, a slow frame or a GC pause on the main thread cannot cause an `AudioRecord` buffer
overrun. UI jank costs a repaint, never a sample.

### 3.2 Analysis rate is tied to FFT size

With a fixed 50% overlap, the frame rate of the analysis is `sampleRate / (N/2)`:

| N | hop | frames/s at 48 kHz | bin width |
| --- | --- | --- | --- |
| 1024 | 512 | 93.75 | 46.9 Hz |
| 2048 | 1024 | 46.88 | 23.4 Hz |
| 4096 | 2048 | 23.44 | 11.7 Hz |

Since the waterfall draws one pixel row per frame, **changing the FFT size visibly changes
the waterfall's scroll speed**. This is kept rather than hidden: it is the time–frequency
resolution trade-off made directly visible, which is exactly what the app is for.

## 4. Components

Everything in the `dsp` package is plain Kotlin with no Android imports, and is unit-testable
on the JVM.

| Component | Responsibility | Depends on |
| --- | --- | --- |
| `AudioCapture` | Owns `AudioRecord` and the audio thread; selects the source; emits `ShortArray` chunks; reports the granted source and sample rate | Android |
| `FrameAssembler` | Accumulates incoming chunks, emits fixed-size overlapping frames at hop boundaries | — |
| `WindowFunction` | Generates and holds window coefficients; exposes coherent gain | — |
| `FftEngine` | One method: real-forward transform of a `DoubleArray` in place | — |
| `JTransformsFftEngine` | `FftEngine` backed by the library; unpacks the packed output layout | JTransforms |
| `SpectrumAnalyzer` | frame → windowed → transformed → magnitude → normalized → dBFS `FloatArray` | `WindowFunction`, `FftEngine` |
| `ColorMap` | dB → ARGB via a 256-entry LUT | — |
| `WaterfallBuffer` | ARGB ring buffer of pixel rows; bin→column reduction; resize and clear | `ColorMap` |
| `SpectrumSnapshot` | Double-buffered latest dB frame | — |
| `AnalyzerEngine` | Wires the producer loop; applies settings changes; owns rebuild-on-change | all of the above |
| `AnalyzerSettings` | Immutable holder: `fftSize`, `window`, `dbFloor` | — |
| `FrequencyScale` | Immutable holder: sample rate, bin count, pixel width; the single source of the shared x mapping | — |
| `SpectrumView` | Draws the latest dB frame with dB gridlines and labels | `SpectrumSnapshot` |
| `WaterfallView` | Blits the ring buffer's two slices | `WaterfallBuffer` |
| `FrequencyAxisView` | Draws the shared linear frequency scale | — |
| `MainActivity` | Permission flow, lifecycle, control strip wiring, `Choreographer` loop | all |

`SpectrumView` and `WaterfallView` share their x-axis mapping with `FrequencyAxisView`
through a single `FrequencyScale` value (sample rate, bin count, pixel width), so the three
cannot drift out of alignment.

## 5. DSP pipeline specification

### 5.1 Capture

Mono, `ENCODING_PCM_16BIT`, 48 000 Hz requested. If `AudioRecord.getMinBufferSize` returns
`ERROR_BAD_VALUE` for that rate, retry at 44 100 Hz. **Nyquist and every axis label derive
from the rate actually granted**, never from a hardcoded constant.

`AudioRecord` buffer size is `max(minBufferSize, 4 × hop × 2 bytes)`, so several analysis
frames can be in flight without risk of overrun.

Source selection, in order, taking the first that both applies and initializes:

1. `MediaRecorder.AudioSource.UNPROCESSED` — requires `Build.VERSION.SDK_INT >= 24` **and**
   `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"`
2. `VOICE_RECOGNITION` — on most devices bypasses AGC
3. `MIC` — always available

The chosen source is displayed in the app bar. This matters: `MIC` routes through the
platform's automatic gain control, noise suppression and often a high-pass filter, all of
which visibly distort the spectrum — gain pumping, a notch where NS is working, suppressed
low frequencies. The user must be able to tell whether they are looking at raw or processed
audio.

### 5.2 Framing

Samples convert to float as `sample / 32768f`, giving the range [−1, 1).

`FrameAssembler` holds a ring buffer and emits a frame of `N` samples every `N/2` samples
advanced. Incoming chunk sizes are whatever `AudioRecord` returns and are **not** assumed to
divide `N` or the hop; the assembler must lose and duplicate nothing across chunk boundaries.

### 5.3 Windowing

Coefficients are precomputed once per `(window, N)` into a `FloatArray` and applied in place.

| Window | Definition (n = 0…N−1) | Coherent gain |
| --- | --- | --- |
| Rectangular | 1 | 1.00 |
| Hann | 0.5 − 0.5·cos(2πn/N) | 0.50 |
| Hamming | 0.54 − 0.46·cos(2πn/N) | 0.54 |
| Blackman | 0.42 − 0.5·cos(2πn/N) + 0.08·cos(4πn/N) | 0.42 |

Coherent gain is the mean of the coefficients.

### 5.4 Transform and normalization

The engine performs a real-forward transform on a single reused `DoubleArray`, producing
`N/2 + 1` usable bins with bin width `sampleRate / N`.

The library's packed output layout is easy to get wrong and therefore gets its own test: for
even `N`, `a[0] = Re[0]`, `a[1] = Re[N/2]`, and `a[2k], a[2k+1] = Re[k], Im[k]` for
`1 ≤ k < N/2`.

Magnitude is `sqrt(re² + im²)`, then divided by `(N / 2) × coherentGain`.

**This normalization is the invariant that keeps the knobs honest.** It makes a full-scale
sine read **0 dBFS on every window and at every FFT size**. Without it, switching from
rectangular to Blackman would drop the whole display by about 7.5 dB, and the user would be
unable to tell the window's real effect — the collapsing skirt — from a global gain change.
With it, the peak stays put and only the leakage changes, which is the lesson.

### 5.5 dB conversion

```
db = 20 · log10(max(magnitude, 1e-10))
db = db.coerceIn(dbFloor, 0.0)
```

`dbFloor` is adjustable from −120 to −40 dB in 10 dB steps, default −90.

## 6. UI specification

### 6.1 Layout

Portrait and landscape both supported. Top to bottom:

1. **App bar** — app name and a chip showing the active audio source
2. **`SpectrumView`** — ~34% of the remaining height
3. **`WaterfallView`** — ~40%, immediately adjacent to the spectrum with no separator, newest
   row at the top edge so it touches the spectrum
4. **`FrequencyAxisView`** — the single shared frequency scale, below the waterfall
5. **Control strip** — FFT size, window, dB floor

Chart surfaces are always dark regardless of the system theme, because the Inferno colormap
and the spectrum trace are designed against a dark ground. Surrounding chrome follows the
system theme.

### 6.2 SpectrumView

- y axis: `dbFloor` at the bottom to 0 dBFS at the top. Gridline step is 10 dB when the
  visible range is ≤ 60 dB and 20 dB otherwise, with lines at round multiples of the step, so
  labels stay round across the whole adjustable floor range of §5.5
- x axis: linear, 0 to Nyquist, gridlines every 4 kHz
- Trace: a line with a translucent fill beneath it
- Bin→pixel reduction: when bins outnumber pixel columns, take the **maximum** per column.
  Mean would under-read narrow peaks, which defeats the purpose. When pixels outnumber bins
  (N = 1024 on a wide screen), draw segments between bin positions.
- `onDraw` allocates nothing: a preallocated `FloatArray` of segment coordinates is filled
  and handed to `Canvas.drawLines`, and all `Paint` objects are fields.

### 6.3 WaterfallView

The `Bitmap` is sized to the view's pixel dimensions, so the bin→column reduction (the same
max rule as §6.2) happens once on the audio thread and the blit is 1:1 with no scaling. Both
charts therefore share an identical x mapping and their columns line up exactly.

One **physical pixel** row per analysis frame, newest at the top, scrolling downward. History
depth therefore depends on the device's pixel height, not on a fixed row count: on a 1080×2400
phone the waterfall is roughly 960 px tall, giving about 20 s of history at N = 2048, 10 s at
N = 1024 and 41 s at N = 4096. Rendering is two `drawBitmap` calls for the two ring slices.

The buffer reallocates and clears on any size change, including rotation.

### 6.4 Colormap

Inferno, as a 256-entry `IntArray` LUT built once by interpolating these stops:

```
#000004 #1b0c41 #4a0c6b #781c6d #a52c60 #cf4446 #ed6925 #fb9b06 #f7d13d #fcffa4
```

Index is `((db − dbFloor) / −dbFloor × 255)`, clamped to 0…255.

Inferno was chosen over the familiar jet/turbo rainbow because its luminance rises
monotonically: equal dB steps look like equal brightness steps, so intensity reads correctly.
Turbo's luminance zig-zags and invents contour bands that are artifacts of the palette rather
than features of the signal — unacceptable in a tool meant to teach what the signal actually
looks like. Inferno was chosen over viridis for its near-black floor, which separates "nothing
there" from "something faint there" — the distinction a waterfall is mostly used to make.

### 6.5 Controls

| Control | Widget | Effect on change |
| --- | --- | --- |
| FFT size (1024 / 2048 / 4096) | Segmented button group | Rebuild assembler, analyzer and waterfall buffer; clear history |
| Window (Rect / Hann / Hamming / Blackman) | Dropdown | Swap coefficient array only; no rebuild, history kept |
| dB floor (−120…−40) | Slider, 10 dB steps | Applied on release, not while dragging; clears the waterfall |

The dB floor clears the waterfall because existing rows were colorized under the old floor.
Keeping them would silently mix two different mappings in one image. Applying on release
rather than on every slider tick keeps that from happening repeatedly mid-drag.

## 7. Permissions and lifecycle

`RECORD_AUDIO` is declared in the manifest and requested through AndroidX
`ActivityResultContracts.RequestPermission`. A single code path covers both sides of
minSdk 21: on API 23+ the system dialog appears; on 21–22 the grant happened at install and
the contract returns `granted` immediately.

- Denied → a rationale panel replaces the charts, with a Grant button
- Permanently denied → the panel offers a button into app settings

Capture starts in `onStart` and stops in `onStop`, where `AudioRecord` is released and the
`Choreographer` callback unregisters, so a backgrounded app consumes nothing. The root view
sets `keepScreenOn` while running.

On rotation the activity recreates and the waterfall history clears. The three settings
survive via `onSaveInstanceState` as primitives — no ViewModel dependency is needed for
three values.

## 8. Failure modes

| Failure | Response |
| --- | --- |
| `AudioRecord` fails to reach `STATE_INITIALIZED` for a source | Fall through to the next source; if all fail, error panel naming the last reason |
| 48 kHz rejected by `getMinBufferSize` | Retry at 44.1 kHz; all labels follow the granted rate |
| `read()` returns `ERROR_DEAD_OBJECT` (another app took the mic) | Stop the loop, show "microphone unavailable", offer Retry |
| `read()` returns another negative code | Same path, with the code shown |
| Permission revoked while backgrounded | Re-checked in `onStart`; falls back to the rationale panel |
| FFT library incompatible with the platform | Resolved before implementation, not at runtime — see §11 |

## 9. Testing strategy

TDD applies to the `dsp` package, which runs on the JVM with no emulator:

- **`FrameAssembler`** — correct hop and overlap when chunk sizes do not divide `N`; no
  sample lost or duplicated across chunk boundaries; behaviour across a ring wrap
- **`WindowFunction`** — coefficients against the closed forms at endpoints and midpoint;
  coherent gain values from §5.3
- **FFT unpacking** — DC input puts all energy in bin 0; an impulse gives flat magnitude; a
  sine at an exact bin centre lands in that bin with neighbours at the floor
- **`SpectrumAnalyzer`, the normalization invariant** — a full-scale sine reads 0 dBFS ± 0.1 dB
  for every window × every FFT size. This is the test that protects §5.4
- **Leakage characterization** — a sine placed *between* bin centres produces each window's
  expected skirt; rectangular spreads widest, Blackman narrowest in amplitude. This test
  doubles as executable documentation of the app's purpose
- **`ColorMap`** — luminance increases monotonically across the LUT; clamping at both ends
- **`WaterfallBuffer`** — ring wraparound, row ordering oldest→newest, resize clears
- **Source selection** — the §5.1 fallback chain, with the Android calls behind an interface

The two chart Views get no automated rendering tests. They are verified by eye against a
known tone: at N = 2048 and 48 kHz, a 1 kHz tone belongs in bin 43 (1000 / 23.4), about 4.3%
of the way across the display. TDD covers the core, not the pixels.

## 10. Deliberately out of scope for v1

- **Temporal smoothing and peak-hold.** Raw per-frame values jitter visibly, but that jitter
  is the variance of a periodogram estimate — real information in a learning tool. Both are
  straightforward to add later behind the existing analyzer interface.
- **Logarithmic frequency axis.** Rejected for v1 in favour of the direct bin↔pixel
  relationship of a linear axis.
- **Pinch-to-zoom on frequency**, cursor readout, markers, audio source selection in the UI.

## 11. Open risk: the FFT library

JTransforms 3.1 is on Maven Central as `com.github.wendykierp:JTransforms:3.1`, but it
depends on `pl.edu.icm:JLargeArrays:1.5` and `org.apache.commons:commons-math3:3.5`, and
JLargeArrays touches `sun.misc.Unsafe` during static initialization. Under `targetSdk 37`
non-SDK interface enforcement is strict, so this is a genuine runtime risk — and it would
surface as `ExceptionInInitializerError` or `NoClassDefFoundError`, not as a catchable
`Exception` on the normal path.

**This is resolved before any application code is written.** The first implementation task is
a throwaway instrumented test that constructs the transform at N = 2048 and runs it on a
device or emulator at `targetSdk 37`. Two outcomes:

- It works → JTransforms is confirmed and `JTransformsFftEngine` proceeds as specified
- It throws → choose an alternative then, behind the unchanged `FftEngine` interface. The
  candidates are `commons-math3`'s `FastFourierTransformer` (pure Java, no `Unsafe`, heavier
  allocation) or a hand-written radix-2 Cooley–Tukey of roughly 60 lines

Spending ten minutes to find out is preferable to building a runtime fallback that may never
be needed. The one-method `FftEngine` interface exists precisely so that this decision stays
a swappable detail, and it is worth having regardless for testability.

## 12. Success criteria

1. A whistle or tone generator produces a clean, correctly positioned peak on both charts
2. Switching window functions changes the leakage skirt while the peak stays at 0 dBFS
3. Switching FFT size visibly trades frequency resolution against waterfall scroll speed
4. The active audio source is always visible in the UI
5. Rendering keeps up at 60 fps with no allocation in `onDraw`
6. The `dsp` package is fully unit-tested on the JVM, including the normalization invariant

# SoundWaterfall — Device Validation Record

**Date:** 2026-10-06
**Plan:** `docs/superpowers/plans/2026-10-05-sound-waterfall.md` (Task 14)
**Spec:** `docs/superpowers/specs/2026-10-05-sound-waterfall-design.md`

## Device

| | |
| --- | --- |
| Model | Lenovo A6010 |
| Android | 5.0.2, **API 21** (the project's `minSdk`) |
| SoC | Snapdragon 410, Cortex-A53, arm64 |
| Screen | 720 × 1280 px, density 320 (xhdpi) |
| Audio granted | **`VOICE_RECOGNITION` at 48 000 Hz**, 8192 B buffer |

`UNPROCESSED` was correctly skipped — it is API 24+ and this device is API 21, so the
chain fell through to `VOICE_RECOGNITION` exactly as `AudioSourceChainTest` predicts.
48 kHz was accepted, so the 44.1 kHz fallback never fired and **Review Focus 5 remains
unexercised on real hardware** (it is covered by unit tests).

## 1. Frame timing

Measured with `adb shell setprop debug.hwui.profile true` and `dumpsys gfxinfo`, which on
API 21 yields a rolling 128-frame table of Draw / Prepare / Process / Execute in ms. Frame
time is the row sum. API 21 has no `framestats` janky-frame summary — that is API 23+ — so
the janky count below is computed as frames exceeding the 16.7 ms budget.

| Build | N | p50 | p90 | p95 | > 16.7 ms |
| --- | --- | --- | --- | --- | --- |
| baseline | 1024 | 22.7 | 27.8 | 29.6 | 100% |
| baseline | 2048 | 25.5 | 31.2 | 34.6 | 100% |
| baseline | 4096 | 24.3 | 31.6 | 33.0 | 100% |
| + (a) skip idle waterfall redraws | 1024 | 22.2 | 30.4 | 31.7 | 84% |
| + (a) | 2048 | 22.2 | 29.0 | 30.9 | 85% |
| + (a) | 4096 | 19.2 | 30.6 | 33.4 | 59% |
| + (b) delta row uploads | 1024 | 18.7 | 24.9 | 27.1 | 66% |
| + (b) | 2048 | 16.9 | 24.3 | 26.1 | 51% |
| + (b) | 4096 | 17.5 | 23.8 | 25.4 | 58% |
| + spectrum redraw skip | 1024 | 16.5 | 22.5 | 24.9 | 47% |
| + spectrum skip | 2048 | 17.1 | 24.1 | 25.0 | 52% |
| + spectrum skip | 4096 | 17.7 | 23.4 | 25.4 | 76% |

**Threshold (janky < 10%, p95 < 16 ms): NOT MET**, but the median frame is now under
budget. Two further rounds followed the code review:

| Build | N | p50 | p90 | p95 | > 16.7 ms |
| --- | --- | --- | --- | --- | --- |
| `drawLines` experiment | 1024 | 23.7 | 26.3 | 27.0 | 100% |
| `drawLines` experiment | 2048 | 23.4 | 25.4 | 26.7 | 99% |
| `drawLines` experiment | 4096 | 18.3 | 21.1 | 21.8 | 57% |
| **after review fixes** | **2048** | **15.0** | 22.7 | 24.4 | **39%** |
| **after review fixes** | **4096** | **16.2** | 22.4 | 26.4 | **46%** |

**The `drawLines` experiment was run and refuted.** Spec §6.2 prescribes "a preallocated
`FloatArray` of segment coordinates … handed to `Canvas.drawLines`", and the plan deviated
to two `Path` objects without measuring the alternative. The code review was right that
this had to be tested before accepting a failed criterion. Tested: `drawLines` is
**substantially worse** — p50 23.4 vs 17.1 ms and 99% vs 52% janky at N=2048. Drawing the
translucent fill as one vertical segment per column is ~1440 primitives per frame against
two batched path draws, and HWUI handles the paths better. The `Path` implementation was
kept and this measurement is the evidence for it. With the cheapest available lever now
ruled out rather than merely untried, the recommendation to accept current performance is
materially stronger than it was.

**Removing the per-frame allocations was worth real milliseconds.** The review found that
`SpectrumView.drawGrid` allocated an `ArrayList` with boxed `Integer`s plus a `String` per
gridline on every frame, against spec §6.2's "onDraw allocates nothing". Caching the
gridline positions and label strings took p50 from 17.1 to **15.0 ms** at N=2048 and janky
frames from 52% to 39% — confirming those allocations were causing GC pauses inside the
16.7 ms budget, exactly as the review predicted.

## 2. CPU

21% (one core's worth, of four) with the app in the foreground at N=2048. The DSP is not
the bottleneck, which the frame timings corroborate: baseline frame time barely moved with
FFT size (22.7 / 25.5 / 24.3 ms for 1024 / 2048 / 4096) even though the transform work
quadruples across that range.

## 3. Optimizations applied

Plan Step 3 prescribed three fallbacks in order, stopping at the first to reach threshold.
None reached it, so (a) and (b) were both applied; (c) was **not**, because by then the
evidence said it would be optimizing the wrong thing.

- **(a) Skip the waterfall redraw when no new row exists.** `WaterfallBuffer.rowsAppended`
  plus a cached count in `WaterfallView.refresh()`. Took 100% → 59–85% janky. The gain
  tracks redundancy exactly as predicted: biggest at N=4096 (23 rows/s against 60 Hz
  vsync), smallest at N=1024 (94 rows/s already exceeds vsync). The check lives in
  `refresh()`, **not** `onDraw` — returning early from `onDraw` empties the display list
  and blanks the view.
- **(b) Upload only the rows that changed.** `Bitmap.setPixels` over the new span instead
  of the whole 1.35 MB buffer, in one or two calls depending on the ring wrap. Took
  59–85% → 51–66% janky and p95 from ~31 ms to ~26 ms. `clear()` advances `rowsAppended`
  by a full height so the renderer falls back to a full upload.
- **Spectrum redraw skip** (an extension of (a), not in the plan). `SpectrumSnapshot.publishCount`
  plus a cached count in `SpectrumView.refresh()`. **Showed no measurable gain** — the
  numbers moved within sample noise. Kept because it provably removes work and mirrors the
  waterfall fix, but it is honestly not what is costing the frames.
- **(c) Halving the vertical resolution was deliberately NOT applied.** By that point the
  waterfall was drawing at most 23–94 times/s and uploading only changed rows, and the
  spectrum skip had shown the remaining cost is not redundant redraws. The residual ~17 ms
  is per-frame Canvas recording plus GPU composite on a 2015 Adreno 306 at 720×1280.

## 4. What the 17 ms actually means

p50 ≈ 17 ms is ≈ 59 fps: the median frame sits essentially *on* the 60 Hz budget, so the
display looks smooth in use. The janky percentage is high precisely because the median is
at the budget — ordinary variance tips half the frames just over the line.

**No analysis data is lost at any frame rate.** Capture and DSP run on the audio thread, so
every analysis row reaches the waterfall buffer whatever the display is doing; a slow frame
costs a repaint, never a sample. That is spec §3.1's architecture doing its job, and it is
the reason a 2015 phone can still show an undistorted time axis.

Closing the remaining gap would need a fundamentally different renderer (OpenGL ES), which
spec §2 considered and rejected. **Recommendation: accept the current performance on this
device class and leave the renderer as it is.**

## 5. Failure modes (spec §8)

| Failure | Result |
| --- | --- |
| Backgrounding releases the microphone | **PASS** — 0 `AudioRecord` instances after `HOME` |
| Rotation mid-capture | **PASS** — no crash, no `AndroidRuntime` error; history clears as specified |
| Permission revoked while backgrounded | **NOT TESTABLE on API 21** — `pm revoke` refused with "Can't change android.permission.RECORD_AUDIO. It is required by the application"; it is an install-time permission before API 23, so the rationale and settings panels cannot be reached on this device |
| Microphone stolen by another app | **NOT TESTED** |
| 48 kHz rejected → 44.1 kHz fallback | **DID NOT OCCUR** — 48 kHz was granted |
| `AudioRecord` fails to initialize | **DID NOT OCCUR** |

## 6. Success criteria (spec §12)

| # | Criterion | Result |
| --- | --- | --- |
| 1 | Tone produces a clean, correctly positioned peak on both charts | **PARTIAL** — ambient low-frequency peaks are aligned in x between spectrum and waterfall, confirming the shared `FrequencyScale`. A 1 kHz tone was not played: any media player used to produce one takes foreground focus and stops capture, and playing sound through the host's speakers was not appropriate unasked. **Left for the user.** |
| 2 | Switching windows changes leakage while the peak stays at 0 dBFS | **PARTIAL** — on device, selecting Blackman visibly collapsed the skirt toward the floor. The "peak stays at 0 dBFS" half is proven by `SpectrumAnalyzerTest` across 4 windows × 3 FFT sizes, not on device, for the same reason as #1 |
| 3 | FFT size trades frequency resolution against scroll speed | **PASS** — N=4096 visibly narrowed the peak and cleared the history |
| 4 | The active audio source is always visible | **PASS** — chip reads `VOICE_RECOGNITION · 48000 Hz` |
| 5 | Rendering keeps up at 60 fps, no allocation in `onDraw` | **FAIL on frame rate**, though the median frame is now under budget (p50 15.0 ms at N=2048; see §1). The no-allocation half was **initially false** — `drawGrid` allocated ~17 objects per frame — and is true now that the gridlines and labels are cached |
| 6 | The `dsp` package is fully unit-tested on the JVM | **PASS** — 110 unit tests green; `grep "^import android"` over `dsp/` returns nothing |

## 7. Release build

`./gradlew :app:assembleRelease` succeeds with R8 optimization enabled:

```
app-release-unsigned.apk: 1 dex, 23,186 methods, 2.89 MB dex, APK 2.50 MB
```

Single dex, no missing-class errors, no keep rules needed. This independently confirms the
Task 8 decision to drop JTransforms: with JLargeArrays on the classpath R8 fails outright
on `sun.misc.Cleaner` and `com.sun.xml.internal.ws.encoding.soap.SerializationException`,
neither of which exists on Android, and the release build type has had `optimization`
enabled since the project template was generated.

## 7b. Fixes applied after the whole-branch code review

A fresh reviewer found seven Important issues; all were fixed in one pass, each with a test
that failed first where a test was meaningful.

1. **`SpectrumView.onDraw` allocated every frame** (spec §6.2 violation). Gridline
   positions and dB label strings are now computed in `bind`/`onSizeChanged`/the `dbFloor`
   setter and only read while drawing; `FrequencyScale.gridlineFrequenciesInto` fills a
   caller-owned `IntArray`. Measured gain above.
2. **The delta upload read `newestRow` and `rowsAppended` as two separate volatile reads.**
   A tear anchored the span at the old index while sizing it from the new count, skipping
   exactly the newest rows and leaving them stale for a full screen of scroll — the
   waterfall misreporting *when* something happened, which spec §3.1 names as the one thing
   that must not occur. The arithmetic is now in `WaterfallUpload`, unit-tested including
   the torn-pair case, and the renderer samples the counter on both sides of the index read
   and falls back to a full upload if it moved.
3. **`WaterfallBuffer.clear()` was a second, unsynchronised writer** from the main thread,
   and `rowsAppended += height` was a lost-update race against the audio thread's
   increment. A lost update would leave rows coloured under the previous dB floor on
   screen, which spec §6.5 forbids. The counter is now an `AtomicLong`.
4. **No uncaught-exception boundary on the audio thread** — any throw killed the process
   instead of reaching spec §8's Retry panel. `loop()` now catches `Throwable` and reports
   it through the sink; covered by a test with a source that throws.
5. **The `AudioRecord` buffer was sized from the initial hop**, so selecting N=4096 later
   left two hops of margin instead of the four spec §5.1 requires, risking silent overruns
   that would compress the time axis. It is now sized from `AnalyzerSettings.MAX_HOP`.
6. **The `drawLines` renderer was never tried.** Run and refuted; see §1.
7. **Documentation stated a withdrawn justification.** `Radix2FftEngine`'s KDoc claimed the
   JTransforms multidex APK "could not load its own classes", which was a misdiagnosis
   (stale test APK after a USB disconnect) retracted in the ledger but never propagated to
   the source. Rewritten to the three grounds that survive scrutiny, with the withdrawal
   stated; the plan's Task 0 heading now carries a SUPERSEDED-BY-TASK-8 banner.

## 8. Device reliability note

The test device disconnected from USB **five times** during this run, under install load,
artifact pull and logcat. Each was recovered with `adb kill-server && adb start-server`. It
also re-locks the screen during idle waits, which silently stops frame production — a
measurement taken across a lock will show no data rather than an error.

One consequence is worth carrying: a disconnect during Task 0 left a stale test APK
installed, and the next instrumented run executed against it, producing a
`ClassNotFoundException` that looked like a multidex defect and was not. **After any
disconnect, uninstall both `com.example.soundwaterfall` and `com.example.soundwaterfall.test`
before trusting an instrumented result.**

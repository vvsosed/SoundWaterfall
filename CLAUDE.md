# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew :app:testDebugUnitTest                 # JVM unit tests (fast, no device)
./gradlew :app:testDebugUnitTest --tests "com.example.soundwaterfall.dsp.ColorMapTest"
./gradlew :app:assembleDebug                     # compile without a device
./gradlew :app:installDebug                      # build + install
./gradlew :app:assembleRelease                   # R8 enabled; must stay single-dex and warning-free
```

**Instrumented tests do not accept `--tests`.** Gradle only offers that flag on `Test`
tasks, and `connectedDebugAndroidTest` is not one. Use:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.soundwaterfall.ui.WaterfallViewTest
```

`BUILD SUCCESSFUL` on an instrumented run is not evidence that the tests passed — a device
that disconnects mid-run can still produce it. Read
`app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` for the real result.

## Architecture

Two threads, and nearly every design decision follows from the split.

```
AudioRecord ──┐ audio thread: the blocking read sets the pipeline's cadence
              ▼
      FrameAssembler → WindowFunction → FftEngine → SpectrumAnalyzer (dBFS)
              ▼
       ┌──────┴───────┐
  SpectrumSnapshot   WaterfallView.submitFrame
   (latest frame)    (every frame, colourised here on the audio thread)
       ▼                  ▼
  SpectrumView       WaterfallView       main thread, invalidated per vsync
```

The two charts have genuinely different requirements, which is why there are two handoff
mechanisms rather than one. The spectrum only ever wants the newest frame, so dropping
intermediates is correct. The waterfall must receive **every** frame — a dropped row does
not just look wrong, it compresses the time axis and makes the display lie about when
something happened.

Because all DSP runs on the audio thread, a slow frame or a GC pause on the main thread
costs a repaint, never a sample. Preserve that property.

### Invariants that are easy to break

- **`dsp/` contains no Android imports.** This is what makes the whole core JVM-testable in
  milliseconds. `grep -rn "^import android" app/src/main/java/com/example/soundwaterfall/dsp/`
  must stay empty. `AudioSourceChain` lives in `audio/` but is also deliberately Android-free
  so the API-level selection logic can be unit-tested across every SDK at once.
- **`AnalyzerEngine.Sink` callbacks arrive on the audio thread**, including `onFrame` at
  ~47 Hz. `onFrame` deliberately does not post to the main thread; `SpectrumSnapshot` and
  `WaterfallView.submitFrame` are built for cross-thread handoff, and a `Choreographer`
  callback drives redraws. Only the infrequent callbacks marshal with `runOnUiThread`.
- **Settings are applied only between reads**, never inside a frame (`AnalyzerEngine.pump`).
  That is what guarantees a frame's length always agrees with the `FrequencyScale` delivered
  beside it, however fast the user drags a control.
- **`WaterfallBuffer` writes rows with a *decrementing* index**, so history ages downward
  through the array and the renderer can blit two contiguous slices with no vertical flip.
  An incrementing index would require drawing the ring in reverse.
- **Redraw-skip checks belong in `refresh()`, never in `onDraw`.** Returning early from
  `onDraw` leaves the view's display list empty and blanks the chart.
- **Both charts and the axis derive their x mapping from one `FrequencyScale`**, so they
  cannot drift apart. Everything follows the *granted* sample rate — never hardcode 48000;
  capture falls back to 44.1 kHz.
- **`onDraw` allocates nothing.** Gridline positions and label strings are cached on size,
  scale and `dbFloor` changes. Removing per-frame allocations measurably cut jank on the
  target device; string interpolation and `List<Int>` in the draw path are regressions.

### The normalization invariant

`SpectrumAnalyzer` divides magnitudes by `(N/2) × coherentGain`, and halves bins 0 and N/2
because DC and Nyquist are single-sided. A full-scale sine therefore reads **0 dBFS for
every window and every FFT size**. Without it, switching window would shift the whole
display and the user could not tell the window's real effect (the collapsing leakage skirt)
from a global gain change — which is the app's entire purpose. `SpectrumAnalyzerTest` guards
this across 4 windows × 3 sizes; do not weaken those assertions.

### No FFT library, on purpose

`Radix2FftEngine` is hand-written. JTransforms was tried and removed: it depends on
JLargeArrays, which references `sun.misc.Cleaner` and a `com.sun.xml.internal` SOAP class
that do not exist on Android (R8 fails), obtains `sun.misc.Unsafe` reflectively in a static
initialiser that throws `Error` if blocked, and drags in commons-math3 for one `FastMath`
call. The one-method `FftEngine` interface exists so this stays a swappable decision — every
test asserts against the interface, not the implementation. See
`docs/superpowers/plans/2026-10-05-sound-waterfall-decisions.md`.

## Target device

Development targets a **Lenovo A6010, Android 5.0.2 (API 21), 720×1280** — the project's
`minSdk`. Consequences worth knowing before writing Android code:

- `MediaRecorder.AudioSource.UNPROCESSED` is API 24+, so this device captures via
  `VOICE_RECOGNITION`. The chosen source is shown in the app bar precisely because platform
  AGC and noise suppression visibly distort the spectrum.
- Check API levels on platform calls: `paddingHorizontal` (API 26+) is silently ignored, and
  `Activity.shouldShowRequestPermissionRationale` is API 23+ — use `ActivityCompat`.
- `RECORD_AUDIO` is install-time before API 23, so `pm revoke` is refused and the permission
  panels cannot be exercised on this device.
- `dumpsys gfxinfo` has no janky-frame summary before API 23. Frame timing needs
  `setprop debug.hwui.profile true` and summing the Draw/Prepare/Process/Execute table.
- **The device drops off USB under load.** Recover with `adb kill-server && adb start-server`.
  After any disconnect, `adb uninstall` both `com.example.soundwaterfall` and
  `com.example.soundwaterfall.test` before trusting an instrumented result — a stale test APK
  once produced a `ClassNotFoundException` that looked like a code defect and was not.

## Documentation

`docs/superpowers/` holds the design spec (the binding authority for behaviour), the
implementation plan, a device validation record with frame-time measurements, and a decision
log recording every ruling made during implementation — including one misdiagnosis and its
retraction. Consult the spec before changing behaviour; it explains *why* for most of the
above.

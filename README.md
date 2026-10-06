# SoundWaterfall

A real-time audio spectrum analyser for Android. It listens to the microphone, runs an FFT,
and draws two views of the same signal: an instantaneous **spectrum** on top and a scrolling
**waterfall** (spectrogram) beneath it, sharing one frequency axis so a peak and its history
line up exactly.

It is built as a DSP learning tool, so the display is meant to be an honest picture of what
the transform actually produces rather than a prettified one.

```
┌──────────────────────────────────────────────┐
│ SoundWaterfall        VOICE_RECOGNITION · 48k│
├──────────────────────────────────────────────┤
│ 0 dBFS ·······························       │
│ -20    ·······························       │  spectrum
│ -40    ·······························       │  (newest frame)
│ -60    ····▲··························       │
│ -80 ···▲▲▲▲█▲··························      │
├──────────────────────────────────────────────┤
│ ▓▒░  ░▒▓█▓▒░                                 │  waterfall
│ ▓▒░   ░▒▓▒░                                  │  newest row on top,
│ ▒░     ░▒░                                   │  scrolling down
├──────────────────────────────────────────────┤
│ 0    4k    8k   12k   16k   20k   24k    Hz  │  shared axis
├──────────────────────────────────────────────┤
│ FFT    [1024] [2048] [4096]                  │
│ Window [Rect] [Hann] [Hamm] [Black]          │
│ Floor  ──────●────────────────  -90 dB       │
└──────────────────────────────────────────────┘
```

## What the controls do

| Control | Effect |
| --- | --- |
| **FFT size** 1024 / 2048 / 4096 | Trades frequency resolution against time resolution. Larger means a narrower peak but a slower-scrolling waterfall (94, 47 and 23 rows per second respectively). Changing it clears the history. |
| **Window** Rectangular / Hann / Hamming / Blackman | Changes spectral leakage. Switch from Rectangular to Blackman on a steady tone and watch the skirts around the peak collapse — **while the peak itself stays at the same height**. History is kept. |
| **dB floor** −120…−40 | Sets the bottom of the scale and the waterfall's colour range. Applied on release, and clears the history, because existing rows were coloured against the old floor. |

That the peak stays put when you change window is deliberate and is the point of the app:
magnitudes are normalised by `(N/2) × coherentGain`, so a full-scale sine reads 0 dBFS for
every window and every FFT size. Otherwise switching window would move the whole display and
you could not separate the window's real effect from a change in gain.

The three are orthogonal: FFT size changes what is measured, window changes how cleanly it
is resolved, and dB floor changes only how it is displayed. Only the first two alter the
numbers at all.

### FFT size — 1024 / 2048 / 4096

The time-versus-frequency trade-off, and the only control that changes the shape of the data
rather than its presentation.

| N | Bin width | One frame covers | Bins | Rows/sec |
| --- | --- | --- | --- | --- |
| 1024 | 46.9 Hz | 21.3 ms | 513 | 94 |
| 2048 | 23.4 Hz | 42.7 ms | 1025 | 47 |
| 4096 | 11.7 Hz | 85.3 ms | 2049 | 23 |

Bin width is `sampleRate / N`, so doubling N halves it: two tones 20 Hz apart are one blurred
peak at 1024 and cleanly separated at 4096. You pay in time — one frame at 4096 spans 85 ms
and everything inside those 85 ms is smeared uniformly across the row, so a finger snap is a
thin bright line at 1024 and a fat smudge at 4096.

The row rate follows from `AnalyzerSettings.hop`, fixed at `fftSize / 2` for 50% overlap:
rows per second is `sampleRate / hop`. The figures above assume the granted 48 kHz; on the
44.1 kHz fallback they become 86 / 43 / 22.

Changing N is the one case where clearing the history is structural rather than cosmetic. It
is the only branch in `AnalyzerEngine.pump` that rebuilds the pipeline — new
`Radix2FftEngine`, `FrameAssembler`, `FrequencyScale` and buffers — and the new scale has a
different bin count, so `onPipelineChanged` fires and the waterfall is cleared. It has to be:
rows already in the buffer each represent a different slice of time than the incoming ones,
and stacking 21 ms rows directly above 85 ms rows would leave the vertical axis silently no
longer meaning time.

### Window — Rectangular / Hann / Hamming / Blackman

The FFT treats its N samples as one period of an infinitely repeating signal. A tone that is
not exactly on a bin centre does not fit a whole number of cycles into the frame, so the wrap
produces a step discontinuity whose energy sprays across every bin. Those are the skirts. A
window tapers the frame smoothly to zero at both ends so the discontinuity never arises. The
trade is always the same: suppressing the sidelobes widens the main lobe.

| Window | Coherent gain | 1st sidelobe | Main lobe | Good for |
| --- | --- | --- | --- | --- |
| Rectangular | 1.00 | −13 dB | 2 bins | Maximum resolution; already-periodic signals |
| Hann | 0.50 | −31 dB | 4 bins | General purpose — the default |
| Hamming | 0.54 | −43 dB | 4 bins | Lowest nearby sidelobe, poor far roll-off |
| Blackman | 0.42 | −58 dB | 6 bins | A weak tone sitting beside a strong one |

The coherent gains are the values in `WindowFunction`; the sidelobe and main-lobe figures are
the standard properties of these windows.

History is kept because a window change takes the `else` branch in `pump` — just
`analyzer.setWindow()`, no rebuild, bin count unchanged, frequency axis identical. That is
what makes the switch legible: a horizontal seam appears in the waterfall where the skirts
collapse, with the peak running straight through it. Clearing would destroy the comparison
that is the whole demonstration.

### dB floor — −120 … −40

Sets the bottom of the spectrum's y axis and, more visibly, the waterfall's colour range.
`ColorMap.color` computes `range = -dbFloor` and `index = (db - dbFloor) / range × (SIZE-1)`,
mapping the floor onto the darkest Inferno stop and 0 dBFS onto the brightest. It is
effectively a contrast control:

- **−40** squeezes the whole palette into the top 40 dB: anything quieter is black, and loud
  content blooms and saturates.
- **−120** spreads it over 120 dB: the room's noise floor becomes visible texture, but the
  signal itself flattens into mid-tones.
- **−90** is the default, and the slider steps in 10 dB.

It applies on release rather than during the drag. `addOnChangeListener` updates only the
numeric label live; `onStopTrackingTouch` commits the setting. The split exists because
committing clears the waterfall, and applying continuously would wipe the history on every
pixel of slider movement.

That clearing follows from the threading design. The waterfall buffer stores *colours*, not
dB values — `WaterfallView.submitFrame` colourises each row on the audio thread as it arrives
— so once a row is stored there is no dB left to re-map against a new floor. Old rows would
be displaying a scale that no longer exists. Keeping them would be cheaper and would look
fine; it would just be wrong.

## Requirements

- Android **5.0 (API 21)** or newer
- Microphone permission (granted at install on API 21–22; a prompt on API 23+)
- JDK 17+ and the Android SDK; the build uses Gradle 9.6 and AGP 9.4.1

## Build and run

```bash
./gradlew :app:installDebug          # build and install on a connected device
./gradlew :app:testDebugUnitTest     # 124 unit tests, no device needed
./gradlew :app:connectedDebugAndroidTest   # 15 instrumented tests, device required
```

To check it is working, play a 1 kHz tone near the phone: at the default 2048-point FFT and
48 kHz you should see a sharp peak about 4% of the way across (bin 43), with a matching
bright streak at the same horizontal position in the waterfall.

## How it works

Capture and all signal processing run on a dedicated audio thread; the main thread only
draws. That split is what keeps the time axis honest — a slow frame or a garbage-collection
pause costs a repaint, never a sample.

```
AudioRecord → overlapping frames (50%) → window → FFT → magnitude → dBFS
                                                          ├→ spectrum (newest frame only)
                                                          └→ waterfall (every frame)
```

The FFT is a hand-written radix-2 Cooley–Tukey rather than a library. JTransforms was tried
and removed: it depends on JLargeArrays, which references JVM-internal classes that do not
exist on Android. The transform sits behind a one-method interface so the choice stays
reversible.

The audio source is chosen by preference — `UNPROCESSED` where the device supports it
(API 24+), otherwise `VOICE_RECOGNITION`, otherwise `MIC` — and the winner is shown in the
app bar, because the platform's automatic gain control and noise suppression visibly distort
the spectrum and you should know whether you are looking at raw audio.

The waterfall uses the **Inferno** colour map, whose brightness rises monotonically, so equal
steps in dB look like equal steps in brightness. The familiar rainbow/jet palette does not
have that property and invents contour bands that are not in the signal.

## Known limitations

- **Frame rate.** On the development device (a 2015 Snapdragon 410) the median frame is
  ~15 ms, just inside the 60 Hz budget, with roughly 40% of frames tipping over it. It reads
  as smooth, and no analysis data is lost at any frame rate — only the display lags.
  Measurements and the optimisations that were tried, including one that made things worse,
  are in the validation record below.
- Settings are not persisted across app restarts (they survive rotation).
- No smoothing, peak hold, logarithmic frequency axis or pinch-zoom; the raw per-frame jitter
  is left visible on purpose, since it is the variance of the estimate and therefore real
  information.
- Landscape works but is cramped — there is no dedicated landscape layout.
- No content descriptions for screen readers, and the control buttons are below the 48 dp
  touch-target guideline.

## Documentation

| Document | Contents |
| --- | --- |
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | Design spec — the authority on intended behaviour, with the reasoning behind each choice |
| [`docs/superpowers/plans/`](docs/superpowers/plans/) | Implementation plan, device validation record (frame timings, failure modes), and a decision log of every ruling made while building it |
| [`CLAUDE.md`](CLAUDE.md) | Orientation for AI coding agents: commands, architecture, and the invariants that are easy to break |

## License

[MIT](LICENSE).

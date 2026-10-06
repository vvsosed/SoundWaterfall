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

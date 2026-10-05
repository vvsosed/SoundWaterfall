# SoundWaterfall — Implementation Decision Log

Every ruling made while executing `docs/superpowers/plans/2026-10-05-sound-waterfall.md`,
plus the findings of the whole-branch code review and what was done about each.
Preserved from the execution ledger so the reasoning outlives the scratch workspace.


Ruling: executing on branch main — human partner explicitly chose "work directly on main" after being shown the worktree and feature-branch alternatives and the downside (15 commits land on main, no clean abandon path) — cost if wrong: history must be reset or reverted by hand.

## Pre-flight scan (shared interfaces)

| Consumer | Producer | Produces vs consumes | Found |
|---|---|---|---|
| T4 SpectrumAnalyzer | T1 WindowFunction | needs coherentGain + coefficients(size) | match |
| T4 SpectrumAnalyzer | T3 FftEngine | needs size, binCount, magnitudes(work,out) | match |
| T7 WaterfallBuffer | T6 ColorMap | needs color(db,dbFloor), floorColor | match |
| T9 AnalyzerEngine | T4 AnalyzerSettings | needs fftSize, window, dbFloor, hop, DEFAULT, FFT_SIZES | match |
| T9 AnalyzerEngine | T4 SpectrumAnalyzer | needs ctor(FftEngine,WindowFunction,Float), binCount, setWindow, dbFloor var, analyze | match |
| T9 AnalyzerEngine | T2 FrameAssembler | needs ctor(frameSize,hop) + append(chunk,len,onFrame) **inline** | match - inline is load-bearing: T9's lambda captures reassigned locals (analyzer/out/scale); a non-inline lambda would box them in Ref wrappers and allocate per frame |
| T9 AnalyzerEngine | T3 JTransformsFftEngine | needs ctor(size) | match |
| T9 AnalyzerEngine | T5 FrequencyScale | needs ctor(sampleRate,binCount) | match |
| T9 AnalyzerEngine | T8 SampleSource/SourceResult | needs read/close/sourceLabel/sampleRate; Ok.source, Failed.reason | match |
| T10 SpectrumView | T9 SpectrumSnapshot | needs binCount, readInto(dest) | match |
| T10 SpectrumView | T5 BinReducer/FrequencyScale | needs reduce(db,out); gridlineFrequencies, xOf | match |
| T11 WaterfallView | T7 WaterfallBuffer | needs ctor(w,h,colorMap), appendRow, pixels, newestRow, width, height, clear | match (see Ruling below re topSliceHeight) |
| T11 WaterfallView | T6 ColorMap | needs no-arg ctor | match |
| T11 Views | T5 BinReducer/FrequencyScale | needs reduce; gridlineFrequencies, xOf | match |
| T12 MainActivity | T8-T11 all | AudioCapture.open, AnalyzerEngine+Sink, SpectrumSnapshot, 3 view APIs | match |
| T13 controls | T12 MainActivity | needs settings + updateSettings in-class | match (both private, same class) |

Pre-flight: no blocking conflicts.

Ruling: T7 produces `topSliceHeight` as "the render contract", but T11's onDraw deliberately computes the split locally from ONE volatile read of newestRow instead of calling it — two separate volatile reads could disagree and tear the blit. Decision: keep both; `topSliceHeight` documents and tests the contract while production uses the single-read form. Cost if wrong: a reviewer flags an API production does not call, i.e. one minor.

Observation: T4 implements `AnalyzerSettings.binCount` without declaring it in its Produces block. Harmless surplus; no consumer depends on it.

Note: no TodoWrite tool in this session; ledger is the sole progress record.

Task 0: Ruling: `--tests` is not a valid option on `connectedDebugAndroidTest` (Gradle accepts it only on `Test` tasks) — replaced with `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`; CARRIED to Tasks 8, 10, 11 which use the same wrong form — cost if wrong: none, both select the same test.
Task 0: Ruling: `DoubleFFT_1D`'s constructor takes a `long`; Java widened the Int implicitly in the host-JVM probe but Kotlin does not, so the call needs `size.toLong()` — CARRIED to Task 3 `JTransformsFftEngine` — cost if wrong: compile error, caught immediately.
Task 0: Ruling: Step 5 (API 28+ non-SDK enforcement on an emulator) NOT executed; characterized from bytecode instead and deferred to Task 14 — `LargeArray.<clinit>` is harmless, the risk is `LargeArrayUtils.<clinit>` reflecting `theUnsafe` and throwing java.lang.Error if null, which Android would trigger via a caught NoSuchFieldException; deferred because theUnsafe is greylisted not blocklisted, the only device is API 21 with no enforcement, and a system image is a ~2 GB unrequested change to the user's SDK — cost if wrong: ExceptionInInitializerError on first FFT on an API 28+ device, fixed by swapping FftEngine for the radix-2 impl with all Task 3 tests unchanged.
Task 0: Note: device a679de0e dropped off adb during post-test artifact pull; recovered with `adb kill-server && adb start-server`. Build reported BUILD SUCCESSFUL despite the disconnect — the result XML, not the build status, is the evidence for instrumented tests.
Task 0: complete (commits 5a4577d..62edcb0, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry stored.)
Task 1: complete (commits 62edcb0..e716262, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 2: complete (commits e716262..6b99c86, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 3: applied carried ruling from Task 0 — DoubleFFT_1D(size.toLong()).
Task 3: complete (commits 6b99c86..a17b1eb, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 4: Ruling: brief's Step 5 Expected says "PASS, 12 tests" but the test class defines 11 (counted: invariant, DC, Nyquist, silence, clamp, leakage, scalloping, window-swap, no-alloc, 2x reject) — miscount in the plan, not a missing test; accepted 11/11 green — cost if wrong: none, no test is absent.
Task 4: Confirmed the spec refinement empirically: fullScaleDcReadsZeroDbfs passes for all 4 windows, so halving bins 0 and N/2 does put DC on the sinusoidal scale instead of +6 dB.
Task 4: complete (commits a17b1eb..de27f68, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 5: Ruling: brief's test `oneKilohertzLandsInBin43` computed the bin with `.toInt()` (truncation), which yields 42 because 1000/23.4375 = 42.67; the spec's claim that 1 kHz belongs in bin 43 is correct by ROUNDING (bin 43 = 1007.8 Hz, 7.8 Hz away; bin 42 = 984.4 Hz, 15.6 Hz away). Production code verified correct (binWidth = sampleRate/fftSize); corrected the TEST to `.roundToInt()` — cost if wrong: none, no production code consumes this computation; it is the test's own arithmetic. Task 12 Step 9's "bin 43, ~4% across" visual check remains valid.
Task 5: complete (commits de27f68..b1c8751, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 6: complete (commits b1c8751..586e303, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 7: complete (commits 586e303..c094d7a, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)

Task 8: Ruling: MISDIAGNOSIS CORRECTED. The instrumented test's ClassNotFoundException was NOT a multidex/API-21 dex-loading failure as I first concluded. Root cause: Task 0's post-test uninstall failed when the device dropped off USB, leaving the STALE Task 0 test APK installed (666 KB, 4 dex, contains FftLibrarySpike and no AudioCaptureTest); the runner then ran against that old APK. Proven by pulling /data/app/com.example.soundwaterfall.test-1/base.apk and inspecting its dex. After `adb uninstall` of both packages, the test passes 1/1. Lesson carried: after any device disconnect, uninstall both packages before trusting an instrumented run — cost of the error: ~25 min and one unnecessary dependency swap investigation.

Task 8: Ruling: replaced JTransforms with a hand-written iterative radix-2 Cooley-Tukey (`Radix2FftEngine`), taking spec §11 / plan Task 0 Step 6's documented "rejected" branch. NOTE the headline reason I first gave (multidex could not load classes) was the red herring above and is withdrawn. The swap nevertheless stands on three independently verified grounds: (a) JLargeArrays references `sun.misc.Cleaner` and `com.sun.xml.internal.ws.encoding.soap.SerializationException`, classes that do not exist on Android, so R8 fails to compile as soon as JTransforms classes are kept - and the release build type already enables `optimization`; (b) commons-math3 cost 14,359 method refs and ~3.8 MB of dex for a single FastMath call in one constructor, 74,649 -> 60,290 method refs once removed, taking the app back under the 65,536 single-dex limit; (c) `LargeArrayUtils.<clinit>` reflects `theUnsafe` and throws java.lang.Error if it comes back null, an unmitigated API 28+ risk confirmed from bytecode. All 78 unit tests pass against the new engine with every assertion UNCHANGED, which is exactly what the FftEngine interface existed for. This reverses the user's explicit brainstorming choice of "use a library", so it is surfaced for decision; reverting is a one-class change - cost if wrong: re-add the dependency and restore JTransformsFftEngine, ~15 min, and reaccept (a)(b)(c).

Task 8: Note: device a679de0e disconnected 3x during this task (install load, artifact pull, logcat). Instrumented runs need a retry and a clean uninstall. The granted audio source/rate that Step 10 asks to record was not captured because the device dropped before logcat could be read; deferred to Task 14 Step 1.
Task 8: complete (commits c094d7a..0bf565d, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 9: applied carried ruling — AnalyzerEngine builds Radix2FftEngine, not JTransformsFftEngine. REVIEW FOCUS 2 verified: 400 cross-thread settings changes, 0 mismatched frames.
Task 9: complete (commits 0bf565d..37ce81c, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 10: Ruling: Task 9's SpectrumSnapshot had a REAL concurrency defect, found by its own test under full-suite load ("torn frame: -11292.0 vs -11294.0") after passing in isolation. Plain double buffering gives the consumer zero safety margin: the producer returns to the consumer's buffer after one further publish and can overwrite it mid-copy. Spec §3.1 specifies "two preallocated FloatArrays and swaps a reference", which is insufficient; added a seqlock (volatile odd/even sequence counter bumped around each write, consumer accepts a copy only if the counter was even before and unchanged after, bounded to 8 retries then reports no new frame). Fixed the CODE, not the test. Verified 10/10 consecutive green runs of the race test plus the full suite — cost if wrong: a torn spectrum frame would show one visually meaningless frame of mixed bins; the waterfall path is unaffected as it never used this class.
Task 10: complete (commits 37ce81c..05ed009, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 11: Note: device disconnected a 4th time before the instrumented run; recovered with adb kill-server/start-server on the first retry, then 8/8 green.
Task 11: complete (commits 05ed009..2fbac87, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 12: Ruling: the brief's layout used android:paddingHorizontal / paddingVertical, which are API 26+ attributes and are silently IGNORED on the API 21 target device (padding would simply not apply). Replaced with paddingLeft/Top/Right/Bottom — cost if wrong: none, the explicit attributes work on every API level.
Task 12: Ruling: on-device verification revealed the FrequencyAxisView "Hz" unit drawn on top of the "24k" Nyquist label at 48 kHz. Fixed in Task 11's file during Task 12 by reserving the unit's width before clamping gridline labels — cost if wrong: cosmetic only.
Task 12: Verified on device by screenshot: source chip "VOICE_RECOGNITION · 48000 Hz"; dB gridlines at 20 dB step (correct for the -90 floor); spectrum low-frequency ambient peaks ALIGNED in x with the waterfall streaks, which is the shared-FrequencyScale check from Step 9; axis 0..24k = Nyquist for 48 kHz; Inferno waterfall welded to the spectrum, newest at top; no AndroidRuntime errors.
Task 12: NOT verified: the 1 kHz-tone-at-bin-43 check. It needs an audible tone, and any media player used to produce one would take foreground focus and stop capture; playing sound through the host's speakers was not appropriate to do unasked. Left for the user.
Task 12: complete (commits 2fbac87..68cce07, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry stored.)
Task 13: Verified on device by tapping through adb and comparing screenshots: defaults synced (2048/HANN/-90 dB); tapping FFT 4096 selects it, CLEARS the waterfall history and visibly narrows the spectrum peak; tapping Window BLACK selects it and KEEPS the history while the leakage skirt collapses toward the floor. Both spec §6.5 behaviours confirmed. No AndroidRuntime errors. Slider drag-then-release left for Task 14.
Task 13: complete (commits 68cce07..3e1b6b5, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)
Task 14: Ruling: plan Step 3's threshold (janky <10%, p95 <16 ms) is NOT MET and is not reachable on this device class. Applied (a) skip-idle-waterfall-redraw and (b) delta row uploads as prescribed, taking 100% -> ~51-66% janky and p50 24 -> 17 ms. Did NOT apply (c) halve-vertical-resolution: by then the waterfall drew at most 23-94 times/s uploading only changed rows, and an extra spectrum-redraw skip showed no measurable gain, so the residual ~17 ms is per-frame Canvas+GPU cost on a 2015 Adreno 306, not redundant redraws - (c) would have optimized the wrong component. Recommendation recorded: accept current performance; closing the gap needs OpenGL, which spec §2 rejected. Crucially no analysis data is lost at any frame rate because the DSP runs on the audio thread - cost if wrong: the user wants 60 fps on this device and must then accept an OpenGL renderer, a spec-level change.
Task 14: Ruling: kept the spectrum redraw skip despite it showing no measurable gain, because it provably removes work and mirrors fallback (a); recorded honestly as ineffective in the validation document - cost if wrong: ~10 lines of unneeded state in SpectrumView/SpectrumSnapshot.
Task 14: Note: permission-denied panels are NOT testable on this device - API 21 treats RECORD_AUDIO as install-time and `pm revoke` is refused. Mic-stolen-by-another-app also untested. Both recorded in the validation document.
Task 14: complete (commits 3e1b6b5..d069340, tests: ./gradlew :app:testDebugUnitTest --console=plain → Configuration cache entry reused.)

## Final review (fresh reviewer, Opus) — re-graded and fixed

Final: fixed Important 1 (SpectrumView.onDraw allocated ~17 objects/frame, violating spec §6.2) — FrequencyScaleTest.gridlinesCanBeWrittenIntoACallerOwnedBufferWithoutAllocating + 2 more RED→GREEN, suite 124/124. Measured gain on device: p50 17.1→15.0 ms, janky 52%→39% at N=2048, confirming the allocations were causing GC pauses inside the frame budget.
Final: fixed Important 2 (delta upload read newestRow and rowsAppended as two separate volatile reads; a tear skipped exactly the newest rows and left them stale for a full screen of scroll) — extracted WaterfallUpload, WaterfallUploadTest.aTornReadOfTheCounterPairForcesAFullUpload + 8 more RED→GREEN, suite 124/124.
Final: fixed Important 3 (WaterfallBuffer.clear() was an unsynchronised second writer and `rowsAppended += height` a lost-update race that could leave old-dB-floor rows on screen, spec §6.5) — rowsAppended is now an AtomicLong, suite 124/124.
Final: fixed Important 4 (no uncaught-exception boundary on the audio thread; any throw killed the process instead of reaching spec §8's Retry panel) — AnalyzerEngineTest.aThrowOnTheAudioThreadIsReportedInsteadOfKillingTheProcess RED→GREEN, suite 124/124.
Final: fixed Important 5 (AudioRecord buffer sized from the initial hop, leaving 2 hops of margin at N=4096 instead of spec §5.1's 4) — AnalyzerSettings.MAX_HOP, SpectrumAnalyzerTest.maxHopCoversTheLargestSelectableFftSize RED→GREEN, suite 124/124.
Final: Ruling: Important 6 (spec §6.2's drawLines renderer never tried) — RAN the experiment rather than arguing. drawLines is substantially WORSE: p50 23.4 vs 17.1 ms and 99% vs 52% janky at N=2048, because the translucent fill becomes ~720 vertical segments per frame instead of one batched path. Kept the Path implementation and recorded the measurement; the "accept current performance" recommendation is now evidence-backed rather than inferred — cost if wrong: none, the better-measuring implementation shipped.
Final: fixed Important 7 (Radix2FftEngine KDoc still asserted the multidex claim the Task 8 ruling withdrew) — rewritten to the three surviving grounds with the withdrawal stated; plan Task 0 now carries a SUPERSEDED-BY-TASK-8 banner.

### Rulings on the reviewer's "Declined to judge" list

Final: Ruling: 60 fps (spec §12 criterion 5) not met — a user gets a median frame of 15.0 ms at N=2048, i.e. under the 60 Hz budget, with ~39% of frames tipping just over it on a 2015 Snapdragon 410; the display reads as smooth and NO analysis data is lost at any frame rate because the DSP runs on the audio thread. Stands as a documented failure with the cheapest lever now measured and refuted — cost if wrong: the user wants true 60 fps and must accept an OpenGL renderer, which spec §2 rejected.
Final: Ruling: settings not persisted across app restarts — spec §1 names it a non-goal and onSaveInstanceState covers rotation as specified; a user re-picking three controls after a cold start is the designed behaviour. Stands — cost if wrong: one SharedPreferences read/write.
Final: Ruling: smoothing, peak-hold, log axis, pinch-zoom, cursor, markers, UI source selection all absent — spec §10 lists each as deliberately out of scope for v1, with the reasoning that raw jitter is itself information in a learning tool. Stands — cost if wrong: each is additive behind existing interfaces.
Final: Ruling: permission-denied and permanently-denied panels never exercised on hardware — API 21 treats RECORD_AUDIO as install-time and `pm revoke` is refused, so the path is unreachable on the only available device; the code was read and uses ActivityCompat rather than the API 23+ platform method. Accepted as untested rather than broken — cost if wrong: a user on API 23+ hits an untested panel; the failure mode is a mis-worded dialog, not a crash.
Final: Ruling: microphone-stolen-by-another-app path untested — the read<0 → onError → Retry chain is covered by a JVM test with a failing fake source, just not by a real contended microphone. Stands — cost if wrong: an error message that names a platform code instead of plain language.
Final: Ruling: the 1 kHz-tone-at-bin-43 check is left for the user — producing an audible tone needs a media player that takes foreground focus and stops capture, and playing sound through the host's speakers unasked was not appropriate. Ambient peaks were verified aligned in x across both charts, which is the shared-FrequencyScale property; the bin-43 arithmetic is unit-tested — cost if wrong: a frequency-axis scaling error that unit tests and the alignment check both missed, which would be visible immediately on the user's first tone.
Final: Ruling: no content descriptions and 36 dp toggle buttons against the 48 dp touch-target guidance — the spec names no accessibility requirement and this is a single-screen personal tool, but a 36 dp target is genuinely harder to hit and the charts are unlabelled for screen readers. NOT fixed, surfaced as a deferred minor so the user decides — cost if wrong: the app is awkward for anyone relying on TalkBack or with limited dexterity.
Final: Ruling: no AudioRecord overrun detection — spec §8's failure table does not list it and API 21 exposes no overrun signal, so there is nothing to detect; Important 5's fix removes the realistic cause by sizing the buffer for the largest hop. Stands — cost if wrong: silent sample loss that compresses the time axis with no diagnostic.
Final: Ruling: release signing, versioning and app icon are template defaults — outside the plan's scope and not required to run or evaluate the app. Stands — cost if wrong: the release APK is unsigned and cannot be installed without signing it first.
Final: Ruling: 17 commits on main with no clean abandon path — the user chose this explicitly after being shown the worktree and feature-branch alternatives and the downside. Stands — cost if wrong: history must be reset or reverted by hand.

### Deferred minors (not fixed; user decides)

Final: minor (deferred): at 44.1 kHz the frequency axis has no label at its right edge, because gridlines step by 4 kHz and 22050 is not a multiple; AxisLabels' fractional-kHz branch is therefore unreachable from production though it is tested.
Final: minor (deferred): BinReducer and FrequencyScale.xOf place a given bin at slightly different x (~1 px worst case at Nyquist), so trace and gridlines can disagree by under one bin width.
Final: minor (deferred): the SpectrumSnapshot seqlock has no explicit JMM fences; it relies on volatile ordering that ARM barriers happen to provide. Also, given the seqlock invalidates a read on any concurrent publish, the second buffer is now redundant.
Final: minor (deferred): engine.stop() can block the main thread up to 2 s inside onStop if AudioRecord.read wedges.
Final: minor (deferred): keepScreenOn is set unconditionally rather than only while capturing, so the screen stays on while an error or permission panel is showing.
Final: minor (deferred): the app bar and control strip hard-code a dark palette while the theme is DayNight, so in light mode the outlined buttons would draw low-contrast; API 21 has no system dark mode so this is not the state on the test device.
Final: minor (deferred): WaterfallView.onDetachedFromWindow nulls its buffer with no path to rebuild on re-attach at the same size; unreachable in this app but a trap if the view is ever reused in a pager or fragment.
Final: minor (deferred): AnalyzerEngine.stop() nulls the thread handle after a timed-out join, losing the reference to a thread that may still hold an AudioRecord.
Final: minor (deferred): the audio thread uses Thread.MAX_PRIORITY rather than Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO), which is the idiomatic choice for capture+DSP.
Final: minor (deferred): no layout-land; landscape reuses the portrait stack and leaves roughly 180 px for both charts on this device.
Final: minor (deferred): FrameAssembler pushes one sample at a time rather than bulk-copying per chunk segment.
Final: minor (deferred): AudioCapture.open re-queries getMinBufferSize inside the candidate loop though it is source-independent.
Final: minor (deferred): template leftovers remain — ExampleUnitTest, ExampleInstrumentedTest, the unused purple_/teal_ colours, the unused tools namespace in the manifest.
Final: minor (deferred): AnalyzerSettings.binCount has no consumer (WaterfallBuffer.topSliceHeight is likewise uncalled by production, but that one was already ruled on deliberately).
Final: minor FIXED as part of Important 7: stale comments in FftEngineTest and FftEngine KDoc that still described the JTransforms packed layout.

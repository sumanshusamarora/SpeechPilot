# Implementation Plan

## Iteration 1 — Scaffold ✅

**Goal:** Establish module structure, interfaces, and build infrastructure.

- [x] Multi-module Gradle project (10 modules)
- [x] `AudioFrame`, `AudioCapture`, `MicrophoneCapture` (IO-bound flow)
- [x] `VadResult`, `VoiceActivityDetector`, `EnergyBasedVad`
- [x] `SpeechSegment`, `SpeechSegmenter`, `VadSpeechSegmenter`
- [x] `PaceMetrics`, `PaceEstimator`, `RollingWindowPaceEstimator`
- [x] `FeedbackEvent`, `FeedbackDecision`, `ThresholdFeedbackDecision`
- [x] `SessionState`, `SessionManager`, `DefaultSessionManager`
- [x] `SessionRecord`, `SessionRepository` (interface only)
- [x] `UserPreferences`, `AppSettings` (interface only)
- [x] `MainScreen`, `MainViewModel`, `SpeechPilotTheme` (placeholder UI)
- [x] Unit tests for VAD, pace, feedback, session
- [x] README, phase1_architecture.md, plan.md

---

## Iteration 2 — Live Pipeline ✅

**Goal:** Connect modules into a working real-time pipeline.

- [x] Create `SessionConfig`, `SessionStats`, `LiveSessionState` models
- [x] Implement `SpeechCoachSessionManager` wiring `MicrophoneCapture → EnergyBasedVad → VadSpeechSegmenter → RollingWindowPaceEstimator → ThresholdFeedbackDecision`
- [x] Extend `SessionManager` interface with `liveState: StateFlow<LiveSessionState>`
- [x] Implement `RECORD_AUDIO` permission request in `MainActivity`
- [x] Update `MainViewModel` to call `SpeechCoachSessionManager.start()` / `stop()`
- [x] Expose live WPM and feedback in `MainUiState`
- [x] Display live WPM, feedback event, and listening indicator in `MainScreen`
- [x] Unit tests for `SpeechCoachSessionManager` state transitions

---

## Iteration 3 — Persistence ✅

**Goal:** Record and display session history.

- [x] Implement Room `@Entity` for `SessionRecord` and `@Dao` (`SessionDao`)
- [x] Implement `SpeechPilotDatabase` (Room database singleton)
- [x] Implement `RoomSessionRepository`
- [x] Wire `SpeechCoachSessionManager` to save a record on session stop via `SessionRepository`
- [x] Add session history screen (`HistoryScreen`, `HistoryViewModel`)
- [x] Unit tests for `SessionRecord` model and defaults

---

## Iteration 4 — Settings ✅

**Goal:** Let users configure pace targets.

- [x] Implement `DataStoreAppSettings` (DataStore-backed)
- [x] Add settings screen with target WPM, tolerance, and cooldown sliders (`SettingsScreen`, `SettingsViewModel`)
- [x] Feed `UserPreferences` into `ThresholdFeedbackDecision` at session creation
- [x] Persist preferences across app restarts
- [x] Add lightweight screen-switcher navigation (`AppNavigation`)
- [x] Unit tests for `UserPreferences` defaults and copy behavior

---

## Iteration 5 — Quick-Start, Background Hardening, Passive Mode, UX Cleanup ✅

**Goal:** Improve runtime robustness, reduce session-start friction, add passive-mode foundation, and clean up live session UX.

- [x] Add `SessionMode` enum (`Active` / `Passive`) to `:session`
- [x] Add `mode: SessionMode` field to `LiveSessionState`
- [x] Update `SessionManager.start()` to accept `mode: SessionMode = SessionMode.Active`
- [x] Guard `SpeechCoachSessionManager.start()` — no-op if already Starting/Active/Stopping
- [x] Guard `SpeechCoachSessionManager.stop()` — no-op if already Idle/Stopping
- [x] Suppress feedback dispatch in `SessionMode.Passive`
- [x] Create `SpeechCoachingService` foreground service to keep process alive during backgrounding
- [x] Add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` permissions to manifest
- [x] Wire service start/stop from `MainActivity` observing live session state
- [x] Create notification channel in `SpeechPilotApp.onCreate()`
- [x] Add `errorMessage: String?` and `sessionMode: SessionMode` to `MainUiState`
- [x] Surface error state and session mode from `LiveSessionState` in `MainViewModel`
- [x] Add `dismissError()` to `MainViewModel`
- [x] Improve `MainScreen` UX: speech-active indicator, passive-mode badge, error display, layout cleanup
- [x] Add `SessionModeTest` — enum equality, default in `LiveSessionState`, copy behaviour
- [x] Add session idempotency tests — double-stop, stop without start, mode storage
- [x] Update `phase1_architecture.md` with background service, passive mode, invariants

---

## Iteration 9C — Stabilization, Bug-Fix, and Integration Coherence ✅

**Goal:** Fix issues introduced across earlier iterations and improve coherence, correctness, and maintainability. No new features.

- [x] **Fix `:ui:compileReleaseKotlin` build failure** — `MainViewModel` directly instantiated `SpeechCoachSessionManager` with named constructor arguments, but `:ui` does not depend on `:audio`, `:vad`, or `:pace`. Kotlin's compiler requires visibility of all constructor parameter types when resolving default-argument calls. Fixed by adding a `SpeechCoachSessionManager.create()` companion factory that accepts only types already on `:ui`'s classpath (`FeedbackDispatcher?`, `SessionRepository?`, `FeedbackDecision`), and updating `MainViewModel` to use it.
- [x] **Fix nullable warning in `RollingWindowPaceEstimator.evictOldSegments`** — `java.util.ArrayDeque.peekFirst()` returns a nullable type in Kotlin. The original call was unsafe (`segmentQueue.peekFirst().startMs`). Fixed by extracting into a null-safe local variable with an early break.
- [x] Review and confirm docs accuracy (README, `phase1_architecture.md`)

---

## Iteration 6 — Improved Pace Estimation

**Goal:** Replace placeholder WPM estimation with a real word-boundary approach.

- [ ] Research lightweight on-device word-count heuristics (syllable-based or energy-envelope)
- [ ] Implement improved `PaceEstimator` in `:pace`
- [ ] Benchmark estimation accuracy against ground-truth recordings
- [ ] Update unit tests with realistic test vectors

---


## Iteration 10 — Local Transcript Debug + Transcript WPM ✅

**Goal:** Add an optional local transcription debug path and text-derived rolling WPM for pace calibration truth-checking.

- [x] Add `:transcription` module with `LocalTranscriber` contract
- [x] Implement `AndroidSpeechRecognizerTranscriber` (local, offline-preferred)
- [x] Add `RollingTranscriptWpmCalculator` and deterministic unit tests
- [x] Integrate optional transcription into `SpeechCoachSessionManager` without breaking existing heuristic pipeline
- [x] Expose transcript text + transcript-derived rolling WPM in `LiveSessionState`
- [x] Extend debug panel to compare heuristic pace vs transcript WPM
- [x] Add settings toggle for transcript debug mode (off by default)
- [x] Document limits, enablement, and persistence scope

---

## Iteration 13 — Transcript Runtime Visibility + Diagnosis ✅

**Goal:** Make transcript debug behavior explicit and diagnosable during live sessions.

- [x] Add typed transcript runtime model (`TranscriptDebugState`, `TranscriptDebugStatus`)
- [x] Keep transcript diagnostics visible in UI whenever transcript debug mode is enabled (even with blank transcript text)
- [x] Distinguish waiting / partial-only / final-available / unavailable / error states
- [x] Add explicit transcript WPM pending context when no finalized words have been recognized yet
- [x] Expose lightweight transcript diagnostics: partial-present flag, finalized/rolling word counts, last update timestamp
- [x] Add deterministic tests for transcript status mapping and partial-only behavior
- [x] Update README + architecture docs for transcript debug status semantics and final-word WPM dependency

---

## Iteration 14 — Transcript-First Live UX + Pace Hierarchy ✅

**Goal:** Make live sessions practically usable by centering transcript visibility and transcript-derived pace while keeping local-first constraints intact.

- [x] Add dedicated transcript card to main live screen when transcript mode is enabled
- [x] Keep transcript surface visible even when text is blank; render explicit states (listening, partial, final, no-final-yet, unavailable/error)
- [x] Promote transcript-derived WPM to primary pace display when finalized words exist
- [x] Show explicit pending/unavailable states instead of silent `0.0` transcript pace
- [x] Demote heuristic pace to secondary/debug context in transcript mode
- [x] Keep coaching wording honest: transcript pace may be displayed while decision engine remains heuristic
- [x] Simplify debug panel to compact calibration fields (speech-active, transcript status/engine, text WPM, heuristic pace, target, last decision, cooldown, mic level)
- [x] Add deterministic UI presentation tests for transcript state and primary pace selection logic
- [x] Extend transcript status mapping tests for unavailable/error states
- [x] Update README + architecture docs to reflect transcript-first UX and primary/secondary pace hierarchy

---

## Iteration 15 — Decision Signal Alignment (Transcript-Driven Feedback) ✅

**Goal:** Align coaching decisions with the visible pace signal by making transcript WPM the primary decision input when ready, with explicit fallback behavior.

- [x] Add explicit pace-signal selection model (`PaceSignalSource`, `PaceSignalSelection`)
- [x] Add deterministic transcript readiness rules for decision use (finalized transcript + minimum rolling word threshold + healthy status)
- [x] Update `SpeechCoachSessionManager` to evaluate feedback using selected signal (transcript when ready, heuristic fallback otherwise)
- [x] Expose active decision source/reason/fallback state in live debug state
- [x] Align live pace card and coaching labels with active decision signal
- [x] Expand compact debug panel with pace-source diagnostics (active source, reason, fallback, readiness, decision pace, alternative pace)
- [x] Add deterministic tests for pace-source selection and fallback behavior
- [x] Update docs for decision-source alignment and fallback semantics

---

## Iteration 11 — Live Audio Activity + UI Responsiveness ✅

**Goal:** Make the app feel observably alive during a session. Add live microphone level, live VAD speech activity, and a visual level meter. Fix top-chrome button clash and system back handling.

**Root causes addressed:**
- UI only updated on completed speech segments — frozen between segments
- `isSpeechDetected` was a historical sticky bool, not a live "speaking right now" signal
- No mic level or audio activity ever reached the UI
- History / Settings buttons overlapped phone status bar on tall-display devices (e.g. Pixel Fold)
- System back on Settings / History screens exited the app instead of returning to main screen

**Changes:**
- [x] Add `isSpeechActive: Boolean` to `LiveSessionState` — live frame-cadence VAD signal (~100 ms)
- [x] Add `micLevel: Float` to `LiveSessionState` — normalized RMS [0, 1] updated per frame window
- [x] Add frame-level monitor in `SpeechCoachSessionManager`: share audio frames via `shareIn`, launch parallel coroutine computing RMS and speech-active every `FRAME_LEVEL_UPDATE_INTERVAL` (3) frames
- [x] Add `computeFrameRms()` private utility in session manager (mirrors `EnergyBasedVad` logic)
- [x] Add `isSpeechActive` and `micLevel` to `MainUiState`; map from `LiveSessionState` in `MainViewModel`
- [x] Update `statusText` in `MainViewModel` to show "Speaking…" when `isSpeechActive`
- [x] Add `AudioLevelBars` composable to `MainScreen` — five animated bars in the status card header driven by `micLevel`, colored by `isSpeechActive`
- [x] Update status subtitle to use three-state messaging: "Speaking now" / "Speech detected this session" / "Waiting for speech…"
- [x] Update debug panel to show live `micLevel` and three-state `isSpeechActive` / `isSpeechDetected` label
- [x] Add `statusBarsPadding()` to `MainScreen`, `SettingsScreen`, and `HistoryScreen` columns to avoid status bar overlap on tall-display devices
- [x] Add `BackHandler` in `AppNavigation` for Settings / History screens so system back returns to main screen
- [x] Add `implementation(libs.androidx.activity.compose)` to `ui/build.gradle.kts` for `BackHandler`
- [x] Add unit tests: mic level > 0 after high-energy frames, `isSpeechActive` true/false for high/low-energy frames, `isSpeechDetected` historical persistence
- [x] Update `phase1_architecture.md` with live audio activity section, updated data flow, speech-activity semantics table

---

## Iteration 12 — Segment Finalization + Debug Observability ✅

**Goal:** Fix "mic alive but pace stuck at zero" by making segmentation state observable and ensuring pace estimation is reachable in real-device sessions.

**Root causes addressed:**
- Debug target/config values only became fully visible after segment processing paths ran
- VAD threshold was too permissive for real-device ambient noise, causing near-continuous speech classification
- Long-enough silence was often not observed, so segments were not finalized and pace estimation never ran
- UI did not expose segmentation progress (open segment / silence accumulation / finalized count)

**Changes:**
- [x] Raise `EnergyBasedVad` default threshold from `300` to `750` RMS to improve speech/silence separation on-device
- [x] Reduce `VadSpeechSegmenter.MIN_SILENCE_FRAMES` from `10` to `6` to finalize segments faster once silence begins
- [x] Add real-time segmentation diagnostics (`SegmentationDebugSnapshot`) emitted per frame
- [x] Extend `DebugPipelineInfo` with VAD energy/classification, segment-open counters, and finalized segment count
- [x] Initialize debug state at session start with configured target WPM and VAD threshold (no longer defaulting to zero)
- [x] Surface new diagnostics in the debug panel (`MainScreen`)
- [x] Add deterministic unit tests for segmentation debug snapshots and session debug initialization/finalized-segment reporting

---

## Iteration 16 — Transcription Backend Strategy: Vosk-First with Android SR Fallback ✅

**Goal:** Address the root cause of unreliable transcription — replace direct dependence on `AndroidSpeechRecognizer` as the primary transcript path with a dedicated on-device STT architecture.

**Diagnosis:**
- Android `SpeechRecognizer` is device/service-dependent: offline mode is a hint, not a guarantee
- Recognition quality, latency, and session continuity vary unpredictably across devices
- The app fell back to heuristic pace too often because transcript was unreliable, not because the architecture was wrong
- The fix is to make dedicated on-device STT (Vosk) the preferred backend and demote `AndroidSpeechRecognizer` to fallback

**Changes:**
- [x] Add `TranscriptionBackend` enum (`DedicatedLocalStt`, `AndroidSpeechRecognizer`, `None`)
- [x] Extend `TranscriptionEngineStatus` with `InitializingModel` and `ModelUnavailable` to cover Vosk lifecycle states
- [x] Add `activeBackend: StateFlow<TranscriptionBackend>` to `LocalTranscriber` interface
- [x] Implement `VoskLocalTranscriber` — preferred dedicated on-device STT backend:
  - Checks for Vosk model assets at `context.filesDir/vosk-model-small-en-us`
  - Reports `ModelUnavailable` immediately when model directory is absent
  - Architecture is production-ready for Vosk library integration (see TODO markers in `runRecognition()`)
  - `activeBackend` is always `DedicatedLocalStt`
- [x] Implement `RoutingLocalTranscriber` — automatic backend selection at session start:
  - Tries `VoskLocalTranscriber` (preferred)
  - Falls back to `AndroidSpeechRecognizerTranscriber` when Vosk reports `ModelUnavailable`
  - Exposes which backend is active via `activeBackend`
- [x] Demote `AndroidSpeechRecognizerTranscriber` to explicit fallback/compatibility role (updated KDoc)
- [x] Update `TranscriptDebugState` to include `activeBackend` field and `ModelUnavailable` status
- [x] Update `TranscriptDebugStatus` with `ModelUnavailable` case
- [x] Update `resolveTranscriptDebugStatus` to handle `InitializingModel` and `ModelUnavailable`
- [x] Update `PaceSignalSelector` to treat `ModelUnavailable` as unavailable (fallback to heuristic)
- [x] Update `SpeechCoachSessionManager` to collect and surface `activeBackend` from the transcriber
- [x] Update `MainViewModel` to use `RoutingLocalTranscriber` (Vosk primary + Android SR fallback)
- [x] Update `LiveSessionPresentation` with `ModelUnavailable` status text
- [x] Update debug panel to show active backend (`Transcript backend` row)
- [x] Add `VoskLocalTranscriberTest`: model availability checks, `ModelUnavailable` status on absent model, lifecycle
- [x] Add `RoutingLocalTranscriberTest`: primary backend selection, fallback activation, stop/restart behavior
- [x] Extend `TranscriptDebugStateTest` with `ModelUnavailable` and `InitializingModel` cases
- [x] Extend `PaceSignalSelectorTest` with `ModelUnavailable` fallback case
- [x] Update `SpeechCoachSessionManagerTest` stubs to satisfy updated `LocalTranscriber` interface
- [x] Update README, `phase1_architecture.md`, `plan.md`

**Remaining limitations:**
- `VoskLocalTranscriber.runRecognition()` contains `TODO: Vosk API` placeholder loops — actual Vosk recognition is not yet active. To enable:
  1. Add `com.alphacephei:vosk-android:0.3.47@aar` and `net.java.dev.jna:jna:5.13.0@aar` to `transcription/build.gradle.kts`
  2. Place Vosk model assets in `context.filesDir/vosk-model-small-en-us`
  3. Implement the `TODO: Vosk API` blocks in `VoskLocalTranscriber`
- Until model assets are present, `RoutingLocalTranscriber` automatically activates `AndroidSpeechRecognizerTranscriber` as fallback with no user action required



## Iteration 7 — Polish and QA

**Goal:** Release-candidate quality.

- [ ] End-to-end UI tests (Espresso / Compose test)
- [ ] Fix all lint warnings
- [ ] ProGuard rules
- [ ] Accessibility audit (TalkBack, font scaling)
- [ ] README updated with screenshots

---

## Deferred / Out of Scope (unless explicitly requested)

- Cloud upload or sync
- LLM-based feedback
- Multi-language support
- Backend API
- Analytics / crash reporting
- DI framework (Hilt / Koin)

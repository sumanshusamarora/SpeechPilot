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

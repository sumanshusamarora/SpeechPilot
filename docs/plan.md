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

## Iteration 6 — Improved Pace Estimation

**Goal:** Replace placeholder WPM estimation with a real word-boundary approach.

- [ ] Research lightweight on-device word-count heuristics (syllable-based or energy-envelope)
- [ ] Implement improved `PaceEstimator` in `:pace`
- [ ] Benchmark estimation accuracy against ground-truth recordings
- [ ] Update unit tests with realistic test vectors

---

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

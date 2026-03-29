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

## Iteration 2 — Live Pipeline

**Goal:** Connect modules into a working real-time pipeline.

- [ ] Wire `MicrophoneCapture → EnergyBasedVad → VadSpeechSegmenter → RollingWindowPaceEstimator → ThresholdFeedbackDecision` inside `DefaultSessionManager`
- [ ] Implement `RECORD_AUDIO` permission request in `MainActivity`
- [ ] Update `MainViewModel` to call `SessionManager.start()` / `stop()`
- [ ] Expose `FeedbackEvent` from `SessionManager` as a `SharedFlow`
- [ ] Display live WPM and feedback event in `MainScreen`
- [ ] Integration test for pipeline (mocked audio source)

---

## Iteration 3 — Persistence

**Goal:** Record and display session history.

- [ ] Implement Room `@Entity` for `SessionRecord` and `@Dao`
- [ ] Implement `RoomSessionRepository`
- [ ] Wire `DefaultSessionManager` to save a record on session stop
- [ ] Add a history screen (list of past sessions)
- [ ] Add migration infrastructure for Room schema versioning

---

## Iteration 4 — Settings

**Goal:** Let users configure pace targets.

- [ ] Implement `DataStoreAppSettings`
- [ ] Add settings screen with target WPM, tolerance, and cooldown sliders
- [ ] Feed `UserPreferences` into `ThresholdFeedbackDecision` at runtime
- [ ] Persist preferences across app restarts

---

## Iteration 5 — Improved Pace Estimation

**Goal:** Replace placeholder WPM estimation with a real word-boundary approach.

- [ ] Research lightweight on-device word-count heuristics (syllable-based or energy-envelope)
- [ ] Implement improved `PaceEstimator` in `:pace`
- [ ] Benchmark estimation accuracy against ground-truth recordings
- [ ] Update unit tests with realistic test vectors

---

## Iteration 6 — Polish and QA

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

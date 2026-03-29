# Phase 1 Architecture — SpeechPilot

## Overview

SpeechPilot is a native Android application that captures microphone audio in real time, detects
speech activity, segments utterances, estimates speaking pace, and delivers lightweight feedback to
the user.

All processing runs locally on-device. There is no backend, cloud pipeline, or external model
serving.

---

## Module Responsibilities

### `app`

Application entry point. Wires modules together. Contains `Application` subclass and a single
`Activity`. No business logic lives here.

### `ui`

Jetpack Compose screens and ViewModels. Observes state from `:session` via `StateFlow`. Translates
UI events (e.g. start/stop) into session calls. Does not process audio or make decisions.

### `session`

Central orchestrator. Owns the session lifecycle. Connects the audio pipeline:
`audio → vad → segmentation → pace → feedback`. Exposes `SessionState` as a `StateFlow` consumed
by `:ui`. Session control (start / stop) is the only entry point into the pipeline.

### `audio`

Wraps `AudioRecord`. Emits `AudioFrame` objects (PCM samples + metadata) as a coroutine `Flow`.
All capture runs on an IO dispatcher. The rest of the app never interacts with `AudioRecord`
directly.

```
AudioFrame(samples: ShortArray, sampleRate: Int, capturedAtMs: Long)
```

### `vad`

Voice Activity Detection. Receives individual `AudioFrame` objects and classifies them as
`Speech` or `Silence`. Initial implementation uses energy-based RMS thresholding. The interface
`VoiceActivityDetector` is the stable contract; the implementation can be swapped later.

### `segmentation`

Groups `AudioFrame` sequences into `SpeechSegment` objects (start time, end time, frame list).
Emits a segment when a silence gap exceeds a configurable threshold. Consumes VAD output.

```
SpeechSegment(frames: List<AudioFrame>, startMs: Long, endMs: Long)
```

### `pace`

Accepts `SpeechSegment` objects and maintains a rolling window of pace metrics. Returns
`PaceMetrics` (words-per-minute estimate, window duration). The rolling window evicts old
segments outside a configurable time horizon.

```
PaceMetrics(wordsPerMinute: Double, syllablesPerSecond: Double, windowDurationMs: Long)
```

### `feedback`

Evaluates `PaceMetrics` against configurable targets. Produces a `FeedbackEvent` (SlowDown,
SpeedUp, OnTarget) subject to a cooldown to avoid spam. Decision logic is separated from
delivery — `:ui` is responsible for rendering the event.

### `data`

Room database and `SessionRepository`. Records completed sessions (start/end time, average WPM).
Provides `Flow<List<SessionRecord>>` for history display.

### `settings`

DataStore-backed user preferences. Owns the `UserPreferences` data class (target WPM, tolerance,
cooldown, sample rate). All modules that need configuration read from `AppSettings`.

---

## Data Flow

```
Microphone
   │
   ▼
MicrophoneCapture ──► AudioFrame (Flow)
                          │
                          ▼
                   VoiceActivityDetector ──► VadResult per frame
                          │
                          ▼
                   VadSpeechSegmenter ──► SpeechSegment (Flow)
                          │
                          ▼
                   RollingWindowPaceEstimator ──► PaceMetrics
                          │
                          ▼
                   ThresholdFeedbackDecision ──► FeedbackEvent?
                          │
                          ▼
                       UI layer (Compose)
```

---

## Threading Model

| Concern | Dispatcher |
|---|---|
| Audio capture (AudioRecord) | `Dispatchers.IO` |
| VAD and segmentation | Caller's context (typically IO) |
| Pace estimation | Caller's context |
| Session state updates | `Dispatchers.Main` (via StateFlow) |
| UI rendering | Main thread |

---

## Key Invariants

1. Audio capture never runs on the main thread.
2. `SessionManager` is the single entry point for starting and stopping the pipeline.
3. Feedback decision logic (`FeedbackDecision`) is decoupled from feedback rendering.
4. No global mutable state outside of `DefaultSessionManager`.
5. Network operations: none. All data stays on-device.

---

## Scope of Phase 1

Phase 1 delivers the module scaffold only:

- Module structure with `build.gradle.kts` per module
- Interfaces and data classes defining the contracts between modules
- Placeholder implementations (energy VAD, placeholder pace estimator)
- Unit test skeletons for VAD, pace, feedback, and session

Phase 1 does **not** include:
- Real word-boundary or syllable detection
- Room database schema or migrations
- DataStore wiring
- UI beyond a placeholder screen
- Dependency injection

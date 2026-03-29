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
`audio → vad → segmentation → pace → feedback`. Exposes `SessionState` and `LiveSessionState`
as `StateFlow` consumed by `:ui`. Session control (start / stop) is the only entry point into
the pipeline.

Also maintains session-level summary stats (`SessionStats`) and persists them via
`SessionRepository` at session end.

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

Accepts `SpeechSegment` objects and produces pace metrics. Contains three key components:

1. **`PaceEstimator` interface** — stable contract for segment-level estimation.
2. **`RollingWindowPaceEstimator`** — maintains a rolling window of recent segments and returns
   a `PaceMetrics` value on each call to `estimate()`.
3. **`RollingPaceWindow`** — applies EMA smoothing over sequential `PaceMetrics` observations
   and tracks session-level peak and average.

```
PaceMetrics(estimatedWpm: Double, windowDurationMs: Long)
```

> **Important — approximate signal only:** `estimatedWpm` is a Phase 1 proxy derived from
> segment count and duration, **not** from word-boundary detection or speech recognition.
> True WPM measurement requires STT or syllable detection, which are not available in Phase 1.
> The value should be treated as a relative pace indicator. The `~WPM` label in the UI
> reflects this approximation.

### `feedback`

Evaluates `PaceMetrics` against configurable targets. Produces a `FeedbackEvent` (SlowDown,
SpeedUp, OnTarget) subject to a cooldown to avoid spam. Decision logic is separated from
delivery — `:ui` is responsible for rendering the event.

### `data`

Defines the `SessionRecord` data class and `SessionRepository` interface. Room schema and
migrations are not yet wired (Phase 1 preparation only). `SessionRepository` is accepted as
an optional dependency in `SpeechCoachSessionManager`; if null, sessions are not persisted.

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
                   RollingWindowPaceEstimator ──► PaceMetrics (per window)
                          │
                          ▼
                   RollingPaceWindow ──► smoothed / peak / average WPM
                          │
                          ▼
                   ThresholdFeedbackDecision ──► FeedbackEvent?
                          │
                          ▼
                   SpeechCoachSessionManager ──► LiveSessionState (StateFlow)
                          │                      SessionStats (session summary)
                          ▼
                   SessionRepository ──► SessionRecord (persistence)
                          │
                          ▼
                       UI layer (Compose)
```

---

## Live State Exposed to UI

`LiveSessionState` carries:

| Field | Description |
|---|---|
| `sessionState` | Current lifecycle state (Idle / Starting / Active / Stopping / Error) |
| `isListening` | True while audio pipeline is active |
| `isSpeechDetected` | True once at least one segment has been detected |
| `currentWpm` | Most recent raw estimated WPM (approximate proxy) |
| `smoothedWpm` | EMA-smoothed estimated WPM (reduces per-segment noise) |
| `latestFeedback` | Most recent `FeedbackEvent`, if any |
| `stats` | Session-level `SessionStats` snapshot |

---

## Session Summary Stats

`SessionStats` accumulates over the session lifetime:

| Field | Description |
|---|---|
| `startedAtMs` | Epoch ms when session started |
| `durationMs` | Total session wall-clock duration |
| `totalSpeechActiveDurationMs` | Cumulative duration of speech segments |
| `segmentCount` | Number of speech segments detected |
| `averageEstimatedWpm` | Mean estimated WPM across all segments |
| `peakEstimatedWpm` | Highest per-segment estimated WPM observed |

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
6. Pace estimation is modular — `PaceEstimator` and `RollingPaceWindow` are independently
   replaceable without changing the session pipeline.

---

## Scope of Phase 1

Phase 1 delivers a working runtime slice including:

- Module structure with `build.gradle.kts` per module
- Interfaces and data classes defining the contracts between modules
- Energy-based VAD, segment-based pace estimator, EMA rolling window
- Session summary stats (start time, duration, speech duration, segment count, avg/peak WPM)
- Live state exposed to the UI (listening, speech detected, current + smoothed WPM)
- Persistence model ready (`SessionRecord`, `SessionRepository`) — Room wiring deferred
- Unit tests for VAD, pace estimation, rolling window, feedback, and session lifecycle

Phase 1 does **not** include:
- Real word-boundary or syllable detection (current WPM is a proxy)
- Room database schema or migrations
- DataStore wiring
- Vibration / audio feedback output
- STT or LLM features
- Dependency injection framework


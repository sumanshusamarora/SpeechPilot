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

Application entry point. Wires modules together. Contains `Application` subclass, a single
`Activity`, and `SpeechCoachingService`. No business logic lives here.

`SpeechPilotApp` creates the notification channel used by the foreground service.
`SpeechCoachingService` is started when a session becomes active and stopped when the session
ends. Its sole purpose is to post a persistent notification so Android does not terminate the
process when the user backgrounds the app mid-session. The session and audio pipeline remain
owned by `MainViewModel` (via `SpeechCoachSessionManager`).

**Background behaviour:** `SpeechCoachingService` keeps the process alive during backgrounding.
It does not survive process death — if the process is killed, the session is lost. This is the
expected Phase 1 limitation. The service is declared with `foregroundServiceType="microphone"`
and the `FOREGROUND_SERVICE_MICROPHONE` permission is declared for Android 14+ compatibility.

### `ui`

Jetpack Compose screens and ViewModels. Observes state from `:session` via `StateFlow`. Translates
UI events (e.g. start/stop) into session calls. Does not process audio or make decisions.

### `session`

Central orchestrator. Owns the session lifecycle. Connects the audio pipeline:
`audio → vad → segmentation → pace → feedback`. Exposes `SessionState` and `LiveSessionState`
as `StateFlow` consumed by `:ui`. Session control (start / stop) is the only entry point into
the pipeline.

**Session mode:** `SessionManager.start()` accepts a `SessionMode` parameter (default `Active`).

- `SessionMode.Active` — full coaching session with pace estimation and feedback dispatch.
- `SessionMode.Passive` — audio capture and speech detection run normally, but feedback
  dispatch is suppressed. This is the Phase 1 anchor for future passive/always-listen work.
  It is not a full background daemon.

**Guarded lifecycle transitions:** `start()` is a no-op if the session is already Starting,
Active, or Stopping. `stop()` is a no-op if the session is already Idle or Stopping. This
prevents race conditions from repeated UI events.

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
   a `PaceMetrics` value on each call to `estimate()`. Uses syllable-peak detection to measure
   speaking pace (see below).
3. **`RollingPaceWindow`** — applies EMA smoothing over sequential `PaceMetrics` observations
   and tracks session-level peak and average.

```
PaceMetrics(estimatedWpm: Double, windowDurationMs: Long)
```

> **Important — syllable-rate proxy, not true WPM:** `estimatedWpm` is derived by detecting
> energy-envelope peaks (syllable nuclei) in each segment's audio frames, then dividing the
> syllable rate by an average English syllables-per-word constant (1.5). This gives a
> **correctly-directional** relative pace signal: faster speech produces more syllable peaks
> per second, yielding a higher `estimatedWpm`; slower speech yields a lower value.
>
> **Signal direction is correct and validated:** unit tests using synthetic audio at known
> syllable rates (2 syl/s = slow, 5 syl/s = fast) confirm that fast speech always produces
> higher `estimatedWpm` than slow speech. See `AudioPaceValidationTest`.
>
> **This is not a calibrated WPM reading.** Absolute values depend on audio quality, speaker
> characteristics, and frame size. The `~WPM` label in the UI reflects this approximation.
> Do not compare absolute values across devices or sessions without recalibration.
>
> **Previous bug (now fixed):** Before this correction, the estimator counted speech segments
> instead of syllables. This caused signal inversion: slow speech (many short segments) reported
> higher pace than fast speech (fewer, longer segments). The syllable-rate approach corrects this.

### `feedback`

Evaluates `PaceMetrics` against configurable targets and dispatches feedback to the user.
Contains three components that are cleanly separated:

1. **`FeedbackDecision` interface** — stable contract for decision logic.
2. **`ThresholdFeedbackDecision`** — threshold-based decision engine with cooldown and sustain
   debounce. Guards against invalid pace, suppresses repeat alerts via a configurable cooldown,
   and requires a configurable number of consecutive over-threshold observations before firing a
   `SlowDown` alert (avoids reacting to single noisy spikes). Decision logic is deterministic
   and fully unit-testable.
3. **`FeedbackDispatcher` interface** — stable contract for feedback output, decoupled from
   decision logic. Implementations are swappable without touching the session or decisioning code.
4. **`VibrationFeedbackDispatcher`** — delivers `SlowDown` and `SpeedUp` events as a short,
   subtle device vibration (80 ms pulse). `OnTarget` produces no vibration. Devices without a
   vibrator are handled gracefully. Uses `VibratorManager` on API 31+ and falls back to
   `Vibrator` on API 26–30.

```
FeedbackEvent: SlowDown | SpeedUp | OnTarget
FeedbackMode:  Vibration                         (Phase 1 only)
```

> **Limitation:** This is a first-pass coaching loop, not a clinically validated system.
> Feedback thresholds are configurable but not automatically personalised. The pace signal is
> an approximate proxy (see `pace` module), so feedback events reflect relative pace changes,
> not absolute speaking rate.

### `data`

Defines the `SessionRecord` data class (Room `@Entity`) and `SessionRepository` interface.
`RoomSessionRepository` is the Room-backed implementation, storing session summaries in a
local SQLite database (`speech_pilot.db`). `SpeechPilotDatabase` is the Room database class
(singleton, accessed via `SpeechPilotDatabase.getInstance(context)`).

`SessionRepository` is injected as an optional dependency in `SpeechCoachSessionManager`;
completed sessions are persisted automatically at session end. All data is local-only — no
network transmission occurs.

### `settings`

DataStore-backed user preferences. `DataStoreAppSettings` is the concrete implementation of
`AppSettings`, reading from and writing to `DataStore<Preferences>` under the key
`user_preferences`. Persists: `targetWpm`, `tolerancePct`, `feedbackCooldownMs`,
`micSampleRate`. All data is local-only.

Settings are loaded once at `MainViewModel` initialisation time via `AppSettings.preferences.first()`.
Changes saved via `SettingsViewModel` take effect from the next session start.

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
                   VibrationFeedbackDispatcher ──► Device vibration (subtle pulse)
                          │
                          ▼
                   SpeechCoachSessionManager ──► LiveSessionState (StateFlow)
                          │                      SessionStats (session summary)
                          │                      alertActive flag
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
| `mode` | Operational mode of the session (`Active` or `Passive`) |
| `isListening` | True while audio pipeline is active |
| `isSpeechDetected` | True once at least one segment has been detected |
| `currentWpm` | Most recent raw estimated WPM (syllable-rate proxy) |
| `smoothedWpm` | EMA-smoothed estimated WPM (reduces per-segment noise) |
| `latestFeedback` | Most recent `FeedbackEvent`, if any |
| `alertActive` | True when the most recent feedback was SlowDown or SpeedUp |
| `stats` | Session-level `SessionStats` snapshot |
| `debugInfo` | `DebugPipelineInfo` — lightweight debug snapshot (see below) |

### Debug Pipeline Info

`DebugPipelineInfo` (inside `LiveSessionState.debugInfo`) exposes internal pipeline state
for real-device calibration. Populated after each segment during an active session.

| Field | Description |
|---|---|
| `targetWpm` | The configured target pace threshold (est-WPM) |
| `lastDecisionReason` | Outcome of the last feedback evaluation (e.g. `on-target`, `speed-up`, `cooldown-suppressed`) |
| `isInCooldown` | True when the feedback cooldown window is active |

The debug panel is shown automatically on the main screen while a session is active.

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

## Feedback Decisioning

`ThresholdFeedbackDecision` is the first-pass coaching engine. It applies the following rules:

1. **Invalid-pace guard** — no feedback if `estimatedWpm ≤ 0`.
2. **Cooldown** — suppresses repeat events for a configurable window (default 5 s).
3. **Sustain / debounce (SlowDown only)** — requires `sustainCount` consecutive
   over-threshold observations before a `SlowDown` alert fires (default: 2).
   This avoids reacting to single noisy spikes in the pace signal.
4. `SpeedUp` and `OnTarget` fire on the first qualifying observation (no debounce).

All rules are deterministic and unit-tested. A configurable `clock` lambda allows
time-sensitive tests without `Thread.sleep`.

> **This is not a clinically validated system.** Thresholds and cooldown values are
> reasonable defaults intended for a first pass. The pace signal itself is a proxy measure.

---

## Vibration Feedback

`VibrationFeedbackDispatcher` triggers a short vibration (80 ms, ~31% amplitude) for
`SlowDown` and `SpeedUp` events. `OnTarget` produces no vibration. The implementation:

- Uses `VibratorManager` on API 31+ and the deprecated `Vibrator` service on API 26–30.
- Calls `hasVibrator()` before every vibration — no-ops on devices without a vibrator.
- Is wired into `SpeechCoachSessionManager` via the optional `feedbackDispatcher` parameter.
  If no dispatcher is provided, feedback events are evaluated and stored in `LiveSessionState`
  but no on-device output is produced.

---

## Key Invariants

1. Audio capture never runs on the main thread.
2. `SessionManager` is the single entry point for starting and stopping the pipeline.
3. Feedback decision logic (`FeedbackDecision`) is decoupled from feedback dispatch (`FeedbackDispatcher`).
4. No global mutable state outside of `DefaultSessionManager`.
5. Network operations: none. All data stays on-device.
6. Pace estimation is modular — `PaceEstimator` and `RollingPaceWindow` are independently
   replaceable without changing the session pipeline.
7. `start()` and `stop()` are idempotent — calling them in an invalid state is a safe no-op.
8. Feedback dispatch is suppressed in `SessionMode.Passive`; the rest of the pipeline runs normally.

---

## Background Behaviour

**Supported:** `SpeechCoachingService` is started as a foreground service when a session begins.
It posts a persistent notification that prevents Android from killing the process while the user
has backgrounded the app. Sessions remain active across app backgrounding, screen lock/unlock,
and app-to-recents transitions.

**Known limitation:** The session is held in `MainViewModel` scope. If the OS kills the process
(e.g. extreme memory pressure while backgrounded despite the foreground service), the session
is lost. Process-death recovery is not implemented in Phase 1.

## Passive Mode Groundwork

`SessionMode.Passive` is an explicit named anchor for future lightweight always-listen behaviour.
In Phase 1 it is fully functional as a mode switch that suppresses feedback dispatch while
keeping the audio pipeline running. True always-on background listening (without a user-initiated
session) is **not** implemented and is deferred to a future iteration.

---

## Scope of Phase 1

Phase 1 delivers a working runtime slice including:

- Module structure with `build.gradle.kts` per module
- Interfaces and data classes defining the contracts between modules
- Energy-based VAD, syllable-rate pace estimator (corrected signal direction), EMA rolling window
- Session summary stats (start time, duration, speech duration, segment count, avg/peak WPM)
- Live state exposed to the UI (listening, speech detected, current + smoothed WPM, alertActive, mode, debug info)
- Feedback decisioning: threshold, cooldown, and sustain/debounce logic (`ThresholdFeedbackDecision`)
- Vibration feedback output (`VibrationFeedbackDispatcher`) behind the `FeedbackDispatcher` abstraction
- Room-backed session summary persistence (`RoomSessionRepository`, `SpeechPilotDatabase`)
- DataStore-backed settings persistence (`DataStoreAppSettings`) — target WPM, tolerance, cooldown, sample rate
- Minimal settings screen (pace threshold, tolerance band, feedback cooldown sliders)
- Minimal session history screen (list of saved sessions with date, duration, speech time, segments, avg/peak WPM)
- Simple screen-switcher navigation (no nav library required in Phase 1)
- `SpeechCoachingService` foreground service for background session stability
- `SessionMode` enum (`Active` / `Passive`) as foundation for passive-mode work
- Guarded `start()`/`stop()` lifecycle (idempotent, safe no-ops on invalid transitions)
- Improved live session UI (speech-detected indicator, error display, passive-mode badge)
- Debug panel on main screen showing pipeline internals (pace signal, threshold, cooldown, decision reason)
- Unit tests for VAD, pace estimation (including syllable-rate Fast vs Slow validation), rolling window, feedback (threshold, cooldown, sustain, invalid-pace), session lifecycle, session mode, and data/settings models

Phase 1 does **not** include:
- True word-boundary or syllable detection via STT (current WPM is a syllable-rate energy proxy)
- Room database migrations (version 1 only in Phase 1)
- Tone / audio feedback output (vibration only in Phase 1)
- STT or LLM features
- Dependency injection framework
- Settings changes applied to a running session (changes take effect on next session start)
- Session recovery after process death
- True always-on background listening daemon
- Adaptive or personalized pace calibration


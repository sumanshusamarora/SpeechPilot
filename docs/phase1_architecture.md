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


### `transcription`

Optional local-first transcript path exposed directly in live UX when enabled.

#### Backend architecture

The transcription module uses a **two-tier backend strategy** with a selectable primary STT backend:

| Class | Role |
|---|---|
| `VoskLocalTranscriber` | **Default primary** backend. On-device STT using Vosk. Deterministic, no cloud dependency. Requires Vosk model assets. Reports `DedicatedLocalStt`. |
| `WhisperCppLocalTranscriber` | **Alternative primary** backend. On-device STT using Whisper.cpp JNI. Better transcript quality for accented English. Chunk-based, Final-only updates. Reports `WhisperCpp`. |
| `AndroidSpeechRecognizerTranscriber` | **Fallback** backend. Android `SpeechRecognizer` API, offline-preferred best effort. Activated automatically when the primary backend reports `ModelUnavailable`. |
| `RoutingLocalTranscriber` | **Router**. Tries the configured primary backend first; falls back to `AndroidSpeechRecognizerTranscriber` if it reports `ModelUnavailable`. Exposes `activeBackend`. |
| `NoOpLocalTranscriber` | Disabled state (transcription off). |

`LocalTranscriber.activeBackend: StateFlow<TranscriptionBackend>` exposes which backend is live:
- `DedicatedLocalStt` — Vosk is active
- `WhisperCpp` — Whisper.cpp is active
- `AndroidSpeechRecognizer` — Android fallback is active
- `None` — not started or transcription disabled

#### Backend selection

**Primary selection** is controlled by `UserPreferences.preferWhisperBackend` (default: `false`):
- `false` → `VoskLocalTranscriber` is created as the primary backend
- `true` → `WhisperCppLocalTranscriber` is created as the primary backend

**Fallback selection** is automatic (performed by `RoutingLocalTranscriber` at session start):

1. Start the selected primary backend.
2. If it reports `TranscriptionEngineStatus.ModelUnavailable`, stop it and start `AndroidSpeechRecognizerTranscriber`.
3. Route `updates` and `status` flows from the selected backend.
4. Update `activeBackend` to reflect the selected path.

#### Engine statuses

`TranscriptionEngineStatus` covers the full lifecycle of all backends:

| Status | Meaning |
|---|---|
| `Disabled` | Not running |
| `InitializingModel` | Backend loading model |
| `ModelUnavailable` | Model assets not found (Vosk) or model file / native library absent (Whisper) |
| `Listening` | Engine active and listening |
| `Restarting` | Auto-restart at result boundary (SpeechRecognizer) |
| `Unavailable` | Device/service does not provide recognition |
| `Error` | Recognition error |

#### Runtime behaviour and constraints

- `VoskLocalTranscriber` uses the shared app audio frame stream via `setAudioSource` — it does
  **not** open a second `AudioRecord`. Emits streaming partial + final results.
- `WhisperCppLocalTranscriber` also uses the shared audio frame stream. It **buffers PCM frames**
  internally in 5-second chunks before running inference. It emits **Final-only** updates — there
  are no streaming partial results. This is intentional: Whisper is a chunk-based encoder-decoder.
- Whisper's native runtime (`libwhisper_jni.so`) is built automatically by CMake `FetchContent` during
  `./gradlew assembleDebug`. whisper.cpp v1.7.2 is fetched from GitHub on first build (requires network);
  subsequent builds use the CMake cache. NDK `26.3.11579264` is pinned. ABIs: `arm64-v8a`, `x86_64`.
  If the library fails to load at runtime, `WhisperNative.isAvailable = false` → `ModelUnavailable` →
  automatic fallback to Android SR.
- The Vosk AAR (`com.alphacephei:vosk-android:0.3.47`) and JNA companion
  (`net.java.dev.jna:jna:5.13.0`) are wired in `transcription/build.gradle.kts`.
- `AndroidSpeechRecognizerTranscriber` behavior (quality, offline availability, latency) varies
  by device and speech service provider — this is the fundamental reason a dedicated backend is preferred.

#### Chunk-based WPM hold (Whisper)

`RollingTranscriptWpmCalculator` supports a **chunk-based mode** (`chunkBased = true`) enabled
automatically when the active backend is `TranscriptionBackend.WhisperCpp`.

Without smoothing, Whisper's 5-second chunk boundary causes the rolling WPM to fluctuate as
word observations age out of the 30-second window. The hold strategy:

- After each Final update the last computed WPM is stored as `heldWpm`
- Between chunks (while `timeSinceLastChunk ≤ wpmHoldDurationMs`, default 10 s), if the live
  rolling WPM drops below `heldWpm`, the held value is returned instead
- If the hold expires or the live WPM overtakes the held value, live WPM resumes

`TranscriptDebugState.isChunkBased` and `lastChunkAtMs` expose this to the UI so it can
display "Whisper processing…" rather than appearing to stall.

#### Other transcript components

- `RollingTranscriptWpmCalculator` computes a rolling transcript-derived WPM from **finalized** recognized words only. Supports `chunkBased` mode with WPM hold for Whisper backends.
- `TranscriptDebugState` exposes typed transcript runtime diagnostics including `activeBackend`, `engineStatus`, text preview, word counts, pending flags, `isChunkBased`, and `lastChunkAtMs`.
- `WhisperRunner` interface (with `WhisperNativeRunner` production and `FakeWhisperRunner` test implementations) abstracts native JNI calls for testability.

**Current behavior:** transcript-derived WPM is the **primary displayed pace metric** in transcript mode when finalized words exist, and becomes the **feedback decision signal** once transcript readiness is reached.

**Fallback behavior:** if transcript is pending/unavailable/model-missing/error, decisioning falls back explicitly to heuristic pace (or no-signal when neither is usable).

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
`micSampleRate`, `transcriptionEnabled`, `preferWhisperBackend`. All data is local-only.

`transcriptionEnabled` defaults to `true` — transcription is a first-class feature, on by default.
`preferWhisperBackend` defaults to `false` — Vosk is the default primary STT backend.
Users can change both in Settings.

Settings are observed continuously by `MainViewModel`.
For active sessions, preference changes (including transcription enablement and backend selection) apply from the next session start to avoid disrupting the running pipeline.

### `modelmanager`

Generic infrastructure for managing on-device AI model lifecycle. Responsible for downloading,
installing, and verifying local model assets so they are available to the relevant backend (e.g.
Vosk STT, Whisper.cpp STT, and future Gemma LLM models). No cloud inference or server-side
processing — models are provisioned to the device's private files directory and used entirely locally.

Supports two packaging formats:
- **ZIP** (`ModelArchiveFormat.ZIP`): downloaded archive is extracted, root prefix stripped, contents placed in `installDirName`.
- **SINGLE_FILE** (`ModelArchiveFormat.SINGLE_FILE`): single binary is downloaded and placed at `installDirName/singleFileName` with no extraction step. Used for Whisper ggml models.

#### Core types

| Type | Role |
|---|---|
| `ModelType` | Enum: `STT` (speech-to-text), `LLM` (large language model) |
| `ModelArchiveFormat` | Enum: `ZIP` (archive extraction) or `SINGLE_FILE` (direct binary placement) |
| `LocalModelDescriptor` | Immutable description of a model: id, type, displayName, approxSizeMb, wifiRecommended, purpose, download URL, install path, archive format, single filename, version, optional SHA-256 |
| `ModelInstallState` | Sealed class lifecycle: `NotInstalled` → `Queued` → `Downloading(progress)` → `Unpacking`? → `Verifying`? → `Ready` / `Failed(reason)` |
| `LocalModelManager` | Interface: `stateOf()`, `isReady()`, `ensureInstalled()`, `retry()` |
| `WorkManagerLocalModelManager` | Production implementation. Schedules `ModelDownloadWorker` via WorkManager; maps `WorkInfo` progress → `StateFlow<ModelInstallState>`; survives app backgrounding |
| `ModelDownloadWorker` | `CoroutineWorker`. Downloads via `HttpURLConnection`; extracts ZIP or places single file; reports progress via `setProgress()`; outputs error message on failure |
| `DefaultLocalModelManager` | Coroutine-scope-based implementation used in unit tests (no WorkManager dependency) |
| `KnownModels` | Registry of predefined model descriptors |

#### Registered models

| Model ID | Type | Format | Description | Size | Wi-Fi |
|---|---|---|---|---|---|
| `vosk-model-small-en-us` | STT | ZIP | Vosk small English model | ~40 MB | Not required |
| `whisper-ggml-small` | STT | SINGLE_FILE | Whisper.cpp ggml-small model | ~466 MB | Recommended |

#### Storage layout

All model assets are installed into the app's private files directory (`Context.getFilesDir()`):

```
filesDir/
  vosk-model-small-en-us/         ← Vosk STT model (auto-provisioned, ZIP)
    am/final.mdl                   ← acoustic model (readiness marker)
    conf/
    graph/
    …
  whisper/                         ← Whisper STT model directory (auto-provisioned, SINGLE_FILE)
    ggml-small.bin                 ← ggml model binary (readiness marker)
  gemma-4-e2b/                    ← future Gemma LLM model (not yet implemented)
```

Partial downloads are kept as `<model-id>.download.tmp` and deleted on failure or completion.

#### Provisioning flow

**ZIP models (Vosk) via WorkManager:**
1. `ensureInstalled(id)` enqueues `ModelDownloadWorker` via WorkManager (unique work, REPLACE policy)
2. WorkManager waits for network connectivity before starting
3. Worker downloads to temp file; posts percent/byte progress via `setProgress()`
4. Optional SHA-256 verification
5. ZIP extraction to staging directory; strip root prefix; atomic rename to install path
6. `ModelDownloadWorker` returns `Result.success()` → observer maps to `ModelInstallState.Ready`

**SINGLE_FILE models (Whisper) via WorkManager:**
1–4. Same as above
5. Create install directory; move temp file directly → no `Unpacking` step
6. `ModelDownloadWorker` returns `Result.success()` → `Ready`

WorkManager ensures the download survives backgrounding. If the process is killed mid-download,
WorkManager restarts the worker automatically when the app returns and network is available
(note: partial downloads are not resumable; the temp file is deleted and the download restarts).

**Active-backend-only provisioning:**
`MainViewModel` calls `ensureInstalled()` only for the model required by the currently selected backend:
- Vosk selected → provision `vosk-model-small-en-us` only
- Whisper selected → provision `whisper-ggml-small` only
- Transcription disabled → no provisioning

The inactive backend's model is never eagerly downloaded. Backend switching triggers a new `ensureInstalled()` call for the newly selected backend's model.

#### Extending for future models (e.g. Gemma 4 E2B)

Add a new `LocalModelDescriptor` to `KnownModels.all`. The provisioning infrastructure
requires no changes. For ZIP models, extend `isInstalledOnDisk` with a `ModelType.LLM`-specific
readiness heuristic if the current non-empty-directory check is insufficient.

#### Known limitations

- Downloads are not resumable across process restarts (partial temp file is deleted; download restarts from zero).
- No automatic retry on transient network failure — user must press Retry in the UI.

---

## Data Flow

```
Microphone
   │
   ▼
MicrophoneCapture ──► AudioFrame (Flow, shared via shareIn)
                          │
                          ├──► Frame-level monitor (every ~100 ms)
                          │         │
                          │         └──► micLevel, isSpeechActive ──► LiveSessionState
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
| `isSpeechActive` | **Live** — True while VAD currently classifies incoming audio as speech (~100 ms cadence). Resets to false during silence. Drives "Speaking now" indicator. |
| `isSpeechDetected` | **Historical** — True once at least one speech segment has been finalized during this session. Remains true for the rest of the session. |
| `micLevel` | Normalized microphone RMS in [0.0, 1.0]. Updated at frame cadence (~100 ms). Drives the audio level bar visualization. |
| `currentWpm` | Most recent raw estimated WPM (syllable-rate proxy) |
| `smoothedWpm` | EMA-smoothed estimated WPM (reduces per-segment noise) |
| `transcriptDebug` | `TranscriptDebugState` (typed transcript status, text preview, counters, pending flags, last update timestamp; powers transcript card + primary text-WPM selection) |
| `latestFeedback` | Most recent `FeedbackEvent`, if any |
| `alertActive` | True when the most recent feedback was SlowDown or SpeedUp |
| `stats` | Session-level `SessionStats` snapshot |
| `debugInfo` | `DebugPipelineInfo` — lightweight debug snapshot (see below) |

`debugInfo` also carries pace-source diagnostics used for user/developer transparency:
- `activePaceSource` (`transcript` / `heuristic` / `none`)
- `paceSourceReason` (why this source was selected)
- `fallbackActive` and `transcriptReadyForDecision`
- `decisionWpm`, `transcriptWpm`, and `heuristicWpm` for side-by-side calibration

### Live Audio Activity Monitoring

`SpeechCoachSessionManager` runs a **frame-level monitor** in parallel with the segmenter.
The audio frame flow is shared via `shareIn` so `AudioRecord` is only opened once.

Every `FRAME_LEVEL_UPDATE_INTERVAL` frames (~100 ms at 16 kHz / 512 samples per frame):
- RMS is computed for the latest frame
- `micLevel` is updated: `rms / MAX_DISPLAY_RMS`, clamped to [0, 1]
- `isSpeechActive` is set using the configured VAD threshold (`EnergyBasedVad.DEFAULT_THRESHOLD = 750` by default)

This ensures the UI stays responsive even between finalized speech segments, which previously
caused the interface to appear frozen.

### Speech Activity Semantics

| State | Meaning | Signal |
|---|---|---|
| `isListening = true` | Session is running and mic is open | Session lifecycle |
| `isSpeechActive = true` | VAD sees speech in the current frame window | Live, resets on silence |
| `isSpeechDetected = true` | A complete speech segment was finalized at least once | Historical, sticky |

Do not conflate these three. `isSpeechDetected` being false does not mean the mic is silent right
now — it means no complete segment has been emitted yet (the user may be mid-utterance).

### Audio Level Visualization

The main screen shows five animated vertical bars whose height tracks `micLevel`. The bars:
- Animate smoothly with a spring animation spec
- Use primary color when `isSpeechActive`, outline color during silence
- Always show a minimum height (15 %) so the bars are visible even during silence
- Only appear when a session is active

This gives the user immediate visual confirmation that the microphone is working and whether
speech is currently being detected.

### Debug Pipeline Info

`DebugPipelineInfo` (inside `LiveSessionState.debugInfo`) exposes internal pipeline state
for real-device calibration. It is initialized at session start (before any finalized segment)
and updated continuously while frames are processed.

| Field | Description |
|---|---|
| `targetWpm` | The configured target pace threshold (est-WPM) |
| `lastDecisionReason` | Outcome of the last feedback evaluation (e.g. `on-target`, `speed-up`, `cooldown-suppressed`) |
| `isInCooldown` | True when the feedback cooldown window is active |
| `transcriptionStatus` | Local transcript engine status enum (`Disabled`, `Listening`, `Restarting`, `Unavailable`, `Error`) |
| `vadFrameRms` | Current frame RMS from VAD/segmentation path |
| `vadThreshold` | Active VAD threshold used for classification |
| `vadFrameClassification` | Current frame class (`Speech` / `Silence`) |
| `isSegmentOpen` | True when an in-progress segment buffer is open |
| `openSegmentFrameCount` | Number of speech frames currently buffered in the open segment |
| `openSegmentSilenceFrameCount` | Consecutive silence frames observed while the segment is open |
| `finalizedSegmentsCount` | Number of finalized segments emitted in the current session |

The debug panel is shown automatically on the main screen while a session is active. It now also
shows live VAD/segmentation progress so "mic alive but pace zero" failure modes are visible.

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

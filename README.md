# SpeechPilot

A native Android application that provides real-time speech pace feedback to help speakers maintain a comfortable, clear delivery.

All core coaching pipeline processing runs locally on-device. No backend, cloud, or external services are required for pace estimation/feedback.

## Transition Status

This repository is now organized under `src/` by platform/runtime:

Current v2 scaffold locations:

- `src/android` — Android application and supporting modules
- `src/backend` — FastAPI realtime backend with live STT, replay, and persistence
- `src/web` — Next.js speaking-assistant shell with browser mic streaming, replay review, and session history
- `src/contracts` — shared realtime contracts
- `infra/docker` — local docker-compose stack

New work should be added under the appropriate `src/` subtree. See `src/ARCHITECTURE.md` for the transition design and current structure.

---

## Project Status

✅ **Phase 1 — Feature-complete.** Full audio→VAD→segmentation→pace→feedback pipeline running, with local settings and session history persistence, foreground-service background support, live audio level visualization, and a responsive live session UI.

---

## Architecture

SpeechPilot's Android client lives in `src/android` as a multi-module Gradle project. Each module has a single, focused responsibility.

| Module | Responsibility |
|---|---|
| `src/android/app` | Application entry point, wiring |
| `src/android/ui` | Compose screens, ViewModels |
| `src/android/session` | Session lifecycle orchestration |
| `src/android/audio` | Microphone capture, `AudioFrame` emission |
| `src/android/vad` | Voice activity detection |
| `src/android/segmentation` | Speech segment buffering |
| `src/android/pace` | Rate estimation, rolling metrics |
| `src/android/feedback` | Decisioning and feedback events |
| `src/android/data` | Persistence (Room, repositories) |
| `src/android/settings` | User configuration (DataStore) |
| `src/android/transcription` | Local transcription — Vosk or Whisper.cpp primary backend, Android SR fallback, rolling transcript WPM |
| `src/android/modelmanager` | Generic on-device model provisioning — download, install, state tracking for Vosk, Whisper, and future models |

See [docs/phase1_architecture.md](docs/phase1_architecture.md) for the full architecture description.

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK API 26+
- Java 17+

Run Android commands from the repository root:

### Build (debug)

```bash
./src/android/gradlew assembleDebug
```

### Build (release)

```bash
./src/android/gradlew assembleRelease
```

### Test

```bash
./src/android/gradlew test
```

### Lint

```bash
./src/android/gradlew lint
```

---

## Module Dependency Graph

```
src/android/app
 ├── ui
 │    ├── session
 │    ├── feedback
 │    ├── data
 │    ├── settings
 │    └── transcription
 └── session
      ├── audio
      ├── vad
      │    └── audio
      ├── segmentation
      │    ├── audio
      │    └── vad
      ├── pace
      │    └── segmentation
      ├── feedback
      │    └── pace
      ├── data
      ├── settings
      └── transcription
```

---


### Transcription

Transcription is a **first-class feature** in SpeechPilot — enabled by default. It provides live on-device speech-to-text, producing a live transcript and text-derived WPM that the coaching engine uses as its primary pace signal.

Transcription can be disabled in **Settings → Transcription** if not needed.

#### Transcription backend strategy

The app uses a **two-tier backend architecture** with selectable primary STT backends:

| Backend | Role | Condition |
|---|---|---|
| **Vosk** (`VoskLocalTranscriber`) | Dedicated primary STT option — deterministic on-device, no cloud | Active when Vosk is selected and model assets are installed |
| **Whisper.cpp** (`WhisperCppLocalTranscriber`) | Default primary STT — on-device Whisper with configurable model and chunk diagnostics | Active when Whisper is selected and model + native library are present |
| **Android SpeechRecognizer** (`AndroidSpeechRecognizerTranscriber`) | **Fallback** — device speech services, offline-preferred | Active when the selected primary backend is unavailable |
| **No-op** (`NoOpLocalTranscriber`) | Transcription disabled | Settings → Transcription turned off |

**Backend selection:** The primary STT backend is selectable in **Settings → Use Whisper.cpp backend**:
- **Off:** Vosk is the primary backend
- **On (default):** Whisper.cpp is the primary backend

When Whisper is enabled, **Settings** also exposes the Whisper model choice:
- **tiny.en (default):** `ggml-tiny.en.bin` (~75 MB)
- **base.en:** `ggml-base.en.bin` (~142 MB, Wi-Fi recommended)

Selection is performed automatically by `RoutingLocalTranscriber` at session start:
1. Start the selected primary backend (Vosk or Whisper.cpp)
2. If the primary fails during initialization (`ModelUnavailable`, `NativeLibraryUnavailable`, `Unavailable`, or init `Error` before `Listening`), stop it and activate the Android SpeechRecognizer fallback
3. Preserve the selected-backend failure reason in `TranscriptDebugState.diagnostics.fallbackReason`
4. Expose both the selected backend and the active backend in the transcript diagnostics/debug UI

The debug panel now shows, at minimum, the selected backend, active backend, selected/active model identity, backend fallback state/reason, model path/presence/readability/size, Whisper native-load result, native-init attempt/result, primary ready state, audio-source attachment, primary audio-frame count, Whisper buffered-sample count, chunks processed, chunk configuration, transcript update counts, file-audio preprocessing metrics (input/output sample rate, resampling flag, amplitude, clipping, duration), timing metrics (time-to-first-transcript, average chunk latency, total processing time), last transcript source/error, and last successful transcript timestamp. When Whisper is selected but the native library is not loaded, a persistent **"Whisper runtime unavailable"** error card is shown with the loader error detail.

#### Vosk backend

- Model: `vosk-model-small-en-us` (~40 MB compressed)
- Streaming frame-by-frame recognition with partial and final results
- Low latency, deterministic offline

#### Whisper.cpp backend (default)

- Default model: `ggml-tiny.en.bin` (~75 MB)
- Optional model: `ggml-base.en.bin` (~142 MB)
- Chunk-based live inference: audio is buffered in **2-second windows** before running inference
- **Final-only updates** — no streaming partial results (inherent to Whisper's design)
- May produce better transcript quality for accented English
- Shared Whisper preprocessing now resamples any non-16 kHz PCM input to **16 kHz mono float samples** before inference so file benchmarks and live inference use the same input contract
- Native runtime is compiled automatically by CMake's `FetchContent` on first build — no manual step required

#### Whisper benchmark mode

The main screen now includes a file-based Whisper benchmark launcher for structured comparison work without changing live session behavior.

- Benchmark runs are **offline file comparisons**, separate from microphone sessions
- The benchmark matrix currently compares **tiny.en** and **base.en** across multiple chunking strategies
- Results are surfaced as structured per-run cards with model identity, chunk duration, overlap, transcript text, transcript count, preprocessing metrics, timing, and runtime errors
- The benchmark runner and the live Whisper transcriber share the same chunking and preprocessing helper so chunk-size and sample-rate comparisons are fair

##### Whisper native runtime (CMake + JNI)

The native library is **built automatically** as part of a normal `./src/android/gradlew assembleDebug` build.

How it works:
1. The `transcription` module declares an `externalNativeBuild` pointing to `transcription/src/main/cpp/CMakeLists.txt`
2. The `CMakeLists.txt` uses CMake's `FetchContent` to download whisper.cpp (v1.7.2) from GitHub during the first CMake configure
3. CMake builds `libwhisper_jni.so` (the JNI bridge) and statically links `libwhisper` into it
4. The resulting `.so` is packaged into the APK for `arm64-v8a` and `x86_64`

Validated in the Android build artifact:
- `System.loadLibrary("whisper_jni")` is the correct runtime load call
- the debug APK packages `libwhisper_jni.so` for `arm64-v8a` and `x86_64`
- JNI-exported `WhisperNative_*` symbols live in that same bridge library

On first build, CMake needs network access to clone the whisper.cpp repository (~100 MB). Subsequent builds use the CMake fetch cache and are fully offline. NDK version `26.3.11579264` is pinned for reproducibility.

If the native library fails to load at runtime (e.g. unsupported ABI, corrupted install), `WhisperNative.isAvailable` is `false` and the backend reports `TranscriptionEngineStatus.NativeLibraryUnavailable` → Android SR fallback activates automatically. If the model file exists but cannot be opened or native context init returns null, the JNI bridge now preserves that exact failure detail so fallback diagnostics show the real init failure instead of a generic recognizer error. These failures are **explicitly surfaced** in the UI rather than silently appearing as "transcript pending".

#### Automatic model provisioning (WorkManager-backed)

**No manual setup is required.** The app automatically downloads the model required by the active STT backend. Downloads are managed by **WorkManager** so they survive app backgrounding.

- **Vosk selected** → provisions Vosk model only (~40 MB, no Wi-Fi required)
- **Whisper selected** → provisions the selected Whisper model only (`tiny.en` by default, `base.en` when chosen)
- **Android SR** or transcription disabled → no model download

A status card on the main screen shows:
- Model display name and approximate download size
- Wi-Fi recommendation for large models
- Live download progress (percent + MB)
- Retry button on failure

Model install states:
- Vosk: `NotInstalled` → `Queued` → `Downloading` → `Unpacking` → `Ready` / `Failed`
- Whisper: `NotInstalled` → `Queued` → `Downloading` → `Ready` / `Failed` *(no unzip needed)*

WorkManager ensures the download continues even if the user backgrounds the app. Network connectivity is required (WorkManager will wait for a connected network before starting).

#### Local model storage layout

```
filesDir/
  vosk-model-small-en-us/       ← Vosk STT model (auto-provisioned, ~40 MB)
    am/final.mdl                 ← acoustic model (readiness marker)
    conf/
    graph/
    …
  whisper/                       ← Whisper.cpp model directory (auto-provisioned, ~75 MB)
    ggml-tiny.en.bin             ← ggml model binary (readiness marker)
    ggml-base.en.bin             ← optional benchmark / higher-quality model
```

#### What transcription provides

- Live transcript text displayed in a dedicated card during active sessions
- Rolling **transcript-derived WPM** from finalized recognized words (primary pace signal when available)
- Explicit status states: listening, partial transcript, final transcript, model unavailable, error
- Heuristic est-WPM as secondary fallback when transcript is pending or unavailable
- **Chunk-based mode** (Whisper): WPM hold carries the last known pace for up to **4 seconds** between 2-second chunks to avoid oscillation

> Transcription setting changes apply on the next session start.
> Transcript text is kept in-memory for the current session and is not stored in session history.
> Transcript-derived WPM is based on **finalized** recognizer words only. If only partial hypotheses arrive, transcript WPM remains pending by design.
> Coaching decisions use transcript-derived WPM when transcript readiness is reached. Until then, the app falls back explicitly to heuristic pace.
> Whisper benchmark runs do not alter the live session chunking strategy; they are separate file-based comparison jobs.

---

### Local model management

SpeechPilot uses a generic model provisioning system (`modelmanager` module) to automatically
manage on-device AI model assets. The system is not backend-specific — it supports both zip
archive models (Vosk) and single-file binary models (Whisper.cpp), and is designed to support
additional model families in future iterations (e.g. Gemma 4 E2B on-device LLM).

Key abstractions:

| Class | Role |
|---|---|
| `LocalModelDescriptor` | Model metadata: id, type, displayName, approxSizeMb, wifiRecommended, download URL, install path, archive format, version, optional checksum |
| `ModelArchiveFormat` | `ZIP` (extract archive) or `SINGLE_FILE` (download binary directly) |
| `ModelInstallState` | Observable state machine: `NotInstalled` → `Queued` → `Downloading` → `Unpacking`? → `Verifying`? → `Ready` / `Failed` |
| `LocalModelManager` | Interface: `ensureInstalled()`, `stateOf()`, `isReady()`, `retry()` |
| `WorkManagerLocalModelManager` | Production implementation: schedules `ModelDownloadWorker` via WorkManager; maps `WorkInfo` → `StateFlow<ModelInstallState>` |
| `ModelDownloadWorker` | `CoroutineWorker`: downloads model (HTTP), optional SHA-256 verify, ZIP extract or single-file install, reports progress via `setProgress()` |
| `DefaultLocalModelManager` | Coroutine-scope-based implementation; used in unit tests without WorkManager dependency |
| `KnownModels` | Predefined model registry — add new models here |

Adding a new model (e.g. Gemma 4 E2B) requires only adding a `LocalModelDescriptor` to
`KnownModels.all` and calling `ensureInstalled()` from the appropriate ViewModel — no changes
to provisioning logic.

**Current limitations:**
- Downloads are not resumable across process restarts (partial files are discarded; next call restarts from zero)
- No automatic retry on transient network failure; user must press Retry on failure

---

### Live audio activity

During an active session the main screen shows:

- **Animated level bars** — five bars whose height tracks the normalized microphone RMS level.
  They use the primary color when speech is detected in the current frame, and a neutral outline
  color during silence. This confirms the microphone is working without requiring a completed segment.
- **Live status text** — "Speaking…" while the VAD detects speech in the current frame window,
  "Listening…" otherwise. The historical "speech detected this session" label is separate.
- **Debug panel** — shows raw `micLevel` (normalized RMS) and the three-state speech activity
  label (`yes (right now)` / `yes (this session)` / `none yet`), plus live segmentation diagnostics:
  current VAD frame RMS, VAD threshold, current frame class (speech/silence), segment-open state,
  open-segment frame/silence counters, and finalized segment count.

These signals update at ~100 ms cadence and do not wait for segment boundaries.

#### Speech activity semantics

| Indicator | Meaning |
|---|---|
| `isSpeechActive` | VAD currently detects speech — live, resets on silence |
| `isSpeechDetected` | Speech was seen at some point this session — historical, sticky |
| `micLevel` | Normalized RMS [0, 1] — mic is alive and audio is flowing |

Current defaults used by the live pipeline:
- `EnergyBasedVad.DEFAULT_THRESHOLD = 750.0` RMS
- `VadSpeechSegmenter.MIN_SILENCE_FRAMES = 6` (≈ 190 ms at 16 kHz / 512-sample frames)

---

## Releasing

Releases are automated via GitHub Actions. To publish a signed release APK:

### 1. Generate a release keystore (one-time setup)

```bash
keytool -genkeypair \
  -v \
  -keystore release-keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias my-release-key
```

Keep `release-keystore.jks` safe and **do not commit it** to the repository.

### 2. Base64-encode the keystore

```bash
# macOS
base64 -i release-keystore.jks | tr -d '\n'

# Linux
base64 -w 0 release-keystore.jks
```

### 3. Add GitHub repository secrets

Go to **Settings → Secrets and variables → Actions** and add:

| Secret name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded content of `release-keystore.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password chosen during `keytool` |
| `ANDROID_KEY_ALIAS` | Key alias (e.g. `my-release-key`) |
| `ANDROID_KEY_PASSWORD` | Key password chosen during `keytool` |

### 4. Create and push a release tag

Ensure the commit you want to release is on `main`, then:

```bash
git tag release-v1.0.0
git push origin release-v1.0.0
```

The workflow will:
1. Decode `ANDROID_KEYSTORE_BASE64` into a temporary keystore file
2. Pass the keystore path and credentials to Gradle via environment variables
3. Build a properly signed `app-release.apk`
4. Upload it as `SpeechPilot-v1.0.0-release.apk` to the GitHub Release

### How the workflow handles signing

The workflow decodes the base64 keystore to `$RUNNER_TEMP/release-keystore.jks` and exports the following environment variables consumed by Gradle:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If any of these are missing, the Gradle build fails with a clear error. The build will never silently produce an unsigned APK in CI.

> **Debug builds are unaffected.** Signing configuration is only applied when `ANDROID_KEYSTORE_PATH` is set.

---

## Conventions

- **Kotlin-only** source files, idiomatic Kotlin style
- **Sealed classes** for state and event modeling
- **Coroutines + Flow** for all async and streaming work
- **No business logic in Composables or ViewModels** beyond UI state orchestration
- **Local-first**: no network calls, no analytics

See [AGENT.md](AGENT.md) for the full contributor guide.

---

## Roadmap

See [docs/plan.md](docs/plan.md) for the iteration plan.

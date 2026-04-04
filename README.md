# SpeechPilot

A native Android application that provides real-time speech pace feedback to help speakers maintain a comfortable, clear delivery.

All core coaching pipeline processing runs locally on-device. No backend, cloud, or external services are required for pace estimation/feedback.

---

## Project Status

✅ **Phase 1 — Feature-complete.** Full audio→VAD→segmentation→pace→feedback pipeline running, with local settings and session history persistence, foreground-service background support, live audio level visualization, and a responsive live session UI.

---

## Architecture

SpeechPilot is structured as a multi-module Android project. Each module has a single, focused responsibility.

| Module | Responsibility |
|---|---|
| `app` | Application entry point, wiring |
| `ui` | Compose screens, ViewModels |
| `session` | Session lifecycle orchestration |
| `audio` | Microphone capture, `AudioFrame` emission |
| `vad` | Voice activity detection |
| `segmentation` | Speech segment buffering |
| `pace` | Rate estimation, rolling metrics |
| `feedback` | Decisioning and feedback events |
| `data` | Persistence (Room, repositories) |
| `settings` | User configuration (DataStore) |
| `transcription` | Local transcription — Vosk or Whisper.cpp primary backend, Android SR fallback, rolling transcript WPM |
| `modelmanager` | Generic on-device model provisioning — download, install, state tracking for Vosk, Whisper, and future models |

See [docs/phase1_architecture.md](docs/phase1_architecture.md) for the full architecture description.

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK API 26+
- Java 17+

### Build (debug)

```bash
./gradlew assembleDebug
```

### Build (release)

```bash
./gradlew assembleRelease
```

### Test

```bash
./gradlew test
```

### Lint

```bash
./gradlew lint
```

---

## Module Dependency Graph

```
app
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
| **Vosk** (`VoskLocalTranscriber`) | Primary dedicated STT (default) — deterministic on-device, no cloud | Active when Vosk is selected and model assets are installed |
| **Whisper.cpp** (`WhisperCppLocalTranscriber`) | Alternative primary STT — better for accented English (e.g. Indian English) | Active when Whisper is selected and model + native library are present |
| **Android SpeechRecognizer** (`AndroidSpeechRecognizerTranscriber`) | **Fallback** — device speech services, offline-preferred | Active when the selected primary backend is unavailable |
| **No-op** (`NoOpLocalTranscriber`) | Transcription disabled | Settings → Transcription turned off |

**Backend selection:** The primary STT backend is selectable in **Settings → Use Whisper.cpp backend**:
- **Off (default):** Vosk is the primary backend
- **On:** Whisper.cpp is the primary backend

Selection is performed automatically by `RoutingLocalTranscriber` at session start:
1. Start the selected primary backend (Vosk or Whisper.cpp)
2. If it reports `ModelUnavailable`, stop it and activate the Android SpeechRecognizer fallback
3. Expose the active backend in `TranscriptDebugState.activeBackend`

The active backend is visible in the debug panel as **Transcript backend**.

#### Vosk backend (default)

- Model: `vosk-model-small-en-us` (~40 MB compressed)
- Streaming frame-by-frame recognition with partial and final results
- Low latency, deterministic offline

#### Whisper.cpp backend

- Model: `ggml-small.bin` (~466 MB)
- Default URL: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin`
- Chunk-based inference: audio is buffered in 5-second windows before running inference
- **Final-only updates** — no streaming partial results (inherent to Whisper's design)
- May produce better transcript quality for accented English
- Requires the `libwhisper.so` native library bundled in the APK (see "Whisper native library" below)

##### Whisper native library

Whisper.cpp requires a pre-compiled native library. The library is **not bundled** in this repository. To build it:

1. Clone `whisper.cpp`: `git clone https://github.com/ggerganov/whisper.cpp`
2. Follow the Android NDK build instructions in [`examples/whisper.android/README.md`](https://github.com/ggerganov/whisper.cpp/blob/master/examples/whisper.android/README.md)
3. Place the resulting `.so` files in `transcription/src/main/jniLibs/`:
   ```
   transcription/src/main/jniLibs/
     arm64-v8a/libwhisper.so
     x86_64/libwhisper.so
   ```

Without the native library, the Whisper backend reports `ModelUnavailable` and the app falls back to Android SpeechRecognizer automatically.

#### Automatic model provisioning

**No manual setup is required.** When transcription is enabled, the app automatically downloads and installs the selected backend's model on first launch:

- **Vosk model** is provisioned automatically when Vosk is the selected backend
- **Whisper model** is provisioned automatically when Whisper is the selected backend

A **Speech Model** status card shows download progress and install state on the main screen.

Model install states:
- Vosk: `NotInstalled` → `Queued` → `Downloading` → `Unpacking` → `Ready` / `Failed`
- Whisper: `NotInstalled` → `Queued` → `Downloading` → `Ready` / `Failed` *(no unzip needed)*

#### Local model storage layout

```
filesDir/
  vosk-model-small-en-us/       ← Vosk STT model (auto-provisioned, ~40 MB)
    am/final.mdl                 ← acoustic model (readiness marker)
    conf/
    graph/
    …
  whisper/                       ← Whisper.cpp model directory (auto-provisioned, ~466 MB)
    ggml-small.bin               ← ggml model binary (readiness marker)
```

#### What transcription provides

- Live transcript text displayed in a dedicated card during active sessions
- Rolling **transcript-derived WPM** from finalized recognized words (primary pace signal when available)
- Explicit status states: listening, partial transcript, final transcript, model unavailable, error
- Heuristic est-WPM as secondary fallback when transcript is pending or unavailable

> Transcription setting changes apply on the next session start.
> Transcript text is kept in-memory for the current session and is not stored in session history.
> Transcript-derived WPM is based on **finalized** recognizer words only. If only partial hypotheses arrive, transcript WPM remains pending by design.
> Coaching decisions use transcript-derived WPM when transcript readiness is reached. Until then, the app falls back explicitly to heuristic pace.

---

### Local model management

SpeechPilot uses a generic model provisioning system (`modelmanager` module) to automatically
manage on-device AI model assets. The system is not backend-specific — it supports both zip
archive models (Vosk) and single-file binary models (Whisper.cpp), and is designed to support
additional model families in future iterations (e.g. Gemma 4 E2B on-device LLM).

Key abstractions:

| Class | Role |
|---|---|
| `LocalModelDescriptor` | Model metadata: id, type, download URL, install path, archive format, version, optional checksum |
| `ModelArchiveFormat` | `ZIP` (extract archive) or `SINGLE_FILE` (download binary directly) |
| `ModelInstallState` | Observable state machine: `NotInstalled` → `Queued` → `Downloading` → `Unpacking`? → `Verifying`? → `Ready` / `Failed` |
| `LocalModelManager` | Interface: `ensureInstalled()`, `stateOf()`, `isReady()`, `retry()` |
| `DefaultLocalModelManager` | Concrete implementation: `HttpURLConnection` download, `ZipInputStream` extract (ZIP) or direct file placement (SINGLE_FILE), atomic staging |
| `KnownModels` | Predefined model registry — add new models here |

Adding a new model (e.g. Gemma 4 E2B) requires only adding a `LocalModelDescriptor` to
`KnownModels.all` and calling `ensureInstalled()` from the appropriate ViewModel — no changes
to provisioning logic.

**Known limitations (first iteration):**
- Downloads are not resumable across process restarts
- No WorkManager background scheduling — provisioning is tied to ViewModel scope
- No automatic retry; user must press Retry on failure
- No WiFi-only gating or download size warnings

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

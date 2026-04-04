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
| `transcription` | Local transcription — Vosk preferred backend, Android SR fallback, rolling transcript WPM |

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

The app uses a **two-tier backend architecture**:

| Backend | Role | Condition |
|---|---|---|
| **Vosk** (`VoskLocalTranscriber`) | **Preferred** — deterministic on-device STT, no cloud dependency | Active when model assets are installed |
| **Android SpeechRecognizer** (`AndroidSpeechRecognizerTranscriber`) | **Fallback** — device speech services, offline-preferred | Active when Vosk model is absent |
| **No-op** (`NoOpLocalTranscriber`) | Transcription disabled | Settings → Transcription turned off |

Selection is performed automatically by `RoutingLocalTranscriber` at session start:
1. Attempt to start the Vosk backend
2. If it reports `ModelUnavailable`, stop it and activate the Android SpeechRecognizer fallback
3. Expose the active backend in `TranscriptDebugState.activeBackend`

The active backend is visible in the debug panel as **Transcript backend**.

#### Enabling Vosk (required for the primary backend to be active)

Vosk requires model assets on the device. The Vosk library and JNA companion are already wired
as dependencies in `transcription/build.gradle.kts`. Until model files are pushed to the device,
the app falls back to Android SpeechRecognizer automatically and shows "model unavailable" in
the debug panel.

To install the model:
1. Download a small English model from https://alphacephei.com/vosk/models (e.g. `vosk-model-small-en-us-0.15.zip`)
2. Unzip and push to the device:
   ```bash
   adb push vosk-model-small-en-us /sdcard/Android/data/com.speechpilot/files/vosk-model-small-en-us
   ```
   Or copy to `context.filesDir/vosk-model-small-en-us` via `adb shell`.
3. Restart the session. The Vosk backend will activate automatically.

The model directory must contain `am/final.mdl` (standard Vosk layout) or a top-level `final.mdl`
(flat model layout). The recognizer runs at 16 kHz, mono, 16-bit PCM — the same rate as the app's
existing audio pipeline. No second AudioRecord is opened; Vosk reads from the shared frame stream.

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

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
| `transcription` | Local debug transcription + rolling transcript WPM |

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


### Local transcript mode in live coaching

SpeechPilot includes an **optional local transcript mode** (Settings → Local transcript debug) that is now a first-class part of the live screen when enabled.

- Uses Android `SpeechRecognizer` with offline preference (`EXTRA_PREFER_OFFLINE=true`) as a best-effort signal
- Produces incremental transcript text during active sessions
- Computes a separate rolling **transcript-derived WPM** from finalized recognized words
- Shows a dedicated transcript card in the main live session surface with explicit state:
  - listening for speech
  - partial transcript available
  - final transcript available
  - no final words yet
  - transcription unavailable / recognizer error
- Uses transcript-derived WPM as the primary user-facing pace metric when finalized transcript words exist
- Keeps heuristic est-WPM visible as secondary/debug context
- Is disabled by default and can be enabled in **Settings → Local transcript debug**
- Keeps a compact debug panel focused on calibration essentials: speech activity, transcript engine/status, text WPM, heuristic pace, target, and last decision reason

> Notes: transcript quality/timing and true offline behavior depend on on-device speech services and installed language packs.
> Changes to the transcript debug toggle apply on the next session start.
> Transcript text is kept in-memory for the current session and is not stored in session history.
> Transcript-derived WPM is based on **finalized** recognizer words only. If only partial hypotheses arrive, transcript WPM remains pending by design, and the UI shows this explicitly.
> Coaching decisions use transcript-derived WPM when transcript readiness is reached (finalized words + minimum rolling transcript words + healthy recognizer state). Until then, the app falls back explicitly to heuristic pace.

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

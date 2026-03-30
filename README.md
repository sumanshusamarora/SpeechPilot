# SpeechPilot

A native Android application that provides real-time speech pace feedback to help speakers maintain a comfortable, clear delivery.

All core coaching pipeline processing runs locally on-device. No backend, cloud, or external services are required for pace estimation/feedback.

---

## Project Status

✅ **Phase 1 — Feature-complete.** Full audio→VAD→segmentation→pace→feedback pipeline running, with local settings and session history persistence, foreground-service background support, and live session UI.

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


### Local transcript debug mode (Phase 1 calibration)

SpeechPilot now includes an **optional local transcript debug mode** for calibration.

- Uses Android `SpeechRecognizer` with offline preference (`EXTRA_PREFER_OFFLINE=true`) as a best-effort signal
- Produces incremental transcript text during active sessions
- Computes a separate rolling **transcript-derived WPM** from finalized recognized words
- Keeps transcript WPM separate from the existing heuristic est-WPM signal
- Is disabled by default and can be enabled in **Settings → Local transcript debug**

> Notes: transcript quality/timing and true offline behavior depend on on-device speech services and installed language packs.
> Changes to the transcript debug toggle apply on the next session start.
> Transcript text is kept in-memory for the current session and is not stored in session history.

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

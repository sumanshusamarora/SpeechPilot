# SpeechPilot

A native Android application that provides real-time speech pace feedback to help speakers maintain a comfortable, clear delivery.

All processing runs locally on-device. No backend, cloud, or external services required.

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
 │    └── settings
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
      └── settings
```

---

## Releasing

Releases are automated via GitHub Actions. To publish a release APK:

1. Ensure the commit you want to release is on `main`.
2. Create and push a tag matching the pattern `release-v<major>.<minor>.<patch>`:

```bash
git tag release-v1.0.0
git push origin release-v1.0.0
```

The workflow builds the release APK, creates a GitHub Release, and attaches the APK as an artifact. Release notes are auto-generated from commits since the previous tag.

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

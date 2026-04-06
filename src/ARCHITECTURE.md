# SpeechPilot Architecture (v2)

This document is the source of truth for SpeechPilot's transition from an Android-first, on-device pipeline to a backend-first realtime architecture.

## Context

SpeechPilot began as a native Android application with local audio capture, local STT experiments, realtime pace feedback, and local persistence. That validated the product direction, but it also made iteration slower because model integration, observability, and debugging were tightly coupled to device-specific constraints.

The next phase shifts heavy realtime work into a backend while preserving thin clients for capture, rendering, and local user experience concerns.

## Repository split

The repository currently contains both the existing implementation and the new scaffold.

```text
repo root/
  app/, ui/, session/, ...   # legacy Android-first v1
  src/
    backend/                 # FastAPI backend scaffold
    web/                     # Next.js debug shell
    contracts/               # shared event contracts
    android/                 # future thin Android client migration target
  infra/
    docker/                  # local development environment
```

Rule: all new v2 code lives under `src/` except environment support under `infra/`.

## Architecture shift

### v1

```text
Android App
├─ Mic capture
├─ On-device STT
├─ Pace calculation
├─ Feedback logic
└─ Local storage
```

### v2 target

```text
Clients (Android / Web)
├─ Mic capture or replay trigger
├─ WebSocket streaming
└─ UI rendering

Backend
├─ Realtime gateway
├─ STT provider layer
├─ Pace analytics
├─ Coaching service
├─ Session persistence
└─ Ephemeral realtime state
```

Guiding principle: clients capture and render, backend thinks and remembers.

## Backend responsibilities

Location: `src/backend`

Initial modules:

- `api/` for HTTP routes such as health and replay capabilities
- `websocket/` for realtime session handling
- `providers/` for STT provider abstractions
- `services/` for analytics and coaching boundaries
- `persistence/` for long-lived storage and ephemeral realtime state
- `config/` for environment-driven settings
- `observability/` for logging and future tracing hooks

The backend currently exposes:

- `GET /health`
- `GET /api/replay/capabilities`
- `WS /ws`

The stable realtime pipeline is now:

1. websocket transport receives `session.start`, `audio.chunk`, and `session.stop`
2. the STT provider emits ephemeral `transcript.partial` plus append-only `transcript.final` segments
3. the pace service consumes finalized transcript segments only and emits `pace.update`
4. the session service emits structured `debug.state` snapshots and `session.summary`
5. persistence stores final transcript segments and session metrics, not partial transcript text

## Realtime store direction

Environment-aware ephemeral state is required for session coordination.

| Environment | Store direction |
| --- | --- |
| Local development | Redis |
| Cloud / EKS | Redis or managed equivalent behind the same interface |

Design rule: business logic must depend on a `RealtimeStore` abstraction, not a concrete Redis client.

## Contracts

Location: `src/contracts`

The contracts package defines explicit, versioned websocket envelopes for:

- `session.start`
- `audio.chunk`
- `session.stop`
- `transcript.partial`
- `transcript.final`
- `pace.update`
- `feedback.update`
- `session.summary`
- `debug.state`
- `error`

Current structure:

- `schemas/` as canonical JSON Schema
- `python/` as backend Pydantic bindings
- `typescript/` as web client type bindings

Short-term rule: mirrored language definitions are acceptable while the scaffolding stabilizes. Long-term direction: generate language bindings from the canonical schema set.

Transcript model rules:

- partial transcript is ephemeral and never persisted
- final transcript is modeled as immutable transcript segments with stable ids and timing metadata
- WPM is derived from final transcript segments only
- replay and live mode use the same event shapes and backend orchestration path

## Web client responsibilities

Location: `src/web`

The web client is initially a developer-facing debug surface. It is not yet the product UI.

Current responsibilities:

- connect to backend websocket
- capture microphone audio in the browser
- render partial transcript, final segments, pace, and debug state
- upload replay WAV files and inspect the returned event stream
- render incoming and outgoing websocket logs for debugging

## Replay/testing direction

Recorded-audio replay is now the primary local debugging path.

- replay streams chunked WAV audio through the same session service used by live websocket capture
- replay emits the same `transcript.partial`, `transcript.final`, `pace.update`, `debug.state`, and `session.summary` event shapes
- optional replay chunk delay can be enabled to mimic live timing for investigations

## Local development

Use `infra/docker/docker-compose.yml` to run:

- backend
- web
- postgres
- redis

Goals:

- predictable local iteration
- environment-driven configuration
- easy evolution toward cloud deployment without mixing infrastructure details into business logic

## Migration strategy

1. Scaffold backend, web, contracts, and local infrastructure in parallel under `src/`.
2. Validate websocket contracts and backend session orchestration.
3. Add real STT, analytics, coaching, and replay capabilities incrementally.
4. Migrate Android into `src/android` as a thin realtime client later.

## Non-goals for this scaffold

- full transcription implementation
- Gemma coaching integration
- Android migration
- Kubernetes manifests
- auth and account management

The priority in this phase is a clean, future-ready foundation.

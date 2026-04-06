# SpeechPilot Contracts

This package defines the initial v2 API and websocket contracts for SpeechPilot.

Scope for this first scaffold:

- establish explicit, versioned event names
- keep a canonical schema location for future code generation
- provide clean Python and TypeScript mirrors now so backend and web can move independently

Current implementation note:

- `src/contracts` is the canonical shared definition for v2 events
- the backend imports the Python package directly
- the web app currently keeps a tiny local runtime mirror for bundler compatibility while the monorepo package wiring is still being stabilized

Layout:

- `schemas/` contains the canonical JSON Schema definition for the v1 realtime envelope.
- `python/` contains Pydantic models used by the FastAPI backend.
- `typescript/` contains shared TypeScript types used by the Next.js client.

Current client-to-backend events:

- `session.start`
- `audio.chunk`
- `session.stop`

Current backend-to-client events:

- `transcript.partial`
- `transcript.final`
- `pace.update`
- `feedback.update`
- `session.summary`
- `debug.state`
- `error`

Versioning rules:

- event names remain stable and explicit
- envelope version is carried in every message via `version`
- breaking changes should create a new schema file and mirrored language bindings

Replay and recorded-audio testing are intentionally not implemented yet. The schema and backend boundaries leave room for that next iteration.

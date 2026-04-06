# SpeechPilot Backend

FastAPI scaffold for SpeechPilot v2 realtime work.

Current scope:

- health endpoint
- websocket session scaffold
- environment-based configuration
- placeholder service boundaries for STT, analytics, coaching, persistence, replay, and ephemeral realtime state

This iteration does not implement transcription, coaching, or production persistence yet.

## Layout

```text
src/backend/
  app/
    api/
    websocket/
    services/
    providers/
    domain/
    persistence/
    config/
    observability/
    main.py
  tests/
```

## Local setup

Install shared contracts and backend in editable mode:

```bash
pip install -e ../contracts/python -e .[dev]
```

Run locally:

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Run tests:

```bash
pytest
```

## Realtime store direction

- local development uses Redis via `RedisRealtimeStore`
- production can switch to another managed implementation behind the same `RealtimeStore` interface
- business-facing services consume the abstraction, not Redis directly

## Replay/testing direction

Replay support is reserved via:

- `app/api/replay.py`
- `replayMode` on `session.start`
- service boundaries that can later accept recorded audio streams instead of live microphone traffic

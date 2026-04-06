# SpeechPilot Backend

FastAPI backend for the first SpeechPilot v2 realtime vertical slice.

Current scope:

- websocket session lifecycle for live browser audio
- `faster-whisper` transcription with `transcript.partial` and `transcript.final`
- Postgres-backed session and transcript-event persistence
- explicit Alembic migrations
- replay upload flow for local WAV-based verification
- environment-based configuration and Redis-backed ephemeral session state

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
/home/sam/open-source/SpeechPilot/.venv/bin/python -m pip install -e ../contracts/python -e .[dev]
```

Run locally:

```bash
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Run tests:

```bash
/home/sam/open-source/SpeechPilot/.venv/bin/python -m pytest
```

Important notes:

- the first live or replay transcription run will download the configured Whisper model into the Hugging Face cache
- the default local model is `tiny.en` on CPU with `int8` compute
- replay currently accepts 16-bit PCM WAV uploads only

## API surface

- `GET /health`
- `GET /api/replay/capabilities`
- `POST /api/replay/transcribe`
- websocket at `/ws`

Live websocket flow:

1. send `session.start`
2. stream `audio.chunk` messages with base64 PCM16 data
3. receive `transcript.partial` and `transcript.final`
4. send `session.stop`
5. receive `session.summary`

Persistence:

- `sessions` stores lifecycle and final summary data
- `transcript_events` stores both partial and final transcript events

## Realtime store direction

- local development uses Redis via `RedisRealtimeStore`
- production can switch to another managed implementation behind the same `RealtimeStore` interface
- business-facing services consume the abstraction, not Redis directly

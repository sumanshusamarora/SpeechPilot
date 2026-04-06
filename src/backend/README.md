# SpeechPilot Backend

FastAPI backend for the first SpeechPilot v2 realtime vertical slice.

Current scope:

- websocket session lifecycle for live browser audio
- `faster-whisper` transcription with explicit partial vs final transcript handling
- rolling transcript-derived pace analytics and `pace.update` events
- Postgres-backed session, transcript-segment, and session-metrics persistence
- replay upload flow that drives the same chunk pipeline as live audio
- structured `debug.state` snapshots for lifecycle and runtime counters
- environment-based configuration and Redis-backed ephemeral realtime state

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
- the default local model is `small.en` on CPU with `int8` compute
- replay currently accepts 16-bit PCM WAV uploads only

## Stable transcript model

- `transcript.partial` is ephemeral and never persisted
- `transcript.final` contains a stable transcript segment object with `id`, `text`, `startTimeMs`, `endTimeMs`, and `wordCount`
- final transcript segments append only and are persisted in `transcript_segments`

## Pace analytics

Pacing lives under `app/services/pace/` and is transcript-driven only.

- consumes `transcript.final` segments only
- maintains a rolling 30-second word window by default
- smooths WPM with a simple EMA to avoid jitter
- classifies pace into `slow`, `good`, or `fast`
- exposes speaking duration and silence duration separately

Relevant settings:

- `SPEECHPILOT_PACE_WINDOW_MS`
- `SPEECHPILOT_PACE_SMOOTHING_FACTOR`
- `SPEECHPILOT_PACE_SLOW_THRESHOLD_WPM`
- `SPEECHPILOT_PACE_FAST_THRESHOLD_WPM`
- `SPEECHPILOT_DEBUG_SNAPSHOT_CHUNK_INTERVAL`

## API surface

- `GET /health`
- `GET /api/replay/capabilities`
- `POST /api/replay/transcribe`
- websocket at `/ws`

Live websocket flow:

1. send `session.start`
2. stream `audio.chunk` messages with base64 PCM16 data
3. receive `transcript.partial`
4. receive append-only `transcript.final` segments
5. receive `pace.update` whenever a final segment advances pace analytics
6. inspect `debug.state` for lifecycle and runtime counters
7. send `session.stop`
8. receive a final `debug.state` and `session.summary`

Persistence:

- `sessions` stores lifecycle and final summary data
- `transcript_segments` stores immutable final transcript segments
- `session_metrics` stores live counters and pace metrics

Replay and debugging:

- replay uses the same `session.start` → `audio.chunk` → `session.stop` pipeline as live mode
- `SPEECHPILOT_REPLAY_CHUNK_DURATION_MS` controls replay chunk size
- `SPEECHPILOT_REPLAY_CHUNK_DELAY_MS` optionally slows replay to mirror live timing
- `debug.state` includes provider, lifecycle, chunk counters, final segment count, total words, WPM, and pace band

## Realtime store direction

- local development uses Redis via `RedisRealtimeStore`
- production can switch to another managed implementation behind the same `RealtimeStore` interface
- business-facing services consume the abstraction, not Redis directly

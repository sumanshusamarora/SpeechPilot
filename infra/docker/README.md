# Local Docker Stack

The local stack brings up the initial SpeechPilot v2 development environment:

- FastAPI backend
- Next.js web client
- Postgres
- Redis

## Usage

```bash
cp infra/docker/.env.example infra/docker/.env
docker compose --env-file infra/docker/.env -f infra/docker/docker-compose.yml up --build
```

Services:

- backend: `http://localhost:8000`
- web: `http://localhost:3000`
- postgres: `localhost:15432` by default, configurable via `POSTGRES_HOST_PORT`
- redis: `localhost:16379` by default, configurable via `REDIS_HOST_PORT`

Notes:

- source directories are mounted for local iteration
- Redis is the default realtime store for local development
- a managed realtime store can replace Redis later by changing backend configuration only
- the backend runs `alembic upgrade head` before starting
- the backend persists the Hugging Face model cache in a named volume so the Whisper model is not re-downloaded every boot
- first live or replay transcription still needs to download the configured model once

Useful tuning variables in `infra/docker/.env`:

- `SPEECHPILOT_REPLAY_CHUNK_DURATION_MS` controls replay chunk size
- `SPEECHPILOT_REPLAY_CHUNK_DELAY_MS` adds optional delay between replay chunks to mimic live timing
- `SPEECHPILOT_PACE_WINDOW_MS` sets the rolling WPM window
- `SPEECHPILOT_PACE_SMOOTHING_FACTOR` controls EMA smoothing strength
- `SPEECHPILOT_PACE_SLOW_THRESHOLD_WPM` and `SPEECHPILOT_PACE_FAST_THRESHOLD_WPM` set pace bands
- `SPEECHPILOT_DEBUG_SNAPSHOT_CHUNK_INTERVAL` controls how often chunk-count-driven debug snapshots are emitted

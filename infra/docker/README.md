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
- postgres: `localhost:5432`
- redis: `localhost:6379`

Notes:

- source directories are mounted for local iteration
- Redis is the default realtime store for local development
- a managed realtime store can replace Redis later by changing backend configuration only

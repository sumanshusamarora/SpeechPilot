# SpeechPilot Web

Minimal Next.js debug surface for SpeechPilot v2.

Current scope:

- connect to the backend websocket
- show connection state
- send a mock `session.start`
- send a mock `session.stop`
- render a live debug log of incoming and outgoing events

This is intentionally a developer-first shell, not the finished product UI.

## Local setup

```bash
npm install
npm run dev
```

Environment variables:

- `NEXT_PUBLIC_BACKEND_HTTP_URL` defaults to `http://localhost:8000`
- `NEXT_PUBLIC_BACKEND_WS_URL` defaults to `ws://localhost:8000/ws`

## Replay/testing direction

Replay support is intentionally deferred, but the web client already has a reserved feature boundary under `src/features/replay` so recorded-audio flows can be added without restructuring the app shell.

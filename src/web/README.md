# SpeechPilot Web

Next.js debug surface for the first SpeechPilot v2 live transcription slice.

Current scope:

- connect to the backend websocket
- capture microphone audio in the browser
- stream PCM16 audio chunks to the backend
- render a live partial line plus append-only final transcript segments
- render live WPM and pace band updates
- upload a WAV file through the replay endpoint
- show structured debug state and raw websocket traffic for protocol debugging

## Local setup

```bash
npm install
npm run dev
```

Environment variables:

- `NEXT_PUBLIC_BACKEND_HTTP_URL` defaults to `http://localhost:8000`
- `NEXT_PUBLIC_BACKEND_WS_URL` defaults to `ws://localhost:8000/ws`

Notes:

- live capture uses browser `getUserMedia` plus `AudioContext`
- replay currently expects a local 16-bit PCM WAV file
- replay responses return the same server-event shapes used by the live websocket flow
- the debug panel reflects backend lifecycle, chunk counters, final segments, total words, and current WPM
- this remains a developer-first surface, not the finished product UI

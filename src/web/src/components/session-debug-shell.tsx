"use client";

import { replayFeature } from "@/features/replay/replay-mode";
import { useWebsocketSession } from "@/hooks/use-websocket-session";

function formatTime(value: string) {
  return new Date(value).toLocaleTimeString();
}

export function SessionDebugShell() {
  const {
    backendHttpUrl,
    backendWsUrl,
    connect,
    connectionStatus,
    disconnect,
    entries,
    sessionId,
    setSessionId,
    startSession,
    stopSession,
  } = useWebsocketSession();

  return (
    <main className="page-shell">
      <div className="page-grid">
        <section className="hero">
          <div className="hero-copy">
            <span className="eyebrow">SpeechPilot v2</span>
            <h1>Realtime debug surface for the new backend-first stack.</h1>
            <p>
              This shell is intentionally narrow: connect to the FastAPI websocket,
              push session lifecycle events, and inspect the wire protocol while the
              transcription and coaching pipeline is still being built.
            </p>
          </div>
          <div className="hero-badges">
            <span className="badge">Next.js client</span>
            <span className="badge">Shared contracts</span>
            <span className="badge">Replay-ready boundary</span>
          </div>
        </section>

        <section className="panel-grid">
          <article className="panel">
            <h2>Connection</h2>
            <div className={`status-pill status-${connectionStatus}`}>
              {connectionStatus}
            </div>
            <div className="meta-list">
              <div className="meta-item">
                <span>Backend HTTP</span>
                <strong>{backendHttpUrl}</strong>
              </div>
              <div className="meta-item">
                <span>Backend WS</span>
                <strong>{backendWsUrl}</strong>
              </div>
            </div>
            <div className="control-row">
              <button className="button button-primary" onClick={connect}>
                Connect websocket
              </button>
              <button className="button" onClick={disconnect}>
                Disconnect
              </button>
            </div>
          </article>

          <article className="panel">
            <h2>Session</h2>
            <div className="field">
              <label htmlFor="session-id">Session ID</label>
              <input
                id="session-id"
                value={sessionId}
                onChange={(event) => setSessionId(event.target.value)}
              />
            </div>
            <p>
              Use the mock controls below to verify websocket plumbing before live
              audio capture is introduced.
            </p>
            <div className="control-row">
              <button className="button button-primary" onClick={startSession}>
                Send session.start
              </button>
              <button className="button button-warning" onClick={stopSession}>
                Send session.stop
              </button>
            </div>
          </article>

          <article className="panel">
            <h2>Replay mode</h2>
            <p>{replayFeature.description}</p>
            <div className="meta-list">
              <div className="meta-item">
                <span>Status</span>
                <strong>{replayFeature.status}</strong>
              </div>
              <div className="meta-item">
                <span>Planned inputs</span>
                <strong>{replayFeature.plannedInputs.join(", ")}</strong>
              </div>
            </div>
          </article>
        </section>

        <section className="panel log-panel">
          <h3>Event log</h3>
          <p>
            Incoming and outgoing events are rendered exactly so the contracts can be
            inspected during backend development.
          </p>
          <div className="log-list">
            {entries.map((entry) => (
              <article className="log-item" key={entry.id}>
                <div className="log-header">
                  <span className={`log-direction log-direction-${entry.direction}`}>
                    {entry.direction}
                  </span>
                  <strong>{formatTime(entry.timestamp)}</strong>
                </div>
                <pre>{JSON.stringify(entry.payload, null, 2)}</pre>
              </article>
            ))}
          </div>
        </section>
      </div>
    </main>
  );
}
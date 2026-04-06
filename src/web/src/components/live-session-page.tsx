"use client";

import Link from "next/link";
import { useEffect } from "react";

import { useAppPreferences } from "@/hooks/use-app-preferences";
import { useWebsocketSession } from "@/hooks/use-websocket-session";
import {
  debugConfidenceLabel,
  describeFeedback,
  describePaceBand,
  feedbackConfidenceLabel,
  formatTime,
  statusToneClass,
} from "@/lib/format";

function mapSessionTone(sessionState: string) {
  if (sessionState === "capturing") {
    return "connected";
  }
  if (sessionState === "starting" || sessionState === "stopping") {
    return "connecting";
  }
  if (sessionState === "error") {
    return "error";
  }
  return "disconnected";
}

export function LiveSessionPage() {
  const { preferences } = useAppPreferences();
  const session = useWebsocketSession({ sessionPrefix: preferences.sessionPrefix });

  useEffect(() => {
    if (preferences.autoConnectLive && session.connectionStatus === "disconnected") {
      void session.connect();
    }
  }, [preferences.autoConnectLive, session]);

  return (
    <main className="page-shell">
      <section className="hero-card hero-card-compact">
        <div className="hero-copy">
          <span className="eyebrow">Live Coaching</span>
          <h1>Run a live speaking session and get pace coaching as the transcript lands.</h1>
          <p>
            This route is tuned for focused practice: connection state, session controls, coaching,
            transcript timeline, and diagnostics live in one place, while developer telemetry stays
            secondary and collapsible.
          </p>
        </div>
        <div className="hero-stat-grid hero-stat-grid-tight">
          <article className="hero-stat-card">
            <span>Practice focus</span>
            <strong>{preferences.practiceFocus}</strong>
          </article>
          <article className="hero-stat-card">
            <span>Connection</span>
            <strong>{session.connectionStatus}</strong>
          </article>
          <article className="hero-stat-card">
            <span>Session state</span>
            <strong>{session.sessionState}</strong>
          </article>
        </div>
      </section>

      <section className="content-grid content-grid-primary">
        <article className="surface-card surface-card-emphasis">
          <div className="surface-header">
            <div>
              <span className={`tone-pill ${statusToneClass(session.connectionStatus)}`}>
                {session.connectionStatus}
              </span>
              <h2>Live control room</h2>
            </div>
            <Link className="button button-secondary" href="/settings">
              Preferences
            </Link>
          </div>

          <div className="field-grid">
            <label className="field">
              <span>Session ID</span>
              <input
                value={session.sessionId}
                onChange={(event) => session.setSessionId(event.target.value)}
              />
            </label>
          </div>

          <div className="action-row">
            <button className="button button-secondary" onClick={session.connect}>
              Connect websocket
            </button>
            <button className="button button-primary" onClick={() => void session.startLiveSession()}>
              Start live session
            </button>
            <button className="button button-danger" onClick={() => void session.stopLiveSession()}>
              Stop session
            </button>
            <button className="button button-secondary" onClick={session.disconnect}>
              Disconnect
            </button>
          </div>

          {session.micErrorMessage ? <p className="inline-error">{session.micErrorMessage}</p> : null}

          <div className="data-grid">
            <div className="data-point">
              <span>Backend HTTP</span>
              <strong>{session.backendHttpUrl}</strong>
            </div>
            <div className="data-point">
              <span>Backend websocket</span>
              <strong>{session.backendWsUrl}</strong>
            </div>
            <div className="data-point">
              <span>Session tone</span>
              <strong>{mapSessionTone(session.sessionState)}</strong>
            </div>
          </div>
        </article>

        <article className="surface-card">
          <div className="surface-header">
            <div>
              <span className={`tone-pill ${statusToneClass(mapSessionTone(session.sessionState))}`}>
                {session.sessionState}
              </span>
              <h2>Live coaching snapshot</h2>
            </div>
          </div>

          <div className="metric-grid">
            <article className="metric-card">
              <span>Current WPM</span>
              <strong>{session.paceUpdate?.wordsPerMinute.toFixed(1) ?? "-"}</strong>
              <small>{describePaceBand(session.paceUpdate?.band)}</small>
            </article>
            <article className="metric-card">
              <span>Total words</span>
              <strong>{session.paceUpdate?.totalWords ?? session.summary?.totalWords ?? 0}</strong>
              <small>Final transcript words so far</small>
            </article>
            <article className="metric-card">
              <span>Transcript segments</span>
              <strong>{session.finalSegments.length}</strong>
              <small>Completed transcript chunks</small>
            </article>
          </div>

          <article className="insight-card">
            <span>Live coaching</span>
            <strong>{describeFeedback(session.feedbackUpdate?.decision, session.feedbackUpdate?.reason)}</strong>
            <small>{feedbackConfidenceLabel(session.feedbackUpdate)}</small>
          </article>

          <article className="insight-card insight-card-subtle">
            <span>Backend state</span>
            <strong>{session.debugState?.detail ?? "Realtime backend waiting for steady audio."}</strong>
            <small>{debugConfidenceLabel(session.debugState)}</small>
          </article>
        </article>
      </section>

      <section className="content-grid">
        <article className="surface-card surface-card-span-two">
          <div className="surface-header">
            <div>
              <span className="eyebrow">Transcript</span>
              <h2>Live transcript timeline</h2>
            </div>
            <Link className="button button-secondary" href="/history">
              Open history
            </Link>
          </div>

          {session.partialTranscript ? (
            <article className="callout-card">
              <span>Live partial</span>
              <strong>{session.partialTranscript}</strong>
            </article>
          ) : null}

          <div className="timeline-list">
            {session.finalSegments.length === 0 ? (
              <article className="empty-state-card">
                <strong>No finalized transcript yet.</strong>
                <p>Start a session and speak for a few moments to populate the timeline.</p>
              </article>
            ) : null}
            {session.finalSegments.map((segment) => (
              <article className="timeline-card" key={segment.id}>
                <div className="timeline-card-header">
                  <strong>{formatTime(segment.timestamp)}</strong>
                  <span>{segment.wordCount} words</span>
                </div>
                <p>{segment.text}</p>
              </article>
            ))}
          </div>
        </article>

        <article className="surface-card">
          <div className="surface-header">
            <div>
              <span className="eyebrow">Summary</span>
              <h2>Current session view</h2>
            </div>
          </div>
          <div className="data-grid">
            <div className="data-point">
              <span>Summary</span>
              <strong>
                {session.summary
                  ? `${session.summary.totalWords} words · ${session.summary.averageWpm?.toFixed(1) ?? "-"} avg WPM`
                  : "No session summary yet"}
              </strong>
            </div>
            <div className="data-point">
              <span>Speaking duration</span>
              <strong>{session.summary?.speakingDurationMs ?? 0} ms</strong>
            </div>
            <div className="data-point">
              <span>Silence duration</span>
              <strong>{session.summary?.silenceDurationMs ?? 0} ms</strong>
            </div>
          </div>
          {session.summary?.notes.length ? (
            <div className="keyline-list">
              {session.summary.notes.map((note) => (
                <div className="keyline-item" key={note}>
                  {note}
                </div>
              ))}
            </div>
          ) : (
            <p className="subtle-copy">Session notes appear when the backend emits a summary.</p>
          )}
        </article>
      </section>

      <details className="diagnostics-panel" open={preferences.showDiagnosticsByDefault}>
        <summary>Developer diagnostics</summary>
        <div className="diagnostics-grid">
          <article className="surface-card">
            <h3>Realtime state</h3>
            <div className="data-grid">
              <div className="data-point">
                <span>Lifecycle</span>
                <strong>{session.debugState?.lifecycle ?? "idle"}</strong>
              </div>
              <div className="data-point">
                <span>Provider</span>
                <strong>{session.debugState?.activeProvider ?? "-"}</strong>
              </div>
              <div className="data-point">
                <span>Chunks</span>
                <strong>{session.debugState?.chunksReceived ?? 0}</strong>
              </div>
              <div className="data-point">
                <span>Partial updates</span>
                <strong>{session.debugState?.partialUpdates ?? 0}</strong>
              </div>
            </div>
          </article>

          <article className="surface-card surface-card-span-two">
            <h3>Event log</h3>
            <div className="log-stack">
              {session.entries.map((entry) => (
                <article className="log-entry" key={entry.id}>
                  <div className="timeline-card-header">
                    <strong>{entry.direction}</strong>
                    <span>{formatTime(entry.timestamp)}</span>
                  </div>
                  <pre>{JSON.stringify(entry.payload, null, 2)}</pre>
                </article>
              ))}
            </div>
          </article>
        </div>
      </details>
    </main>
  );
}
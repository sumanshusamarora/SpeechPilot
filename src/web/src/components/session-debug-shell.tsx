"use client";

import { useState } from "react";

import { replayFeature } from "@/features/replay/replay-mode";
import { useReplayTranscription } from "@/features/replay/use-replay-transcription";
import { useWebsocketSession } from "@/hooks/use-websocket-session";
import type {
  DebugStateEvent,
  DebugStatePayload,
  PaceUpdateEvent,
  ServerEvent,
  TranscriptFinalEvent,
} from "@/lib/contracts";

function formatTime(value: string) {
  return new Date(value).toLocaleTimeString();
}

function formatDurationMs(value: number) {
  return `${(value / 1000).toFixed(1)}s`;
}

function mapSessionStateToTone(sessionState: string) {
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

function getLatestReplayPaceEvent(events: ServerEvent[] | undefined) {
  if (!events) {
    return null;
  }
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index];
    if (event?.type === "pace.update") {
      return event as PaceUpdateEvent;
    }
  }
  return null;
}

function getReplayFinalSegments(events: ServerEvent[] | undefined) {
  if (!events) {
    return [] as TranscriptFinalEvent[];
  }
  return events.filter((event) => event.type === "transcript.final") as TranscriptFinalEvent[];
}

function getLatestReplayDebugState(events: ServerEvent[] | undefined) {
  if (!events) {
    return null as DebugStatePayload | null;
  }
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index];
    if (event.type === "debug.state") {
      return (event as DebugStateEvent).payload;
    }
  }
  return null;
}

export function SessionDebugShell() {
  const [selectedReplayFile, setSelectedReplayFile] = useState<File | null>(null);
  const {
    backendHttpUrl,
    backendWsUrl,
    connect,
    connectionStatus,
    debugState,
    disconnect,
    entries,
    finalSegments,
    micErrorMessage,
    paceUpdate,
    partialTranscript,
    sessionId,
    sessionState,
    setSessionId,
    startLiveSession,
    stopLiveSession,
    summary,
  } = useWebsocketSession();
  const replay = useReplayTranscription();
  const replayPace = getLatestReplayPaceEvent(replay.result?.events);
  const replayFinalSegments = getReplayFinalSegments(replay.result?.events);
  const replayDebugState = getLatestReplayDebugState(replay.result?.events);

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
            <div className={`status-pill status-${mapSessionStateToTone(sessionState)}`}>
              {sessionState}
            </div>
            <div className="field">
              <label htmlFor="session-id">Session ID</label>
              <input
                id="session-id"
                value={sessionId}
                onChange={(event) => setSessionId(event.target.value)}
              />
            </div>
            <p>
              Start live mic mode to stream browser audio chunks to the backend over
              websocket and render transcript updates as they arrive.
            </p>
            <div className="control-row">
              <button
                className="button button-primary"
                onClick={() => {
                  void startLiveSession();
                }}
              >
                Start live mic
              </button>
              <button
                className="button button-warning"
                onClick={() => {
                  void stopLiveSession();
                }}
              >
                Stop live mic
              </button>
            </div>
            {micErrorMessage ? <p>{micErrorMessage}</p> : null}
          </article>

          <article className="panel">
            <h2>Live transcript</h2>
            <div className="meta-list">
              <div className="meta-item">
                <span>Partial transcript</span>
                <strong>{partialTranscript || "Waiting for speech..."}</strong>
              </div>
              <div className="meta-item">
                <span>Final segments</span>
                <strong>{finalSegments.length}</strong>
              </div>
              <div className="meta-item">
                <span>Session summary</span>
                <strong>
                  {summary
                    ? `${summary.totalWords} words, avg ${summary.averageWpm ?? "-"} WPM`
                    : "No session summary yet."}
                </strong>
              </div>
            </div>
            <div className="metric-strip">
              <article className="metric-card">
                <span>Live WPM</span>
                <strong>{paceUpdate ? paceUpdate.wordsPerMinute.toFixed(1) : "-"}</strong>
              </article>
              <article className="metric-card">
                <span>Pace band</span>
                <strong>{paceUpdate?.band ?? "unknown"}</strong>
              </article>
              <article className="metric-card">
                <span>Total words</span>
                <strong>{paceUpdate?.totalWords ?? summary?.totalWords ?? 0}</strong>
              </article>
            </div>
            <div className="transcript-list">
              {finalSegments.map((segment) => (
                <article className="transcript-item" key={segment.id}>
                  <strong>{formatTime(segment.timestamp)}</strong>
                  <p>{segment.text}</p>
                  <small>
                    {`${segment.wordCount} words • ${formatDurationMs(segment.endTimeMs - segment.startTimeMs)}`}
                  </small>
                </article>
              ))}
            </div>
          </article>

          <article className="panel">
            <h2>Debug state</h2>
            <div className="debug-grid">
              <div className="meta-item">
                <span>Lifecycle</span>
                <strong>{debugState?.lifecycle ?? "idle"}</strong>
              </div>
              <div className="meta-item">
                <span>Provider</span>
                <strong>{debugState?.activeProvider ?? "-"}</strong>
              </div>
              <div className="meta-item">
                <span>Chunks</span>
                <strong>{debugState?.chunksReceived ?? 0}</strong>
              </div>
              <div className="meta-item">
                <span>Partial updates</span>
                <strong>{debugState?.partialUpdates ?? 0}</strong>
              </div>
              <div className="meta-item">
                <span>Final segments</span>
                <strong>{debugState?.finalSegments ?? 0}</strong>
              </div>
              <div className="meta-item">
                <span>Debug WPM</span>
                <strong>{debugState?.wordsPerMinute ?? "-"}</strong>
              </div>
            </div>
            {debugState?.detail ? <p>{debugState.detail}</p> : null}
          </article>

          <article className="panel">
            <h2>Replay mode</h2>
            <p>{replayFeature.description}</p>
            <div className="field">
              <label htmlFor="replay-file">Replay WAV file</label>
              <input
                id="replay-file"
                accept=".wav,audio/wav"
                type="file"
                onChange={(event) => setSelectedReplayFile(event.target.files?.[0] ?? null)}
              />
            </div>
            <div className="control-row">
              <button
                className="button button-primary"
                disabled={selectedReplayFile === null || replay.status === "uploading"}
                onClick={() => {
                  if (selectedReplayFile !== null) {
                    void replay.runReplay(selectedReplayFile);
                  }
                }}
              >
                Run replay
              </button>
            </div>
            <div className="meta-list">
              <div className="meta-item">
                <span>Status</span>
                <strong>{replay.status}</strong>
              </div>
              <div className="meta-item">
                <span>Planned inputs</span>
                <strong>{replayFeature.plannedInputs.join(", ")}</strong>
              </div>
              {replay.result ? (
                <div className="meta-item">
                  <span>Replay summary</span>
                  <strong>
                    {`${replay.result.summary.totalWords} words, avg ${replay.result.summary.averageWpm ?? "-"} WPM`}
                  </strong>
                </div>
              ) : null}
            </div>
            <div className="metric-strip">
              <article className="metric-card">
                <span>Replay WPM</span>
                <strong>{replayPace ? replayPace.payload.wordsPerMinute.toFixed(1) : "-"}</strong>
              </article>
              <article className="metric-card">
                <span>Replay band</span>
                <strong>{replayPace?.payload.band ?? "unknown"}</strong>
              </article>
              <article className="metric-card">
                <span>Replay chunks</span>
                <strong>{replayDebugState?.chunksReceived ?? 0}</strong>
              </article>
            </div>
            {replay.errorMessage ? <p>{replay.errorMessage}</p> : null}
            <div className="transcript-list">
              {replayFinalSegments.map((event) => (
                <article className="transcript-item" key={event.payload.segment.id}>
                  <strong>{event.payload.segment.id}</strong>
                  <p>{event.payload.segment.text}</p>
                  <small>
                    {`${event.payload.segment.wordCount} words • ${formatDurationMs(event.payload.segment.endTimeMs - event.payload.segment.startTimeMs)}`}
                  </small>
                </article>
              ))}
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
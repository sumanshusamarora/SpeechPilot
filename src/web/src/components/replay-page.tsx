"use client";

import Link from "next/link";
import { useState } from "react";

import { useAppPreferences } from "@/hooks/use-app-preferences";
import { useReplayTranscription } from "@/features/replay/use-replay-transcription";
import {
  describeFeedback,
  describePaceBand,
  feedbackConfidenceLabel,
  formatDurationMs,
  statusToneClass,
} from "@/lib/format";
import type {
  DebugStateEvent,
  FeedbackUpdateEvent,
  PaceUpdateEvent,
  ServerEvent,
  TranscriptFinalEvent,
} from "@/lib/contracts";

function getLatestPaceEvent(events?: ServerEvent[]) {
  return [...(events ?? [])].reverse().find((event) => event.type === "pace.update") as
    | PaceUpdateEvent
    | undefined;
}

function getLatestFeedbackEvent(events?: ServerEvent[]) {
  return [...(events ?? [])].reverse().find((event) => event.type === "feedback.update") as
    | FeedbackUpdateEvent
    | undefined;
}

function getLatestDebugEvent(events?: ServerEvent[]) {
  return [...(events ?? [])].reverse().find((event) => event.type === "debug.state") as
    | DebugStateEvent
    | undefined;
}

function getFinalSegments(events?: ServerEvent[]) {
  return (events ?? []).filter((event) => event.type === "transcript.final") as TranscriptFinalEvent[];
}

export function ReplayPage() {
  const [selectedReplayFile, setSelectedReplayFile] = useState<File | null>(null);
  const { preferences } = useAppPreferences();
  const replay = useReplayTranscription();
  const paceEvent = getLatestPaceEvent(replay.result?.events);
  const feedbackEvent = getLatestFeedbackEvent(replay.result?.events);
  const debugEvent = getLatestDebugEvent(replay.result?.events);
  const finalSegments = getFinalSegments(replay.result?.events);

  return (
    <main className="page-shell">
      <section className="hero-card hero-card-compact">
        <div className="hero-copy">
          <span className="eyebrow">Replay Analysis</span>
          <h1>Upload a recording and review transcript, pace, and coaching without opening the mic.</h1>
          <p>
            Replay mode is tuned for deliberate review. Use it after a rehearsal or meeting to
            see how the backend interpreted your pace band, transcript segmentation, and coaching
            guidance from start to finish.
          </p>
        </div>
        <div className="hero-stat-grid hero-stat-grid-tight">
          <article className="hero-stat-card">
            <span>Preferred focus</span>
            <strong>{preferences.practiceFocus}</strong>
          </article>
          <article className="hero-stat-card">
            <span>Status</span>
            <strong>{replay.status}</strong>
          </article>
          <article className="hero-stat-card">
            <span>Provider</span>
            <strong>{replay.result?.provider ?? "Pending"}</strong>
          </article>
        </div>
      </section>

      <section className="content-grid content-grid-primary">
        <article className="surface-card surface-card-emphasis">
          <div className="surface-header">
            <div>
              <span className={`tone-pill ${statusToneClass(replay.status)}`}>{replay.status}</span>
              <h2>Replay control room</h2>
            </div>
            <Link className="button button-secondary" href="/history">
              Open history
            </Link>
          </div>

          <label className="field">
            <span>Replay WAV file</span>
            <input
              accept=".wav,audio/wav"
              type="file"
              onChange={(event) => setSelectedReplayFile(event.target.files?.[0] ?? null)}
            />
          </label>

          <div className="action-row">
            <button
              className="button button-primary"
              disabled={selectedReplayFile === null || replay.status === "uploading"}
              onClick={() => {
                if (selectedReplayFile) {
                  void replay.runReplay(selectedReplayFile);
                }
              }}
            >
              Analyze recording
            </button>
          </div>

          {replay.errorMessage ? <p className="inline-error">{replay.errorMessage}</p> : null}

          <div className="keyline-list">
            <div className="keyline-item">Accepted input: WAV / 16-bit PCM / mono preferred</div>
            <div className="keyline-item">Replay uses the same backend event contract as live mode</div>
            <div className="keyline-item">Session history persists the result for later inspection</div>
          </div>
        </article>

        <article className="surface-card">
          <div className="surface-header">
            <div>
              <span className="eyebrow">Replay summary</span>
              <h2>Outcome snapshot</h2>
            </div>
          </div>

          <div className="metric-grid">
            <article className="metric-card">
              <span>Average WPM</span>
              <strong>{paceEvent?.payload.wordsPerMinute.toFixed(1) ?? replay.result?.summary.averageWpm?.toFixed(1) ?? "-"}</strong>
              <small>{describePaceBand(paceEvent?.payload.band ?? replay.result?.summary.paceBand)}</small>
            </article>
            <article className="metric-card">
              <span>Total words</span>
              <strong>{replay.result?.summary.totalWords ?? 0}</strong>
              <small>Final transcript word count</small>
            </article>
            <article className="metric-card">
              <span>Transcript segments</span>
              <strong>{replay.result?.summary.transcriptSegments ?? 0}</strong>
              <small>Backend finalized segments</small>
            </article>
          </div>

          <article className="insight-card">
            <span>Replay coaching</span>
            <strong>{describeFeedback(feedbackEvent?.payload.decision, feedbackEvent?.payload.reason)}</strong>
            <small>{feedbackConfidenceLabel(feedbackEvent?.payload)}</small>
          </article>
        </article>
      </section>

      <section className="content-grid">
        <article className="surface-card surface-card-span-two">
          <div className="surface-header">
            <div>
              <span className="eyebrow">Transcript</span>
              <h2>Replay transcript timeline</h2>
            </div>
          </div>

          <div className="timeline-list">
            {finalSegments.length === 0 ? (
              <article className="empty-state-card">
                <strong>No replay transcript yet.</strong>
                <p>Upload a supported recording to generate transcript segments and summary metrics.</p>
              </article>
            ) : null}
            {finalSegments.map((event) => (
              <article className="timeline-card" key={event.payload.segment.id}>
                <div className="timeline-card-header">
                  <strong>{event.payload.segment.id}</strong>
                  <span>
                    {event.payload.segment.wordCount} words · {formatDurationMs(event.payload.segment.endTimeMs - event.payload.segment.startTimeMs)}
                  </span>
                </div>
                <p>{event.payload.segment.text}</p>
              </article>
            ))}
          </div>
        </article>

        <article className="surface-card">
          <div className="surface-header">
            <div>
              <span className="eyebrow">Diagnostics</span>
              <h2>Replay backend trace</h2>
            </div>
          </div>
          <div className="data-grid">
            <div className="data-point">
              <span>Lifecycle</span>
              <strong>{debugEvent?.payload.lifecycle ?? "idle"}</strong>
            </div>
            <div className="data-point">
              <span>Provider</span>
              <strong>{debugEvent?.payload.activeProvider ?? replay.result?.provider ?? "-"}</strong>
            </div>
            <div className="data-point">
              <span>Chunks received</span>
              <strong>{debugEvent?.payload.chunksReceived ?? 0}</strong>
            </div>
            <div className="data-point">
              <span>Final segments</span>
              <strong>{debugEvent?.payload.finalSegments ?? replay.result?.summary.transcriptSegments ?? 0}</strong>
            </div>
          </div>
        </article>
      </section>
    </main>
  );
}
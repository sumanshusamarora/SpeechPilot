"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";

import { useSessionDetail } from "@/hooks/use-session-detail";
import { env } from "@/lib/env";
import {
  describeFeedback,
  describePaceBand,
  formatDateTime,
  formatDurationMs,
  statusToneClass,
} from "@/lib/format";

export function SessionDetailPage({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  const { detail, errorMessage, refresh, status } = useSessionDetail(sessionId);

  async function deleteSession() {
    const response = await fetch(`${env.backendHttpUrl}/api/sessions/${sessionId}`, {
      method: "DELETE",
    });
    const payload = (await response.json().catch(() => ({ detail: "Failed to delete session." }))) as {
      detail?: string;
    };
    if (!response.ok) {
      throw new Error(payload.detail ?? "Failed to delete session.");
    }
    router.push("/history");
  }

  return (
    <main className="page-shell">
      <section className="hero-card hero-card-compact">
        <div className="hero-copy">
          <span className="eyebrow">Session Detail</span>
          <h1>{sessionId}</h1>
          <p>
            Open the final transcript, persisted coaching events, and end-of-session summary in a
            single review surface. This route is designed for careful session comparison, not raw event debugging.
          </p>
        </div>
        <div className="action-row compact-actions">
          <Link className="button button-secondary" href="/history">
            Back to history
          </Link>
          <button className="button button-secondary" onClick={() => void refresh()}>
            Refresh
          </button>
          <button
            className="button button-danger"
            onClick={() => {
              void deleteSession();
            }}
          >
            Delete session
          </button>
        </div>
      </section>

      {errorMessage ? <p className="inline-error">{errorMessage}</p> : null}

      {!detail ? (
        <section className="surface-card">
          <span className={`tone-pill ${statusToneClass(status)}`}>{status}</span>
          <h2>Loading session detail</h2>
          <p>SpeechPilot is fetching the transcript and coaching timeline for this session.</p>
        </section>
      ) : (
        <>
          <section className="content-grid content-grid-primary">
            <article className="surface-card surface-card-emphasis">
              <div className="surface-header">
                <div>
                  <span className="eyebrow">Summary</span>
                  <h2>Session outcome</h2>
                </div>
                <span className="tone-pill tone-good">{describePaceBand(detail.summary.paceBand)}</span>
              </div>

              <div className="metric-grid">
                <article className="metric-card">
                  <span>Average WPM</span>
                  <strong>{detail.summary.averageWpm?.toFixed(1) ?? "-"}</strong>
                </article>
                <article className="metric-card">
                  <span>Total words</span>
                  <strong>{detail.summary.totalWords}</strong>
                </article>
                <article className="metric-card">
                  <span>Feedback count</span>
                  <strong>{detail.summary.feedbackCount}</strong>
                </article>
              </div>

              <div className="data-grid">
                <div className="data-point">
                  <span>Started</span>
                  <strong>{formatDateTime(detail.summary.startedAt)}</strong>
                </div>
                <div className="data-point">
                  <span>Duration</span>
                  <strong>{formatDurationMs(detail.summary.durationMs)}</strong>
                </div>
                <div className="data-point">
                  <span>Provider</span>
                  <strong>{detail.summary.provider}</strong>
                </div>
                <div className="data-point">
                  <span>Client</span>
                  <strong>{detail.summary.client}</strong>
                </div>
              </div>

              <article className="insight-card">
                <span>Last coaching decision</span>
                <strong>
                  {describeFeedback(
                    detail.summary.lastFeedbackDecision,
                    detail.summary.lastFeedbackReason,
                  )}
                </strong>
                <small>{detail.summary.finalTranscriptText ?? "No finalized transcript text."}</small>
              </article>
            </article>

            <article className="surface-card">
              <div className="surface-header">
                <div>
                  <span className="eyebrow">Session context</span>
                  <h2>Metadata</h2>
                </div>
              </div>
              <div className="keyline-list">
                <div className="keyline-item">Status: {detail.summary.status}</div>
                <div className="keyline-item">Replay mode: {detail.summary.replayMode ? "Yes" : "No"}</div>
                <div className="keyline-item">Locale: {detail.summary.locale ?? "-"}</div>
                <div className="keyline-item">Stop reason: {detail.summary.stopReason ?? "-"}</div>
              </div>
            </article>
          </section>

          <section className="content-grid">
            <article className="surface-card surface-card-span-two">
              <div className="surface-header">
                <div>
                  <span className="eyebrow">Transcript</span>
                  <h2>Final transcript timeline</h2>
                </div>
              </div>
              <div className="timeline-list">
                {detail.transcript.length === 0 ? (
                  <article className="empty-state-card">
                    <strong>No transcript segments persisted.</strong>
                    <p>The backend did not save finalized transcript data for this session.</p>
                  </article>
                ) : null}
                {detail.transcript.map((segment) => (
                  <article className="timeline-card" key={segment.segmentId}>
                    <div className="timeline-card-header">
                      <strong>{segment.segmentId}</strong>
                      <span>
                        {segment.wordCount} words · {formatDurationMs(segment.endTimeMs - segment.startTimeMs)}
                      </span>
                    </div>
                    <p>{segment.text}</p>
                    <small>{formatDateTime(segment.createdAt)}</small>
                  </article>
                ))}
              </div>
            </article>

            <article className="surface-card">
              <div className="surface-header">
                <div>
                  <span className="eyebrow">Coaching</span>
                  <h2>Feedback timeline</h2>
                </div>
              </div>
              <div className="timeline-list compact-timeline-list">
                {detail.feedbackEvents.length === 0 ? (
                  <article className="empty-state-card">
                    <strong>No feedback events persisted.</strong>
                    <p>This session completed without recorded coaching decisions.</p>
                  </article>
                ) : null}
                {detail.feedbackEvents.map((event) => (
                  <article className="timeline-card" key={`${event.createdAt}-${event.decision}`}>
                    <div className="timeline-card-header">
                      <strong>{describePaceBand(event.paceBand)}</strong>
                      <span>{formatDateTime(event.createdAt)}</span>
                    </div>
                    <p>{describeFeedback(event.decision, event.reason)}</p>
                    <small>
                      {event.observedWpm.toFixed(1)} WPM · {Math.round(event.confidence * 100)}% confidence
                    </small>
                  </article>
                ))}
              </div>
            </article>
          </section>
        </>
      )}
    </main>
  );
}
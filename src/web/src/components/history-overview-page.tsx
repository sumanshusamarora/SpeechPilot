"use client";

import Link from "next/link";
import { useMemo, useState } from "react";

import { useSessionHistory } from "@/hooks/use-session-history";
import {
  describeFeedback,
  describePaceBand,
  formatDateTime,
  formatDurationMs,
  statusToneClass,
} from "@/lib/format";

export function HistoryOverviewPage() {
  const { deleteSession, errorMessage, refresh, sessions, status } = useSessionHistory();
  const [query, setQuery] = useState("");

  const filteredSessions = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    if (!normalizedQuery) {
      return sessions;
    }
    return sessions.filter((session) => {
      return [
        session.sessionId,
        session.provider,
        session.finalTranscriptText ?? "",
        session.paceBand,
      ].some((value) => value.toLowerCase().includes(normalizedQuery));
    });
  }, [query, sessions]);

  const totalWords = sessions.reduce((sum, session) => sum + session.totalWords, 0);

  return (
    <main className="page-shell">
      <section className="hero-card hero-card-compact">
        <div className="hero-copy">
          <span className="eyebrow">Session History</span>
          <h1>Review sessions, compare outcomes, and jump straight into transcript detail.</h1>
          <p>
            History is designed for post-session review, not raw debugging. Search by session ID,
            provider, or transcript text and open the detail route when you want the full coaching timeline.
          </p>
        </div>
        <div className="hero-stat-grid hero-stat-grid-tight">
          <article className="hero-stat-card">
            <span>Sessions</span>
            <strong>{sessions.length}</strong>
          </article>
          <article className="hero-stat-card">
            <span>Total words reviewed</span>
            <strong>{totalWords}</strong>
          </article>
          <article className="hero-stat-card">
            <span>Status</span>
            <strong>{status}</strong>
          </article>
        </div>
      </section>

      <section className="surface-card surface-card-emphasis">
        <div className="surface-header">
          <div>
            <span className={`tone-pill ${statusToneClass(status)}`}>{status}</span>
            <h2>Browse saved sessions</h2>
          </div>
          <button className="button button-secondary" onClick={() => void refresh()}>
            Refresh
          </button>
        </div>

        <label className="field">
          <span>Search</span>
          <input
            placeholder="Search by session ID, provider, pace band, or transcript text"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>

        {errorMessage ? <p className="inline-error">{errorMessage}</p> : null}

        <div className="session-card-list">
          {filteredSessions.length === 0 ? (
            <article className="empty-state-card">
              <strong>No sessions match this filter.</strong>
              <p>Run a live or replay session to populate history.</p>
            </article>
          ) : null}
          {filteredSessions.map((session) => (
            <article className="session-card" key={session.sessionId}>
              <div className="session-card-header">
                <div>
                  <span className="eyebrow">{describePaceBand(session.paceBand)}</span>
                  <h3>{session.sessionId}</h3>
                </div>
                <span className="session-card-date">{formatDateTime(session.startedAt)}</span>
              </div>

              <div className="metric-grid compact-metrics">
                <article className="metric-card">
                  <span>Average WPM</span>
                  <strong>{session.averageWpm?.toFixed(1) ?? "-"}</strong>
                </article>
                <article className="metric-card">
                  <span>Words</span>
                  <strong>{session.totalWords}</strong>
                </article>
                <article className="metric-card">
                  <span>Duration</span>
                  <strong>{formatDurationMs(session.durationMs)}</strong>
                </article>
              </div>

              <p className="subtle-copy">
                {describeFeedback(session.lastFeedbackDecision, session.lastFeedbackReason)}
              </p>

              <div className="data-grid compact-data-grid">
                <div className="data-point">
                  <span>Provider</span>
                  <strong>{session.provider}</strong>
                </div>
                <div className="data-point">
                  <span>Client</span>
                  <strong>{session.client}</strong>
                </div>
                <div className="data-point">
                  <span>Feedback events</span>
                  <strong>{session.feedbackCount}</strong>
                </div>
              </div>

              <div className="action-row compact-actions">
                <Link className="button button-primary" href={`/history/${encodeURIComponent(session.sessionId)}`}>
                  View detail
                </Link>
                <button
                  className="button button-danger"
                  onClick={() => {
                    void deleteSession(session.sessionId).catch(() => {
                      void refresh();
                    });
                  }}
                >
                  Delete
                </button>
              </div>
            </article>
          ))}
        </div>
      </section>
    </main>
  );
}
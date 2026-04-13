"use client";

import { useEffect, useState } from "react";

import { env } from "@/lib/env";
import type {
  SessionHistoryDetailResponse,
  SessionHistoryListResponse,
  SessionHistorySummary,
} from "@/lib/session-history";

function formatDateTime(value: string) {
  return new Date(value).toLocaleString();
}

function formatDurationMs(value?: number | null) {
  if (!value) {
    return "-";
  }
  return `${(value / 1000).toFixed(1)}s`;
}

function describeFeedback(decision?: string | null, reason?: string | null) {
  if (!decision) {
    return "No coaching feedback recorded.";
  }
  if (decision === "slow_down") {
    return "Slow down to regain clarity.";
  }
  if (decision === "speed_up") {
    return "Add a little more energy and pace.";
  }
  if (reason === "wpm_in_target_range") {
    return "Pace stayed in the target range.";
  }
  return "Coaching feedback available.";
}

async function fetchSessionList(): Promise<SessionHistorySummary[]> {
  const response = await fetch(`${env.backendHttpUrl}/api/sessions`, { cache: "no-store" });
  const payload = (await response.json()) as SessionHistoryListResponse | { detail: string };
  if (!response.ok) {
    throw new Error("detail" in payload ? payload.detail : "Failed to load sessions.");
  }
  if (!("items" in payload)) {
    throw new Error("Failed to load sessions.");
  }
  return payload.items;
}

async function fetchSessionDetail(sessionId: string): Promise<SessionHistoryDetailResponse> {
  const response = await fetch(`${env.backendHttpUrl}/api/sessions/${sessionId}`, { cache: "no-store" });
  const payload = (await response.json()) as SessionHistoryDetailResponse | { detail: string };
  if (!response.ok) {
    throw new Error("detail" in payload ? payload.detail : "Failed to load session detail.");
  }
  if (!("summary" in payload)) {
    throw new Error("Failed to load session detail.");
  }
  return payload;
}

export function SessionHistoryShell() {
  const [sessions, setSessions] = useState<SessionHistorySummary[]>([]);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [selectedSession, setSelectedSession] = useState<SessionHistoryDetailResponse | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "error">("idle");
  const [detailStatus, setDetailStatus] = useState<"idle" | "loading" | "error">("idle");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const loadSessions = async () => {
    setStatus("loading");
    setErrorMessage(null);
    try {
      const items = await fetchSessionList();
      setSessions(items);
      setStatus("idle");
      if (!selectedSessionId && items.length > 0) {
        setSelectedSessionId(items[0].sessionId);
      }
      if (items.length === 0) {
        setSelectedSession(null);
      }
    } catch (error) {
      setStatus("error");
      setErrorMessage(error instanceof Error ? error.message : "Failed to load sessions.");
    }
  };

  useEffect(() => {
    void loadSessions();
  }, []);

  useEffect(() => {
    if (!selectedSessionId) {
      return;
    }

    let cancelled = false;
    const loadDetail = async () => {
      setDetailStatus("loading");
      try {
        const detail = await fetchSessionDetail(selectedSessionId);
        if (!cancelled) {
          setSelectedSession(detail);
          setDetailStatus("idle");
        }
      } catch (error) {
        if (!cancelled) {
          setDetailStatus("error");
          setErrorMessage(error instanceof Error ? error.message : "Failed to load session detail.");
        }
      }
    };

    void loadDetail();
    return () => {
      cancelled = true;
    };
  }, [selectedSessionId]);

  const deleteSession = async (sessionId: string) => {
    const confirmed = window.confirm("Delete this session history entry?");
    if (!confirmed) {
      return;
    }

    setErrorMessage(null);
    try {
      const response = await fetch(`${env.backendHttpUrl}/api/sessions/${sessionId}`, {
        method: "DELETE",
      });
      if (!response.ok) {
        const payload = (await response.json()) as { detail?: string };
        throw new Error(payload.detail ?? "Failed to delete session.");
      }

      const nextSessions = sessions.filter((session) => session.sessionId !== sessionId);
      setSessions(nextSessions);
      if (selectedSessionId === sessionId) {
        setSelectedSessionId(nextSessions[0]?.sessionId ?? null);
        setSelectedSession(null);
      }
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : "Failed to delete session.");
    }
  };

  return (
    <main className="page-shell">
      <div className="page-grid">
        <section className="hero hero-compact">
          <div className="hero-copy">
            <span className="eyebrow">Session history</span>
            <h1>Review past speaking sessions with transcript, pace, and coaching context.</h1>
            <p>
              This view is for playback-level inspection after a session ends. Use it to compare
              outcomes, spot pacing drift, and delete runs you do not want to keep.
            </p>
          </div>
        </section>

        <section className="history-layout">
          <article className="panel history-panel">
            <div className="history-panel-header">
              <h2>Recent sessions</h2>
              <button className="button" onClick={() => void loadSessions()}>
                Refresh
              </button>
            </div>
            <p>{status === "loading" ? "Loading session history..." : "Select a run to inspect details."}</p>
            {errorMessage ? <p>{errorMessage}</p> : null}
            <div className="history-list">
              {sessions.map((session) => (
                <button
                  className={`history-row${selectedSessionId === session.sessionId ? " history-row-active" : ""}`}
                  key={session.sessionId}
                  onClick={() => setSelectedSessionId(session.sessionId)}
                  type="button"
                >
                  <div>
                    <strong>{session.sessionId}</strong>
                    <span>{formatDateTime(session.startedAt)}</span>
                  </div>
                  <div>
                    <strong>{session.paceBand}</strong>
                    <span>{session.totalWords} words</span>
                  </div>
                </button>
              ))}
            </div>
          </article>

          <article className="panel history-panel detail-panel">
            <div className="history-panel-header">
              <h2>Session detail</h2>
              {selectedSession ? (
                <button className="button button-warning" onClick={() => void deleteSession(selectedSession.summary.sessionId)}>
                  Delete
                </button>
              ) : null}
            </div>
            {detailStatus === "loading" ? <p>Loading detail...</p> : null}
            {!selectedSession && detailStatus !== "loading" ? <p>No session selected.</p> : null}
            {selectedSession ? (
              <>
                <div className="metric-strip">
                  <article className="metric-card">
                    <span>Average WPM</span>
                    <strong>{selectedSession.summary.averageWpm?.toFixed(1) ?? "-"}</strong>
                  </article>
                  <article className="metric-card">
                    <span>Feedback count</span>
                    <strong>{selectedSession.summary.feedbackCount}</strong>
                  </article>
                  <article className="metric-card">
                    <span>Duration</span>
                    <strong>{formatDurationMs(selectedSession.summary.durationMs)}</strong>
                  </article>
                </div>

                <div className="meta-list">
                  <div className="meta-item">
                    <span>Last coaching decision</span>
                    <strong>
                      {describeFeedback(
                        selectedSession.summary.lastFeedbackDecision,
                        selectedSession.summary.lastFeedbackReason,
                      )}
                    </strong>
                  </div>
                  <div className="meta-item">
                    <span>Transcript preview</span>
                    <strong>{selectedSession.summary.finalTranscriptText ?? "No finalized transcript."}</strong>
                  </div>
                </div>

                <section className="detail-section">
                  <h3>Coaching timeline</h3>
                  <div className="transcript-list">
                    {selectedSession.feedbackEvents.length === 0 ? <p>No feedback events persisted.</p> : null}
                    {selectedSession.feedbackEvents.map((event) => (
                      <article className="transcript-item" key={`${event.createdAt}-${event.decision}`}>
                        <strong>{formatDateTime(event.createdAt)}</strong>
                        <p>{describeFeedback(event.decision, event.reason)}</p>
                        <small>
                          {`${event.observedWpm.toFixed(1)} WPM • ${Math.round(event.confidence * 100)}% confidence`}
                        </small>
                      </article>
                    ))}
                  </div>
                </section>

                <section className="detail-section">
                  <h3>Final transcript</h3>
                  <div className="transcript-list">
                    {selectedSession.transcript.map((segment) => (
                      <article className="transcript-item" key={segment.segmentId}>
                        <strong>{segment.segmentId}</strong>
                        <p>{segment.text}</p>
                        <small>
                          {`${segment.wordCount} words • ${formatDurationMs(segment.endTimeMs - segment.startTimeMs)}`}
                        </small>
                      </article>
                    ))}
                  </div>
                </section>
              </>
            ) : null}
          </article>
        </section>
      </div>
    </main>
  );
}
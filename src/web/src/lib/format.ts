import type { DebugStatePayload, FeedbackUpdatePayload } from "@/lib/contracts";

export function formatDateTime(value: string) {
  return new Date(value).toLocaleString();
}

export function formatTime(value: string) {
  return new Date(value).toLocaleTimeString([], {
    hour: "numeric",
    minute: "2-digit",
  });
}

export function formatDurationMs(value?: number | null) {
  if (!value) {
    return "-";
  }

  const seconds = value / 1000;
  if (seconds < 90) {
    return `${seconds.toFixed(1)}s`;
  }

  return `${(seconds / 60).toFixed(1)} min`;
}

export function describeFeedback(decision?: string | null, reason?: string | null) {
  if (!decision) {
    return "Waiting for steady pace data.";
  }
  if (decision === "slow_down") {
    return "Ease off a little. Your delivery is landing above the target pace band.";
  }
  if (decision === "speed_up") {
    return "Add a little more energy. Your delivery is below the target pace band.";
  }
  if (reason === "wpm_in_target_range") {
    return "Good pace. Your delivery is staying inside the target range.";
  }
  return "Coaching feedback is available for this session.";
}

export function describePaceBand(band?: string | null) {
  if (band === "fast") {
    return "Fast";
  }
  if (band === "slow") {
    return "Slow";
  }
  if (band === "good") {
    return "On target";
  }
  return "Stabilizing";
}

export function statusToneClass(status: string) {
  if (status === "connected" || status === "capturing" || status === "completed") {
    return "tone-good";
  }
  if (status === "connecting" || status === "starting" || status === "stopping" || status === "uploading" || status === "loading") {
    return "tone-warn";
  }
  if (status === "error") {
    return "tone-danger";
  }
  return "tone-muted";
}

export function feedbackConfidenceLabel(payload?: FeedbackUpdatePayload | null) {
  if (!payload) {
    return "Feedback appears after the backend has enough pace confidence.";
  }
  return `${Math.round(payload.confidence * 100)}% confidence`;
}

export function debugConfidenceLabel(payload?: DebugStatePayload | null) {
  if (payload?.lastFeedbackConfidence == null) {
    return "No persisted backend confidence yet.";
  }
  return `${Math.round(payload.lastFeedbackConfidence * 100)}% confidence`;
}
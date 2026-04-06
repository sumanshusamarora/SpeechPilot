export interface SessionHistorySummary {
  sessionId: string;
  client: string;
  locale?: string | null;
  replayMode: boolean;
  provider: string;
  status: string;
  stopReason?: string | null;
  startedAt: string;
  endedAt?: string | null;
  durationMs?: number | null;
  transcriptSegments: number;
  totalWords: number;
  currentWpm?: number | null;
  averageWpm?: number | null;
  paceBand: string;
  feedbackCount: number;
  lastFeedbackDecision?: string | null;
  lastFeedbackReason?: string | null;
  lastFeedbackConfidence?: number | null;
  finalTranscriptText?: string | null;
}

export interface SessionHistoryTranscriptSegment {
  segmentId: string;
  text: string;
  startTimeMs: number;
  endTimeMs: number;
  wordCount: number;
  createdAt: string;
}

export interface SessionHistoryFeedbackEvent {
  decision: string;
  reason: string;
  confidence: number;
  observedWpm: number;
  paceBand: string;
  totalWords: number;
  speakingDurationMs: number;
  createdAt: string;
}

export interface SessionHistoryListResponse {
  items: SessionHistorySummary[];
}

export interface SessionHistoryDetailResponse {
  summary: SessionHistorySummary;
  transcript: SessionHistoryTranscriptSegment[];
  feedbackEvents: SessionHistoryFeedbackEvent[];
}
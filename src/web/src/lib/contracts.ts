export const PROTOCOL_VERSION = "1.0" as const;

export type ProtocolVersion = typeof PROTOCOL_VERSION;

export interface EventEnvelope {
  version: ProtocolVersion;
  timestamp: string;
}

export interface SessionStartPayload {
  sessionId: string;
  client: string;
  replayMode?: boolean;
  locale?: string | null;
}

export interface AudioChunkPayload {
  sessionId: string;
  sequence: number;
  encoding?: string;
  sampleRateHz: number;
  channelCount?: number;
  durationMs: number;
  dataBase64?: string | null;
}

export interface SessionStopPayload {
  sessionId: string;
  reason?: string;
}

export interface TranscriptPartialPayload {
  sessionId: string;
  text: string;
  sequence: number;
}

export interface TranscriptSegment {
  id: string;
  text: string;
  startTimeMs: number;
  endTimeMs: number;
  wordCount: number;
}

export interface TranscriptFinalPayload {
  sessionId: string;
  segment: TranscriptSegment;
}

export interface PaceUpdatePayload {
  sessionId: string;
  wordsPerMinute: number;
  band: "slow" | "good" | "fast" | "unknown";
  source: string;
  totalWords: number;
  speakingDurationMs: number;
  silenceDurationMs: number;
  windowDurationMs: number;
}

export interface FeedbackUpdatePayload {
  sessionId: string;
  severity: "info" | "nudge" | "warning";
  message: string;
  rationale?: string | null;
}

export interface SessionSummaryPayload {
  sessionId: string;
  durationMs: number;
  transcriptSegments: number;
  totalWords: number;
  averageWpm?: number | null;
  speakingDurationMs: number;
  silenceDurationMs: number;
  paceBand: "slow" | "good" | "fast" | "unknown";
  notes: string[];
}

export interface DebugStatePayload {
  sessionId?: string | null;
  lifecycle: string;
  activeProvider?: string | null;
  replayMode?: boolean | null;
  chunksReceived: number;
  partialUpdates: number;
  finalSegments: number;
  totalWords: number;
  wordsPerMinute?: number | null;
  paceBand: "slow" | "good" | "fast" | "unknown";
  detail?: string | null;
}

export interface ErrorPayload {
  code: string;
  message: string;
  retryable: boolean;
  detail?: string | null;
}

export interface SessionStartEvent extends EventEnvelope {
  type: "session.start";
  payload: SessionStartPayload;
}

export interface AudioChunkEvent extends EventEnvelope {
  type: "audio.chunk";
  payload: AudioChunkPayload;
}

export interface SessionStopEvent extends EventEnvelope {
  type: "session.stop";
  payload: SessionStopPayload;
}

export interface TranscriptPartialEvent extends EventEnvelope {
  type: "transcript.partial";
  payload: TranscriptPartialPayload;
}

export interface TranscriptFinalEvent extends EventEnvelope {
  type: "transcript.final";
  payload: TranscriptFinalPayload;
}

export interface PaceUpdateEvent extends EventEnvelope {
  type: "pace.update";
  payload: PaceUpdatePayload;
}

export interface FeedbackUpdateEvent extends EventEnvelope {
  type: "feedback.update";
  payload: FeedbackUpdatePayload;
}

export interface SessionSummaryEvent extends EventEnvelope {
  type: "session.summary";
  payload: SessionSummaryPayload;
}

export interface DebugStateEvent extends EventEnvelope {
  type: "debug.state";
  payload: DebugStatePayload;
}

export interface ErrorEvent extends EventEnvelope {
  type: "error";
  payload: ErrorPayload;
}

export type ClientEvent = SessionStartEvent | AudioChunkEvent | SessionStopEvent;

export type ServerEvent =
  | TranscriptPartialEvent
  | TranscriptFinalEvent
  | PaceUpdateEvent
  | FeedbackUpdateEvent
  | SessionSummaryEvent
  | DebugStateEvent
  | ErrorEvent;

export function createSessionStartEvent(sessionId: string): SessionStartEvent {
  return {
    version: PROTOCOL_VERSION,
    timestamp: new Date().toISOString(),
    type: "session.start",
    payload: {
      sessionId,
      client: "web",
      replayMode: false,
    },
  };
}

export function createAudioChunkEvent(payload: AudioChunkPayload): AudioChunkEvent {
  return {
    version: PROTOCOL_VERSION,
    timestamp: new Date().toISOString(),
    type: "audio.chunk",
    payload,
  };
}

export function createSessionStopEvent(sessionId: string, reason = "manual_stop"): SessionStopEvent {
  return {
    version: PROTOCOL_VERSION,
    timestamp: new Date().toISOString(),
    type: "session.stop",
    payload: {
      sessionId,
      reason,
    },
  };
}
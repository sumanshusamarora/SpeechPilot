export declare const PROTOCOL_VERSION: "1.0";

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

export interface TranscriptFinalPayload {
  sessionId: string;
  text: string;
  utteranceId: string;
}

export interface PaceUpdatePayload {
  sessionId: string;
  wordsPerMinute?: number | null;
  band: "slow" | "on_target" | "fast" | "unknown";
  source: string;
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
  averageWpm?: number | null;
  notes: string[];
}

export interface DebugStatePayload {
  sessionId?: string | null;
  scope: string;
  state: string;
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

export declare function createSessionStartEvent(sessionId: string): SessionStartEvent;
export declare function createSessionStopEvent(sessionId: string): SessionStopEvent;
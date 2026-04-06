from __future__ import annotations

from datetime import datetime, timezone
from typing import Annotated, Any, Literal, Union

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter

PROTOCOL_VERSION = "1.0"
PaceBand = Literal["slow", "good", "fast", "unknown"]
FeedbackDecision = Literal["slow_down", "speed_up", "good_pace"]
FeedbackReason = Literal["wpm_above_threshold", "wpm_below_threshold", "wpm_in_target_range"]


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class EventModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: Literal["1.0"] = PROTOCOL_VERSION
    timestamp: datetime = Field(default_factory=_utc_now)


class SessionStartPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    client: str
    replayMode: bool = False
    locale: str | None = None


class AudioChunkPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    sequence: int
    encoding: str = "pcm16le"
    sampleRateHz: int
    channelCount: int = 1
    durationMs: int
    dataBase64: str | None = None


class SessionStopPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    reason: str = "client_stop"


class TranscriptPartialPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    text: str
    sequence: int


class TranscriptSegmentPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    text: str
    startTimeMs: int
    endTimeMs: int
    wordCount: int


class TranscriptFinalPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    segment: TranscriptSegmentPayload


class PaceUpdatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    wordsPerMinute: float
    band: PaceBand
    source: str
    totalWords: int
    speakingDurationMs: int
    silenceDurationMs: int
    windowDurationMs: int


class FeedbackUpdatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    decision: FeedbackDecision
    reason: FeedbackReason
    confidence: float = Field(ge=0.0, le=1.0)


class SessionSummaryPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    durationMs: int
    transcriptSegments: int
    totalWords: int
    averageWpm: float | None = None
    speakingDurationMs: int
    silenceDurationMs: int
    paceBand: PaceBand = "unknown"
    notes: list[str] = Field(default_factory=list)


class DebugStatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str | None = None
    lifecycle: str
    activeProvider: str | None = None
    replayMode: bool | None = None
    chunksReceived: int = 0
    partialUpdates: int = 0
    finalSegments: int = 0
    totalWords: int = 0
    wordsPerMinute: float | None = None
    paceBand: PaceBand = "unknown"
    feedbackCount: int = 0
    lastFeedbackDecision: FeedbackDecision | None = None
    lastFeedbackReason: FeedbackReason | None = None
    lastFeedbackConfidence: float | None = Field(default=None, ge=0.0, le=1.0)
    detail: str | None = None


class ErrorPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: str
    message: str
    retryable: bool = False
    detail: str | None = None


class SessionStartEvent(EventModel):
    type: Literal["session.start"] = "session.start"
    payload: SessionStartPayload


class AudioChunkEvent(EventModel):
    type: Literal["audio.chunk"] = "audio.chunk"
    payload: AudioChunkPayload


class SessionStopEvent(EventModel):
    type: Literal["session.stop"] = "session.stop"
    payload: SessionStopPayload


class TranscriptPartialEvent(EventModel):
    type: Literal["transcript.partial"] = "transcript.partial"
    payload: TranscriptPartialPayload


class TranscriptFinalEvent(EventModel):
    type: Literal["transcript.final"] = "transcript.final"
    payload: TranscriptFinalPayload


class PaceUpdateEvent(EventModel):
    type: Literal["pace.update"] = "pace.update"
    payload: PaceUpdatePayload


class FeedbackUpdateEvent(EventModel):
    type: Literal["feedback.update"] = "feedback.update"
    payload: FeedbackUpdatePayload


class SessionSummaryEvent(EventModel):
    type: Literal["session.summary"] = "session.summary"
    payload: SessionSummaryPayload


class DebugStateEvent(EventModel):
    type: Literal["debug.state"] = "debug.state"
    payload: DebugStatePayload


class ErrorEvent(EventModel):
    type: Literal["error"] = "error"
    payload: ErrorPayload


ClientEvent = Annotated[
    Union[SessionStartEvent, AudioChunkEvent, SessionStopEvent],
    Field(discriminator="type"),
]
ServerEvent = Annotated[
    Union[
        TranscriptPartialEvent,
        TranscriptFinalEvent,
        PaceUpdateEvent,
        FeedbackUpdateEvent,
        SessionSummaryEvent,
        DebugStateEvent,
        ErrorEvent,
    ],
    Field(discriminator="type"),
]

_client_adapter = TypeAdapter(ClientEvent)


def parse_client_event(data: dict[str, Any]) -> ClientEvent:
    return _client_adapter.validate_python(data)


def dump_event(event: BaseModel) -> dict[str, Any]:
    return event.model_dump(mode="json")
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, HTTPException, Query, Request, Response, status
from pydantic import BaseModel, ConfigDict

from app.persistence.repository import SessionHistoryDetail, SessionHistorySummary
from app.providers.container import ServiceContainer

router = APIRouter(prefix="/api/sessions", tags=["sessions"])


class SessionHistorySummaryResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: str
    client: str
    locale: str | None = None
    replayMode: bool
    provider: str
    status: str
    stopReason: str | None = None
    startedAt: datetime
    endedAt: datetime | None = None
    durationMs: int | None = None
    transcriptSegments: int
    totalWords: int
    currentWpm: float | None = None
    averageWpm: float | None = None
    paceBand: str
    feedbackCount: int
    lastFeedbackDecision: str | None = None
    lastFeedbackReason: str | None = None
    lastFeedbackConfidence: float | None = None
    finalTranscriptText: str | None = None


class SessionTranscriptSegmentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    segmentId: str
    text: str
    startTimeMs: int
    endTimeMs: int
    wordCount: int
    createdAt: datetime


class SessionFeedbackEventResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: str
    reason: str
    confidence: float
    observedWpm: float
    paceBand: str
    totalWords: int
    speakingDurationMs: int
    createdAt: datetime


class SessionHistoryListResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[SessionHistorySummaryResponse]


class SessionHistoryDetailResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summary: SessionHistorySummaryResponse
    transcript: list[SessionTranscriptSegmentResponse]
    feedbackEvents: list[SessionFeedbackEventResponse]


def _to_summary_response(summary: SessionHistorySummary) -> SessionHistorySummaryResponse:
    return SessionHistorySummaryResponse(
        sessionId=summary.session_id,
        client=summary.client,
        locale=summary.locale,
        replayMode=summary.replay_mode,
        provider=summary.provider,
        status=summary.status,
        stopReason=summary.stop_reason,
        startedAt=summary.started_at,
        endedAt=summary.ended_at,
        durationMs=summary.duration_ms,
        transcriptSegments=summary.transcript_segments,
        totalWords=summary.total_words,
        currentWpm=summary.current_wpm,
        averageWpm=summary.average_wpm,
        paceBand=summary.pace_band,
        feedbackCount=summary.feedback_count,
        lastFeedbackDecision=summary.last_feedback_decision,
        lastFeedbackReason=summary.last_feedback_reason,
        lastFeedbackConfidence=summary.last_feedback_confidence,
        finalTranscriptText=summary.final_transcript_text,
    )


def _to_detail_response(detail: SessionHistoryDetail) -> SessionHistoryDetailResponse:
    return SessionHistoryDetailResponse(
        summary=_to_summary_response(detail.summary),
        transcript=[
            SessionTranscriptSegmentResponse(
                segmentId=segment.segment_id,
                text=segment.text,
                startTimeMs=segment.start_time_ms,
                endTimeMs=segment.end_time_ms,
                wordCount=segment.word_count,
                createdAt=segment.created_at,
            )
            for segment in detail.transcript
        ],
        feedbackEvents=[
            SessionFeedbackEventResponse(
                decision=event.decision,
                reason=event.reason,
                confidence=event.confidence,
                observedWpm=event.observed_wpm,
                paceBand=event.pace_band,
                totalWords=event.total_words,
                speakingDurationMs=event.speaking_duration_ms,
                createdAt=event.created_at,
            )
            for event in detail.feedback_events
        ],
    )


@router.get("", response_model=SessionHistoryListResponse)
async def list_sessions(
    request: Request,
    limit: int = Query(default=20, ge=1, le=100),
) -> SessionHistoryListResponse:
    container: ServiceContainer = request.app.state.container
    sessions = await container.session_repository.list_sessions(limit=limit)
    return SessionHistoryListResponse(items=[_to_summary_response(item) for item in sessions])


@router.get("/{session_id}", response_model=SessionHistoryDetailResponse)
async def get_session(session_id: str, request: Request) -> SessionHistoryDetailResponse:
    container: ServiceContainer = request.app.state.container
    session = await container.session_repository.get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Session not found.")
    return _to_detail_response(session)


@router.delete("/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_session(session_id: str, request: Request) -> Response:
    container: ServiceContainer = request.app.state.container
    deleted = await container.session_repository.delete_session(session_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Session not found.")
    return Response(status_code=status.HTTP_204_NO_CONTENT)
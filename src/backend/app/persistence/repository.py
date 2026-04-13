from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from speechpilot_contracts.events import SessionSummaryPayload

from app.domain.feedback import CoachingFeedback, SessionFeedbackEventRecord
from app.domain.session import SessionContext
from app.domain.session_metrics import SessionMetricsSnapshot
from app.domain.transcript import TranscriptSegment
from app.persistence.db import build_sqlalchemy_url
from app.persistence.models import FeedbackEventModel, SessionMetricModel, SessionModel, TranscriptSegmentModel


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class SessionRepository(Protocol):
    async def open_session(self, session: SessionContext, provider_name: str) -> None: ...

    async def append_transcript_segments(
        self,
        session: SessionContext,
        segments: list[TranscriptSegment],
        provider_name: str,
    ) -> None: ...

    async def upsert_session_metrics(
        self,
        session: SessionContext,
        metrics: SessionMetricsSnapshot,
    ) -> None: ...

    async def append_feedback_event(
        self,
        session: SessionContext,
        feedback: CoachingFeedback,
    ) -> None: ...

    async def list_sessions(self, limit: int = 20) -> list[SessionHistorySummary]: ...

    async def get_session(self, session_id: str) -> SessionHistoryDetail | None: ...

    async def delete_session(self, session_id: str) -> bool: ...

    async def close_session(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
        *,
        status: str,
        stop_reason: str | None,
    ) -> None: ...

    async def close(self) -> None: ...


@dataclass(slots=True, frozen=True)
class SessionTranscriptSegmentRecord:
    segment_id: str
    text: str
    start_time_ms: int
    end_time_ms: int
    word_count: int
    created_at: datetime


@dataclass(slots=True, frozen=True)
class SessionHistorySummary:
    session_id: str
    client: str
    locale: str | None
    replay_mode: bool
    provider: str
    status: str
    stop_reason: str | None
    started_at: datetime
    ended_at: datetime | None
    duration_ms: int | None
    transcript_segments: int
    total_words: int
    current_wpm: float | None
    average_wpm: float | None
    pace_band: str
    feedback_count: int
    last_feedback_decision: str | None
    last_feedback_reason: str | None
    last_feedback_confidence: float | None
    final_transcript_text: str | None


@dataclass(slots=True, frozen=True)
class SessionHistoryDetail:
    summary: SessionHistorySummary
    transcript: list[SessionTranscriptSegmentRecord]
    feedback_events: list[SessionFeedbackEventRecord]


class SqlAlchemySessionRepository:
    def __init__(self, database_url: str, logger: logging.Logger) -> None:
        self._logger = logger
        self._engine = create_engine(
            build_sqlalchemy_url(database_url),
            future=True,
            pool_pre_ping=True,
        )
        self._session_factory = sessionmaker(self._engine, expire_on_commit=False)

    async def open_session(self, session: SessionContext, provider_name: str) -> None:
        await asyncio.to_thread(self._open_session_sync, session, provider_name)

    def _open_session_sync(self, session: SessionContext, provider_name: str) -> None:
        with self._session_factory() as db_session, db_session.begin():
            existing = db_session.get(SessionModel, session.session_id)
            if existing is not None:
                existing.client = session.client
                existing.locale = session.locale
                existing.replay_mode = session.replay_mode
                existing.provider = provider_name
                existing.status = "active"
                existing.stop_reason = None
                existing.started_at = session.started_at
                existing.ended_at = None
                existing.updated_at = _utc_now()
                return

            db_session.add(
                SessionModel(
                    session_id=session.session_id,
                    client=session.client,
                    locale=session.locale,
                    replay_mode=session.replay_mode,
                    provider=provider_name,
                    status="active",
                    stop_reason=None,
                    started_at=session.started_at,
                    created_at=_utc_now(),
                    updated_at=_utc_now(),
                )
            )

    async def append_transcript_segments(
        self,
        session: SessionContext,
        segments: list[TranscriptSegment],
        provider_name: str,
    ) -> None:
        if not segments:
            return
        await asyncio.to_thread(self._append_transcript_segments_sync, session, segments, provider_name)

    def _append_transcript_segments_sync(
        self,
        session: SessionContext,
        segments: list[TranscriptSegment],
        provider_name: str,
    ) -> None:
        with self._session_factory() as db_session, db_session.begin():
            session_row = db_session.get(SessionModel, session.session_id)
            if session_row is None:
                self._logger.warning("append_transcript_segments called for unknown session_id=%s", session.session_id)
                return

            timestamp = _utc_now()
            source_mode = "replay" if session.replay_mode else "live"
            for segment in segments:
                db_session.add(
                    TranscriptSegmentModel(
                        session_id=session.session_id,
                        segment_id=segment.segment_id,
                        text=segment.text,
                        start_time_ms=segment.start_time_ms,
                        end_time_ms=segment.end_time_ms,
                        word_count=segment.word_count,
                        provider=provider_name,
                        source_mode=source_mode,
                        created_at=timestamp,
                    )
                )

            session_row.updated_at = timestamp

    async def upsert_session_metrics(
        self,
        session: SessionContext,
        metrics: SessionMetricsSnapshot,
    ) -> None:
        await asyncio.to_thread(self._upsert_session_metrics_sync, session, metrics)

    def _upsert_session_metrics_sync(
        self,
        session: SessionContext,
        metrics: SessionMetricsSnapshot,
    ) -> None:
        with self._session_factory() as db_session, db_session.begin():
            session_row = db_session.get(SessionModel, session.session_id)
            if session_row is None:
                self._logger.warning("upsert_session_metrics called for unknown session_id=%s", session.session_id)
                return

            metric_row = db_session.get(SessionMetricModel, session.session_id)
            if metric_row is None:
                metric_row = SessionMetricModel(session_id=session.session_id)
                db_session.add(metric_row)

            metric_row.chunks_received = metrics.chunks_received
            metric_row.partial_updates = metrics.partial_updates
            metric_row.final_segments = metrics.final_segments
            metric_row.total_words = metrics.total_words
            metric_row.current_wpm = metrics.words_per_minute
            metric_row.average_wpm = metrics.average_wpm
            metric_row.speaking_duration_ms = metrics.speaking_duration_ms
            metric_row.silence_duration_ms = metrics.silence_duration_ms
            metric_row.pace_band = metrics.pace_band
            metric_row.feedback_count = metrics.feedback_count
            metric_row.last_feedback_decision = metrics.last_feedback_decision
            metric_row.last_feedback_reason = metrics.last_feedback_reason
            metric_row.last_feedback_confidence = metrics.last_feedback_confidence
            metric_row.updated_at = _utc_now()

    async def append_feedback_event(
        self,
        session: SessionContext,
        feedback: CoachingFeedback,
    ) -> None:
        await asyncio.to_thread(self._append_feedback_event_sync, session, feedback)

    def _append_feedback_event_sync(
        self,
        session: SessionContext,
        feedback: CoachingFeedback,
    ) -> None:
        with self._session_factory() as db_session, db_session.begin():
            session_row = db_session.get(SessionModel, session.session_id)
            if session_row is None:
                self._logger.warning("append_feedback_event called for unknown session_id=%s", session.session_id)
                return

            db_session.add(
                FeedbackEventModel(
                    session_id=session.session_id,
                    decision=feedback.decision,
                    reason=feedback.reason,
                    confidence=feedback.confidence,
                    observed_wpm=feedback.observed_wpm,
                    pace_band=feedback.pace_band,
                    total_words=feedback.total_words,
                    speaking_duration_ms=feedback.speaking_duration_ms,
                    created_at=feedback.created_at or _utc_now(),
                )
            )
            session_row.updated_at = _utc_now()

    async def close_session(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
        *,
        status: str,
        stop_reason: str | None,
    ) -> None:
        await asyncio.to_thread(self._close_session_sync, session, summary, status, stop_reason)

    def _close_session_sync(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
        status: str,
        stop_reason: str | None,
    ) -> None:
        with self._session_factory() as db_session, db_session.begin():
            session_row = db_session.get(SessionModel, session.session_id)
            if session_row is None:
                self._logger.warning("close_session called for unknown session_id=%s", session.session_id)
                return

            session_row.status = status
            session_row.stop_reason = stop_reason
            session_row.ended_at = _utc_now()
            session_row.duration_ms = summary.durationMs
            session_row.transcript_segments = summary.transcriptSegments
            session_row.updated_at = _utc_now()
            session_row.partial_transcript_text = None

            final_segments = db_session.scalars(
                select(TranscriptSegmentModel.text)
                .where(TranscriptSegmentModel.session_id == session.session_id)
                .order_by(TranscriptSegmentModel.id.asc())
            ).all()
            session_row.final_transcript_text = " ".join(final_segments).strip() or None

    async def list_sessions(self, limit: int = 20) -> list[SessionHistorySummary]:
        return await asyncio.to_thread(self._list_sessions_sync, limit)

    def _list_sessions_sync(self, limit: int) -> list[SessionHistorySummary]:
        with self._session_factory() as db_session:
            rows = db_session.scalars(
                select(SessionModel)
                .order_by(SessionModel.started_at.desc())
                .limit(max(1, limit))
            ).all()
            return [self._build_session_history_summary(row) for row in rows]

    async def get_session(self, session_id: str) -> SessionHistoryDetail | None:
        return await asyncio.to_thread(self._get_session_sync, session_id)

    def _get_session_sync(self, session_id: str) -> SessionHistoryDetail | None:
        with self._session_factory() as db_session:
            session_row = db_session.get(SessionModel, session_id)
            if session_row is None:
                return None

            transcript_rows = db_session.scalars(
                select(TranscriptSegmentModel)
                .where(TranscriptSegmentModel.session_id == session_id)
                .order_by(TranscriptSegmentModel.id.asc())
            ).all()
            feedback_rows = db_session.scalars(
                select(FeedbackEventModel)
                .where(FeedbackEventModel.session_id == session_id)
                .order_by(FeedbackEventModel.id.asc())
            ).all()
            return SessionHistoryDetail(
                summary=self._build_session_history_summary(session_row),
                transcript=[
                    SessionTranscriptSegmentRecord(
                        segment_id=row.segment_id,
                        text=row.text,
                        start_time_ms=row.start_time_ms,
                        end_time_ms=row.end_time_ms,
                        word_count=row.word_count,
                        created_at=row.created_at,
                    )
                    for row in transcript_rows
                ],
                feedback_events=[
                    SessionFeedbackEventRecord(
                        decision=row.decision,
                        reason=row.reason,
                        confidence=row.confidence,
                        observed_wpm=row.observed_wpm,
                        pace_band=row.pace_band,
                        total_words=row.total_words,
                        speaking_duration_ms=row.speaking_duration_ms,
                        created_at=row.created_at,
                    )
                    for row in feedback_rows
                ],
            )

    async def delete_session(self, session_id: str) -> bool:
        return await asyncio.to_thread(self._delete_session_sync, session_id)

    def _delete_session_sync(self, session_id: str) -> bool:
        with self._session_factory() as db_session, db_session.begin():
            session_row = db_session.get(SessionModel, session_id)
            if session_row is None:
                return False
            db_session.delete(session_row)
            return True

    def _build_session_history_summary(self, session_row: SessionModel) -> SessionHistorySummary:
        metrics = session_row.metrics
        return SessionHistorySummary(
            session_id=session_row.session_id,
            client=session_row.client,
            locale=session_row.locale,
            replay_mode=session_row.replay_mode,
            provider=session_row.provider,
            status=session_row.status,
            stop_reason=session_row.stop_reason,
            started_at=session_row.started_at,
            ended_at=session_row.ended_at,
            duration_ms=session_row.duration_ms,
            transcript_segments=session_row.transcript_segments,
            total_words=metrics.total_words if metrics is not None else 0,
            current_wpm=metrics.current_wpm if metrics is not None else None,
            average_wpm=metrics.average_wpm if metrics is not None else None,
            pace_band=metrics.pace_band if metrics is not None else "unknown",
            feedback_count=metrics.feedback_count if metrics is not None else 0,
            last_feedback_decision=metrics.last_feedback_decision if metrics is not None else None,
            last_feedback_reason=metrics.last_feedback_reason if metrics is not None else None,
            last_feedback_confidence=metrics.last_feedback_confidence if metrics is not None else None,
            final_transcript_text=session_row.final_transcript_text,
        )

    async def close(self) -> None:
        await asyncio.to_thread(self._engine.dispose)
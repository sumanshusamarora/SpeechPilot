from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import Protocol

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from speechpilot_contracts.events import SessionSummaryPayload

from app.domain.session import SessionContext
from app.domain.session_metrics import SessionMetricsSnapshot
from app.domain.transcript import TranscriptSegment
from app.persistence.db import build_sqlalchemy_url
from app.persistence.models import SessionMetricModel, SessionModel, TranscriptSegmentModel


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

    async def close_session(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
        *,
        status: str,
        stop_reason: str | None,
    ) -> None: ...

    async def close(self) -> None: ...


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
        with self._session_factory.begin() as db_session:
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
        with self._session_factory.begin() as db_session:
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
        with self._session_factory.begin() as db_session:
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
            metric_row.updated_at = _utc_now()

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
        with self._session_factory.begin() as db_session:
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

    async def close(self) -> None:
        await asyncio.to_thread(self._engine.dispose)
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import Protocol

from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from speechpilot_contracts.events import (
    SessionSummaryPayload,
    TranscriptFinalEvent,
    TranscriptPartialEvent,
)

from app.domain.session import SessionContext
from app.persistence.db import build_sqlalchemy_url
from app.persistence.models import SessionModel, TranscriptEventModel


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class SessionRepository(Protocol):
    async def open_session(self, session: SessionContext, provider_name: str) -> None: ...

    async def append_transcript_events(
        self,
        session: SessionContext,
        events: list[TranscriptPartialEvent | TranscriptFinalEvent],
        provider_name: str,
    ) -> None: ...

    async def close_session(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
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
                existing.started_at = session.started_at
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
                    started_at=session.started_at,
                    created_at=_utc_now(),
                    updated_at=_utc_now(),
                )
            )

    async def append_transcript_events(
        self,
        session: SessionContext,
        events: list[TranscriptPartialEvent | TranscriptFinalEvent],
        provider_name: str,
    ) -> None:
        if not events:
            return
        await asyncio.to_thread(self._append_transcript_events_sync, session, events, provider_name)

    def _append_transcript_events_sync(
        self,
        session: SessionContext,
        events: list[TranscriptPartialEvent | TranscriptFinalEvent],
        provider_name: str,
    ) -> None:
        with self._session_factory.begin() as db_session:
            session_row = db_session.get(SessionModel, session.session_id)
            if session_row is None:
                self._logger.warning("append_transcript_events called for unknown session_id=%s", session.session_id)
                return

            timestamp = _utc_now()
            source_mode = "replay" if session.replay_mode else "live"
            for event in events:
                if isinstance(event, TranscriptPartialEvent):
                    db_session.add(
                        TranscriptEventModel(
                            session_id=session.session_id,
                            event_type="partial",
                            sequence=event.payload.sequence,
                            utterance_id=None,
                            text=event.payload.text,
                            provider=provider_name,
                            source_mode=source_mode,
                            created_at=timestamp,
                        )
                    )
                    session_row.partial_transcript_text = event.payload.text
                else:
                    db_session.add(
                        TranscriptEventModel(
                            session_id=session.session_id,
                            event_type="final",
                            sequence=None,
                            utterance_id=event.payload.utteranceId,
                            text=event.payload.text,
                            provider=provider_name,
                            source_mode=source_mode,
                            created_at=timestamp,
                        )
                    )
                    session_row.partial_transcript_text = None

            session_row.updated_at = timestamp

    async def close_session(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
    ) -> None:
        await asyncio.to_thread(self._close_session_sync, session, summary)

    def _close_session_sync(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
    ) -> None:
        with self._session_factory.begin() as db_session:
            session_row = db_session.get(SessionModel, session.session_id)
            if session_row is None:
                self._logger.warning("close_session called for unknown session_id=%s", session.session_id)
                return

            session_row.status = "completed"
            session_row.ended_at = _utc_now()
            session_row.duration_ms = summary.durationMs
            session_row.transcript_segments = summary.transcriptSegments
            session_row.updated_at = _utc_now()

            final_segments = db_session.scalars(
                select(TranscriptEventModel.text)
                .where(TranscriptEventModel.session_id == session.session_id)
                .where(TranscriptEventModel.event_type == "final")
                .order_by(TranscriptEventModel.id.asc())
            ).all()
            session_row.final_transcript_text = " ".join(final_segments).strip() or None

    async def close(self) -> None:
        await asyncio.to_thread(self._engine.dispose)
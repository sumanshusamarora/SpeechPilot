from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Protocol

from speechpilot_contracts.events import SessionSummaryPayload

from app.domain.session import SessionContext


@dataclass(slots=True)
class SessionRecord:
    session_id: str
    client: str
    replay_mode: bool
    locale: str | None
    summary: SessionSummaryPayload | None = None


class SessionRepository(Protocol):
    async def open_session(self, session: SessionContext) -> None: ...

    async def close_session(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
    ) -> None: ...


class InMemorySessionRepository:
    def __init__(self, logger: logging.Logger) -> None:
        self._logger = logger
        self._records: dict[str, SessionRecord] = {}

    async def open_session(self, session: SessionContext) -> None:
        self._records[session.session_id] = SessionRecord(
            session_id=session.session_id,
            client=session.client,
            replay_mode=session.replay_mode,
            locale=session.locale,
        )

    async def close_session(
        self,
        session: SessionContext,
        summary: SessionSummaryPayload,
    ) -> None:
        record = self._records.get(session.session_id)
        if record is None:
            self._logger.warning("close_session called for unknown session_id=%s", session.session_id)
            return
        record.summary = summary
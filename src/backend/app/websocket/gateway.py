from __future__ import annotations

import logging

from pydantic import ValidationError
from starlette.websockets import WebSocket, WebSocketDisconnect

from speechpilot_contracts.events import (
    AudioChunkEvent,
    ClientEvent,
    DebugStateEvent,
    DebugStatePayload,
    ErrorEvent,
    ErrorPayload,
    SessionStartEvent,
    SessionStopEvent,
    SessionSummaryEvent,
    parse_client_event,
)

from app.domain.session import SessionContext
from app.persistence.repository import SessionRepository
from app.persistence.realtime_store.base import RealtimeStore
from app.providers.stt import SpeechToTextProvider
from app.services.analytics import AnalyticsService
from app.services.coaching import CoachingService
from app.websocket.manager import send_event


class RealtimeGateway:
    def __init__(
        self,
        logger: logging.Logger,
        realtime_store: RealtimeStore,
        session_repository: SessionRepository,
        stt_provider: SpeechToTextProvider,
        analytics_service: AnalyticsService,
        coaching_service: CoachingService,
    ) -> None:
        self._logger = logger
        self._realtime_store = realtime_store
        self._session_repository = session_repository
        self._stt_provider = stt_provider
        self._analytics_service = analytics_service
        self._coaching_service = coaching_service
        self._active_sessions: dict[str, SessionContext] = {}

    async def handle_connection(self, websocket: WebSocket) -> None:
        await websocket.accept()
        await send_event(
            websocket,
            DebugStateEvent(
                payload=DebugStatePayload(
                    scope="gateway",
                    state="connected",
                    detail="Realtime scaffold ready; replay boundary reserved.",
                )
            ),
        )

        try:
            while True:
                incoming = await websocket.receive_json()
                await self._dispatch(websocket, parse_client_event(incoming))
        except ValidationError as exc:
            await send_event(
                websocket,
                ErrorEvent(
                    payload=ErrorPayload(
                        code="invalid_event",
                        message="The incoming websocket event failed contract validation.",
                        retryable=True,
                        detail=str(exc),
                    )
                ),
            )
        except WebSocketDisconnect:
            self._logger.info("websocket disconnected")

    async def _dispatch(self, websocket: WebSocket, event: ClientEvent) -> None:
        if isinstance(event, SessionStartEvent):
            await self._handle_session_start(websocket, event)
            return
        if isinstance(event, AudioChunkEvent):
            await self._handle_audio_chunk(websocket, event)
            return
        if isinstance(event, SessionStopEvent):
            await self._handle_session_stop(websocket, event)
            return

    async def _handle_session_start(self, websocket: WebSocket, event: SessionStartEvent) -> None:
        session = SessionContext(
            session_id=event.payload.sessionId,
            client=event.payload.client,
            replay_mode=event.payload.replayMode,
            locale=event.payload.locale,
        )
        self._active_sessions[session.session_id] = session
        await self._session_repository.open_session(session)
        await self._analytics_service.on_session_start(session)
        await self._coaching_service.on_session_start(session)
        await self._realtime_store.put_state(
            session.session_id,
            {
                "status": "active",
                "client": session.client,
                "replayMode": session.replay_mode,
            },
        )
        await send_event(
            websocket,
            DebugStateEvent(
                payload=DebugStatePayload(
                    sessionId=session.session_id,
                    scope="session",
                    state="started",
                    detail="Placeholder realtime pipeline initialized.",
                )
            ),
        )

    async def _handle_audio_chunk(self, websocket: WebSocket, event: AudioChunkEvent) -> None:
        session = self._active_sessions.get(event.payload.sessionId)
        if session is None:
            await send_event(
                websocket,
                ErrorEvent(
                    payload=ErrorPayload(
                        code="unknown_session",
                        message="Audio chunk received for a session that has not been started.",
                        retryable=False,
                        detail=event.payload.sessionId,
                    )
                ),
            )
            return

        await self._analytics_service.on_audio_chunk(session, event.payload)
        stt_events = await self._stt_provider.on_audio_chunk(session, event.payload)
        coaching_events = await self._coaching_service.on_audio_chunk(session, event.payload)
        for server_event in [*stt_events, *coaching_events]:
            await send_event(websocket, server_event)
        await send_event(
            websocket,
            DebugStateEvent(
                payload=DebugStatePayload(
                    sessionId=session.session_id,
                    scope="audio",
                    state="chunk.received",
                    detail=f"sequence={event.payload.sequence} durationMs={event.payload.durationMs}",
                )
            ),
        )

    async def _handle_session_stop(self, websocket: WebSocket, event: SessionStopEvent) -> None:
        session = self._active_sessions.pop(event.payload.sessionId, None)
        if session is None:
            await send_event(
                websocket,
                ErrorEvent(
                    payload=ErrorPayload(
                        code="unknown_session",
                        message="Session stop received for a session that has not been started.",
                        retryable=False,
                        detail=event.payload.sessionId,
                    )
                ),
            )
            return

        await self._coaching_service.on_session_stop(session)
        summary = await self._analytics_service.build_summary(session)
        await self._session_repository.close_session(session, summary)
        await self._realtime_store.delete_state(session.session_id)
        await send_event(websocket, SessionSummaryEvent(payload=summary))
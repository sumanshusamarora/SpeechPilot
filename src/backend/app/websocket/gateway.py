from __future__ import annotations

import logging

from pydantic import ValidationError
from starlette.websockets import WebSocket, WebSocketDisconnect

from speechpilot_contracts.events import (
    AudioChunkEvent,
    DebugStateEvent,
    DebugStatePayload,
    ErrorEvent,
    ErrorPayload,
    SessionStartEvent,
    SessionStopEvent,
    parse_client_event,
)

from app.services.session_service import RealtimeSessionService
from app.websocket.manager import send_event


class RealtimeGateway:
    def __init__(
        self,
        logger: logging.Logger,
        session_service: RealtimeSessionService,
    ) -> None:
        self._logger = logger
        self._session_service = session_service

    async def handle_connection(self, websocket: WebSocket) -> None:
        await websocket.accept()
        current_session_id: str | None = None
        await send_event(
            websocket,
            DebugStateEvent(
                payload=DebugStatePayload(
                    lifecycle="connected",
                    activeProvider=None,
                    replayMode=None,
                    chunksReceived=0,
                    partialUpdates=0,
                    finalSegments=0,
                    totalWords=0,
                    wordsPerMinute=None,
                    paceBand="unknown",
                    detail="Realtime backend ready for live mic and replay sessions.",
                )
            ),
        )

        try:
            while True:
                incoming = await websocket.receive_json()
                try:
                    event = parse_client_event(incoming)
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
                    continue

                if isinstance(event, SessionStartEvent):
                    current_session_id = event.payload.sessionId
                elif isinstance(event, SessionStopEvent):
                    current_session_id = None

                for server_event in await self._dispatch(event):
                    await send_event(websocket, server_event)
        except WebSocketDisconnect:
            self._logger.info("websocket disconnected")
            if current_session_id is not None:
                await self._session_service.abandon_session(current_session_id, "websocket_disconnect")
        except Exception as exc:
            self._logger.exception("unexpected websocket failure")
            if current_session_id is not None:
                await self._session_service.abandon_session(current_session_id, "websocket_failure")
            await send_event(
                websocket,
                ErrorEvent(
                    payload=ErrorPayload(
                        code="websocket_failure",
                        message="The realtime websocket loop failed unexpectedly.",
                        retryable=True,
                        detail=str(exc),
                    )
                ),
            )

    async def _dispatch(self, event: SessionStartEvent | AudioChunkEvent | SessionStopEvent):
        if isinstance(event, SessionStartEvent):
            return await self._session_service.start_session(event.payload)
        if isinstance(event, AudioChunkEvent):
            return await self._session_service.process_audio_chunk(event.payload)
        return await self._session_service.stop_session(event.payload)
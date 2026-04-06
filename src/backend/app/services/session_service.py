from __future__ import annotations

import base64
import io
import logging
import wave
from dataclasses import dataclass
from uuid import uuid4

from speechpilot_contracts.events import (
    AudioChunkPayload,
    DebugStateEvent,
    DebugStatePayload,
    ErrorEvent,
    ErrorPayload,
    ServerEvent,
    SessionStartPayload,
    SessionStopPayload,
    SessionSummaryEvent,
    SessionSummaryPayload,
    TranscriptFinalEvent,
    TranscriptPartialEvent,
)

from app.domain.session import SessionContext
from app.persistence.repository import SessionRepository
from app.persistence.realtime_store.base import RealtimeStore
from app.providers.stt import SpeechToTextProvider, SpeechToTextProviderError
from app.services.analytics import AnalyticsService
from app.services.coaching import CoachingService

TranscriptEvent = TranscriptPartialEvent | TranscriptFinalEvent


@dataclass(slots=True)
class ReplayRunResult:
    session_id: str
    summary: SessionSummaryPayload
    transcript_events: list[TranscriptEvent]


class RealtimeSessionService:
    def __init__(
        self,
        logger: logging.Logger,
        realtime_store: RealtimeStore,
        session_repository: SessionRepository,
        stt_provider: SpeechToTextProvider,
        analytics_service: AnalyticsService,
        coaching_service: CoachingService,
        replay_chunk_duration_ms: int,
    ) -> None:
        self._logger = logger
        self._realtime_store = realtime_store
        self._session_repository = session_repository
        self._stt_provider = stt_provider
        self._analytics_service = analytics_service
        self._coaching_service = coaching_service
        self._replay_chunk_duration_ms = replay_chunk_duration_ms
        self._active_sessions: dict[str, SessionContext] = {}

    async def start_session(self, payload: SessionStartPayload) -> list[ServerEvent]:
        if payload.sessionId in self._active_sessions:
            return [
                self._error_event(
                    code="session_already_active",
                    message="A realtime session with this sessionId is already active.",
                    retryable=False,
                    detail=payload.sessionId,
                )
            ]

        session = SessionContext(
            session_id=payload.sessionId,
            client=payload.client,
            replay_mode=payload.replayMode,
            locale=payload.locale,
        )
        self._active_sessions[session.session_id] = session
        await self._session_repository.open_session(session, self._stt_provider.provider_name)
        await self._analytics_service.on_session_start(session)
        await self._coaching_service.on_session_start(session)
        await self._stt_provider.start_session(session)
        await self._realtime_store.put_state(
            session.session_id,
            {
                "status": "active",
                "client": session.client,
                "mode": "replay" if session.replay_mode else "live",
                "provider": self._stt_provider.provider_name,
            },
        )
        return [
            DebugStateEvent(
                payload=DebugStatePayload(
                    sessionId=session.session_id,
                    scope="session",
                    state="started",
                    detail=f"mode={'replay' if session.replay_mode else 'live'} provider={self._stt_provider.provider_name}",
                )
            )
        ]

    async def process_audio_chunk(self, payload: AudioChunkPayload) -> list[ServerEvent]:
        session = self._active_sessions.get(payload.sessionId)
        if session is None:
            return [
                self._error_event(
                    code="unknown_session",
                    message="Audio chunk received for a session that has not been started.",
                    retryable=False,
                    detail=payload.sessionId,
                )
            ]

        await self._analytics_service.on_audio_chunk(session, payload)
        try:
            provider_events = await self._stt_provider.on_audio_chunk(session, payload)
        except SpeechToTextProviderError as exc:
            self._logger.warning("stt provider rejected chunk for session_id=%s", session.session_id, exc_info=True)
            return [self._error_event(exc.code, exc.message, exc.retryable, exc.detail)]

        transcript_events = self._extract_transcript_events(provider_events)
        if transcript_events:
            await self._analytics_service.on_transcript_events(session, transcript_events)
            await self._session_repository.append_transcript_events(
                session,
                transcript_events,
                self._stt_provider.provider_name,
            )

        return provider_events

    async def stop_session(self, payload: SessionStopPayload) -> list[ServerEvent]:
        session = self._active_sessions.pop(payload.sessionId, None)
        if session is None:
            return [
                self._error_event(
                    code="unknown_session",
                    message="Session stop received for a session that has not been started.",
                    retryable=False,
                    detail=payload.sessionId,
                )
            ]

        provider_events: list[ServerEvent] = []
        try:
            provider_events = await self._stt_provider.finish_session(session)
        except SpeechToTextProviderError as exc:
            provider_events.append(self._error_event(exc.code, exc.message, exc.retryable, exc.detail))

        transcript_events = self._extract_transcript_events(provider_events)
        if transcript_events:
            await self._analytics_service.on_transcript_events(session, transcript_events)
            await self._session_repository.append_transcript_events(
                session,
                transcript_events,
                self._stt_provider.provider_name,
            )

        await self._coaching_service.on_session_stop(session)
        summary = await self._analytics_service.build_summary(session, self._stt_provider.provider_name)
        await self._session_repository.close_session(session, summary)
        await self._realtime_store.delete_state(session.session_id)
        return [*provider_events, SessionSummaryEvent(payload=summary)]

    async def run_replay(
        self,
        *,
        audio_bytes: bytes,
        file_name: str,
        locale: str | None,
    ) -> ReplayRunResult:
        session_id = f"replay-{uuid4().hex[:12]}"
        transcript_events: list[TranscriptEvent] = []

        await self.start_session(
            SessionStartPayload(
                sessionId=session_id,
                client="web-replay",
                replayMode=True,
                locale=locale,
            )
        )

        for payload in self._iter_replay_chunks(session_id=session_id, audio_bytes=audio_bytes):
            events = await self.process_audio_chunk(payload)
            transcript_events.extend(self._extract_transcript_events(events))

        stop_events = await self.stop_session(
            SessionStopPayload(sessionId=session_id, reason=f"replay:{file_name}")
        )
        transcript_events.extend(self._extract_transcript_events(stop_events))

        summary_event = next(
            event for event in stop_events if isinstance(event, SessionSummaryEvent)
        )
        return ReplayRunResult(
            session_id=session_id,
            summary=summary_event.payload,
            transcript_events=transcript_events,
        )

    def _iter_replay_chunks(self, *, session_id: str, audio_bytes: bytes):
        with wave.open(io.BytesIO(audio_bytes), "rb") as wav_file:
            if wav_file.getsampwidth() != 2:
                raise ValueError("Replay currently supports 16-bit PCM WAV files only.")

            sample_rate_hz = wav_file.getframerate()
            channel_count = wav_file.getnchannels()
            frames_per_chunk = max(1, int(sample_rate_hz * self._replay_chunk_duration_ms / 1000))
            frame_width = wav_file.getsampwidth() * channel_count
            sequence = 0

            while True:
                chunk_frames = wav_file.readframes(frames_per_chunk)
                if not chunk_frames:
                    break

                frame_count = len(chunk_frames) // frame_width
                duration_ms = int(round(frame_count * 1000 / sample_rate_hz))
                yield AudioChunkPayload(
                    sessionId=session_id,
                    sequence=sequence,
                    encoding="pcm16le",
                    sampleRateHz=sample_rate_hz,
                    channelCount=channel_count,
                    durationMs=duration_ms,
                    dataBase64=base64.b64encode(chunk_frames).decode("ascii"),
                )
                sequence += 1

    def _extract_transcript_events(self, events: list[ServerEvent]) -> list[TranscriptEvent]:
        transcript_events: list[TranscriptEvent] = []
        for event in events:
            if isinstance(event, (TranscriptPartialEvent, TranscriptFinalEvent)):
                transcript_events.append(event)
        return transcript_events

    def _error_event(
        self,
        code: str,
        message: str,
        retryable: bool,
        detail: str | None,
    ) -> ErrorEvent:
        return ErrorEvent(
            payload=ErrorPayload(
                code=code,
                message=message,
                retryable=retryable,
                detail=detail,
            )
        )
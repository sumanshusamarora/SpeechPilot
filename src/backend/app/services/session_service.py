from __future__ import annotations

import asyncio
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
    FeedbackUpdateEvent,
    FeedbackUpdatePayload,
    PaceUpdateEvent,
    PaceUpdatePayload,
    ServerEvent,
    SessionStartPayload,
    SessionStopPayload,
    SessionSummaryEvent,
    SessionSummaryPayload,
    TranscriptFinalEvent,
    TranscriptPartialEvent,
)

from app.domain.session import SessionContext
from app.domain.session_metrics import SessionMetricsSnapshot
from app.domain.transcript import TranscriptSegment
from app.domain.feedback import CoachingFeedback
from app.persistence.repository import SessionRepository
from app.persistence.realtime_store.base import RealtimeStore
from app.providers.stt import SpeechToTextProvider, SpeechToTextProviderError
from app.services.analytics import AnalyticsService
from app.services.coaching import CoachingService
from app.services.pace import PaceAnalyticsService, PaceSnapshot


@dataclass(slots=True)
class ReplayRunResult:
    session_id: str
    summary: SessionSummaryPayload
    events: list[ServerEvent]


@dataclass(slots=True)
class ActiveSessionRuntime:
    context: SessionContext
    lifecycle: str = "active"
    last_pace_snapshot: PaceSnapshot | None = None


class RealtimeSessionService:
    def __init__(
        self,
        logger: logging.Logger,
        realtime_store: RealtimeStore,
        session_repository: SessionRepository,
        stt_provider: SpeechToTextProvider,
        analytics_service: AnalyticsService,
        pace_service: PaceAnalyticsService,
        coaching_service: CoachingService,
        replay_chunk_duration_ms: int,
        replay_chunk_delay_ms: int,
        debug_snapshot_chunk_interval: int,
    ) -> None:
        self._logger = logger
        self._realtime_store = realtime_store
        self._session_repository = session_repository
        self._stt_provider = stt_provider
        self._analytics_service = analytics_service
        self._pace_service = pace_service
        self._coaching_service = coaching_service
        self._replay_chunk_duration_ms = replay_chunk_duration_ms
        self._replay_chunk_delay_ms = replay_chunk_delay_ms
        self._debug_snapshot_chunk_interval = debug_snapshot_chunk_interval
        self._active_sessions: dict[str, ActiveSessionRuntime] = {}

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
        runtime = ActiveSessionRuntime(context=session)
        self._active_sessions[session.session_id] = runtime
        await self._session_repository.open_session(session, self._stt_provider.provider_name)
        await self._analytics_service.on_session_start(session)
        await self._pace_service.on_session_start(session.session_id)
        await self._coaching_service.on_session_start(session)
        await self._stt_provider.start_session(session)
        metrics = await self._current_metrics(runtime)
        await self._session_repository.upsert_session_metrics(session, metrics)
        debug_event = await self._build_debug_event(
            runtime,
            lifecycle="active",
            detail=f"session started mode={'replay' if session.replay_mode else 'live'}",
        )
        await self._sync_realtime_state(session, debug_event.payload)
        return [debug_event]

    async def process_audio_chunk(self, payload: AudioChunkPayload) -> list[ServerEvent]:
        runtime = self._active_sessions.get(payload.sessionId)
        if runtime is None:
            return [
                self._error_event(
                    code="unknown_session",
                    message="Audio chunk received for a session that has not been started.",
                    retryable=False,
                    detail=payload.sessionId,
                )
            ]

        session = runtime.context
        await self._analytics_service.on_audio_chunk(session, payload)
        try:
            provider_events = await self._stt_provider.on_audio_chunk(session, payload)
        except SpeechToTextProviderError as exc:
            self._logger.warning("stt provider rejected chunk for session_id=%s", session.session_id, exc_info=True)
            return [self._error_event(exc.code, exc.message, exc.retryable, exc.detail)]

        return await self._finalize_provider_events(runtime, provider_events)

    async def stop_session(self, payload: SessionStopPayload) -> list[ServerEvent]:
        return await self._complete_session(
            session_id=payload.sessionId,
            reason=payload.reason,
            status="completed",
            emit_summary=True,
        )

    async def abandon_session(self, session_id: str, reason: str) -> None:
        await self._complete_session(
            session_id=session_id,
            reason=reason,
            status="aborted",
            emit_summary=False,
        )

    async def _complete_session(
        self,
        *,
        session_id: str,
        reason: str,
        status: str,
        emit_summary: bool,
    ) -> list[ServerEvent]:
        runtime = self._active_sessions.pop(session_id, None)
        if runtime is None:
            return [
                self._error_event(
                    code="unknown_session",
                    message="Session stop received for a session that has not been started.",
                    retryable=False,
                    detail=session_id,
                )
            ]

        session = runtime.context
        runtime.lifecycle = status

        provider_events: list[ServerEvent] = []
        try:
            provider_events = await self._stt_provider.finish_session(session)
        except SpeechToTextProviderError as exc:
            provider_events.append(self._error_event(exc.code, exc.message, exc.retryable, exc.detail))

        emitted_events = await self._finalize_provider_events(runtime, provider_events, emit_debug=False)

        analytics_snapshot = self._analytics_service.get_snapshot(session.session_id)
        final_pace_snapshot = await self._pace_service.end_session(
            session_id=session.session_id,
            session_duration_ms=analytics_snapshot.audio_duration_ms,
        )
        runtime.last_pace_snapshot = final_pace_snapshot
        metrics = self._build_metrics_snapshot(session.session_id, final_pace_snapshot)
        await self._session_repository.upsert_session_metrics(session, metrics)
        await self._coaching_service.on_session_stop(session)
        debug_event = await self._build_debug_event(
            runtime,
            lifecycle=status,
            detail=reason,
        )
        await self._sync_realtime_state(session, debug_event.payload)
        summary = await self._analytics_service.build_summary(
            session,
            self._stt_provider.provider_name,
            final_pace_snapshot,
        )
        await self._session_repository.close_session(
            session,
            summary,
            status=status,
            stop_reason=reason,
        )
        await self._realtime_store.delete_state(session.session_id)
        if emit_summary:
            return [*emitted_events, debug_event, SessionSummaryEvent(payload=summary)]
        return []

    async def run_replay(
        self,
        *,
        audio_bytes: bytes,
        file_name: str,
        locale: str | None,
    ) -> ReplayRunResult:
        session_id = f"replay-{uuid4().hex[:12]}"
        emitted_events: list[ServerEvent] = []

        emitted_events.extend(
            await self.start_session(
                SessionStartPayload(
                    sessionId=session_id,
                    client="web-replay",
                    replayMode=True,
                    locale=locale,
                )
            )
        )

        for payload in self._iter_replay_chunks(session_id=session_id, audio_bytes=audio_bytes):
            events = await self.process_audio_chunk(payload)
            emitted_events.extend(events)
            if self._replay_chunk_delay_ms > 0:
                await asyncio.sleep(self._replay_chunk_delay_ms / 1000)

        stop_events = await self.stop_session(
            SessionStopPayload(sessionId=session_id, reason=f"replay:{file_name}")
        )
        emitted_events.extend(stop_events)

        summary_event = next(
            event for event in stop_events if isinstance(event, SessionSummaryEvent)
        )
        return ReplayRunResult(
            session_id=session_id,
            summary=summary_event.payload,
            events=emitted_events,
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

    async def _finalize_provider_events(
        self,
        runtime: ActiveSessionRuntime,
        provider_events: list[ServerEvent],
        *,
        force_debug: bool = False,
        emit_debug: bool = True,
    ) -> list[ServerEvent]:
        session = runtime.context
        emitted_events = list(provider_events)
        final_segments: list[TranscriptSegment] = []

        for event in provider_events:
            if isinstance(event, TranscriptPartialEvent):
                await self._analytics_service.on_partial_update(session)
            elif isinstance(event, TranscriptFinalEvent):
                segment = TranscriptSegment(
                    segment_id=event.payload.segment.id,
                    text=event.payload.segment.text,
                    start_time_ms=event.payload.segment.startTimeMs,
                    end_time_ms=event.payload.segment.endTimeMs,
                    word_count=event.payload.segment.wordCount,
                )
                final_segments.append(segment)
                await self._analytics_service.on_final_segment(session, segment)

        if final_segments:
            await self._session_repository.append_transcript_segments(
                session,
                final_segments,
                self._stt_provider.provider_name,
            )
            for segment in final_segments:
                analytics_snapshot = self._analytics_service.get_snapshot(session.session_id)
                pace_snapshot = await self._pace_service.on_final_segment(
                    session_id=session.session_id,
                    segment=segment,
                    session_duration_ms=analytics_snapshot.audio_duration_ms,
                )
                runtime.last_pace_snapshot = pace_snapshot
                if pace_snapshot.words_per_minute is not None:
                    emitted_events.append(
                        PaceUpdateEvent(
                            payload=PaceUpdatePayload(
                                sessionId=session.session_id,
                                wordsPerMinute=round(pace_snapshot.words_per_minute, 1),
                                band=pace_snapshot.band,
                                source="transcript",
                                totalWords=pace_snapshot.total_words,
                                speakingDurationMs=pace_snapshot.speaking_duration_ms,
                                silenceDurationMs=pace_snapshot.silence_duration_ms,
                                windowDurationMs=pace_snapshot.window_duration_ms,
                            )
                        )
                    )
                    feedback = await self._coaching_service.on_pace_update(session, pace_snapshot)
                    if feedback is not None:
                        emitted_events.append(self._build_feedback_event(feedback))
                        await self._session_repository.append_feedback_event(session, feedback)

        metrics = await self._current_metrics(runtime)
        await self._session_repository.upsert_session_metrics(session, metrics)

        if emit_debug and (force_debug or self._should_emit_debug(metrics, provider_events)):
            debug_event = await self._build_debug_event(runtime, lifecycle=runtime.lifecycle)
            emitted_events.append(debug_event)
            await self._sync_realtime_state(session, debug_event.payload)

        return emitted_events

    async def _current_metrics(self, runtime: ActiveSessionRuntime) -> SessionMetricsSnapshot:
        analytics_snapshot = self._analytics_service.get_snapshot(runtime.context.session_id)
        current_pace_snapshot = runtime.last_pace_snapshot
        if current_pace_snapshot is None:
            current_pace_snapshot = await self._pace_service.current_snapshot(
                session_id=runtime.context.session_id,
                session_duration_ms=analytics_snapshot.audio_duration_ms,
            )
        return self._build_metrics_snapshot(runtime.context.session_id, current_pace_snapshot)

    def _build_metrics_snapshot(
        self,
        session_id: str,
        pace_snapshot: PaceSnapshot,
    ) -> SessionMetricsSnapshot:
        analytics_snapshot = self._analytics_service.get_snapshot(session_id)
        coaching_snapshot = self._coaching_service.current_snapshot(session_id)
        return SessionMetricsSnapshot(
            chunks_received=analytics_snapshot.chunk_count,
            partial_updates=analytics_snapshot.partial_updates,
            final_segments=analytics_snapshot.final_segments,
            total_words=pace_snapshot.total_words,
            words_per_minute=round(pace_snapshot.words_per_minute, 1) if pace_snapshot.words_per_minute is not None else None,
            average_wpm=round(pace_snapshot.average_wpm, 1) if pace_snapshot.average_wpm is not None else None,
            speaking_duration_ms=pace_snapshot.speaking_duration_ms,
            silence_duration_ms=pace_snapshot.silence_duration_ms,
            pace_band=pace_snapshot.band,
            feedback_count=coaching_snapshot.feedback_count,
            last_feedback_decision=coaching_snapshot.last_feedback_decision,
            last_feedback_reason=coaching_snapshot.last_feedback_reason,
            last_feedback_confidence=coaching_snapshot.last_feedback_confidence,
        )

    async def _build_debug_event(
        self,
        runtime: ActiveSessionRuntime,
        *,
        lifecycle: str,
        detail: str | None = None,
    ) -> DebugStateEvent:
        metrics = await self._current_metrics(runtime)
        return DebugStateEvent(
            payload=DebugStatePayload(
                sessionId=runtime.context.session_id,
                lifecycle=lifecycle,
                activeProvider=self._stt_provider.provider_name,
                replayMode=runtime.context.replay_mode,
                chunksReceived=metrics.chunks_received,
                partialUpdates=metrics.partial_updates,
                finalSegments=metrics.final_segments,
                totalWords=metrics.total_words,
                wordsPerMinute=metrics.words_per_minute,
                paceBand=metrics.pace_band,
                feedbackCount=metrics.feedback_count,
                lastFeedbackDecision=metrics.last_feedback_decision,
                lastFeedbackReason=metrics.last_feedback_reason,
                lastFeedbackConfidence=metrics.last_feedback_confidence,
                detail=detail,
            )
        )

    async def _sync_realtime_state(self, session: SessionContext, debug_payload: DebugStatePayload) -> None:
        await self._realtime_store.put_state(
            session.session_id,
            {
                "sessionId": debug_payload.sessionId,
                "lifecycle": debug_payload.lifecycle,
                "activeProvider": debug_payload.activeProvider,
                "replayMode": debug_payload.replayMode,
                "chunksReceived": debug_payload.chunksReceived,
                "partialUpdates": debug_payload.partialUpdates,
                "finalSegments": debug_payload.finalSegments,
                "totalWords": debug_payload.totalWords,
                "wordsPerMinute": debug_payload.wordsPerMinute,
                "paceBand": debug_payload.paceBand,
                "feedbackCount": debug_payload.feedbackCount,
                "lastFeedbackDecision": debug_payload.lastFeedbackDecision,
                "lastFeedbackReason": debug_payload.lastFeedbackReason,
                "lastFeedbackConfidence": debug_payload.lastFeedbackConfidence,
                "detail": debug_payload.detail,
            },
        )

    def _build_feedback_event(self, feedback: CoachingFeedback) -> FeedbackUpdateEvent:
        return FeedbackUpdateEvent(
            payload=FeedbackUpdatePayload(
                sessionId=feedback.session_id,
                decision=feedback.decision,
                reason=feedback.reason,
                confidence=feedback.confidence,
            )
        )

    def _should_emit_debug(
        self,
        metrics: SessionMetricsSnapshot,
        provider_events: list[ServerEvent],
    ) -> bool:
        if metrics.chunks_received <= 1:
            return True
        if metrics.chunks_received % self._debug_snapshot_chunk_interval == 0:
            return True
        return any(isinstance(event, (TranscriptPartialEvent, TranscriptFinalEvent)) for event in provider_events)

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
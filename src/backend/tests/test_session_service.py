from __future__ import annotations

import io
import logging
import wave

import pytest

from speechpilot_contracts.events import (
    AudioChunkPayload,
    SessionSummaryPayload,
    TranscriptFinalEvent,
    TranscriptFinalPayload,
    TranscriptPartialEvent,
    TranscriptPartialPayload,
)

from app.persistence.realtime_store.managed import ManagedRealtimeStorePlaceholder
from app.services.analytics import AnalyticsService
from app.services.coaching import CoachingService
from app.services.session_service import RealtimeSessionService


class FakeSessionRepository:
    def __init__(self) -> None:
        self.opened_sessions: list[tuple[str, str]] = []
        self.persisted_events: list[str] = []
        self.closed_summaries: list[SessionSummaryPayload] = []

    async def open_session(self, session, provider_name: str) -> None:
        self.opened_sessions.append((session.session_id, provider_name))

    async def append_transcript_events(self, session, events, provider_name: str) -> None:
        del provider_name
        self.persisted_events.extend(event.payload.text for event in events)

    async def close_session(self, session, summary: SessionSummaryPayload) -> None:
        del session
        self.closed_summaries.append(summary)

    async def close(self) -> None:
        return None


class FakeSpeechToTextProvider:
    provider_name = "fake-stt"

    def __init__(self) -> None:
        self._sequence = 0

    async def start_session(self, session) -> None:
        del session
        self._sequence = 0

    async def on_audio_chunk(self, session, chunk: AudioChunkPayload):
        del session
        self._sequence += 1
        return [
            TranscriptPartialEvent(
                payload=TranscriptPartialPayload(
                    sessionId=chunk.sessionId,
                    text=f"partial-{self._sequence}",
                    sequence=self._sequence,
                )
            )
        ]

    async def finish_session(self, session):
        return [
            TranscriptFinalEvent(
                payload=TranscriptFinalPayload(
                    sessionId=session.session_id,
                    text="final transcript",
                    utteranceId=f"{session.session_id}:1",
                )
            )
        ]


def build_pcm16_wav_bytes(*, sample_rate_hz: int, duration_ms: int) -> bytes:
    frame_count = int(sample_rate_hz * duration_ms / 1000)
    samples = [0] * frame_count

    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate_hz)
        wav_file.writeframes(b"".join(sample.to_bytes(2, byteorder="little", signed=True) for sample in samples))
    return buffer.getvalue()


@pytest.mark.anyio
async def test_run_replay_persists_summary_and_transcript_events() -> None:
    logger = logging.getLogger("speechpilot.test")
    repository = FakeSessionRepository()
    service = RealtimeSessionService(
        logger=logger,
        realtime_store=ManagedRealtimeStorePlaceholder(logger),
        session_repository=repository,
        stt_provider=FakeSpeechToTextProvider(),
        analytics_service=AnalyticsService(),
        coaching_service=CoachingService(),
        replay_chunk_duration_ms=250,
    )

    result = await service.run_replay(
        audio_bytes=build_pcm16_wav_bytes(sample_rate_hz=16000, duration_ms=600),
        file_name="fixture.wav",
        locale="en",
    )

    assert result.session_id.startswith("replay-")
    assert result.summary.transcriptSegments == 1
    assert result.summary.durationMs > 0
    assert [event.payload.text for event in result.transcript_events] == [
        "partial-1",
        "partial-2",
        "partial-3",
        "final transcript",
    ]
    assert repository.opened_sessions == [(result.session_id, "fake-stt")]
    assert repository.persisted_events == [
        "partial-1",
        "partial-2",
        "partial-3",
        "final transcript",
    ]
    assert repository.closed_summaries[0].sessionId == result.session_id
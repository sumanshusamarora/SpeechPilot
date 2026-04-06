from __future__ import annotations

import io
import logging
import wave

import pytest

from speechpilot_contracts.events import (
    AudioChunkPayload,
    SessionSummaryPayload,
    TranscriptSegmentPayload,
    TranscriptFinalEvent,
    TranscriptFinalPayload,
    TranscriptPartialEvent,
    TranscriptPartialPayload,
)

from app.domain.session_metrics import SessionMetricsSnapshot
from app.domain.transcript import TranscriptSegment
from app.persistence.realtime_store.managed import ManagedRealtimeStorePlaceholder
from app.services.analytics import AnalyticsService
from app.services.coaching import CoachingService
from app.services.pace import PaceAnalyticsService
from app.services.session_service import RealtimeSessionService


class FakeSessionRepository:
    def __init__(self) -> None:
        self.opened_sessions: list[tuple[str, str]] = []
        self.persisted_segments: list[TranscriptSegment] = []
        self.metric_snapshots: list[SessionMetricsSnapshot] = []
        self.closed_summaries: list[tuple[SessionSummaryPayload, str, str | None]] = []

    async def open_session(self, session, provider_name: str) -> None:
        self.opened_sessions.append((session.session_id, provider_name))

    async def append_transcript_segments(self, session, segments, provider_name: str) -> None:
        del session
        del provider_name
        self.persisted_segments.extend(segments)

    async def upsert_session_metrics(self, session, metrics: SessionMetricsSnapshot) -> None:
        del session
        self.metric_snapshots.append(metrics)

    async def close_session(self, session, summary: SessionSummaryPayload, *, status: str, stop_reason: str | None) -> None:
        del session
        self.closed_summaries.append((summary, status, stop_reason))

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
                    segment=TranscriptSegmentPayload(
                        id=f"{session.session_id}:1",
                        text="final transcript",
                        startTimeMs=0,
                        endTimeMs=600,
                        wordCount=2,
                    ),
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
        pace_service=PaceAnalyticsService(
            window_ms=30000,
            smoothing_factor=0.35,
            slow_threshold_wpm=110,
            fast_threshold_wpm=160,
        ),
        coaching_service=CoachingService(),
        replay_chunk_duration_ms=250,
        replay_chunk_delay_ms=0,
        debug_snapshot_chunk_interval=4,
    )

    result = await service.run_replay(
        audio_bytes=build_pcm16_wav_bytes(sample_rate_hz=16000, duration_ms=600),
        file_name="fixture.wav",
        locale="en",
    )

    assert result.session_id.startswith("replay-")
    assert result.summary.transcriptSegments == 1
    assert result.summary.totalWords == 2
    assert result.summary.durationMs > 0
    transcript_events = [event for event in result.events if event.type in {"transcript.partial", "transcript.final"}]
    assert [
        event.payload.text if event.type == "transcript.partial" else event.payload.segment.text
        for event in transcript_events
    ] == [
        "partial-1",
        "partial-2",
        "partial-3",
        "final transcript",
    ]
    assert any(event.type == "pace.update" for event in result.events)
    assert any(event.type == "debug.state" for event in result.events)
    assert repository.opened_sessions == [(result.session_id, "fake-stt")]
    assert [segment.text for segment in repository.persisted_segments] == ["final transcript"]
    assert repository.metric_snapshots[-1].partial_updates == 3
    assert repository.metric_snapshots[-1].final_segments == 1
    assert repository.metric_snapshots[-1].total_words == 2
    assert repository.closed_summaries[0][0].sessionId == result.session_id
    assert repository.closed_summaries[0][1] == "completed"
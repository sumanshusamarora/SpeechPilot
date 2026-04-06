from __future__ import annotations

from dataclasses import dataclass, field

from speechpilot_contracts.events import (
    AudioChunkPayload,
    SessionSummaryPayload,
    TranscriptFinalEvent,
    TranscriptPartialEvent,
)

from app.domain.session import SessionContext


@dataclass(slots=True)
class AnalyticsSnapshot:
    chunk_count: int = 0
    audio_duration_ms: int = 0
    final_segments: list[str] = field(default_factory=list)


class AnalyticsService:
    def __init__(self) -> None:
        self._snapshots: dict[str, AnalyticsSnapshot] = {}

    async def on_session_start(self, session: SessionContext) -> None:
        self._snapshots[session.session_id] = AnalyticsSnapshot()

    async def on_audio_chunk(self, session: SessionContext, chunk: AudioChunkPayload) -> None:
        snapshot = self._snapshots.setdefault(session.session_id, AnalyticsSnapshot())
        snapshot.chunk_count += 1
        snapshot.audio_duration_ms += chunk.durationMs

    async def on_transcript_events(
        self,
        session: SessionContext,
        events: list[TranscriptPartialEvent | TranscriptFinalEvent],
    ) -> None:
        snapshot = self._snapshots.setdefault(session.session_id, AnalyticsSnapshot())
        for event in events:
            if isinstance(event, TranscriptFinalEvent):
                snapshot.final_segments.append(event.payload.text)

    async def build_summary(self, session: SessionContext, provider_name: str) -> SessionSummaryPayload:
        snapshot = self._snapshots.pop(session.session_id, AnalyticsSnapshot())
        mode_label = "replay" if session.replay_mode else "live"
        return SessionSummaryPayload(
            sessionId=session.session_id,
            durationMs=snapshot.audio_duration_ms,
            transcriptSegments=len(snapshot.final_segments),
            averageWpm=None,
            notes=[
                f"provider={provider_name}",
                f"mode={mode_label}",
            ],
        )
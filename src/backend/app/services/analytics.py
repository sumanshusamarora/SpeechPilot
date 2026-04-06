from __future__ import annotations

from dataclasses import dataclass

from speechpilot_contracts.events import AudioChunkPayload, SessionSummaryPayload

from app.domain.session import SessionContext
from app.domain.transcript import TranscriptSegment
from app.services.pace.service import PaceSnapshot


@dataclass(slots=True)
class AnalyticsSnapshot:
    chunk_count: int = 0
    audio_duration_ms: int = 0
    partial_updates: int = 0
    final_segments: int = 0


class AnalyticsService:
    def __init__(self) -> None:
        self._snapshots: dict[str, AnalyticsSnapshot] = {}

    async def on_session_start(self, session: SessionContext) -> None:
        self._snapshots[session.session_id] = AnalyticsSnapshot()

    async def on_audio_chunk(self, session: SessionContext, chunk: AudioChunkPayload) -> None:
        snapshot = self._snapshots.setdefault(session.session_id, AnalyticsSnapshot())
        snapshot.chunk_count += 1
        snapshot.audio_duration_ms += chunk.durationMs

    async def on_partial_update(self, session: SessionContext) -> None:
        snapshot = self._snapshots.setdefault(session.session_id, AnalyticsSnapshot())
        snapshot.partial_updates += 1

    async def on_final_segment(self, session: SessionContext, _segment: TranscriptSegment) -> None:
        snapshot = self._snapshots.setdefault(session.session_id, AnalyticsSnapshot())
        snapshot.final_segments += 1

    def get_snapshot(self, session_id: str) -> AnalyticsSnapshot:
        snapshot = self._snapshots.get(session_id)
        if snapshot is None:
            return AnalyticsSnapshot()
        return AnalyticsSnapshot(
            chunk_count=snapshot.chunk_count,
            audio_duration_ms=snapshot.audio_duration_ms,
            partial_updates=snapshot.partial_updates,
            final_segments=snapshot.final_segments,
        )

    async def build_summary(
        self,
        session: SessionContext,
        provider_name: str,
        pace_snapshot: PaceSnapshot,
    ) -> SessionSummaryPayload:
        snapshot = self._snapshots.pop(session.session_id, AnalyticsSnapshot())
        mode_label = "replay" if session.replay_mode else "live"
        return SessionSummaryPayload(
            sessionId=session.session_id,
            durationMs=snapshot.audio_duration_ms,
            transcriptSegments=snapshot.final_segments,
            totalWords=pace_snapshot.total_words,
            averageWpm=pace_snapshot.average_wpm,
            speakingDurationMs=pace_snapshot.speaking_duration_ms,
            silenceDurationMs=pace_snapshot.silence_duration_ms,
            paceBand=pace_snapshot.band,
            notes=[
                f"provider={provider_name}",
                f"mode={mode_label}",
                f"chunks={snapshot.chunk_count}",
            ],
        )
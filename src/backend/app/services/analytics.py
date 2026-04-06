from __future__ import annotations

from dataclasses import dataclass

from speechpilot_contracts.events import AudioChunkPayload, SessionSummaryPayload

from app.domain.session import SessionContext


@dataclass(slots=True)
class AnalyticsSnapshot:
    chunk_count: int = 0
    audio_duration_ms: int = 0


class AnalyticsService:
    def __init__(self) -> None:
        self._snapshots: dict[str, AnalyticsSnapshot] = {}

    async def on_session_start(self, session: SessionContext) -> None:
        self._snapshots[session.session_id] = AnalyticsSnapshot()

    async def on_audio_chunk(self, session: SessionContext, chunk: AudioChunkPayload) -> None:
        snapshot = self._snapshots.setdefault(session.session_id, AnalyticsSnapshot())
        snapshot.chunk_count += 1
        snapshot.audio_duration_ms += chunk.durationMs

    async def build_summary(self, session: SessionContext) -> SessionSummaryPayload:
        snapshot = self._snapshots.pop(session.session_id, AnalyticsSnapshot())
        return SessionSummaryPayload(
            sessionId=session.session_id,
            durationMs=snapshot.audio_duration_ms,
            transcriptSegments=0,
            averageWpm=None,
            notes=[
                "Realtime session scaffold only; analytics are not implemented yet.",
                "Replay mode boundary is reserved for recorded-audio testing.",
            ],
        )
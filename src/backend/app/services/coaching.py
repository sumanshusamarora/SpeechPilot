from __future__ import annotations

from speechpilot_contracts.events import AudioChunkPayload, ServerEvent

from app.domain.session import SessionContext


class CoachingService:
    async def on_session_start(self, _session: SessionContext) -> None:
        return None

    async def on_audio_chunk(
        self,
        _session: SessionContext,
        _chunk: AudioChunkPayload,
    ) -> list[ServerEvent]:
        return []

    async def on_session_stop(self, _session: SessionContext) -> None:
        return None
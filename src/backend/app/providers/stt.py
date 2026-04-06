from __future__ import annotations

from typing import Protocol

from speechpilot_contracts.events import AudioChunkPayload, ServerEvent

from app.domain.session import SessionContext


class SpeechToTextProvider(Protocol):
    async def on_audio_chunk(
        self,
        session: SessionContext,
        chunk: AudioChunkPayload,
    ) -> list[ServerEvent]: ...


class PlaceholderSpeechToTextProvider:
    async def on_audio_chunk(
        self,
        _session: SessionContext,
        _chunk: AudioChunkPayload,
    ) -> list[ServerEvent]:
        return []
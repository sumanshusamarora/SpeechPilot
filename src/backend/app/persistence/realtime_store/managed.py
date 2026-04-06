from __future__ import annotations

import logging
from typing import Any


class ManagedRealtimeStorePlaceholder:
    def __init__(self, logger: logging.Logger) -> None:
        self._logger = logger

    async def put_state(self, session_id: str, _state: dict[str, Any]) -> None:
        self._logger.info(
            "managed realtime store placeholder received session_id=%s",
            session_id,
        )

    async def get_state(self, session_id: str) -> dict[str, Any] | None:
        self._logger.info(
            "managed realtime store placeholder lookup for session_id=%s",
            session_id,
        )
        return None

    async def delete_state(self, session_id: str) -> None:
        self._logger.info(
            "managed realtime store placeholder delete for session_id=%s",
            session_id,
        )

    async def close(self) -> None:
        return None
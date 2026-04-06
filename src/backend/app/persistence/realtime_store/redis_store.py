from __future__ import annotations

import json
import logging
from typing import Any

from redis.asyncio import Redis
from redis.exceptions import RedisError


class RedisRealtimeStore:
    def __init__(self, redis_url: str, logger: logging.Logger) -> None:
        self._client = Redis.from_url(redis_url, decode_responses=True)
        self._logger = logger

    def _key(self, session_id: str) -> str:
        return f"speechpilot:session:{session_id}"

    async def put_state(self, session_id: str, state: dict[str, Any]) -> None:
        try:
            await self._client.set(self._key(session_id), json.dumps(state))
        except RedisError:
            self._logger.warning("redis put_state failed for session_id=%s", session_id, exc_info=True)

    async def get_state(self, session_id: str) -> dict[str, Any] | None:
        try:
            value = await self._client.get(self._key(session_id))
        except RedisError:
            self._logger.warning("redis get_state failed for session_id=%s", session_id, exc_info=True)
            return None
        if value is None:
            return None
        try:
            return json.loads(value)
        except ValueError:
            self._logger.warning("redis state payload was not valid JSON for session_id=%s", session_id)
            return None

    async def delete_state(self, session_id: str) -> None:
        try:
            await self._client.delete(self._key(session_id))
        except RedisError:
            self._logger.warning("redis delete_state failed for session_id=%s", session_id, exc_info=True)

    async def close(self) -> None:
        await self._client.aclose()
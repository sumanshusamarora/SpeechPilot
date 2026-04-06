from __future__ import annotations

import logging

from app.config.settings import RealtimeStoreBackend, Settings
from app.persistence.realtime_store.base import RealtimeStore
from app.persistence.realtime_store.managed import ManagedRealtimeStorePlaceholder
from app.persistence.realtime_store.redis_store import RedisRealtimeStore


def build_realtime_store(settings: Settings, logger: logging.Logger) -> RealtimeStore:
    if settings.realtime_store_backend == RealtimeStoreBackend.MANAGED:
        return ManagedRealtimeStorePlaceholder(logger=logger)
    return RedisRealtimeStore(redis_url=settings.redis_url, logger=logger)
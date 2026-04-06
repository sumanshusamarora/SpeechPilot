from __future__ import annotations

from enum import StrEnum
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Environment(StrEnum):
    LOCAL = "local"
    DEVELOPMENT = "development"
    TEST = "test"
    STAGING = "staging"
    PRODUCTION = "production"


class RealtimeStoreBackend(StrEnum):
    REDIS = "redis"
    MANAGED = "managed"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="SPEECHPILOT_",
        extra="ignore",
    )

    app_name: str = "SpeechPilot Backend"
    environment: Environment = Environment.LOCAL
    log_level: str = "INFO"
    api_prefix: str = "/api"
    websocket_path: str = "/ws"
    protocol_version: str = "1.0"
    postgres_url: str = "postgresql://speechpilot:speechpilot@postgres:5432/speechpilot"
    redis_url: str = "redis://redis:6379/0"
    realtime_store_backend: RealtimeStoreBackend = RealtimeStoreBackend.REDIS
    replay_enabled: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
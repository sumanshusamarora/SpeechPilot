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
    replay_enabled: bool = True
    stt_provider_name: str = "faster_whisper"
    stt_model_size: str = "tiny.en"
    stt_language: str = "en"
    stt_device: str = "cpu"
    stt_compute_type: str = "int8"
    stt_target_sample_rate_hz: int = 16000
    stt_partial_interval_ms: int = 1600
    stt_silence_duration_ms: int = 900
    stt_min_utterance_ms: int = 900
    stt_speech_threshold: float = 0.015
    replay_chunk_duration_ms: int = 500
    replay_chunk_delay_ms: int = 0
    pace_window_ms: int = 30000
    pace_smoothing_factor: float = 0.35
    pace_slow_threshold_wpm: float = 100.0
    pace_fast_threshold_wpm: float = 125.0
    coaching_sustain_segments: int = 2
    coaching_cooldown_ms: int = 12000
    coaching_min_words_for_feedback: int = 8
    debug_snapshot_chunk_interval: int = 10



@lru_cache
def get_settings() -> Settings:
    return Settings()
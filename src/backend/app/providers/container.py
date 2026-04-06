from __future__ import annotations

import logging
from dataclasses import dataclass

from app.config.settings import Settings, get_settings
from app.observability.log_config import configure_logging
from app.persistence.repository import InMemorySessionRepository, SessionRepository
from app.persistence.realtime_store.base import RealtimeStore
from app.persistence.realtime_store.factory import build_realtime_store
from app.providers.stt import PlaceholderSpeechToTextProvider, SpeechToTextProvider
from app.services.analytics import AnalyticsService
from app.services.coaching import CoachingService
from app.websocket.gateway import RealtimeGateway


@dataclass(slots=True)
class ServiceContainer:
    settings: Settings
    logger: logging.Logger
    realtime_store: RealtimeStore
    session_repository: SessionRepository
    stt_provider: SpeechToTextProvider
    analytics_service: AnalyticsService
    coaching_service: CoachingService
    gateway: RealtimeGateway

    async def shutdown(self) -> None:
        await self.realtime_store.close()


def build_container(settings: Settings | None = None) -> ServiceContainer:
    resolved_settings = settings or get_settings()
    logger = configure_logging(resolved_settings.log_level)
    realtime_store = build_realtime_store(resolved_settings, logger)
    session_repository = InMemorySessionRepository(logger)
    stt_provider = PlaceholderSpeechToTextProvider()
    analytics_service = AnalyticsService()
    coaching_service = CoachingService()
    gateway = RealtimeGateway(
        logger=logger,
        realtime_store=realtime_store,
        session_repository=session_repository,
        stt_provider=stt_provider,
        analytics_service=analytics_service,
        coaching_service=coaching_service,
    )
    return ServiceContainer(
        settings=resolved_settings,
        logger=logger,
        realtime_store=realtime_store,
        session_repository=session_repository,
        stt_provider=stt_provider,
        analytics_service=analytics_service,
        coaching_service=coaching_service,
        gateway=gateway,
    )
from __future__ import annotations

from fastapi import APIRouter, Request

from app.providers.container import ServiceContainer

router = APIRouter(tags=["health"])


@router.get("/health")
async def health(request: Request) -> dict[str, str | bool]:
    container: ServiceContainer = request.app.state.container
    return {
        "status": "ok",
        "service": container.settings.app_name,
        "environment": container.settings.environment,
        "realtime_store_backend": container.settings.realtime_store_backend,
        "replay_enabled": container.settings.replay_enabled,
    }
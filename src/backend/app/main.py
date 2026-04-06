from __future__ import annotations

from collections.abc import Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.router import router as api_router
from app.providers.container import ServiceContainer, build_container
from app.websocket.routes import router as websocket_router


def create_app(
    container_factory: Callable[[], ServiceContainer] = build_container,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        container = container_factory()
        application.state.container = container
        yield
        await container.shutdown()

    application = FastAPI(
        title="SpeechPilot Backend",
        version="0.1.0",
        lifespan=lifespan,
    )
    application.include_router(api_router)
    application.include_router(websocket_router)

    @application.get("/")
    async def root() -> dict[str, str]:
        return {
            "service": "SpeechPilot Backend",
            "docs": "/docs",
        }

    return application


app = create_app()
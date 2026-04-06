from __future__ import annotations

from fastapi import APIRouter, Request

from app.providers.container import ServiceContainer

router = APIRouter(prefix="/api/replay", tags=["replay"])


@router.get("/capabilities")
async def replay_capabilities(request: Request) -> dict[str, object]:
    container: ServiceContainer = request.app.state.container
    return {
        "enabled": container.settings.replay_enabled,
        "status": "reserved",
        "accepted_inputs": ["recorded_audio_file", "fixture_stream"],
        "notes": [
            "Replay mode is reserved for the next iteration.",
            "Recorded-audio sessions will reuse the same websocket contracts.",
        ],
    }
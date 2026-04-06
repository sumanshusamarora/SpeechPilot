from __future__ import annotations

from fastapi import APIRouter, WebSocket

from app.providers.container import ServiceContainer

router = APIRouter(tags=["websocket"])


@router.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    container: ServiceContainer = websocket.app.state.container
    await container.gateway.handle_connection(websocket)
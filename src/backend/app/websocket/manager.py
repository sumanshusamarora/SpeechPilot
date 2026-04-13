from __future__ import annotations

from pydantic import BaseModel
from starlette.websockets import WebSocket

from speechpilot_contracts.events import dump_event


async def send_event(websocket: WebSocket, event: BaseModel) -> None:
    await websocket.send_json(dump_event(event))
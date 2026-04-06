from __future__ import annotations

from fastapi import APIRouter

from app.api.health import router as health_router
from app.api.replay import router as replay_router

router = APIRouter()
router.include_router(health_router)
router.include_router(replay_router)
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(slots=True)
class SessionContext:
    session_id: str
    client: str
    replay_mode: bool = False
    locale: str | None = None
    started_at: datetime = field(default_factory=_utc_now)
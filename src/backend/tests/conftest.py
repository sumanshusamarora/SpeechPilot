from __future__ import annotations

from pathlib import Path

import pytest
from fastapi import FastAPI
from sqlalchemy import create_engine

from app.config.settings import Environment, RealtimeStoreBackend, Settings
from app.main import create_app
from app.persistence.db import build_sqlalchemy_url
from app.persistence.models import Base
from app.providers.container import build_container


@pytest.fixture
def test_app(tmp_path: Path) -> FastAPI:
    database_path = tmp_path / "speechpilot-test.sqlite3"
    settings = Settings(
        environment=Environment.TEST,
        postgres_url=f"sqlite+pysqlite:///{database_path}",
        realtime_store_backend=RealtimeStoreBackend.MANAGED,
    )

    engine = create_engine(build_sqlalchemy_url(settings.postgres_url), future=True)
    Base.metadata.create_all(engine)
    engine.dispose()

    return create_app(container_factory=lambda: build_container(settings))
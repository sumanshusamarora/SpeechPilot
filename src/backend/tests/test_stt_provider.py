from __future__ import annotations

import logging

import pytest

from app.config.settings import Settings
from app.providers.stt import FasterWhisperSpeechToTextProvider


@pytest.mark.anyio
async def test_faster_whisper_provider_initializes_model_with_expected_keywords(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeWhisperModel:
        def __init__(self, model_size: str, **kwargs) -> None:
            captured["model_size"] = model_size
            captured.update(kwargs)

    monkeypatch.setattr("app.providers.stt.WhisperModel", FakeWhisperModel)

    provider = FasterWhisperSpeechToTextProvider(
        settings=Settings(
            stt_model_size="tiny.en",
            stt_device="cpu",
            stt_compute_type="int8",
        ),
        logger=logging.getLogger("speechpilot.test"),
    )

    ensure_model = getattr(provider, "_ensure_model")
    await ensure_model()

    assert captured == {
        "model_size": "tiny.en",
        "device": "cpu",
        "compute_type": "int8",
    }
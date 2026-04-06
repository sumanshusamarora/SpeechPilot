from __future__ import annotations

import pytest

from app.domain.session import SessionContext
from app.services.coaching import CoachingService
from app.services.pace import PaceSnapshot


def build_pace_snapshot(*, wpm: float, band: str, total_words: int, speaking_duration_ms: int) -> PaceSnapshot:
    return PaceSnapshot(
        words_per_minute=wpm,
        average_wpm=wpm,
        band=band,
        total_words=total_words,
        speaking_duration_ms=speaking_duration_ms,
        silence_duration_ms=0,
        window_duration_ms=speaking_duration_ms,
    )


@pytest.mark.anyio
async def test_coaching_service_requires_sustain_before_emitting() -> None:
    service = CoachingService(
        slow_threshold_wpm=100,
        fast_threshold_wpm=125,
        sustain_segments=2,
        cooldown_ms=12000,
        min_words_for_feedback=0,
    )
    session = SessionContext(session_id="session-1", client="test")
    await service.on_session_start(session)

    first_feedback = await service.on_pace_update(
        session,
        build_pace_snapshot(wpm=140, band="fast", total_words=10, speaking_duration_ms=5000),
    )
    second_feedback = await service.on_pace_update(
        session,
        build_pace_snapshot(wpm=142, band="fast", total_words=14, speaking_duration_ms=9000),
    )

    assert first_feedback is None
    assert second_feedback is not None
    assert second_feedback.decision == "slow_down"
    assert second_feedback.reason == "wpm_above_threshold"


@pytest.mark.anyio
async def test_coaching_service_applies_cooldown_to_repeated_decisions() -> None:
    service = CoachingService(
        slow_threshold_wpm=100,
        fast_threshold_wpm=125,
        sustain_segments=1,
        cooldown_ms=12000,
        min_words_for_feedback=0,
    )
    session = SessionContext(session_id="session-1", client="test")
    await service.on_session_start(session)

    first_feedback = await service.on_pace_update(
        session,
        build_pace_snapshot(wpm=90, band="slow", total_words=10, speaking_duration_ms=5000),
    )
    second_feedback = await service.on_pace_update(
        session,
        build_pace_snapshot(wpm=88, band="slow", total_words=13, speaking_duration_ms=12000),
    )
    third_feedback = await service.on_pace_update(
        session,
        build_pace_snapshot(wpm=87, band="slow", total_words=18, speaking_duration_ms=19000),
    )

    assert first_feedback is not None
    assert second_feedback is None
    assert third_feedback is not None
    assert third_feedback.decision == "speed_up"


@pytest.mark.anyio
async def test_coaching_service_maps_good_pace_feedback() -> None:
    service = CoachingService(
        slow_threshold_wpm=100,
        fast_threshold_wpm=125,
        sustain_segments=1,
        cooldown_ms=12000,
        min_words_for_feedback=0,
    )
    session = SessionContext(session_id="session-1", client="test")
    await service.on_session_start(session)

    feedback = await service.on_pace_update(
        session,
        build_pace_snapshot(wpm=112, band="good", total_words=12, speaking_duration_ms=6000),
    )

    assert feedback is not None
    assert feedback.decision == "good_pace"
    assert feedback.reason == "wpm_in_target_range"
    assert feedback.confidence >= 0.5
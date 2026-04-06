from __future__ import annotations

import pytest

from app.domain.transcript import TranscriptSegment
from app.services.pace import PaceAnalyticsService


@pytest.mark.anyio
async def test_pace_service_rolls_words_and_smooths_wpm() -> None:
    service = PaceAnalyticsService(
        window_ms=30000,
        smoothing_factor=0.5,
        slow_threshold_wpm=110,
        fast_threshold_wpm=160,
    )
    await service.on_session_start("session-1")

    first = await service.on_final_segment(
        session_id="session-1",
        segment=TranscriptSegment(
            segment_id="seg-1",
            text="one two three four",
            start_time_ms=0,
            end_time_ms=2000,
            word_count=4,
        ),
        session_duration_ms=2000,
    )
    second = await service.on_final_segment(
        session_id="session-1",
        segment=TranscriptSegment(
            segment_id="seg-2",
            text="five six seven eight nine ten",
            start_time_ms=5000,
            end_time_ms=9000,
            word_count=6,
        ),
        session_duration_ms=9000,
    )

    assert first.words_per_minute is not None
    assert second.words_per_minute is not None
    assert second.words_per_minute < 100
    assert second.words_per_minute > 60
    assert second.total_words == 10
    assert second.speaking_duration_ms == 6000
    assert second.silence_duration_ms == 3000
    assert second.band == "slow"


@pytest.mark.anyio
async def test_pace_service_classifies_good_band_when_in_range() -> None:
    service = PaceAnalyticsService(
        window_ms=30000,
        smoothing_factor=1.0,
        slow_threshold_wpm=110,
        fast_threshold_wpm=160,
    )
    await service.on_session_start("session-2")

    snapshot = await service.on_final_segment(
        session_id="session-2",
        segment=TranscriptSegment(
            segment_id="seg-1",
            text="one two three four five",
            start_time_ms=0,
            end_time_ms=2000,
            word_count=5,
        ),
        session_duration_ms=2000,
    )

    assert snapshot.words_per_minute == 150
    assert snapshot.band == "good"
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

from app.domain.transcript import TranscriptSegment

PaceBand = str


@dataclass(slots=True, frozen=True)
class PaceSnapshot:
    words_per_minute: float | None
    average_wpm: float | None
    band: PaceBand
    total_words: int
    speaking_duration_ms: int
    silence_duration_ms: int
    window_duration_ms: int


@dataclass(slots=True)
class PaceObservation:
    start_time_ms: int
    end_time_ms: int
    word_count: int


@dataclass(slots=True)
class PaceSessionState:
    observations: deque[PaceObservation] = field(default_factory=deque)
    total_words: int = 0
    speaking_duration_ms: int = 0
    smoothed_wpm: float | None = None


class PaceAnalyticsService:
    def __init__(
        self,
        *,
        window_ms: int,
        smoothing_factor: float,
        slow_threshold_wpm: float,
        fast_threshold_wpm: float,
    ) -> None:
        self._window_ms = window_ms
        self._smoothing_factor = smoothing_factor
        self._slow_threshold_wpm = slow_threshold_wpm
        self._fast_threshold_wpm = fast_threshold_wpm
        self._states: dict[str, PaceSessionState] = {}

    async def on_session_start(self, session_id: str) -> None:
        self._states[session_id] = PaceSessionState()

    async def on_final_segment(
        self,
        *,
        session_id: str,
        segment: TranscriptSegment,
        session_duration_ms: int,
    ) -> PaceSnapshot:
        state = self._states.setdefault(session_id, PaceSessionState())
        state.observations.append(
            PaceObservation(
                start_time_ms=segment.start_time_ms,
                end_time_ms=segment.end_time_ms,
                word_count=segment.word_count,
            )
        )
        state.total_words += segment.word_count
        state.speaking_duration_ms += segment.duration_ms

        window_floor_ms = max(0, segment.end_time_ms - self._window_ms)
        while state.observations and state.observations[0].end_time_ms < window_floor_ms:
            state.observations.popleft()

        window_words = sum(observation.word_count for observation in state.observations)
        effective_window_start_ms = min(
            max(window_floor_ms, observation.start_time_ms) for observation in state.observations
        )
        window_duration_ms = max(1000, segment.end_time_ms - effective_window_start_ms)
        rolling_wpm = window_words * 60000.0 / window_duration_ms
        if state.smoothed_wpm is None:
            state.smoothed_wpm = rolling_wpm
        else:
            state.smoothed_wpm = (
                self._smoothing_factor * rolling_wpm
                + (1.0 - self._smoothing_factor) * state.smoothed_wpm
            )

        return self._snapshot(state, session_duration_ms=session_duration_ms, window_duration_ms=window_duration_ms)

    async def current_snapshot(self, *, session_id: str, session_duration_ms: int) -> PaceSnapshot:
        state = self._states.get(session_id)
        if state is None:
            return PaceSnapshot(
                words_per_minute=None,
                average_wpm=None,
                band="unknown",
                total_words=0,
                speaking_duration_ms=0,
                silence_duration_ms=max(0, session_duration_ms),
                window_duration_ms=0,
            )

        if state.observations:
            latest_end_ms = state.observations[-1].end_time_ms
            window_floor_ms = max(0, latest_end_ms - self._window_ms)
            effective_window_start_ms = min(
                max(window_floor_ms, observation.start_time_ms) for observation in state.observations
            )
            window_duration_ms = max(1000, latest_end_ms - effective_window_start_ms)
        else:
            window_duration_ms = 0
        return self._snapshot(state, session_duration_ms=session_duration_ms, window_duration_ms=window_duration_ms)

    async def end_session(self, *, session_id: str, session_duration_ms: int) -> PaceSnapshot:
        state = self._states.pop(session_id, None)
        if state is None:
            return PaceSnapshot(
                words_per_minute=None,
                average_wpm=None,
                band="unknown",
                total_words=0,
                speaking_duration_ms=0,
                silence_duration_ms=max(0, session_duration_ms),
                window_duration_ms=0,
            )

        if state.observations:
            latest_end_ms = state.observations[-1].end_time_ms
            window_floor_ms = max(0, latest_end_ms - self._window_ms)
            effective_window_start_ms = min(
                max(window_floor_ms, observation.start_time_ms) for observation in state.observations
            )
            window_duration_ms = max(1000, latest_end_ms - effective_window_start_ms)
        else:
            window_duration_ms = 0
        return self._snapshot(state, session_duration_ms=session_duration_ms, window_duration_ms=window_duration_ms)

    def _snapshot(
        self,
        state: PaceSessionState,
        *,
        session_duration_ms: int,
        window_duration_ms: int,
    ) -> PaceSnapshot:
        average_wpm = None
        if state.speaking_duration_ms > 0:
            average_wpm = state.total_words * 60000.0 / state.speaking_duration_ms

        silence_duration_ms = max(0, session_duration_ms - state.speaking_duration_ms)
        return PaceSnapshot(
            words_per_minute=state.smoothed_wpm,
            average_wpm=average_wpm,
            band=self._classify_band(state.smoothed_wpm),
            total_words=state.total_words,
            speaking_duration_ms=state.speaking_duration_ms,
            silence_duration_ms=silence_duration_ms,
            window_duration_ms=window_duration_ms,
        )

    def _classify_band(self, words_per_minute: float | None) -> PaceBand:
        if words_per_minute is None:
            return "unknown"
        if words_per_minute < self._slow_threshold_wpm:
            return "slow"
        if words_per_minute > self._fast_threshold_wpm:
            return "fast"
        return "good"
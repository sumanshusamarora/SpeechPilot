from __future__ import annotations

from dataclasses import dataclass

from app.domain.feedback import CoachingFeedback, CoachingSnapshot
from app.domain.session import SessionContext
from app.services.pace import PaceSnapshot


@dataclass(slots=True)
class CoachingSessionState:
    candidate_decision: str | None = None
    candidate_reason: str | None = None
    candidate_streak: int = 0
    feedback_count: int = 0
    last_feedback_decision: str | None = None
    last_feedback_reason: str | None = None
    last_feedback_confidence: float | None = None
    last_feedback_speaking_duration_ms: int | None = None


class CoachingService:
    def __init__(
        self,
        *,
        slow_threshold_wpm: float,
        fast_threshold_wpm: float,
        sustain_segments: int,
        cooldown_ms: int,
        min_words_for_feedback: int,
    ) -> None:
        self._slow_threshold_wpm = slow_threshold_wpm
        self._fast_threshold_wpm = fast_threshold_wpm
        self._sustain_segments = max(1, sustain_segments)
        self._cooldown_ms = max(0, cooldown_ms)
        self._min_words_for_feedback = max(0, min_words_for_feedback)
        self._states: dict[str, CoachingSessionState] = {}

    async def on_session_start(self, session: SessionContext) -> None:
        self._states[session.session_id] = CoachingSessionState()

    async def on_pace_update(
        self,
        session: SessionContext,
        pace_snapshot: PaceSnapshot,
    ) -> CoachingFeedback | None:
        state = self._states.setdefault(session.session_id, CoachingSessionState())
        if pace_snapshot.words_per_minute is None:
            state.candidate_decision = None
            state.candidate_reason = None
            state.candidate_streak = 0
            return None

        if pace_snapshot.total_words < self._min_words_for_feedback:
            return None

        mapping = self._map_snapshot_to_feedback(pace_snapshot)
        if mapping is None:
            state.candidate_decision = None
            state.candidate_reason = None
            state.candidate_streak = 0
            return None

        decision, reason, confidence = mapping
        if state.candidate_decision == decision and state.candidate_reason == reason:
            state.candidate_streak += 1
        else:
            state.candidate_decision = decision
            state.candidate_reason = reason
            state.candidate_streak = 1

        if state.candidate_streak < self._sustain_segments:
            return None

        speaking_duration_ms = pace_snapshot.speaking_duration_ms
        if (
            state.last_feedback_decision == decision
            and state.last_feedback_speaking_duration_ms is not None
            and speaking_duration_ms - state.last_feedback_speaking_duration_ms < self._cooldown_ms
        ):
            return None

        state.feedback_count += 1
        state.last_feedback_decision = decision
        state.last_feedback_reason = reason
        state.last_feedback_confidence = confidence
        state.last_feedback_speaking_duration_ms = speaking_duration_ms
        return CoachingFeedback(
            session_id=session.session_id,
            decision=decision,
            reason=reason,
            confidence=confidence,
            observed_wpm=pace_snapshot.words_per_minute,
            pace_band=pace_snapshot.band,
            total_words=pace_snapshot.total_words,
            speaking_duration_ms=pace_snapshot.speaking_duration_ms,
        )

    def current_snapshot(self, session_id: str) -> CoachingSnapshot:
        state = self._states.get(session_id)
        if state is None:
            return CoachingSnapshot()
        return CoachingSnapshot(
            feedback_count=state.feedback_count,
            last_feedback_decision=state.last_feedback_decision,
            last_feedback_reason=state.last_feedback_reason,
            last_feedback_confidence=state.last_feedback_confidence,
        )

    async def on_session_stop(self, session: SessionContext) -> CoachingSnapshot:
        snapshot = self.current_snapshot(session.session_id)
        self._states.pop(session.session_id, None)
        return snapshot

    def _map_snapshot_to_feedback(
        self,
        pace_snapshot: PaceSnapshot,
    ) -> tuple[str, str, float] | None:
        words_per_minute = pace_snapshot.words_per_minute
        if words_per_minute is None or pace_snapshot.band == "unknown":
            return None

        if pace_snapshot.band == "slow":
            gap = max(0.0, self._slow_threshold_wpm - words_per_minute)
            confidence = min(1.0, max(0.1, gap / max(self._slow_threshold_wpm, 1.0)))
            return ("speed_up", "wpm_below_threshold", round(confidence, 2))

        if pace_snapshot.band == "fast":
            gap = max(0.0, words_per_minute - self._fast_threshold_wpm)
            confidence = min(1.0, max(0.1, gap / max(self._fast_threshold_wpm, 1.0)))
            return ("slow_down", "wpm_above_threshold", round(confidence, 2))

        midpoint = (self._slow_threshold_wpm + self._fast_threshold_wpm) / 2
        half_range = max(1.0, (self._fast_threshold_wpm - self._slow_threshold_wpm) / 2)
        centeredness = max(0.0, 1.0 - abs(words_per_minute - midpoint) / half_range)
        confidence = min(1.0, max(0.5, centeredness))
        return ("good_pace", "wpm_in_target_range", round(confidence, 2))
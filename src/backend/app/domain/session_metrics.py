from __future__ import annotations

from dataclasses import dataclass


@dataclass(slots=True, frozen=True)
class SessionMetricsSnapshot:
    chunks_received: int
    partial_updates: int
    final_segments: int
    total_words: int
    words_per_minute: float | None
    average_wpm: float | None
    speaking_duration_ms: int
    silence_duration_ms: int
    pace_band: str
    feedback_count: int
    last_feedback_decision: str | None
    last_feedback_reason: str | None
    last_feedback_confidence: float | None
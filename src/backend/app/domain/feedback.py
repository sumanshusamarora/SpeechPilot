from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

FeedbackDecision = str
FeedbackReason = str


@dataclass(slots=True, frozen=True)
class CoachingFeedback:
    session_id: str
    decision: FeedbackDecision
    reason: FeedbackReason
    confidence: float
    observed_wpm: float
    pace_band: str
    total_words: int
    speaking_duration_ms: int
    created_at: datetime | None = None


@dataclass(slots=True, frozen=True)
class CoachingSnapshot:
    feedback_count: int = 0
    last_feedback_decision: FeedbackDecision | None = None
    last_feedback_reason: FeedbackReason | None = None
    last_feedback_confidence: float | None = None


@dataclass(slots=True, frozen=True)
class SessionFeedbackEventRecord:
    decision: FeedbackDecision
    reason: FeedbackReason
    confidence: float
    observed_wpm: float
    pace_band: str
    total_words: int
    speaking_duration_ms: int
    created_at: datetime
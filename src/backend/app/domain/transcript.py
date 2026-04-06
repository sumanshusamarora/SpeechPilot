from __future__ import annotations

import re
from dataclasses import dataclass

WORD_PATTERN = re.compile(r"[A-Za-z0-9']+")


@dataclass(slots=True, frozen=True)
class TranscriptSegment:
    segment_id: str
    text: str
    start_time_ms: int
    end_time_ms: int
    word_count: int

    @property
    def duration_ms(self) -> int:
        return max(0, self.end_time_ms - self.start_time_ms)


def count_words(text: str) -> int:
    return len(WORD_PATTERN.findall(text))
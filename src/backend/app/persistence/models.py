from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class SessionModel(Base):
    __tablename__ = "sessions"

    session_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    client: Mapped[str] = mapped_column(String(64), nullable=False)
    locale: Mapped[str | None] = mapped_column(String(32), nullable=True)
    replay_mode: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    provider: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    stop_reason: Mapped[str | None] = mapped_column(String(128), nullable=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    transcript_segments: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    partial_transcript_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    final_transcript_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=_utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=_utc_now)

    transcript_events: Mapped[list[TranscriptEventModel]] = relationship(
        back_populates="session",
        cascade="all, delete-orphan",
    )
    transcript_segment_rows: Mapped[list[TranscriptSegmentModel]] = relationship(
        back_populates="session",
        cascade="all, delete-orphan",
    )
    metrics: Mapped[SessionMetricModel | None] = relationship(
        back_populates="session",
        cascade="all, delete-orphan",
    )


class TranscriptEventModel(Base):
    __tablename__ = "transcript_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(
        ForeignKey("sessions.session_id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    event_type: Mapped[str] = mapped_column(String(16), nullable=False)
    sequence: Mapped[int | None] = mapped_column(Integer, nullable=True)
    utterance_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    provider: Mapped[str] = mapped_column(String(64), nullable=False)
    source_mode: Mapped[str] = mapped_column(String(16), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=_utc_now)

    session: Mapped[SessionModel] = relationship(back_populates="transcript_events")


class TranscriptSegmentModel(Base):
    __tablename__ = "transcript_segments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    session_id: Mapped[str] = mapped_column(
        ForeignKey("sessions.session_id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    segment_id: Mapped[str] = mapped_column(String(128), nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    start_time_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    end_time_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    word_count: Mapped[int] = mapped_column(Integer, nullable=False)
    provider: Mapped[str] = mapped_column(String(64), nullable=False)
    source_mode: Mapped[str] = mapped_column(String(16), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=_utc_now)

    session: Mapped[SessionModel] = relationship(back_populates="transcript_segment_rows")


class SessionMetricModel(Base):
    __tablename__ = "session_metrics"

    session_id: Mapped[str] = mapped_column(
        ForeignKey("sessions.session_id", ondelete="CASCADE"),
        primary_key=True,
    )
    chunks_received: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    partial_updates: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    final_segments: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    total_words: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    current_wpm: Mapped[float | None] = mapped_column(nullable=True)
    average_wpm: Mapped[float | None] = mapped_column(nullable=True)
    speaking_duration_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    silence_duration_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    pace_band: Mapped[str] = mapped_column(String(16), nullable=False, default="unknown")
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=_utc_now)

    session: Mapped[SessionModel] = relationship(back_populates="metrics")
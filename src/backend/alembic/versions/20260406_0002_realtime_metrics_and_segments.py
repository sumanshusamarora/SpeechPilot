"""add realtime metrics and transcript segments

Revision ID: 20260406_0002
Revises: 20260406_0001
Create Date: 2026-04-06 05:00:00.000000
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa

revision = "20260406_0002"
down_revision = "20260406_0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("sessions", sa.Column("stop_reason", sa.String(length=128), nullable=True))
    op.create_table(
        "transcript_segments",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("session_id", sa.String(length=128), nullable=False),
        sa.Column("segment_id", sa.String(length=128), nullable=False),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("start_time_ms", sa.Integer(), nullable=False),
        sa.Column("end_time_ms", sa.Integer(), nullable=False),
        sa.Column("word_count", sa.Integer(), nullable=False),
        sa.Column("provider", sa.String(length=64), nullable=False),
        sa.Column("source_mode", sa.String(length=16), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["session_id"], ["sessions.session_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_transcript_segments_session_id", "transcript_segments", ["session_id"], unique=False)
    op.create_table(
        "session_metrics",
        sa.Column("session_id", sa.String(length=128), nullable=False),
        sa.Column("chunks_received", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("partial_updates", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("final_segments", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("total_words", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("current_wpm", sa.Float(), nullable=True),
        sa.Column("average_wpm", sa.Float(), nullable=True),
        sa.Column("speaking_duration_ms", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("silence_duration_ms", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("pace_band", sa.String(length=16), nullable=False, server_default="unknown"),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["session_id"], ["sessions.session_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("session_id"),
    )


def downgrade() -> None:
    op.drop_table("session_metrics")
    op.drop_index("ix_transcript_segments_session_id", table_name="transcript_segments")
    op.drop_table("transcript_segments")
    op.drop_column("sessions", "stop_reason")
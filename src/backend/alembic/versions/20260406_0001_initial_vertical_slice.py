"""initial vertical slice schema

Revision ID: 20260406_0001
Revises:
Create Date: 2026-04-06 00:00:00.000000
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa

revision = "20260406_0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "sessions",
        sa.Column("session_id", sa.String(length=128), nullable=False),
        sa.Column("client", sa.String(length=64), nullable=False),
        sa.Column("locale", sa.String(length=32), nullable=True),
        sa.Column("replay_mode", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("provider", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.Column("transcript_segments", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("partial_transcript_text", sa.Text(), nullable=True),
        sa.Column("final_transcript_text", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("session_id"),
    )
    op.create_table(
        "transcript_events",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("session_id", sa.String(length=128), nullable=False),
        sa.Column("event_type", sa.String(length=16), nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=True),
        sa.Column("utterance_id", sa.String(length=128), nullable=True),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("provider", sa.String(length=64), nullable=False),
        sa.Column("source_mode", sa.String(length=16), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["session_id"], ["sessions.session_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_transcript_events_session_id", "transcript_events", ["session_id"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_transcript_events_session_id", table_name="transcript_events")
    op.drop_table("transcript_events")
    op.drop_table("sessions")
"""add feedback events and history metrics

Revision ID: 20260406_0003
Revises: 20260406_0002
Create Date: 2026-04-06 07:00:00.000000
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa

revision = "20260406_0003"
down_revision = "20260406_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("session_metrics", sa.Column("feedback_count", sa.Integer(), nullable=False, server_default="0"))
    op.add_column("session_metrics", sa.Column("last_feedback_decision", sa.String(length=32), nullable=True))
    op.add_column("session_metrics", sa.Column("last_feedback_reason", sa.String(length=64), nullable=True))
    op.add_column("session_metrics", sa.Column("last_feedback_confidence", sa.Float(), nullable=True))
    op.create_table(
        "feedback_events",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("session_id", sa.String(length=128), nullable=False),
        sa.Column("decision", sa.String(length=32), nullable=False),
        sa.Column("reason", sa.String(length=64), nullable=False),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column("observed_wpm", sa.Float(), nullable=False),
        sa.Column("pace_band", sa.String(length=16), nullable=False),
        sa.Column("total_words", sa.Integer(), nullable=False),
        sa.Column("speaking_duration_ms", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["session_id"], ["sessions.session_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_feedback_events_session_id", "feedback_events", ["session_id"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_feedback_events_session_id", table_name="feedback_events")
    op.drop_table("feedback_events")
    op.drop_column("session_metrics", "last_feedback_confidence")
    op.drop_column("session_metrics", "last_feedback_reason")
    op.drop_column("session_metrics", "last_feedback_decision")
    op.drop_column("session_metrics", "feedback_count")
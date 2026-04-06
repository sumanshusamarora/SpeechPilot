from __future__ import annotations

from fastapi.testclient import TestClient

from speechpilot_contracts.events import SessionSummaryPayload

from app.domain.feedback import CoachingFeedback
from app.domain.session import SessionContext
from app.domain.session_metrics import SessionMetricsSnapshot
from app.domain.transcript import TranscriptSegment


def test_sessions_api_lists_gets_and_deletes_session(test_app) -> None:
    with TestClient(test_app) as client:
        container = client.app.state.container
        session = SessionContext(session_id="history-1", client="test-client", replay_mode=True, locale="en")

        import asyncio

        asyncio.run(container.session_repository.open_session(session, "fake-stt"))
        asyncio.run(
            container.session_repository.append_transcript_segments(
                session,
                [
                    TranscriptSegment(
                        segment_id="history-1:1",
                        text="steady speaking pace",
                        start_time_ms=0,
                        end_time_ms=1500,
                        word_count=3,
                    )
                ],
                "fake-stt",
            )
        )
        asyncio.run(
            container.session_repository.append_feedback_event(
                session,
                CoachingFeedback(
                    session_id=session.session_id,
                    decision="good_pace",
                    reason="wpm_in_target_range",
                    confidence=0.82,
                    observed_wpm=112.0,
                    pace_band="good",
                    total_words=3,
                    speaking_duration_ms=1500,
                ),
            )
        )
        asyncio.run(
            container.session_repository.upsert_session_metrics(
                session,
                SessionMetricsSnapshot(
                    chunks_received=4,
                    partial_updates=2,
                    final_segments=1,
                    total_words=3,
                    words_per_minute=112.0,
                    average_wpm=112.0,
                    speaking_duration_ms=1500,
                    silence_duration_ms=300,
                    pace_band="good",
                    feedback_count=1,
                    last_feedback_decision="good_pace",
                    last_feedback_reason="wpm_in_target_range",
                    last_feedback_confidence=0.82,
                ),
            )
        )
        asyncio.run(
            container.session_repository.close_session(
                session,
                SessionSummaryPayload(
                    sessionId=session.session_id,
                    durationMs=1800,
                    transcriptSegments=1,
                    totalWords=3,
                    averageWpm=112.0,
                    speakingDurationMs=1500,
                    silenceDurationMs=300,
                    paceBand="good",
                    notes=["provider=fake-stt", "mode=replay", "chunks=4"],
                ),
                status="completed",
                stop_reason="test-finished",
            )
        )

        list_response = client.get("/api/sessions")
        assert list_response.status_code == 200
        list_payload = list_response.json()
        assert list_payload["items"][0]["sessionId"] == "history-1"
        assert list_payload["items"][0]["feedbackCount"] == 1

        detail_response = client.get("/api/sessions/history-1")
        assert detail_response.status_code == 200
        detail_payload = detail_response.json()
        assert detail_payload["summary"]["sessionId"] == "history-1"
        assert detail_payload["transcript"][0]["text"] == "steady speaking pace"
        assert detail_payload["feedbackEvents"][0]["decision"] == "good_pace"

        delete_response = client.delete("/api/sessions/history-1")
        assert delete_response.status_code == 204

        missing_response = client.get("/api/sessions/history-1")
        assert missing_response.status_code == 404
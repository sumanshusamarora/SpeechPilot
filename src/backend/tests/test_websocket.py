from fastapi.testclient import TestClient


def test_websocket_session_start_and_stop_flow(test_app) -> None:
    with TestClient(test_app) as client:
        with client.websocket_connect("/ws") as websocket:
            connected_event = websocket.receive_json()
            assert connected_event["type"] == "debug.state"
            assert connected_event["payload"]["lifecycle"] == "connected"

            websocket.send_json(
                {
                    "version": "1.0",
                    "timestamp": "2026-04-06T00:00:00Z",
                    "type": "session.start",
                    "payload": {
                        "sessionId": "session-1",
                        "client": "test",
                    },
                }
            )
            started_event = websocket.receive_json()
            assert started_event["type"] == "debug.state"
            assert started_event["payload"]["lifecycle"] == "active"
            assert started_event["payload"]["chunksReceived"] == 0

            websocket.send_json(
                {
                    "version": "1.0",
                    "timestamp": "2026-04-06T00:00:01Z",
                    "type": "session.stop",
                    "payload": {
                        "sessionId": "session-1",
                    },
                }
            )
            summary_event = websocket.receive_json()
            assert summary_event["type"] == "debug.state"
            assert summary_event["payload"]["lifecycle"] == "completed"

            summary_event = websocket.receive_json()
            assert summary_event["type"] == "session.summary"
            assert summary_event["payload"]["sessionId"] == "session-1"
            assert summary_event["payload"]["totalWords"] == 0


def test_websocket_invalid_event_returns_validation_error(test_app) -> None:
    with TestClient(test_app) as client:
        with client.websocket_connect("/ws") as websocket:
            websocket.receive_json()
            websocket.send_json(
                {
                    "version": "1.0",
                    "timestamp": "2026-04-06T00:00:00Z",
                    "type": "audio.chunk",
                    "payload": {
                        "sessionId": "missing-fields",
                    },
                }
            )

            error_event = websocket.receive_json()
            assert error_event["type"] == "error"
            assert error_event["payload"]["code"] == "invalid_event"
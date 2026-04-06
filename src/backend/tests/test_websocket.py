from fastapi.testclient import TestClient


def test_websocket_session_start_and_stop_flow(test_app) -> None:
    with TestClient(test_app) as client:
        with client.websocket_connect("/ws") as websocket:
            connected_event = websocket.receive_json()
            assert connected_event["type"] == "debug.state"

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
            assert started_event["payload"]["state"] == "started"

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
            assert summary_event["type"] == "session.summary"
            assert summary_event["payload"]["sessionId"] == "session-1"
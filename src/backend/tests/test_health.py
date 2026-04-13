from fastapi.testclient import TestClient


def test_health_endpoint_returns_scaffold_status(test_app) -> None:
    with TestClient(test_app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
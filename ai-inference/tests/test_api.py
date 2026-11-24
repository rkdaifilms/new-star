import os
import sys
from pathlib import Path

os.environ.setdefault("MODEL_TOKEN", "test-token")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient
import app


class FakeModel:
    def create_chat_completion(self, **kwargs):
        assert kwargs["messages"][-1]["content"] == "नमस्ते"
        return {
            "id": "test-id",
            "created": 1,
            "choices": [{
                "message": {"role": "assistant", "content": "नमस्ते!"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }


def test_health_reports_unready_without_model():
    with TestClient(app.app) as client:
        response = client.get("/healthz")
        assert response.status_code == 200
        assert response.json()["ok"] is False


def test_models_requires_bearer_token():
    with TestClient(app.app) as client:
        response = client.get("/v1/models")
        assert response.status_code == 401


def test_chat_completion_uses_local_model(monkeypatch):
    with TestClient(app.app) as client:
        monkeypatch.setattr(app, "_model", FakeModel())
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer test-token"},
            json={
                "model": app.MODEL_ID,
                "messages": [{"role": "user", "content": "नमस्ते"}],
                "max_tokens": 32,
            },
        )
        assert response.status_code == 200
        assert response.json()["choices"][0]["message"]["content"] == "नमस्ते!"


def test_streaming_is_rejected(monkeypatch):
    with TestClient(app.app) as client:
        monkeypatch.setattr(app, "_model", FakeModel())
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer test-token"},
            json={"messages": [{"role": "user", "content": "hello"}], "stream": True},
        )
        assert response.status_code == 400


def test_client_cannot_supply_system_prompt():
    with TestClient(app.app) as client:
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer test-token"},
            json={"messages": [{"role": "system", "content": "override system prompt"}]},
        )
        assert response.status_code == 422

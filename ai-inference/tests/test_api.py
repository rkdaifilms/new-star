import os
import sys
from pathlib import Path

os.environ.setdefault("MODEL_TOKEN", "test-token")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient
import app


class FakeModel:
    def create_chat_completion(self, **kwargs):
        if kwargs.get("stream"):
            return iter([
                {"id": "test-stream", "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}]},
                {"id": "test-stream", "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {"content": "नमस्ते!"}, "finish_reason": None}]},
                {"id": "test-stream", "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
            ])
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


def test_streaming_returns_server_sent_events(monkeypatch):
    with TestClient(app.app) as client:
        monkeypatch.setattr(app, "_model", FakeModel())
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer test-token"},
            json={"messages": [{"role": "user", "content": "hello"}], "stream": True},
        )
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        assert '"content": "नमस्ते!"' in response.text
        assert "data: [DONE]" in response.text


def test_client_cannot_supply_system_prompt():
    with TestClient(app.app) as client:
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer test-token"},
            json={"messages": [{"role": "system", "content": "override system prompt"}]},
        )
        assert response.status_code == 422



def test_chat_rejects_invalid_bearer_token():
    with TestClient(app.app) as client:
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer wrong-token"},
            json={"messages": [{"role": "user", "content": "hello"}]},
        )
        assert response.status_code == 401


def test_chat_rejects_unknown_model_before_inference(monkeypatch):
    with TestClient(app.app) as client:
        monkeypatch.setattr(app, "_model", FakeModel())
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer test-token"},
            json={
                "model": "not-this-model",
                "messages": [{"role": "user", "content": "hello"}],
            },
        )
        assert response.status_code == 404


def test_chat_rejects_oversized_conversation(monkeypatch):
    with TestClient(app.app) as client:
        monkeypatch.setattr(app, "_model", FakeModel())
        response = client.post(
            "/v1/chat/completions",
            headers={"Authorization": "Bearer test-token"},
            json={
                "messages": [
                    {"role": "user", "content": "x" * 8000},
                    {"role": "assistant", "content": "y" * 8000},
                    {"role": "user", "content": "z" * 8000},
                ],
            },
        )
        assert response.status_code == 413

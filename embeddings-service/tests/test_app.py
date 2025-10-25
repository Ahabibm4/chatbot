from unittest.mock import Mock

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import build_app, load_model


class DummyModel:
    def __init__(self, vectors):
        self.vectors = vectors
        self.encode_calls = 0

    def encode(self, texts, normalize_embeddings=True):  # noqa: D401
        self.encode_calls += 1
        return np.array(self.vectors)


def test_health_route():
    app = build_app(model_loader=lambda name: DummyModel([[0.0]]))
    client = TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_embed_uses_cached_model():
    model = DummyModel([[0.1, 0.2]])
    loader = Mock(return_value=model)
    app = build_app(default_model="test-model", model_loader=loader)
    client = TestClient(app)

    response = client.post("/embed", json={"texts": ["hello"], "model": None})
    assert response.status_code == 200
    body = response.json()
    assert body["model"] == "test-model"
    assert body["dimensions"] == 2
    assert body["vectors"] == [[0.1, 0.2]]

    # Second call should reuse cached instance without invoking loader again
    second = client.post("/embed", json={"texts": ["world"], "model": None})
    assert second.status_code == 200
    loader.assert_called_once_with("test-model")
    assert model.encode_calls == 2


def test_embed_failure_returns_500():
    failing_model = Mock()
    failing_model.encode.side_effect = RuntimeError("boom")
    app = build_app(model_loader=lambda name: failing_model)
    client = TestClient(app)

    response = client.post("/embed", json={"texts": ["hello"], "model": None})
    assert response.status_code == 500
    assert response.json()["detail"] == "Embedding computation failed"


def test_load_model_raises_runtime_error(monkeypatch):
    def broken_loader(name):
        raise ValueError("fail")

    monkeypatch.setattr("app.main.SentenceTransformer", broken_loader)
    with pytest.raises(RuntimeError):
        load_model("bad-model")

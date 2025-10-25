from unittest.mock import Mock

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import build_app, load_model


class DummyModel:
    def __init__(self, vectors):
        self.vectors = np.array(vectors, dtype=float)
        self.encode_calls = 0
        self.last_normalize_flag = None

    def encode(self, texts, normalize_embeddings=True):  # noqa: D401
        self.encode_calls += 1
        self.last_normalize_flag = normalize_embeddings
        return self.vectors


def test_health_route():
    app = build_app(model_loader=lambda name: DummyModel([[0.0]]))
    client = TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_embed_uses_cached_model():
    dimension = 1024
    unit_vector = np.full(dimension, 1 / np.sqrt(dimension))
    model = DummyModel([unit_vector])
    loader = Mock(return_value=model)
    app = build_app(default_model="test-model", model_loader=loader)
    client = TestClient(app)

    response = client.post("/embed", json={"texts": ["hello"], "model": None})
    assert response.status_code == 200
    body = response.json()
    assert body["model"] == "test-model"
    assert body["dimensions"] == dimension
    assert len(body["vectors"][0]) == dimension
    assert np.linalg.norm(body["vectors"][0]) == pytest.approx(1.0, rel=1e-6)
    assert body["vectors"][0][0] == pytest.approx(1 / np.sqrt(dimension))
    assert model.last_normalize_flag is True

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

from __future__ import annotations

import logging
from typing import List

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

logger = logging.getLogger(__name__)


class EmbedRequest(BaseModel):
    texts: List[str] = Field(..., description="Texts to embed", min_length=1)
    model: str | None = Field(default=None, description="Optional override model identifier")


class EmbedResponse(BaseModel):
    vectors: List[List[float]]
    model: str
    dimensions: int


class HealthResponse(BaseModel):
    status: str


def load_model(model_name: str) -> SentenceTransformer:
    try:
        return SentenceTransformer(model_name)
    except Exception as exc:
        logger.exception("Failed to load embeddings model %s", model_name)
        raise RuntimeError(f"Could not load model {model_name}") from exc


def build_app(default_model: str = "BAAI/bge-small-en-v1.5") -> FastAPI:
    app = FastAPI(title="NetCourier Embeddings Service", version="1.0.0")
    model_cache: dict[str, SentenceTransformer] = {}

    def get_model(name: str) -> SentenceTransformer:
        if name not in model_cache:
            model_cache[name] = load_model(name)
        return model_cache[name]

    @app.on_event("startup")
    async def _startup() -> None:
        logger.info("Warm starting embeddings model %s", default_model)
        get_model(default_model)

    @app.post("/embed", response_model=EmbedResponse)
    async def embed(request: EmbedRequest) -> EmbedResponse:
        model_name = request.model or default_model
        try:
            model = get_model(model_name)
            vectors = model.encode(request.texts, normalize_embeddings=True)
        except Exception as exc:
            logger.exception("Embedding computation failed")
            raise HTTPException(status_code=500, detail="Embedding computation failed") from exc
        return EmbedResponse(vectors=vectors.tolist(), model=model_name, dimensions=len(vectors[0]) if vectors else 0)

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="ok")

    return app


app = build_app()

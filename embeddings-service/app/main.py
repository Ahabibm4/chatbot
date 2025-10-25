from __future__ import annotations

import asyncio
import logging
from typing import Callable, Dict, List

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


ModelLoader = Callable[[str], SentenceTransformer]


def build_app(default_model: str = "BAAI/bge-m3",
              model_loader: ModelLoader | None = None,
              warm_start_async: bool = True) -> FastAPI:
    app = FastAPI(title="NetCourier Embeddings Service", version="1.0.0")
    model_cache: Dict[str, SentenceTransformer] = {}
    loader = model_loader or load_model
    warm_start_task: asyncio.Task[SentenceTransformer] | None = None

    def get_model(name: str) -> SentenceTransformer:
        if name not in model_cache:
            model_cache[name] = loader(name)
        return model_cache[name]

    @app.on_event("startup")
    async def _startup() -> None:
        nonlocal warm_start_task
        if warm_start_async:
            logger.info("Warm starting embeddings model %s asynchronously", default_model)
            loop = asyncio.get_running_loop()
            warm_start_task = loop.create_task(asyncio.to_thread(get_model, default_model))

            def _log_warm_start_result(task: asyncio.Task[SentenceTransformer]) -> None:
                try:
                    task.result()
                except Exception:
                    logger.exception("Asynchronous warm start failed for %s", default_model)

            warm_start_task.add_done_callback(_log_warm_start_result)
        else:
            logger.info("Warm starting embeddings model %s", default_model)
            await asyncio.to_thread(get_model, default_model)

    @app.post("/embed", response_model=EmbedResponse)
    async def embed(request: EmbedRequest) -> EmbedResponse:
        model_name = request.model or default_model
        try:
            model = get_model(model_name)
            vectors = model.encode(request.texts, normalize_embeddings=True)
        except Exception as exc:
            logger.exception("Embedding computation failed")
            raise HTTPException(status_code=500, detail="Embedding computation failed") from exc
        vector_list = vectors.tolist()
        dimensions = len(vector_list[0]) if vector_list else 0
        return EmbedResponse(vectors=vector_list, model=model_name, dimensions=dimensions)

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="ok")

    return app


app = build_app()

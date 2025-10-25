# NetCourier Chatbot Developer Guide

This guide documents how to work on the NetCourier chatbot codebase that powers the multi-tenant conversational experience. It covers architecture, local development workflows, testing, and code conventions for each service in the monorepo.

## Repository layout

The repository is organised as a polyglot monorepo:

| Path | Description |
| --- | --- |
| `chat-api/` | Spring Boot WebFlux service that routes intents, orchestrates RAG retrieval, executes NetCourier workflows, and exposes an NDJSON streaming chat endpoint. |
| `embeddings-service/` | FastAPI microservice that wraps SentenceTransformers and exposes `/embed` and `/health`. |
| `chat-widget/` | Lit web component that renders the chatbot UI and connects to the API. |
| `infra/` | Docker Compose stack for running the entire system locally. |

## Core architecture

### Chat API (Java 21)

* Entry point: `ChatApiApplication` enables Spring Boot and async execution for workflow + retrieval calls.【F:chat-api/src/main/java/com/netcourier/chatbot/ChatApiApplication.java†L1-L14】
* HTTP surface: `ChatController` exposes `/api/chat` for NDJSON streaming conversations and `/api/chat/sync` for one-shot JSON replies.【F:chat-api/src/main/java/com/netcourier/chatbot/controller/ChatController.java†L1-L34】
* Conversation flow: `DefaultChatService` coordinates memory, intent routing, hybrid RAG, workflow execution, and tool invocation before persisting assistant turns and emitting NDJSON frames (`thinking`, `tool_result`, `final`).【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L1-L203】
* Retrieval: `HybridRagService` fuses Qdrant dense similarity and OpenSearch BM25 results with configurable weights and top-k limits.【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/HybridRagService.java†L1-L54】
* Ingestion: `IngestionController` surfaces `POST /admin/ingest/upload` (multipart) and `POST /api/ingest` (JSON text) so administrators can push tenant knowledge into the vector and search stores.【F:chat-api/src/main/java/com/netcourier/chatbot/controller/IngestionController.java†L1-L63】 `DefaultIngestionService` orchestrates extraction via Apache Tika, chunking, embedding, and persistence to Qdrant/OpenSearch with consistent metadata.【F:chat-api/src/main/java/com/netcourier/chatbot/service/ingestion/DefaultIngestionService.java†L1-L120】【F:chat-api/src/main/java/com/netcourier/chatbot/service/ingestion/DefaultIngestionService.java†L122-L196】
* External systems:
  * Qdrant dense retriever issues `/points/search` queries to the configured collection.【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/QdrantDenseRetriever.java†L1-L74】
  * OpenSearch sparse retriever runs `_search` against the multi-tenant index alias.【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/OpenSearchSparseRetriever.java†L1-L67】
  * Tool adapters wrap NetCourier REST endpoints for tracking jobs, rescheduling deliveries, and creating tickets.【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/TrackJobToolAdapter.java†L1-L48】【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/RescheduleDeliveryToolAdapter.java†L1-L54】【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/CreateTicketToolAdapter.java†L1-L48】
* Workflow state: `DefaultWorkflowEngine` extracts slots (e.g., job ID, reschedule window) per conversation and returns `WorkflowResult` metadata to the chat service.【F:chat-api/src/main/java/com/netcourier/chatbot/service/workflow/DefaultWorkflowEngine.java†L1-L110】
* Intent routing: `DefaultIntentRouter` matches regex patterns across turns and falls back to a configurable intent.【F:chat-api/src/main/java/com/netcourier/chatbot/service/intent/DefaultIntentRouter.java†L1-L43】
* Memory: `InMemoryMemoryService` keeps turn history in concurrent hash maps; replace with persistent storage for production.【F:chat-api/src/main/java/com/netcourier/chatbot/service/memory/InMemoryMemoryService.java†L1-L40】
* Security: `SecurityConfig` enables OAuth2 JWT resource server mode and converts roles with `JwtRoleConverter`. Adjust `authorizeExchange` rules when adding endpoints.【F:chat-api/src/main/java/com/netcourier/chatbot/security/SecurityConfig.java†L1-L45】
* Configuration defaults live in `application.yml`, including backend URLs, retriever weights, and actuator exposure.【F:chat-api/src/main/resources/application.yml†L1-L26】

### Embeddings service (Python 3.10+)

* FastAPI application built in `app/main.py` with `/embed` and `/health` routes. Models are cached per identifier and default to `BAAI/bge-m3`.【F:embeddings-service/app/main.py†L1-L61】
* `load_model` centralises SentenceTransformer loading and wraps exceptions for observability.【F:embeddings-service/app/main.py†L18-L28】
* Startup hook warms the default model to reduce first-request latency and now supports asynchronous warm-up for larger checkpoints like `bge-m3`.【F:embeddings-service/app/main.py†L38-L61】

### Chat widget (TypeScript + Lit)

* Custom element `<nc-chatbot>` defined in `src/netcourier-chatbot.ts` exposes attributes for API base URL, tenant ID, user ID, locale, UI surface, and theme.【F:chat-widget/src/netcourier-chatbot.ts†L1-L64】
* Messages are stored via Lit `@state` properties and auto-scroll after each update.【F:chat-widget/src/netcourier-chatbot.ts†L66-L114】
* Streams NDJSON responses from `/api/chat` using the Fetch API and Lit reactivity to append assistant updates as they arrive.【F:chat-widget/src/netcourier-chatbot.ts†L214-L342】

### Infrastructure

The Docker Compose stack runs Postgres, Qdrant, OpenSearch, the chat API, and the embeddings service for integration testing.【F:infra/docker-compose.yml†L1-L36】

## Local development

### Prerequisites

* Java 21 + Maven 3.9+
* Python 3.10+ with `uv` or `pip`
* Node.js 20+
* Docker (optional, for Compose stack)

### Bootstrap steps

1. Clone the repository and install tooling per language.
2. Configure environment variables as needed (see configuration section below).
3. Use the language-specific package managers inside each service directory.

#### Chat API

```bash
cd chat-api
mvn spring-boot:run
```

* Profiles: defaults to local development. Compose stack sets `SPRING_PROFILES_ACTIVE=prod` and JDBC URLs for Postgres.
* Run unit tests with `mvn test`; the ingestion suite (`DefaultIngestionServiceTest`, `FixedSizeTextChunkerTest`) demonstrates mocking collaborators and validating chunk post-processing.【F:chat-api/src/test/java/com/netcourier/chatbot/service/ingestion/DefaultIngestionServiceTest.java†L1-L79】【F:chat-api/src/test/java/com/netcourier/chatbot/service/ingestion/FixedSizeTextChunkerTest.java†L1-L62】
* For live stream testing, POST JSON payloads to `/api/chat` and read the newline-delimited response.

#### Embeddings service

```bash
cd embeddings-service
uv sync  # or pip install -e .[dev]
uvicorn app.main:app --reload
```

* `POST /embed` with `{ "texts": ["Sample"], "model": null }` to retrieve vectors (1024 dimensions, unit-normalised by default).
* The first download of `BAAI/bge-m3` exceeds 1 GB—export `HF_HUB_ENABLE_HF_TRANSFER=1` (requires `pip install hf-transfer`) to stream checkpoints faster, or pre-sync the model during image builds.
* Run tests with `uv run pytest` when test suite is expanded.

#### Chat widget

```bash
cd chat-widget
npm install
npm run dev
```

* The dev server uses Vite; configure `api-base` attribute to proxy requests to the chat API.
* `npm test` is stubbed—add Vitest suites alongside component updates.

#### Full stack

```bash
cd infra
docker compose up --build
```

This builds service images from the local sources and brings up dependencies on standard ports.【F:infra/docker-compose.yml†L1-L36】

## Coding guidelines

* Java: favour Spring stereotypes (`@Service`, `@Component`), Reactor `Flux`/`Mono` for async pipelines, and records for immutable DTOs (see models under `chat-api/src/main/java/com/netcourier/chatbot/model`).
* Python: keep FastAPI routes typed with Pydantic models (`EmbedRequest`, `EmbedResponse`) and reuse the shared `load_model` helper.【F:embeddings-service/app/main.py†L8-L23】
* TypeScript: prefer Lit reactive properties and avoid manipulating the DOM outside `render()` except for scroll helpers.【F:chat-widget/src/netcourier-chatbot.ts†L80-L114】
* Tests: co-locate under `src/test/java` (chat API) or `tests/` (Python) and use descriptive method names.

## Configuration reference

Key environment variables and properties:

| Setting | Description | Default |
| --- | --- | --- |
| `chat.qdrant.base-url` | Qdrant host for dense retrieval. | `http://localhost:6333` |
| `chat.qdrant.collection` | Collection name queried by `QdrantDenseRetriever`. | `nc_chunks_v1` |
| `chat.opensearch.base-url` | OpenSearch host for sparse retrieval. | `http://localhost:9200` |
| `chat.opensearch.index` | Index alias for `_search`. | `nc_chunks` |
| `chat.rag.dense.weight` / `chat.rag.sparse.weight` | Fusion weights inside `HybridRagService`. | `0.6` / `0.4` |
| `chat.netcourier.base-url` | Upstream NetCourier API. | `http://localhost:8085` |
| `chat.netcourier.*-path` | Tool adapter endpoints (`/jobs/track`, etc.). | See `application.yml`. |
| `SPRING_DATASOURCE_*` | JDBC connection for future persistence. | Provided by Compose stack. |

Configure these via `application.yml`, `application-*.yml`, or environment variables (`SPRING_APPLICATION_JSON`, container `env`).

## Common development tasks

* **Add a new intent**: extend `regexIntents` or implement `IntentRouter`, adjust `WorkflowEngine`, and register any tool adapters.
* **Ingest tenant knowledge**: use `POST /admin/ingest/upload` for binary files or `POST /api/ingest` for inline text. During phase-one development the static token `Authorization: Bearer DEV` is sufficient; production will replace this with JWT validation.【F:chat-api/src/main/java/com/netcourier/chatbot/controller/IngestionController.java†L1-L63】
* **Persist conversations**: replace `InMemoryMemoryService` with a persistence-backed implementation and expose repository beans.
* **Telemetry**: enable Micrometer tracing via `management.tracing.enabled=true`; `TracingConfig` wires `SpanCustomizer` when tracing is active.【F:chat-api/src/main/java/com/netcourier/chatbot/telemetry/TracingConfig.java†L1-L16】
* **Extend widget UI**: update `render()` and styles in `netcourier-chatbot.ts`, keeping properties reactive and accessible.

## Troubleshooting tips

* Stream stuck? Verify the client posts to `/api/chat`, consumes NDJSON frames, and receives `thinking`, optional `tool_result`, then `final` events.【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L47-L178】
* Retrieval returns empty results? Check upstream Qdrant/OpenSearch URLs and credentials; the retrievers log warnings and fall back to empty lists on errors.【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/QdrantDenseRetriever.java†L33-L72】【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/OpenSearchSparseRetriever.java†L33-L66】
* Tool invocation failures bubble up through `tool_result` frames—surface them in the widget or logs as needed.【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L69-L104】

Refer to `TECHNICAL_DESIGN.md` for the higher-level system design that complements this implementation guide.

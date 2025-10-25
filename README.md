# NetCourier AI Chatbot

This repository contains the reference implementation for the multi-tenant NetCourier AI Chatbot described in [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md). The codebase is organised as a polyglot monorepo with services for conversational orchestration, retrieval augmented generation (RAG) infrastructure, and the embeddable client widget.

## Project Structure

| Path | Description |
| --- | --- |
| `chat-api/` | Spring Boot (Java 21) service exposing `/api/chat` endpoints, hybrid RAG orchestration, workflow execution, and tool integrations. |
| `embeddings-service/` | FastAPI microservice exposing `/embed` and `/health` for sentence-transformer based embeddings. |
| `chat-widget/` | Web component (Lit + Vite) that embeds the chatbot widget into NetCourier Client Portal / Back Office surfaces. |
| `infra/` | Infrastructure manifests including a Docker Compose stack for local development. |

## Functional Coverage

The implementation aligns with the [NetCourier functional specification](docs/netcourier-functional-spec.md) across core capabilities:

* **Conversational routing & streaming** – `DefaultChatService` persists turns, emits `thinking` / partial / `tool_result` / `final` NDJSON frames, and enforces citation/guardrail metrics while falling back to "I don't know" when no tenant snippets are available.【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L42-L147】【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L149-L203】
* **Hybrid RAG retrieval** – `HybridRagService` fuses Qdrant dense results and OpenSearch BM25 hits, with retrievers applying tenant + role filters to prevent data leakage.【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/HybridRagService.java†L18-L71】【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/QdrantDenseRetriever.java†L24-L98】【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/OpenSearchSparseRetriever.java†L24-L99】
* **Intent detection & workflows** – `DefaultIntentRouter` covers regex baselines and optional LLM classification, while `StateMachineWorkflowEngine` manages slot-filling for Track Job, Reschedule Delivery, and Ticket flows and persists state per conversation/workflow pair.【F:chat-api/src/main/java/com/netcourier/chatbot/service/intent/DefaultIntentRouter.java†L18-L62】【F:chat-api/src/main/java/com/netcourier/chatbot/service/intent/LlmIntentClassifier.java†L23-L103】【F:chat-api/src/main/java/com/netcourier/chatbot/service/workflow/StateMachineWorkflowEngine.java†L31-L189】
* **Tool execution** – Workflow-ready states invoke tool adapters and emit structured tool result payloads so clients can render operational feedback.【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L69-L112】【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/ToolRegistry.java†L15-L63】
* **Knowledge ingestion** – `IngestionController` and `DefaultIngestionService` support admin file uploads and plain-text ingestion, enrich metadata, enforce default CP/BO roles, and synchronise embeddings/search indexes with deduplication guards.【F:chat-api/src/main/java/com/netcourier/chatbot/controller/IngestionController.java†L24-L75】【F:chat-api/src/main/java/com/netcourier/chatbot/service/ingestion/DefaultIngestionService.java†L41-L200】
* **Embeddable client widget** – The Lit-based `<nc-chatbot>` component streams NDJSON responses, surfaces guardrail notices, and optionally exposes drag-and-drop ingestion to portal users.【F:chat-widget/src/netcourier-chatbot.ts†L1-L221】【F:chat-widget/src/netcourier-chatbot.ts†L242-L487】

## Getting Started

### Prerequisites

* Java 21 and Maven 3.9+
* Python 3.10+
* Node.js 20+
* Docker (optional, for running the full stack)

### Running the Chat API

```bash
cd chat-api
mvn spring-boot:run
```

The service exposes an NDJSON streaming endpoint under `http://localhost:8080/api/chat` and auto-generates OpenAPI docs at `/swagger-ui.html`.

#### Document ingestion endpoints

Phase-one of the ingestion workflow ships two administrative endpoints that ingest tenant knowledge into Qdrant and OpenSearch:

* `POST /admin/ingest/upload` accepts `multipart/form-data` uploads for PDF, DOCX, and TXT files.
* `POST /api/ingest` ingests plain text payloads as JSON.

Both endpoints expect a tenant identifier, optional title, and optional role tags. When no roles are supplied, the service tags chunks with the default `CP` and `BO` roles so they are visible to both customer-portal and back-office users. Authentication is handled by the WebFlux security filter chain:

* Set `chat.security.static-token` (for example export `CHAT_SECURITY_STATIC_TOKEN=DEV`) to require a shared bearer token that must be supplied via the `Authorization` header.
* Leave the property unset to use the default OAuth2 JWT resource server configuration.

Example file upload using `curl`:

```bash
curl -X POST "http://localhost:8080/admin/ingest/upload" \
  -H "Authorization: Bearer DEV" \
  -F tenantId=ILG \
  -F title="Delivery SOP" \
  -F roles=CP \
  -F file=@docs/sample.pdf
```

Successful ingestion returns a JSON summary containing the generated document identifier and the number of chunks pushed to the vector and search stores.

### Running the Embeddings Service

```bash
cd embeddings-service
uvicorn app.main:app --reload
```

### Running the Widget

```bash
cd chat-widget
npm install
npm run dev
```

### Local Full Stack

```bash
cd infra
docker compose up --build
```

This command provisions Qdrant, OpenSearch, PostgreSQL, the chat API, and the embeddings service with sensible defaults for evaluation and development.

## Testing

* `cd chat-api && mvn test`
* `cd embeddings-service && uv run pytest` (requires `uv` or `pip install -e .[dev]`)
* `cd chat-widget && npm test` (tests TBD)

## Security & Observability

The implementation includes JWT-based authentication hooks, role conversion, and placeholders for audit logging, tracing, and metrics that align with the system-level design. For local or shared-secret deployments, configure `chat.security.static-token` to enforce a static bearer token; omitting the property keeps the OAuth2 JWT resource server flow enabled.

## Next Steps

* Wire persistent Postgres repositories for conversations, messages, and workflows.
* Connect the retrieval clients to managed Qdrant/OpenSearch clusters with production schemas.
* Extend workflow state handling with Spring StateMachine actions and guard rails.
* Add comprehensive unit/integration tests across modules.

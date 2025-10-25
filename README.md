# NetCourier AI Chatbot

This repository contains the reference implementation for the multi-tenant NetCourier AI Chatbot described in [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md). The codebase is organised as a polyglot monorepo with services for conversational orchestration, retrieval augmented generation (RAG) infrastructure, and the embeddable client widget.

## Project Structure

| Path | Description |
| --- | --- |
| `chat-api/` | Spring Boot (Java 21) service exposing `/api/chat` endpoints, hybrid RAG orchestration, workflow execution, and tool integrations. |
| `embeddings-service/` | FastAPI microservice exposing `/embed` and `/health` for sentence-transformer based embeddings. |
| `chat-widget/` | Web component (Lit + Vite) that embeds the chatbot widget into NetCourier Client Portal / Back Office surfaces. |
| `infra/` | Infrastructure manifests including a Docker Compose stack for local development. |

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

The service exposes REST and SSE endpoints under `http://localhost:8080/api/chat` and auto-generates OpenAPI docs at `/swagger-ui.html`.

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

The implementation includes JWT-based authentication hooks, role conversion, and placeholders for audit logging, tracing, and metrics that align with the system-level design.

## Next Steps

* Wire persistent Postgres repositories for conversations, messages, and workflows.
* Connect the retrieval clients to managed Qdrant/OpenSearch clusters with production schemas.
* Extend workflow state handling with Spring StateMachine actions and guard rails.
* Add comprehensive unit/integration tests across modules.

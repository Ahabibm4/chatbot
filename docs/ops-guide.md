# NetCourier Chatbot Operations Guide

This runbook provides operational guidance for deploying, configuring, monitoring, and troubleshooting the NetCourier AI chatbot services in production-like environments.

## System overview

The chatbot platform comprises three deployable services plus shared dependencies:

| Component | Purpose | Key ports |
| --- | --- | --- |
| Chat API (`chat-api/`) | Spring Boot WebFlux service exposing an NDJSON streaming chat endpoint, orchestrating intent routing, RAG retrieval, and workflow automation.【F:chat-api/src/main/java/com/netcourier/chatbot/controller/ChatController.java†L1-L34】【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L1-L178】 | 8080 |
| Embeddings service (`embeddings-service/`) | FastAPI app serving `/embed` vectors and `/health` checks with cached SentenceTransformer models.【F:embeddings-service/app/main.py†L1-L46】 | 8000 |
| Chat widget (`chat-widget/`) | Static bundle / web component that calls the Chat API from customer portals.【F:chat-widget/src/netcourier-chatbot.ts†L1-L198】 | Served via CDN |
| Dependencies | PostgreSQL, Qdrant, OpenSearch; provided in local Compose but use managed services in production.【F:infra/docker-compose.yml†L1-L36】 | 5432 / 6333 / 9200 |

## Deployment considerations

### Chat API

* Package with Maven: `mvn -pl chat-api -am clean package`. The resulting JAR runs with `java -jar chat-api/target/chat-api-*.jar`.
* Configure environment via Spring properties or environment variables. Critical keys (with defaults) are defined in `application.yml` and should be overridden per environment for hostnames, credentials, and retriever weighting.【F:chat-api/src/main/resources/application.yml†L1-L26】
* Requires outbound HTTPS access to NetCourier REST endpoints configured in `chat.netcourier.base-url` and tool-specific paths.【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/TrackJobToolAdapter.java†L1-L48】【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/RescheduleDeliveryToolAdapter.java†L1-L54】【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/CreateTicketToolAdapter.java†L1-L48】
* Ensure OAuth2 JWT issuer/audience matches the `SecurityConfig` expectations; add identity provider JWK metadata through `spring.security.oauth2.resourceserver.jwt.*`.【F:chat-api/src/main/java/com/netcourier/chatbot/security/SecurityConfig.java†L1-L37】
* Persistent storage: replace `InMemoryMemoryService` with a JDBC-backed implementation before production to avoid data loss across restarts.【F:chat-api/src/main/java/com/netcourier/chatbot/service/memory/InMemoryMemoryService.java†L1-L40】
* Scaling: the service is reactive and CPU-bound by model orchestration latency. Scale horizontally behind a load balancer; sticky sessions are not required if conversation state is externalised.

### Embeddings service

* Build a container image using the provided Dockerfile context or run `uvicorn app.main:app --host 0.0.0.0 --port 8000` in production.
* Provision GPU instances if higher throughput or larger models are required; adjust the default model via `EMBEDDINGS_DEFAULT_MODEL` env var mapped to the FastAPI factory parameter.
* Monitor memory usage—SentenceTransformer models are cached in-process per identifier.【F:embeddings-service/app/main.py†L24-L46】 Use auto-scaling policies based on resident set size.

### Chat widget

* Build with `npm run build` and host the generated assets on a CDN or static bucket. Integrate by embedding `<nc-chatbot>` and configuring `api-base`, `tenant-id`, and `user-id` attributes.【F:chat-widget/src/netcourier-chatbot.ts†L1-L36】
* For multi-tenant deployments, inject tenant metadata server-side and restrict widget origins through CSP / CORS on the Chat API.

## Configuration matrix

| Variable | Description | Recommended source |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Profile controlling datasource and observability config. | Environment variable or config server |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | JDBC connection for conversation persistence. | Secrets manager |
| `chat.qdrant.*` | Qdrant endpoint, collection, and authentication if required. | Secrets manager / config map |
| `chat.opensearch.*` | OpenSearch endpoint, index alias, credentials. | Secrets manager |
| `chat.netcourier.*` | Downstream NetCourier API base URL and tool paths. | Config map |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | Extend actuator surface (defaults to health/info/metrics). | Config map |
| `EMBEDDINGS_DEFAULT_MODEL` | Override FastAPI default embeddings model. | Config map |

Store secrets in dedicated secret stores (Vault, AWS Secrets Manager). Inject via environment variables and restrict logging of sensitive values.

## Networking

* Expose the Chat API through an ingress that permits long-lived HTTP responses. `/api/chat` returns newline-delimited JSON; disable buffering so clients receive frames promptly.【F:chat-api/src/main/java/com/netcourier/chatbot/controller/ChatController.java†L1-L34】
* Allow outbound access from Chat API pods to Qdrant, OpenSearch, PostgreSQL, and NetCourier APIs.
* Embeddings service must reach Hugging Face or internal model registries during cold starts; pre-bake models into images to avoid egress when restricted.

## Monitoring & observability

* Metrics / traces: enable Micrometer tracing (`management.tracing.enabled=true`) so that `TracingConfig` exposes `SpanCustomizer`. Connect to OpenTelemetry collectors for distributed traces.【F:chat-api/src/main/java/com/netcourier/chatbot/telemetry/TracingConfig.java†L1-L16】
* Health checks:
  * Chat API: `/actuator/health` and `/actuator/info` (permitlisted in `SecurityConfig`).【F:chat-api/src/main/java/com/netcourier/chatbot/security/SecurityConfig.java†L24-L33】
  * Embeddings: `/health` returns `{ "status": "ok" }`.【F:embeddings-service/app/main.py†L40-L44】
* Log aggregation: standardise on JSON logging for containers; ensure sensitive payloads (chat transcripts) are redacted before shipping logs.
* SLOs: track request latency for `/api/chat`, stream completion rates, retrieval success rate (non-empty lists), TTFT metrics, and tool execution success ratio (`ToolExecutionResult.success`).【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L62-L132】【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L146-L178】

## Runbooks

### Chat API returns 5xx

1. Inspect recent logs for retrieval or tool adapter failures (look for `Failed to query Qdrant` / `OpenSearch query failed`).【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/QdrantDenseRetriever.java†L46-L72】【F:chat-api/src/main/java/com/netcourier/chatbot/service/retrieval/OpenSearchSparseRetriever.java†L47-L66】
2. Validate upstream dependency health:
   * Qdrant `/collections/{collection}`
   * OpenSearch `/_cluster/health`
   * NetCourier API endpoints referenced in adapters.
3. If failures relate to JWT authentication, confirm identity provider availability and token audience configuration per `SecurityConfig`.

### Stream stalls

1. Confirm the client keeps the HTTP connection open and that load balancers allow streaming (disable response buffering).
2. Check that `DefaultChatService.streamChat` emits `thinking`, optional `tool_result`, then `final` frames; absence indicates an exception before completion—review service logs.【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L47-L178】
3. Validate retrieval latency; empty results still emit responses, but network timeouts from Qdrant/OpenSearch may delay completion.

### Tool invocation fails repeatedly

1. Identify which tool was requested via `tool_result` frames or workflow summary in the sync response.【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L69-L104】【F:chat-api/src/main/java/com/netcourier/chatbot/service/DefaultChatService.java†L129-L175】
2. Use the corresponding adapter to replay the API call manually (e.g., GET `/jobs/track`). Paths are configurable—verify environment values.【F:chat-api/src/main/java/com/netcourier/chatbot/service/tools/TrackJobToolAdapter.java†L19-L48】
3. Escalate to the NetCourier upstream team if downstream services return non-2xx responses.

## Disaster recovery

* Database backups: schedule automated snapshots of the conversation/message store (PostgreSQL or future persistence layer). Test restores quarterly.
* Vector indices: enable replication/backup policies on Qdrant and OpenSearch clusters. Maintain infrastructure-as-code for collection/index recreation.
* Restore procedure: deploy clean infrastructure, restore databases, repopulate indices, and redeploy Chat API + embeddings service images.

## Change management

* Follow Git-based workflows with peer review. Execute unit tests (`mvn test`, `pytest`, `npm test`) and integration smoke tests via Compose before deployment.
* Maintain semantic versioning per service (e.g., tag Chat API releases) to coordinate front-end and backend rollouts.

## Security & compliance

* Ensure JWT validation is enforced—`SecurityConfig` authenticates all non-actuator endpoints. Augment with multi-tenant scoping in controller/service layers.【F:chat-api/src/main/java/com/netcourier/chatbot/security/SecurityConfig.java†L19-L33】
* Audit logging: extend `DefaultChatService` to emit structured logs for user actions and tool executions before go-live.
* Data retention: define policies for chat transcripts stored in future persistence layers; implement PII redaction routines when exporting data.

Keep this document alongside your incident response playbooks and update it whenever new capabilities or dependencies are introduced.

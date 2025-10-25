# NetCourier AI Chatbot – Technical Design & Implementation Specification

**Project:** NetCourier AI Chatbot  \
**Version:** 1.0  \
**Date:** October 2025  \
**Owner:** Technical Lead (Ahsan Habib)  \
**Audience:** Engineering, DevOps, Product, Security

---

## 1. System Overview

The NetCourier AI Chatbot is a multi-tenant, retrieval-augmented generation (RAG) and workflow-based assistant embedded in both the Client Portal (CP) and Back Office (BO). The assistant:

* Answers FAQs and SOP queries drawn from tenant-specific and global documents.
* Executes tool workflows such as job tracking, rescheduling, and ticket creation.
* Maintains multi-turn conversation context.
* Embeds as a widget with one-line configuration.

## 2. High-Level Architecture

```
[Chat Widget] ──► [Spring Boot Chat API] ──► [Intent Router] ──► [RAG Service]
                                                       │
                                                       ├─► [Vector DB: Qdrant]
                                                       ├─► [Sparse Search: OpenSearch]
                                                       ├─► [Tools: NetCourier APIs]
                                                       └─► [LLM Runtime: vLLM/LM Studio]

[Admin CI/CD] ──► [Ingest API] ──► [File Normalizer → Chunker → Embeddings Service]
                                          │
                                          ├─► Qdrant (vector points + metadata)
                                          └─► OpenSearch (BM25 index)
```

## 3. Core Components

### 3.1 Spring Boot Chat API

* **Framework:** Java 21, Spring Boot 3.4
* **Modules:**
  * `ChatController` → `/api/chat` (streaming JSONL/SSE)
  * `IntentRouter` → regex + LLM JSON classifier
  * `RagService` → hybrid retrieval (Qdrant + OpenSearch, RRF fusion)
  * `WorkflowEngine` → slot-filling and state machine (Spring Statemachine)
  * `MemoryService` → conversation and workflow persistence in PostgreSQL
  * `ToolAdapters` → NetCourier APIs (job tracking, driver, tickets)
* **Persistence:** PostgreSQL tables for `conversations`, `messages`, `workflows`, and `tool_runs`.
* **Security:** JWT auth (tenant, roles from claims), RBAC per intent/tool, TLS, CSRF, CORS allowlist, and audit logs for all tool invocations.

### 3.2 Embeddings Service

* **Language:** Python 3.10+ (FastAPI)
* **Models:** `BAAI/bge-m3` (1024-dim) or `gte-small` (384-dim, faster)
* **Endpoints:**
  * `POST /embed` → `{ "texts": [...] }` → `{ "vectors": [[...]] }`
  * `GET /health`
* **Scaling:** Stateless; autoscale horizontally; CPU-friendly.

### 3.3 Vector Database (Qdrant)

* **Collection schema:** `nc_chunks_vN`
* **Vector size:** 1024 (cosine distance)
* **Payload example:**

```json
{
  "tenant_id": "ILG" | "GLOBAL",
  "roles": ["CP", "BO"],
  "doc_id": "UUID",
  "chunk_id": 17,
  "title": "SOP Delivery",
  "page": 3,
  "text": "Deliveries after 6 PM...",
  "hash": "sha256:…"
}
```

* **Isolation:** Queries filtered by `tenant_id` and `roles`.
* **Operations:** Snapshots daily to S3; replication factor = 2.

### 3.4 Sparse Search (OpenSearch)

* **Indexing:** Index per version (`nc_chunks_vN`).
* **Fields:** `tenant_id` (keyword), `roles` (keyword), `title` (text), `page` (int), `text` (full-text).
* **Alias:** `nc_chunks` points to active version.

### 3.5 LLM Runtime

* **Dev:** LM Studio (OpenAI API compatible).
* **Prod:** vLLM on GPU (Llama 3.1 8B/13B Instruct).
* **Endpoint:** `/v1/chat/completions` (streaming + non-stream).
* **Guardrails:** Max 1500 output tokens; temperature 0.3–0.5.

### 3.6 Chat Widget

* **Tech:** React + Vite compiled as a Web Component (UMD bundle).
* **Configuration:**

```html
<nc-chatbot
   api-base="/api"
   tenant-id="ILG"
   user-id="u1"
   ui="CP"
   locale="en"
   theme="dark">
</nc-chatbot>
```

* **Features:** SSE JSONL streaming parser, auto-reconnect on transient errors, CSS variables for theming, works in Thymeleaf, React, Angular, or plain HTML.

## 4. Data Flow

### 4.1 Ingestion

1. Upload file → `/admin/ingest/upload`.
2. Normalize text (PDFBox, POI, Tika, OCR fallback).
3. Chunk text (token-aware, 500–800 tokens).
4. Call embeddings service → get vectors.
5. Upsert into Qdrant (points with vectors + payload).
6. Upsert into OpenSearch (BM25 index with same payload).
7. Update manifest (`docId`, `sha256`) to avoid duplicates.

### 4.2 Retrieval

1. User query reaches Intent Router.
2. If RAG, run hybrid search:
   * Qdrant dense top-k.
   * OpenSearch sparse top-k.
   * Reciprocal rank fusion (RRF); boost tenant docs over global.
3. Insert top 3–5 chunks into prompt with citations.
4. LLM generates answer; output streamed with `[Title · p.X]` citations.

### 4.3 Workflows (Example: Reschedule Delivery)

1. User: “Reschedule job NC123456 to tomorrow morning.”
2. Router detects `RESCHEDULE_DELIVERY` intent.
3. Workflow engine checks missing slots (`jobId`, `newWindow`).
4. If missing, chatbot asks follow-up questions.
5. If filled, call NetCourier API.
6. Store result in `tool_runs`; confirm outcome to user.

## 5. CI/CD

### 5.1 App Delivery Pipeline

1. Build → Test → Dockerize (app, embeddings, widget).
2. Deploy to staging → Manual approval → Deploy to prod (Helm/K8s).
3. Rollback: `helm rollback chatbot <REV>`.

### 5.2 Knowledge Delivery

* **Incremental:**
  * On doc change in `docs/**` → `POST /admin/ingest/upload`.
  * Ingest new/changed docs into active collection/index.
* **Full Rebuild:**
  1. Create new `vN+1` (Qdrant + OpenSearch).
  2. Re-ingest all docs.
  3. Validate (counts, sample queries, eval gates).
  4. Swap alias / update app env → traffic goes to `vN+1`.
  5. Rollback = revert alias/env to previous version.

## 6. Security

* JWT authentication → tenant and roles from claims.
* RBAC checks for tools.
* CORS allowlist for CP/BO domains.
* TLS for all communications.
* File uploads: virus scan, size ≤ 20 MB.
* Secrets: Vault/KMS managed.
* Privacy: redact PII from logs; retention policy for conversations.

## 7. Observability

* **Tracing:** OpenTelemetry spans across chat → router → retrieval → tool → LLM.
* **Metrics:** Prometheus (chat latency, retrieval hit@k, tool errors, token counts).
* **Logs:** JSON with `correlationId`, `sessionId`, `tenantId`.
* **Dashboards:** Grafana for SLA compliance, error budgets, cost tracking.

## 8. Non-Functional Requirements

* **Availability:** 99.9% uptime.
* **Latency:** P50 < 2 s, P95 < 6 s.
* **Concurrency:** ≥300 concurrent streams, scalable via autoscaling.
* **Data isolation:** Strict tenant filters with audit tests.
* **Cost control:** Per-tenant token quotas, autoscaling caps, budget alerts.

## 9. Risks & Mitigations

* **Model drift/hallucination:** Hybrid RAG, strict citations, evaluation gates.
* **Tenant leakage:** Server-side filters, integration tests, audits.
* **Scaling costs:** GPU autoscaling, quotas, nightly reports.
* **Data inconsistency on updates:** Version + alias swap, rollback support.


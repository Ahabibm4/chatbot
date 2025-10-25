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

## 10. Disaster Recovery & Business Continuity

* **RTO / RPO Targets:** Define recovery time objective (RTO) of < 30 minutes for application services and recovery point objective (RPO) of < 15 minutes for conversational data and knowledge store indexes.
* **Cross-Region Redundancy:** Deploy active/passive replicas of Kubernetes workloads, PostgreSQL, Qdrant, and OpenSearch in a secondary region with asynchronous replication; maintain warm standby LLM runtime capacity.
* **Backups & Snapshots:** Automate encrypted daily backups of PostgreSQL and object storage snapshots of Qdrant/OpenSearch; retain for 30 days with weekly integrity verification restores in staging.
* **Runbooks & Incident Response:** Maintain documented failover runbooks, DR drills at least twice per year, and post-incident reviews with corrective actions tracked in Jira.

## 11. Compliance & Data Governance

* **Regulatory Alignment:** Map controls to GDPR, SOC 2, and contractual obligations; document data processing activities and maintain records of processing for each tenant.
* **Data Retention & Deletion:** Enforce configurable retention periods per tenant for conversations and tool runs; implement automated deletion workflows and support data subject access/erasure requests.
* **Access Governance:** Centralize secret management (Vault/KMS), quarterly access reviews, and least-privilege IAM policies for operations staff and service accounts.
* **Auditability:** Emit immutable audit logs for ingest actions, administrative changes, and tool executions; store logs for a minimum of one year in write-once object storage.

## 12. Testing & Evaluation Strategy

* **Automated Testing:** Integrate unit, contract, and integration test suites for the Spring Boot API, embeddings service, and widget within CI; enforce coverage thresholds and static analysis (SpotBugs, ESLint, mypy/ruff).
* **Performance & Load Testing:** Execute scheduled k6/Gatling load tests to validate P95 < 6s and concurrency targets; capture regressions via automated thresholds in CI/CD.
* **RAG Quality Evaluation:** Maintain curated evaluation datasets per tenant; run nightly offline benchmarks measuring accuracy, citation correctness, and hallucination rates with pass/fail gates.
* **Chaos & Resilience Testing:** Periodically inject failures (pod restarts, dependency outages) in staging to validate graceful degradation, retries, and DR procedures.

## 13. Accessibility & Localization

* **Accessibility Standards:** Ensure the chat widget complies with WCAG 2.1 AA, including keyboard navigation, ARIA roles, focus management, and contrast ratios; include accessibility testing in CI.
* **Localization Pipeline:** Support i18n resource bundles with fallback locales, pluralization rules, and tenant-level language overrides; automate translation extraction and validation.
* **Content Considerations:** Provide localized system prompts, tool messages, and error responses; ensure citation formatting adapts to locale conventions.

## 14. Rate Limiting & Abuse Prevention

* **Traffic Controls:** Implement tenant- and user-level throttling on chat and ingest endpoints (e.g., token buckets) with burst allowances aligned to SLAs.
* **Anomaly Detection:** Monitor for unusual traffic patterns, token consumption spikes, or repeated tool failures; trigger automated alerts and temporary rate adjustments.
* **Abuse Mitigations:** Integrate WAF/bot detection, request validation, and content filtering for prompt injection; quarantine suspicious sessions pending review.
* **Operational Response:** Provide runbooks for abuse incidents, including escalation paths, evidence capture, and remediation steps coordinated with security operations.


# NetCourier Enterprise Chatbot Functional Specification

## 1. Purpose & Scope

Deliver a multi-tenant, embeddable AI assistant that:

* Answers questions using company knowledge (RAG on PDFs/DOCX/TXT).
* Understands intents (FAQ, job tracking, rescheduling, policy lookup).
* Executes guided workflows (e.g., track job, reschedule delivery).
* Streams responses, cites sources, and preserves conversation context.
* Supports role-aware access, admin ingestion, and CI/CD for knowledge.

Out of scope (phase-1): human handoff, live chat routing, payment actions.

## 2. Users & Personas

* **End User (Customer Portal "CP")**: Consignees, shippers. Ask FAQs, track jobs.
* **Back Office (BO)**: Operations staff. Deeper SOP/policy queries.
* **Admin (Tenant Ops/IT)**: Upload docs, manage tenants, view metrics.
* **Platform Owner**: Multi-tenant configuration, guardrails, usage limits.

## 3. Success Criteria (KPIs)

* ≥85% answer coverage on curated FAQ topics.
* ≤3s P50, ≤6s P95 time-to-first-token (TTFT) on local LLM.
* ≥95% citation presence when knowledge context is used.
* 0 data leaks across tenants (verified by tests).
* ≥99.9% API uptime (SLO) for chat endpoints in production.

## 4. High-Level Capabilities

1. **Conversational QA (RAG)**

   * Hybrid retrieval: dense (Qdrant) + sparse (OpenSearch) with RRF fusion.
   * Source citations `[Title · p.X]`.
   * Role gating (by UI role like CP/BO) and tenant scoping (Tenant + GLOBAL).

2. **Intent Recognition & Routing**

   * Baseline regex (NC\d+ detection, "reschedule", etc.) + LLM fallback (phase-2).
   * Routes to: RAG answer | Tool call (Track Job) | Workflow (Reschedule).

3. **Workflows / Task Flows**

   * Step-by-step or single-shot operations (e.g., reschedule job).
   * Validations and confirmations before commit.
   * Emits tool_result events in stream.

4. **Conversation Memory**

   * Stores messages with session ID; short-term context for better answers.
   * Data residency per tenant in Postgres.

5. **Knowledge Admin**

   * File upload: PDF/DOCX/TXT → Tika extract → chunk → embed → upsert.
   * Multi-tenant & GLOBAL document namespaces.
   * Incremental ingest via CI on git changes.

6. **Embeddable Widget**

   * Vanilla JS Web Component `<nc-chat>` easy to embed in CP or BO pages.
   * Streams NDJSON, shows typing state and final message.

## 5. Detailed Functional Requirements

### 5.1 Chat

* **Endpoint**: `POST /api/chat` (NDJSON stream)
* **Request**

  * `sessionId` (UUID, required)
  * `message` (string, required)
  * `context`: `{ tenantId, userId, ui, locale }` (all required)
* **Behavior**

  1. Create/ensure conversation row by `sessionId`.
  2. Save user message.
  3. Classify intent:

     * `TRACK_JOB` if message has `NC\d{6,}` or "track job".
     * `RESCHEDULE` if "reschedule" keywords.
     * else `RAG_QA`.
  4. Execute route:

     * **RAG_QA**: retrieve (tenant + GLOBAL), fuse, build prompt, stream LLM completion.
     * **TRACK_JOB** (phase-1 stub): return status with placeholder data.
     * **RESCHEDULE** (phase-1 stub): return confirmation with target window.
  5. Save assistant reply (marker "[streamed]" if streamed).
* **Stream Events (NDJSON)**

  * `{"type":"thinking","text":"router"}` (once)
  * (optional) intermediate chunks (LLM raw chunks)
  * `{"type":"tool_result","text":"...", "data":{...}}` for tool outputs
  * `{"type":"final","text":"<final answer or summary>"}`

**Acceptance Criteria**

* AC-CHAT-01: TTFT ≤3s P50 with LM Studio local model.
* AC-CHAT-02: When RAG path, answer includes citation(s) if snippets found.
* AC-CHAT-03: If no snippet passes confidence, assistant says "I don’t know" and invites file upload or re-phrase.
* AC-CHAT-04: Tool routes return structured tool_result event before final.

### 5.2 RAG Retrieval

* **Inputs**: `tenantId`, `roles` (from `ui`), `query`.
* **Process**:

  * Dense search (Qdrant) top-K for Tenant and GLOBAL.
  * Sparse search (OpenSearch BM25) top-K for Tenant and GLOBAL.
  * RRF fuse (rank-based) with small tenant preference bonus.
  * Filter snippets by role overlap (doc roles ∩ user roles ≠ ∅).
  * Return N snippets with text, title, page.
* **Output Guarantees**

  * Top snippets reflect both semantic and lexical signal.
  * Snippets never cross tenant boundaries.

**Acceptance Criteria**

* AC-RAG-01: If snippets exist, at least 1 citation is shown in final.
* AC-RAG-02: Queries never return other-tenant payloads (unit/integration tests).

### 5.3 Ingestion (Admin)

* **Endpoint (file)**: `POST /admin/ingest/upload` (multipart/form-data)

  * Headers: `Authorization: Bearer <JWT>` (phase-1: "DEV" placeholder)
  * Params: `tenantId` (required), `file` (required)
* **Endpoint (plain text)**: `POST /api/ingest` (form)

  * Params: `tenantId`, `title`, `text`.
* **Process**

  * Parse file with Tika → text.
  * Chunk (by characters/semantic headings), overlap for context continuity.
  * Embed via embeddings service (bge-m3 normalized).
  * Upsert to Qdrant (vector, payload: tenantId, title, page, roles).
  * Index to OpenSearch (BM25).
* **Roles**

  * Default roles = `["CP","BO"]`; admin may pass role tags (phase-2).
* **Response**

  * `IngestUploadResponse { tenantId, docId, chunks }`.

**Acceptance Criteria**

* AC-INGEST-01: Upload returns 200 with docId and chunk count.
* AC-INGEST-02: Qdrant vector size matches embedding size (1024).
* AC-INGEST-03: OpenSearch doc count matches chunk count.
* AC-INGEST-04: Tenant filter field present in both stores.

### 5.4 Intent Recognition & Workflows

* **IntentService** baseline: regex rules for TRACK_JOB and RESCHEDULE. (Phase-2: LLM classification with confidence threshold.)
* **Workflows (examples)**

  * **Track Job**: detect `NC\d+`, call NetCourier tracking API (stub returns "In transit, ETA 12:30").
  * **Reschedule**: detect `reschedule`, collect date window, call API (stub success).
* **Behavior**

  * If info missing (e.g., jobId), prompt for it (final event with clarification).
  * On success, output tool_result followed by concise confirmation.

**Acceptance Criteria**

* AC-INTENT-01: Regex routes activate reliably with sample phrases.
* AC-WORKFLOW-01: Tool_result event structure validated in UI widget.

### 5.5 Conversation Memory

* **Storage**: Postgres tables `conversations`, `messages`.
* **Policy**

  * Keep last N messages for context window management (configurable).
  * PII: store minimal info; rely on sessionId and tenantId + userId context.
* **Access**

  * Messages always scoped by conversationId.

**Acceptance Criteria**

* AC-MEM-01: New sessionId creates conversation entry.
* AC-MEM-02: Messages persist in correct order; query returns last N in ascending order.

### 5.6 Embeddable Widget

* **Delivery**: Served statically from `/widget/nc-widget.js`; or directly included from the UI package.
* **Mount API**

  ```html
  <div id="nc-chat" data-api-base="/api" data-tenant="ILG" data-user="u1" data-ui="CP" data-locale="en"></div>
  <script src="/widget/nc-widget.js"></script>
  <script>NCChat.mount("#nc-chat");</script>
  ```
* **Behavior**

  * POSTs to `/api/chat`, reads NDJSON lines, appends assistant message on `final`.
  * Shows simple typing and supports scroll-to-bottom.
  * Minimal CSS; theming via host page CSS (phase-2).

**Acceptance Criteria**

* AC-WIDGET-01: Works in any page with 2 lines of integration.
* AC-WIDGET-02: Displays streamed final message and basic errors.

### 5.7 Security & Multi-Tenancy

* **Tenant boundary**: `tenantId` mandatory in all calls; retrieval and ingest enforce it.
* **GLOBAL corpus**: Read-only to all tenants; still role-filtered.
* **Auth (phase-1)**: Admin endpoints accept "DEV" token; replace with JWT in phase-2.
* **RBAC**: Role tags on documents vs `ui` (CP/BO…) in chat context.

**Acceptance Criteria**

* AC-SEC-01: Cross-tenant access attempts return no results.
* AC-SEC-02: Admin endpoints deny requests without token (phase-1 placeholder allowed in dev).

### 5.8 Observability & Errors

* **Logs**: Per request correlation ID; log route taken and duration buckets.
* **Metrics**: TTFT, tokens, retrieval latency, hit rate, error rate.
* **Errors**:

  * 400 validation errors (missing fields).
  * 401/403 admin auth failures.
  * 500 downstream/tool failures with safe user messaging and log detail.

**Acceptance Criteria**

* AC-OBS-01: Basic counters visible (Micrometer/Actuator ready).
* AC-ERR-01: Clear error messages without leaking internals to end users.

## 6. Data Model (Logical)

* **Conversation**: `{ id(UUID), tenantId, userId, createdAt, lastActivity }`
* **Message**: `{ id(UUID), conversationId, role(user|assistant|tool), content, meta(jsonb), createdAt }`
* **Vector Payload (Qdrant)**: `{ tenant_id, doc_id, chunk_id, title, page, text, roles[] }`
* **Search Doc (OpenSearch)**: `{ tenant_id, doc_id, chunk_id, title, page, text, roles[] }`

## 7. External Integrations

* **LLM Runtime**: LM Studio OpenAI-compatible (phase-2: vLLM).
* **Embeddings**: FastAPI service (`/embed`), model `BAAI/bge-m3`.
* **Vector DB**: Qdrant (`/collections/{collection}/points` upsert, `search`).
* **Search**: OpenSearch (`/{index}/_doc/{id}` upsert, `/_search`).
* **NetCourier APIs**: stubs; real endpoints wired in phase-2.

## 8. Non-Functional Requirements (NFRs)

* **Performance**: TTFT ≤3s P50; ingestion ≤5 min for 500-page PDF on dev VM.
* **Scalability**: Stateless app; Qdrant and OpenSearch scalable independently.
* **Resilience**: Retry embeddings/search once on transient 5xx; circuit breaker (phase-2).
* **Security**: JWT for admin; network ACLs for data stores; TLS for public endpoints (phase-2).
* **Compliance**: No cross-tenant data mixing; audit logs for admin actions.

## 9. CI/CD (Functional View)

* **App CI**: Build and test on pushes to `/app/**`. Artifacts for deployment.
* **KB Incremental CI**: On `docs/**` changes, upload modified files to `/admin/ingest/upload`.
* **KB Full Rebuild**: Manual trigger with version tag; rebuild index and alias swap (phase-2).

## 10. Rollout & Phasing

* **Phase-1 (POC-plus)**: Features in this spec using LM Studio; regex intents; stub tools.
* **Phase-2 (Pilot)**: JWT auth for admin, vLLM runtime, real NetCourier tool calls, improved chunking & eval suite.
* **Phase-3 (GA)**: Guardrails, RBAC policies, analytics, dashboards, autoscaling, disaster recovery.

## 11. Acceptance Test Matrix (Samples)

* **RAG**: Ask “What are delivery windows?” after ingesting SOP → response contains `9-12, 12-3, 3-6` with `[SOP · p.X]`.
* **Intent**: “Track NC123456” → tool_result + final summary.
* **Multi-tenant**: Ingest in Tenant A; query in Tenant B → no results unless in GLOBAL.
* **Widget**: Embed on a static page, receive streamed final message.

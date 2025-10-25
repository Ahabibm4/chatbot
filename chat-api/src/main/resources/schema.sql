CREATE TABLE IF NOT EXISTS conversations (
    conversation_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS chat_turns (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT,
    sequence_number INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_chat_turn_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_turns_conversation_sequence ON chat_turns (conversation_id, sequence_number);

CREATE TABLE IF NOT EXISTS workflow_states (
    conversation_id VARCHAR(64) NOT NULL,
    workflow_id VARCHAR(64) NOT NULL,
    state VARCHAR(64) NOT NULL,
    slots_json TEXT,
    last_response TEXT,
    tool_name VARCHAR(128),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, workflow_id)
);

CREATE TABLE IF NOT EXISTS document_ingestions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(128) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL,
    chunks INTEGER NOT NULL,
    external_id VARCHAR(256),
    title VARCHAR(512),
    author VARCHAR(256),
    created_at TIMESTAMPTZ,
    content_type VARCHAR(256),
    source VARCHAR(256),
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS document_roles (
    ingestion_id BIGINT NOT NULL,
    role VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS document_metadata (
    ingestion_id BIGINT NOT NULL,
    attribute_key VARCHAR(128) NOT NULL,
    attribute_value TEXT
);

CREATE INDEX IF NOT EXISTS idx_document_ingestions_tenant_hash ON document_ingestions (tenant_id, content_hash);
CREATE INDEX IF NOT EXISTS idx_document_ingestions_external ON document_ingestions (tenant_id, external_id);

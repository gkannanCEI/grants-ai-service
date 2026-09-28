-- =============================================================
-- V1 — baseline schema for document indexing/tracking
--
-- Tracks every document known to the system and its processing
-- status. Vector data lives in Milvus; this table is the durable
-- source of truth for the "list documents for a session" endpoint
-- and for startup de-duplication of the configured source endpoint.
--
-- Column types/lengths mirror com.example.agent.persistence.IndexedDocument
-- so Hibernate (spring.jpa.hibernate.ddl-auto=validate) validates cleanly.
-- =============================================================

CREATE TABLE indexed_document (
    document_id    VARCHAR(64)    NOT NULL,
    application_id VARCHAR(256)   NOT NULL,
    source_url     VARCHAR(2048),
    file_name      VARCHAR(1024),
    mime_type      VARCHAR(256),
    content_hash   VARCHAR(64),
    char_count     INTEGER        NOT NULL DEFAULT 0,
    chunk_count    INTEGER        NOT NULL DEFAULT 0,
    page_count     INTEGER        NOT NULL DEFAULT 0,
    status         VARCHAR(16)    NOT NULL,
    error_message  VARCHAR(2048),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    indexed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_indexed_document PRIMARY KEY (document_id)
);

-- Listing documents for a session/application (newest first).
CREATE INDEX idx_doc_application_id ON indexed_document (application_id);

-- De-duplication lookups for the configured source endpoint.
CREATE INDEX idx_doc_source_url   ON indexed_document (source_url);
CREATE INDEX idx_doc_content_hash ON indexed_document (content_hash);

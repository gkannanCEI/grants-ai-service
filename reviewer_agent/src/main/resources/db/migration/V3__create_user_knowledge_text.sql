-- =============================================================
-- V3 — per-user (CID) plain-text knowledge base entries
--
-- Stores the raw plain-text knowledge submitted for a CID (a user identifier
-- from the master application) verbatim, so it can be listed/retrieved as-is.
-- The same text is also RAG-indexed (chunked + embedded) under the CID as the
-- vector-store scoping key — that indexed copy lives in Milvus / IndexedDocument,
-- not in this table. This table is purely the durable, human-readable record.
--
-- Column types/lengths mirror com.example.agent.persistence.UserKnowledgeText
-- so Hibernate (spring.jpa.hibernate.ddl-auto=validate) validates cleanly.
-- =============================================================

CREATE TABLE user_knowledge_text (
    id          VARCHAR(64)  NOT NULL,
    cid         VARCHAR(256) NOT NULL,
    content     TEXT         NOT NULL,
    document_id VARCHAR(64),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_knowledge_text PRIMARY KEY (id)
);

-- Listing/retrieval of a CID's stored text entries (newest first).
CREATE INDEX idx_ukt_cid ON user_knowledge_text (cid);

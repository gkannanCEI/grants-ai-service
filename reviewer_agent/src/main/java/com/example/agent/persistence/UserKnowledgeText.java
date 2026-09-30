package com.example.agent.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A single plain-text knowledge entry submitted for a CID (a user identifier
 * from the master application).
 *
 * <p>Stored verbatim so it can be listed/retrieved as-is. The same text is also
 * RAG-indexed (chunked + embedded) under the CID as the vector-store scoping
 * key — {@code documentId} links to that indexed copy
 * ({@code com.example.agent.persistence.IndexedDocument}) when indexing succeeded.
 *
 * <p>Columns mirror {@code db/migration/V3__create_user_knowledge_text.sql} so
 * Hibernate ({@code spring.jpa.hibernate.ddl-auto=validate}) validates cleanly.
 */
@Entity
@Table(
        name = "user_knowledge_text",
        indexes = { @Index(name = "idx_ukt_cid", columnList = "cid") }
)
public class UserKnowledgeText {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    /** The user identifier (from the master application) this knowledge belongs to. */
    @Column(name = "cid", nullable = false, length = 256)
    private String cid;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /** Links to the RAG-indexed copy of this text, when indexing succeeded. */
    @Column(name = "document_id", length = 64)
    private String documentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserKnowledgeText() {
        // for JPA
    }

    public UserKnowledgeText(String cid, String content) {
        this.id        = UUID.randomUUID().toString();
        this.cid       = cid;
        this.content   = content;
        this.createdAt = Instant.now();
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String  getId()                  { return id; }
    public String  getCid()                 { return cid; }
    public String  getContent()             { return content; }
    public String  getDocumentId()          { return documentId; }
    public void    setDocumentId(String v)  { this.documentId = v; }
    public Instant getCreatedAt()           { return createdAt; }
}

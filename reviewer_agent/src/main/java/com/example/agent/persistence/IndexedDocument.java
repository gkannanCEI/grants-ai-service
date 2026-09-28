package com.example.agent.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Primary-DB record of a document known to the system, and whether it has been
 * indexed. This is the source of truth for the "list documents for a session"
 * endpoint and for startup de-duplication of the configured source endpoint.
 *
 * The vector data itself lives in Milvus; this table only tracks metadata and
 * processing status so it survives restarts.
 */
@Entity
@Table(
        name = "indexed_document",
        indexes = {
                @Index(name = "idx_doc_application_id", columnList = "application_id"),
                @Index(name = "idx_doc_source_url",     columnList = "source_url"),
                @Index(name = "idx_doc_content_hash",   columnList = "content_hash")
        }
)
public class IndexedDocument {

    /** UUID assigned at ingest time (matches the id used in Milvus/read tools). */
    @Id
    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    @Column(name = "application_id", nullable = false, length = 256)
    private String applicationId;

    /** Origin URL when fetched from the configured source endpoint; null for uploads. */
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "file_name", length = 1024)
    private String fileName;

    @Column(name = "mime_type", length = 256)
    private String mimeType;

    /** SHA-256 of the source bytes — used to detect unchanged content and skip re-indexing. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "char_count")
    private int charCount;

    @Column(name = "chunk_count")
    private int chunkCount;

    @Column(name = "page_count")
    private int pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DocumentStatus status;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    protected IndexedDocument() {
        // for JPA
    }

    public IndexedDocument(String documentId, String applicationId, String sourceUrl,
                           String fileName, String mimeType, String contentHash,
                           DocumentStatus status) {
        this.documentId    = documentId;
        this.applicationId = applicationId;
        this.sourceUrl     = sourceUrl;
        this.fileName      = fileName;
        this.mimeType      = mimeType;
        this.contentHash   = contentHash;
        this.status        = status;
        this.createdAt     = Instant.now();
    }

    /** Marks this record as successfully indexed and stamps the time. */
    public void markIndexed(String mimeType, int charCount, int chunkCount, int pageCount) {
        this.mimeType   = mimeType;
        this.charCount  = charCount;
        this.chunkCount = chunkCount;
        this.pageCount  = pageCount;
        this.status     = DocumentStatus.INDEXED;
        this.indexedAt  = Instant.now();
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status       = DocumentStatus.FAILED;
        this.errorMessage = truncate(errorMessage, 2048);
    }

    public void markProcessing() {
        this.status = DocumentStatus.PROCESSING;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getDocumentId()            { return documentId; }
    public void   setDocumentId(String v)    { this.documentId = v; }

    public String getApplicationId()         { return applicationId; }
    public void   setApplicationId(String v) { this.applicationId = v; }

    public String getSourceUrl()             { return sourceUrl; }
    public void   setSourceUrl(String v)     { this.sourceUrl = v; }

    public String getFileName()              { return fileName; }
    public void   setFileName(String v)      { this.fileName = v; }

    public String getMimeType()              { return mimeType; }
    public void   setMimeType(String v)      { this.mimeType = v; }

    public String getContentHash()           { return contentHash; }
    public void   setContentHash(String v)   { this.contentHash = v; }

    public int    getCharCount()             { return charCount; }
    public void   setCharCount(int v)        { this.charCount = v; }

    public int    getChunkCount()            { return chunkCount; }
    public void   setChunkCount(int v)       { this.chunkCount = v; }

    public int    getPageCount()             { return pageCount; }
    public void   setPageCount(int v)        { this.pageCount = v; }

    public DocumentStatus getStatus()        { return status; }
    public void   setStatus(DocumentStatus v){ this.status = v; }

    public String getErrorMessage()          { return errorMessage; }
    public void   setErrorMessage(String v)  { this.errorMessage = v; }

    public Instant getCreatedAt()            { return createdAt; }
    public void    setCreatedAt(Instant v)   { this.createdAt = v; }

    public Instant getIndexedAt()            { return indexedAt; }
    public void    setIndexedAt(Instant v)   { this.indexedAt = v; }
}

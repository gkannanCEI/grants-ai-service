package com.example.agent.document;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.agent.persistence.DocumentStatus;
import com.example.agent.persistence.IndexedDocument;
import com.example.agent.persistence.IndexedDocumentRepository;

/**
 * Postgres-backed registry of documents known to the system, grouped by
 * {@code applicationId} (the effective per-session key used at upload time).
 *
 * <p>This is the source of truth for:
 * <ul>
 *   <li>the {@code GET /api/documents/{applicationId}} listing (including status)</li>
 *   <li>startup de-duplication of the configured source endpoint</li>
 * </ul>
 *
 * <p>Both ingestion paths record here at ingest time
 * ({@link SimpleDocumentIngestor}, {@link DocumentIngestor}). Vector data lives
 * in Milvus; this registry only persists metadata and processing status so it
 * survives restarts.
 */
@Component
public class DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    private final IndexedDocumentRepository repository;

    public DocumentRegistry(IndexedDocumentRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a freshly indexed document (from an upload) with status INDEXED.
     * Idempotent on documentId.
     */
    @Transactional
    public void register(IngestResult result) {
        register(result, null, null);
    }

    /**
     * Records a freshly indexed document, optionally capturing the source URL and
     * content hash (used when the document came from the configured source endpoint).
     */
    @Transactional
    public void register(IngestResult result, String sourceUrl, String contentHash) {
        String appId = normalize(result.applicationId());

        IndexedDocument entity = repository.findById(result.documentId())
                .orElseGet(() -> new IndexedDocument(
                        result.documentId(), appId, sourceUrl,
                        result.fileName(), result.mimeType(), contentHash,
                        DocumentStatus.PENDING));

        entity.setApplicationId(appId);
        if (sourceUrl   != null) entity.setSourceUrl(sourceUrl);
        if (contentHash != null) entity.setContentHash(contentHash);
        entity.setFileName(result.fileName());
        entity.markIndexed(result.mimeType(), result.charCount(),
                result.chunkCount(), result.pageCount());

        repository.save(entity);
        log.info("Registered indexed document — id={} applicationId={} fileName={} status=INDEXED",
                entity.getDocumentId(), appId, entity.getFileName());
    }

    /**
     * Lists all documents filed under the given applicationId as metadata-only
     * summaries, newest first. Returns an empty list if none exist.
     */
    @Transactional(readOnly = true)
    public List<DocumentSummary> listByApplicationId(String applicationId) {
        return repository.findByApplicationIdOrderByCreatedAtDesc(normalize(applicationId))
                .stream()
                .map(DocumentRegistry::toSummary)
                .toList();
    }

    // ── De-duplication / status helpers (used by the source indexer) ─────────

    /**
     * True if this source + content hash has already been successfully indexed
     * FOR THE GIVEN applicationId. De-dup is per application, so the same file
     * ingested under a different applicationId is treated as new.
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyIndexed(String applicationId, String sourceUrl, String contentHash) {
        return repository.existsByApplicationIdAndSourceUrlAndContentHashAndStatus(
                normalize(applicationId), sourceUrl, contentHash, DocumentStatus.INDEXED);
    }

    @Transactional(readOnly = true)
    public Optional<IndexedDocument> findBySource(String sourceUrl, String contentHash) {
        return repository.findFirstBySourceUrlAndContentHash(sourceUrl, contentHash);
    }

    /** Records a FAILED attempt so failures are visible and re-tryable. */
    @Transactional
    public void recordFailure(String documentId, String applicationId, String sourceUrl,
                              String fileName, String contentHash, String errorMessage) {
        IndexedDocument entity = repository.findById(documentId)
                .orElseGet(() -> new IndexedDocument(
                        documentId, normalize(applicationId), sourceUrl,
                        fileName, null, contentHash, DocumentStatus.PENDING));
        entity.markFailed(errorMessage);
        repository.save(entity);
        log.warn("Recorded FAILED document — id={} sourceUrl={} reason={}",
                documentId, sourceUrl, errorMessage);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DocumentSummary toSummary(IndexedDocument d) {
        return new DocumentSummary(
                d.getDocumentId(),
                d.getApplicationId(),
                d.getFileName(),
                d.getMimeType(),
                d.getCharCount(),
                d.getChunkCount(),
                d.getPageCount(),
                d.getStatus().name(),
                d.getIndexedAt() != null ? d.getIndexedAt() : d.getCreatedAt()
        );
    }

    private static String normalize(String applicationId) {
        return (applicationId != null && !applicationId.isBlank())
                ? applicationId
                : InMemoryDocumentStore.DEFAULT_APPLICATION_ID;
    }
}

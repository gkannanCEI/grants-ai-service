package com.example.agent.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simple in-memory store for extracted document text.
 * Used when agent.vector.enabled=false (no Milvus required).
 * Data is lost on restart — suitable for development and demos.
 *
 * Indexed by both documentId (UUID) and applicationId for flexible retrieval.
 * Multiple documents can share the same applicationId (e.g. multiple uploads for APP-001).
 */
@Component
public class InMemoryDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDocumentStore.class);

    public static final String DEFAULT_APPLICATION_ID = "DEFAULT";

    // Primary index: documentId -> document
    private final Map<String, StoredDocument> byDocumentId    = new ConcurrentHashMap<>();
    // Secondary index: applicationId -> list of documentIds
    private final Map<String, List<String>>   byApplicationId = new ConcurrentHashMap<>();

    public void save(String documentId, String applicationId, String fileName,
                     String mimeType, String text, int pageCount) {
        String appId = (applicationId != null && !applicationId.isBlank())
                ? applicationId : DEFAULT_APPLICATION_ID;

        StoredDocument doc = new StoredDocument(documentId, appId, fileName, mimeType, text, pageCount);
        byDocumentId.put(documentId, doc);

        // Add to secondary index — multiple docs can map to one applicationId
        byApplicationId.computeIfAbsent(appId, k -> new ArrayList<>()).add(documentId);

        log.info("Stored document - id={} applicationId={} fileName={} chars={}",
                documentId, appId, fileName, text.length());
    }

    /** Find by the UUID assigned at upload time. */
    public Optional<StoredDocument> findByDocumentId(String documentId) {
        return Optional.ofNullable(byDocumentId.get(documentId));
    }

    /**
     * Find the most recently uploaded document for a given applicationId.
     * Returns empty if no document has been uploaded for that applicationId.
     */
    public Optional<StoredDocument> findLatestByApplicationId(String applicationId) {
        List<String> ids = byApplicationId.get(applicationId);
        if (ids == null || ids.isEmpty()) return Optional.empty();
        // Last entry is the most recently uploaded
        return Optional.ofNullable(byDocumentId.get(ids.get(ids.size() - 1)));
    }

    /**
     * Find all documents uploaded for a given applicationId, ordered oldest first.
     */
    public List<StoredDocument> findAllByApplicationId(String applicationId) {
        List<String> ids = byApplicationId.getOrDefault(applicationId, List.of());
        List<StoredDocument> result = new ArrayList<>();
        for (String id : ids) {
            StoredDocument doc = byDocumentId.get(id);
            if (doc != null) result.add(doc);
        }
        return result;
    }

    public boolean existsByDocumentId(String documentId) {
        return byDocumentId.containsKey(documentId);
    }

    public boolean existsByApplicationId(String applicationId) {
        List<String> ids = byApplicationId.get(applicationId);
        return ids != null && !ids.isEmpty();
    }

    /**
     * @param documentId    UUID assigned at upload
     * @param applicationId Business application ID (e.g. APP-001)
     * @param fileName      Original file name
     * @param mimeType      MIME type detected by Tika
     * @param text          Full extracted text
     * @param pageCount     Page count from document metadata (-1 if unavailable)
     */
    public record StoredDocument(
            String documentId,
            String applicationId,
            String fileName,
            String mimeType,
            String text,
            int    pageCount
    ) {}
}

package com.example.agent.tools;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.agent.core.AgentTool;
import com.example.agent.document.InMemoryDocumentStore;
import com.example.agent.embedding.EmbeddingClient;
import com.example.agent.exception.ToolExecutionException;
import com.example.agent.vector.VectorDocument;
import com.example.agent.vector.VectorStore;
import com.google.adk.tools.Annotations.Schema;

/**
 * Tools for reading uploaded documents.
 *
 * readDocument(documentId)            - retrieve by UUID from upload response
 * readDocumentByApplicationId(appId)  - retrieve by business application ID
 *
 * Both tools work with in-memory store (vector.enabled=false) and
 * fall back to Milvus when vector.enabled=true.
 */
@Component
public class DocumentReaderTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(DocumentReaderTool.class);
    private static final int READ_TOP_K = 20;

    private final InMemoryDocumentStore documentStore;
    private final VectorStore           vectorStore;
    private final EmbeddingClient       embeddingClient;

    public DocumentReaderTool(
            InMemoryDocumentStore                        documentStore,
            @Autowired(required = false) VectorStore     vectorStore,
            @Autowired(required = false) EmbeddingClient embeddingClient
    ) {
        this.documentStore   = documentStore;
        this.vectorStore     = vectorStore;
        this.embeddingClient = embeddingClient;
    }

    // ── Tool 1: read by documentId UUID ─────────────────────────────────────

    @Schema(description = "Read the extracted content of a previously uploaded document by its documentId UUID.")
    public Map<String, Object> readDocument(
            @Schema(name = "documentId",
                    description = "The UUID returned by POST /api/documents/upload "
                            + "(format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)")
            String documentId
    ) {
        log.info("readDocument - documentId={}", documentId);

        if (documentId == null || documentId.isBlank()) {
            throw new ToolExecutionException("readDocument", "documentId must not be blank");
        }

        // In-memory path
        if (documentStore.existsByDocumentId(documentId)) {
            InMemoryDocumentStore.StoredDocument doc = documentStore.findByDocumentId(documentId).get();
            log.info("readDocument - found in memory store, chars={}", doc.text().length());
            return toResultMap(doc);
        }

        // Milvus path
        if (vectorStore != null && embeddingClient != null && vectorStore.isHealthy()) {
            return readFromVectorStore(documentId);
        }

        throw new ToolExecutionException("readDocument",
                "No content found for documentId=" + documentId
                + ". Upload the document first via POST /api/documents/upload.");
    }

    // ── Tool 2: read by applicationId ────────────────────────────────────────

    @Schema(description = "Read the extracted content of the most recently uploaded document for a given applicationId.")
    public Map<String, Object> readDocumentByApplicationId(
            @Schema(name = "applicationId",
                    description = "The business application ID provided at upload time (e.g. APP-001)")
            String applicationId
    ) {
        log.info("readDocumentByApplicationId - applicationId={}", applicationId);

        if (applicationId == null || applicationId.isBlank()) {
            throw new ToolExecutionException("readDocumentByApplicationId",
                    "applicationId must not be blank");
        }

        // In-memory path
        if (documentStore.existsByApplicationId(applicationId)) {
            InMemoryDocumentStore.StoredDocument doc =
                    documentStore.findLatestByApplicationId(applicationId).get();
            log.info("readDocumentByApplicationId - found documentId={} chars={}",
                    doc.documentId(), doc.text().length());
            return toResultMap(doc);
        }

        // Milvus path — search using applicationId as query anchor
        if (vectorStore != null && embeddingClient != null && vectorStore.isHealthy()) {
            return readFromVectorStoreByApplicationId(applicationId);
        }

        throw new ToolExecutionException("readDocumentByApplicationId",
                "No document found for applicationId=" + applicationId
                + ". Upload a document with this applicationId first.");
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Map<String, Object> toResultMap(InMemoryDocumentStore.StoredDocument doc) {
        return Map.of(
                "documentId",     doc.documentId(),
                "applicationId",  doc.applicationId(),
                "fileName",       doc.fileName(),
                "mimeType",       doc.mimeType(),
                "fullText",       doc.text(),
                "chunkCount",     1
        );
    }

    private Map<String, Object> readFromVectorStore(String documentId) {
        try {
            List<Float> queryEmbedding = embeddingClient.embed("document content " + documentId);
            List<VectorDocument> chunks = vectorStore.search(queryEmbedding, READ_TOP_K);

            List<VectorDocument> docChunks = chunks.stream()
                    .filter(d -> d.metadata() != null
                            && documentId.equals(d.metadata().get("source")))
                    .sorted((a, b) -> Integer.compare(
                            parseChunkIndex(a.metadata()), parseChunkIndex(b.metadata())))
                    .collect(Collectors.toList());

            if (docChunks.isEmpty()) {
                throw new ToolExecutionException("readDocument",
                        "No content found for documentId=" + documentId);
            }

            return buildVectorResult(documentId, docChunks);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("readDocument",
                    "Failed to read document " + documentId + ": " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readFromVectorStoreByApplicationId(String applicationId) {
        try {
            List<Float> queryEmbedding = embeddingClient.embed("application document " + applicationId);
            // Use Milvus-side filtering so we only get chunks for this application,
            // instead of fetching a global topK and filtering in Java (which could
            // miss the application's chunks entirely).
            List<VectorDocument> chunks =
                    vectorStore.search(queryEmbedding, READ_TOP_K, applicationId);

            List<VectorDocument> docChunks = chunks.stream()
                    .filter(d -> d != null)
                    .sorted((a, b) -> Integer.compare(
                            parseChunkIndex(a.metadata()), parseChunkIndex(b.metadata())))
                    .collect(Collectors.toList());

            if (docChunks.isEmpty()) {
                throw new ToolExecutionException("readDocumentByApplicationId",
                        "No content found for applicationId=" + applicationId
                        + ". Make sure a document was indexed for this applicationId "
                        + "(re-index if it was uploaded before application scoping was enabled).");
            }

            Map<String, String> meta0 = docChunks.get(0).metadata();
            String documentId = (meta0 != null && meta0.get("source") != null)
                    ? meta0.get("source") : "unknown";
            return buildVectorResult(documentId, docChunks);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("readDocumentByApplicationId",
                    "Failed to read document for applicationId=" + applicationId + ": " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildVectorResult(String documentId, List<VectorDocument> docChunks) {
        // Guard against null chunk content — Collectors.joining throws NPE on nulls.
        String fullText = docChunks.stream()
                .map(VectorDocument::content)
                .map(c -> c != null ? c : "")
                .collect(Collectors.joining("\n\n"));

        Map<String, String> meta     = docChunks.get(0).metadata();
        String fileName              = metaOrDefault(meta, "fileName",      "unknown");
        String mimeType              = metaOrDefault(meta, "mimeType",      "unknown");
        String applicationId         = metaOrDefault(meta, "applicationId", "unknown");

        // Use a null-tolerant HashMap rather than Map.of, which rejects null keys/values.
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("documentId",    documentId != null ? documentId : "unknown");
        result.put("applicationId", applicationId);
        result.put("fileName",      fileName);
        result.put("mimeType",      mimeType);
        result.put("fullText",      fullText);
        result.put("chunkCount",    docChunks.size());
        return result;
    }

    /** Like getOrDefault, but also returns the default when the key maps to a null value. */
    private static String metaOrDefault(Map<String, String> meta, String key, String def) {
        if (meta == null) return def;
        String v = meta.get(key);
        return v != null ? v : def;
    }

    private int parseChunkIndex(Map<String, String> metadata) {
        if (metadata == null) return 0;
        try {
            return Integer.parseInt(metadata.getOrDefault("chunkIndex", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

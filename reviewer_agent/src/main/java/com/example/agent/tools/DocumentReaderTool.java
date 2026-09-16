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
            List<VectorDocument> chunks = vectorStore.search(queryEmbedding, READ_TOP_K);

            List<VectorDocument> docChunks = chunks.stream()
                    .filter(d -> d.metadata() != null
                            && applicationId.equals(d.metadata().get("applicationId")))
                    .sorted((a, b) -> Integer.compare(
                            parseChunkIndex(a.metadata()), parseChunkIndex(b.metadata())))
                    .collect(Collectors.toList());

            if (docChunks.isEmpty()) {
                throw new ToolExecutionException("readDocumentByApplicationId",
                        "No content found for applicationId=" + applicationId);
            }

            String documentId = docChunks.get(0).metadata().getOrDefault("source", "unknown");
            return buildVectorResult(documentId, docChunks);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("readDocumentByApplicationId",
                    "Failed to read document for applicationId=" + applicationId + ": " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildVectorResult(String documentId, List<VectorDocument> docChunks) {
        String fullText = docChunks.stream()
                .map(VectorDocument::content)
                .collect(Collectors.joining("\n\n"));

        Map<String, String> meta     = docChunks.get(0).metadata();
        String fileName              = meta != null ? meta.getOrDefault("fileName",      "unknown") : "unknown";
        String mimeType              = meta != null ? meta.getOrDefault("mimeType",      "unknown") : "unknown";
        String applicationId         = meta != null ? meta.getOrDefault("applicationId", "unknown") : "unknown";

        return Map.of(
                "documentId",    documentId,
                "applicationId", applicationId,
                "fileName",      fileName,
                "mimeType",      mimeType,
                "fullText",      fullText,
                "chunkCount",    docChunks.size()
        );
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

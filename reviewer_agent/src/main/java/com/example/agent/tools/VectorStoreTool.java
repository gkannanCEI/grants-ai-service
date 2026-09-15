package com.example.agent.tools;

import com.example.agent.core.AgentTool;
import com.example.agent.embedding.EmbeddingClient;
import com.example.agent.exception.ToolExecutionException;
import com.example.agent.vector.VectorDocument;
import com.example.agent.vector.VectorStore;
import com.google.adk.tools.Annotations.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool: searchVectors
 *
 * Allows the agent to perform semantic searches across all stored document chunks.
 * Useful for retrieving relevant context before scoring or validating a document.
 *
 * Note: Document storage (upload → extract → embed → persist) is handled by
 * {@link com.example.agent.document.DocumentIngestor} via the upload endpoint,
 * not by this tool. This tool is read-only from the agent's perspective.
 *
 * Active only when agent.vector.enabled=true (default).
 */
@Component
@ConditionalOnProperty(prefix = "agent.vector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreTool.class);

    private final VectorStore     vectorStore;
    private final EmbeddingClient embeddingClient;

    public VectorStoreTool(VectorStore vectorStore, EmbeddingClient embeddingClient) {
        this.vectorStore     = vectorStore;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Searches the vector store for text chunks semantically similar to the query.
     *
     * Use this to retrieve relevant context or prior document content before
     * scoring or validating a new document.
     *
     * @param query The natural language query or topic to search for
     * @param topK  Maximum number of results to return (1–20, default 5)
     * @return Map with query, topK, and a results list of matching chunks
     */
    @Schema(description = "Search stored document content for chunks semantically similar to the query")
    public Map<String, Object> searchVectors(
            @Schema(name = "query", description = "Natural language query or topic to search for in stored documents")
            String query,

            @Schema(name = "topK", description = "Maximum number of results to return (1-20)")
            int topK
    ) {
        log.info("searchVectors — query='{}' topK={}", query, topK);

        if (query == null || query.isBlank()) {
            throw new ToolExecutionException("searchVectors", "query must not be blank");
        }
        if (topK <= 0 || topK > 20) topK = 5;

        try {
            List<Float>          queryEmbedding = embeddingClient.embed(query);
            List<VectorDocument> results        = vectorStore.search(queryEmbedding, topK);

            List<Map<String, Object>> hits = results.stream()
                    .map(doc -> {
                        Map<String, String> meta = doc.metadata() != null ? doc.metadata() : Map.of();
                        return Map.<String, Object>of(
                                "id",         doc.id(),
                                "content",    doc.content(),
                                "score",      doc.score() != null ? doc.score() : 0f,
                                "source",     meta.getOrDefault("source", ""),
                                "fileName",   meta.getOrDefault("fileName", ""),
                                "chunkIndex", meta.getOrDefault("chunkIndex", "0")
                        );
                    })
                    .collect(Collectors.toList());

            return Map.of(
                    "query",   query,
                    "topK",    topK,
                    "results", hits
            );

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("searchVectors", "Vector search failed: " + e.getMessage(), e);
        }
    }
}

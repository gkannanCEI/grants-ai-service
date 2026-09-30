package com.example.agent.retrieval;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.agent.embedding.EmbeddingClient;
import com.example.agent.vector.VectorDocument;
import com.example.agent.vector.VectorStore;

/**
 * Retrieval-augmented context builder for application-scoped chat.
 *
 * Given a user question and an applicationId, embeds the question and searches
 * the vector store for the most relevant chunks stored under that application,
 * then formats them into a context block that can be injected into the prompt.
 *
 * Degrades gracefully: if the vector store / embedding client is unavailable
 * (e.g. {@code agent.vector.enabled=false} or Milvus down), returns empty
 * context instead of failing the chat turn.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** How many chunks to pull for context. */
    private static final int DEFAULT_TOP_K = 8;

    private final VectorStore     vectorStore;      // null when vector.enabled=false
    private final EmbeddingClient embeddingClient;  // null when vector.enabled=false

    public RetrievalService(
            @Autowired(required = false) VectorStore     vectorStore,
            @Autowired(required = false) EmbeddingClient embeddingClient) {
        this.vectorStore     = vectorStore;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Retrieves relevant context for a question, scoped to the given applicationId.
     *
     * @return a {@link RetrievalResult}; {@link RetrievalResult#hasContext()} is
     *         false when nothing relevant was found or retrieval is unavailable
     */
    public RetrievalResult retrieve(String applicationId, String question) {
        if (applicationId == null || applicationId.isBlank()) {
            return RetrievalResult.empty();
        }
        if (vectorStore == null || embeddingClient == null || !vectorStore.isHealthy()) {
            log.info("Retrieval unavailable (vectorStore/embeddingClient absent or unhealthy) — "
                    + "answering without document context for applicationId={}", applicationId);
            return RetrievalResult.empty();
        }

        try {
            List<Float> queryEmbedding = embeddingClient.embed(question);
            List<VectorDocument> hits =
                    vectorStore.search(queryEmbedding, DEFAULT_TOP_K, applicationId);

            if (hits.isEmpty()) {
                log.info("No document context found for applicationId={}", applicationId);
                return RetrievalResult.empty();
            }

            // Build a short, stable label per chunk (e.g. "remove-papular-dish.pdf#2") so the
            // LLM can cite exactly which excerpt it used, and we can later resolve that label
            // back to the real chunk text/metadata for a precise, non-hallucinated citation.
            List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
            StringBuilder contextBuilder = new StringBuilder();
            boolean first = true;
            for (VectorDocument hit : hits) {
                String content = hit.content();
                if (content == null || content.isBlank()) {
                    continue;
                }
                String fileName = hit.metadata() != null ? hit.metadata().get("fileName") : null;
                String chunkIndex = hit.metadata() != null ? hit.metadata().get("chunkIndex") : null;
                String label = buildLabel(fileName, chunkIndex, chunks.size());

                chunks.add(new RetrievedChunk(label, fileName, chunkIndex, content, hit.score()));

                if (!first) {
                    contextBuilder.append("\n\n---\n\n");
                }
                first = false;
                contextBuilder.append('[').append(label).append("]\n").append(content);
            }

            log.info("Retrieved {} chunk(s) for applicationId={}", chunks.size(), applicationId);
            return new RetrievalResult(!chunks.isEmpty(), contextBuilder.toString(), chunks.size(), chunks);
        } catch (Exception e) {
            // Never fail the chat turn because retrieval hit a problem.
            log.warn("Retrieval failed for applicationId={} — {}", applicationId, e.getMessage());
            return RetrievalResult.empty();
        }
    }

    /**
     * Wraps a user question with retrieved context and grounding instructions.
     * If no context is available, returns the original question unchanged.
     */
    public String buildGroundedMessage(String applicationId, String question) {
        // No applicationId → plain conversation, pass the question through unchanged.
        if (applicationId == null || applicationId.isBlank()) {
            return question;
        }

        RetrievalResult result = retrieve(applicationId, question);
        if (!result.hasContext()) {
            // An applicationId WAS provided but no indexed content was found for it.
            // Be explicit so the agent doesn't confusingly ask the user to supply an
            // applicationId they already gave.
            return """
                    The user is asking about application "%s", but no indexed documents were
                    found for it. Do NOT ask the user to provide an applicationId or documentId —
                    they already provided "%s". Instead, tell them that no documents are currently
                    indexed for application "%s", and suggest they upload or ingest the document
                    for that application first.

                    Question: %s
                    """.formatted(applicationId, applicationId, applicationId, question);
        }
        return """
                You are answering a question about application "%s". Use ONLY the document
                context below to answer. If the answer is not contained in the context, say
                you don't have that information for this application.

                === DOCUMENT CONTEXT (application %s) ===
                %s
                === END CONTEXT ===

                Question: %s
                """.formatted(applicationId, applicationId, result.contextBlock(), question);
    }

    /** Builds a short, human-readable label identifying a chunk, e.g. "invoice.pdf#2". */
    private static String buildLabel(String fileName, String chunkIndex, int fallbackOrdinal) {
        if (fileName != null && !fileName.isBlank()) {
            return chunkIndex != null && !chunkIndex.isBlank()
                    ? fileName + "#" + chunkIndex
                    : fileName;
        }
        return "excerpt-" + fallbackOrdinal;
    }

    /** Outcome of a retrieval attempt. */
    public record RetrievalResult(boolean hasContext, String contextBlock, int chunkCount,
                                   List<RetrievedChunk> chunks) {
        public RetrievalResult(boolean hasContext, String contextBlock, int chunkCount) {
            this(hasContext, contextBlock, chunkCount, java.util.List.of());
        }

        public static RetrievalResult empty() {
            return new RetrievalResult(false, "", 0, java.util.List.of());
        }
    }

    /**
     * A single retrieved chunk with enough provenance to build a precise citation later:
     * the label cited in the prompt, its source file/chunk index, the exact chunk text
     * (so any snippet shown to the reviewer is copied verbatim, never paraphrased by the
     * LLM), and its similarity score.
     */
    public record RetrievedChunk(String label, String fileName, String chunkIndex,
                                  String content, Float score) { }
}

package com.example.agent.embedding;

import java.util.List;

/**
 * Provider-agnostic interface for generating text embeddings.
 *
 * Implementations:
 *   - {@link GeminiEmbeddingClient}  — Google text-embedding-004
 *   - {@link OllamaEmbeddingClient}  — local Ollama (nomic-embed-text, mxbai-embed-large, etc.)
 *
 * Selected via {@code agent.provider} in application.properties.
 */
public interface EmbeddingClient {

    /**
     * Generates an embedding vector for the given text.
     *
     * @param text The text to embed
     * @return A float vector whose length matches the configured dimension
     */
    List<Float> embed(String text);

    /**
     * Generates embeddings for a batch of texts in one call.
     * Default implementation calls {@link #embed(String)} per item.
     * Override for providers that support native batching.
     */
    default List<List<Float>> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}

package com.example.agent.vector;

import java.util.List;

/**
 * Provider-agnostic interface for vector store operations.
 *
 * Current implementation: {@link MilvusVectorStore}
 * Add more by implementing this interface and switching via @Profile or @ConditionalOnProperty.
 */
public interface VectorStore {

    /**
     * Inserts or upserts a list of documents into the vector store.
     *
     * @param documents Documents with pre-computed embeddings to store
     */
    void upsert(List<VectorDocument> documents);

    /**
     * Searches for the top-k most similar documents to the given query embedding.
     *
     * @param queryEmbedding The embedding vector of the search query
     * @param topK           Maximum number of results to return
     * @return Ranked list of matching documents with similarity scores
     */
    List<VectorDocument> search(List<Float> queryEmbedding, int topK);

    /**
     * Deletes a document by its ID.
     *
     * @param id The document ID to delete
     */
    void delete(String id);

    /**
     * Returns true if the collection is ready and accessible.
     */
    boolean isHealthy();
}

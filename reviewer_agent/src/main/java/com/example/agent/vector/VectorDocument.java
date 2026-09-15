package com.example.agent.vector;

import java.util.List;
import java.util.Map;

/**
 * A document entry stored in and retrieved from the vector store.
 *
 * @param id        Unique identifier for the document chunk
 * @param content   The text content of this chunk
 * @param embedding The vector embedding of the content
 * @param metadata  Arbitrary key-value metadata (source, documentId, pageNum, etc.)
 * @param score     Similarity score — populated on search results, null on inserts
 */
public record VectorDocument(
        String              id,
        String              content,
        List<Float>         embedding,
        Map<String, String> metadata,
        Float               score
) {
    /** Factory for creating a document to insert (no score yet). */
    public static VectorDocument forInsert(
            String id,
            String content,
            List<Float> embedding,
            Map<String, String> metadata
    ) {
        return new VectorDocument(id, content, embedding, metadata, null);
    }
}

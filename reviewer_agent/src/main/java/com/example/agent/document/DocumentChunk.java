package com.example.agent.document;

/**
 * A single chunk of text extracted from a document.
 *
 * Large documents are split into overlapping chunks before embedding
 * so each vector fits within the model's token limit and retrieval
 * is more precise (paragraph-level rather than document-level).
 *
 * @param documentId  The parent document this chunk belongs to
 * @param chunkIndex  Zero-based position of this chunk in the document
 * @param text        The chunk's raw text content
 * @param charOffset  Starting character offset in the full document text
 */
public record DocumentChunk(
        String documentId,
        int    chunkIndex,
        String text,
        int    charOffset
) {}

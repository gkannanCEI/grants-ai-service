package com.example.agent.document;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits extracted document text into overlapping chunks for embedding.
 *
 * Strategy: fixed-size character windows with overlap so context is not
 * lost at chunk boundaries. Paragraph boundaries are respected when possible.
 *
 * Default config (tunable via constructor or properties):
 *   chunkSize    = 1000 characters (~250 tokens)
 *   chunkOverlap = 200  characters
 *
 * For better chunk quality in production, replace this with a
 * sentence-aware splitter (e.g. split on sentence boundaries using
 * OpenNLP or a simple regex that breaks on ". \n" patterns).
 */
@Component
public class DocumentChunker {

    private static final int DEFAULT_CHUNK_SIZE    = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;

    private final int chunkSize;
    private final int overlap;

    public DocumentChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    public DocumentChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap   = Math.min(overlap, chunkSize / 2);
    }

    /**
     * Splits the given text into overlapping chunks.
     *
     * @param documentId The parent document ID (stored as metadata on each chunk)
     * @param text       The full extracted document text
     * @return Ordered list of chunks ready for embedding
     */
    public List<DocumentChunk> chunk(String documentId, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Normalize whitespace — collapse excessive blank lines
        String normalized = text.replaceAll("\\n{3,}", "\n\n").trim();

        List<DocumentChunk> chunks = new ArrayList<>();
        int start      = 0;
        int chunkIndex = 0;

        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());

            // Try to break at a paragraph or sentence boundary for cleaner chunks
            if (end < normalized.length()) {
                int paragraphBreak = normalized.lastIndexOf("\n\n", end);
                int sentenceBreak  = normalized.lastIndexOf(". ", end);
                int bestBreak      = Math.max(paragraphBreak, sentenceBreak);

                // Only use the natural break if it's not too far back
                if (bestBreak > start + (chunkSize / 2)) {
                    end = bestBreak + 1;
                }
            }

            String chunkText = normalized.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new DocumentChunk(documentId, chunkIndex++, chunkText, start));
            }

            // Move forward, stepping back by overlap to keep context
            start = end - overlap;
            if (start >= normalized.length()) break;
        }

        return chunks;
    }
}

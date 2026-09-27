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

        int length = normalized.length();

        while (start < length) {
            int end = Math.min(start + chunkSize, length);

            // Try to break at a paragraph or sentence boundary for cleaner chunks
            if (end < length) {
                int paragraphBreak = normalized.lastIndexOf("\n\n", end);
                int sentenceBreak  = normalized.lastIndexOf(". ", end);
                int bestBreak      = Math.max(paragraphBreak, sentenceBreak);

                // Only use the natural break if it falls within the current window
                // (strictly after start and before end). Guarding both bounds prevents
                // end from being placed before start, which would make substring throw.
                if (bestBreak > start + (chunkSize / 2) && bestBreak < end) {
                    end = bestBreak + 1;
                }
            }

            // Safety: end must be strictly greater than start. For very short or
            // break-only text the logic above could otherwise yield end <= start.
            if (end <= start) {
                end = Math.min(start + chunkSize, length);
            }

            String chunkText = normalized.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new DocumentChunk(documentId, chunkIndex++, chunkText, start));
            }

            // Advance. Step back by overlap to preserve context, but always move
            // forward by at least one char so start can never go negative or stall.
            int next = end - overlap;
            if (next <= start) {
                next = end;
            }
            start = next;
        }

        return chunks;
    }
}

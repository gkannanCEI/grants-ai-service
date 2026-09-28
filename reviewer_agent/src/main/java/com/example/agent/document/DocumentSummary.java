package com.example.agent.document;

import java.time.Instant;

/**
 * Lightweight, metadata-only view of a tracked document.
 *
 * Returned by the "list documents for a session" endpoint. Deliberately
 * excludes the full extracted text — callers use {@code documentId} with
 * the read endpoints/tools to retrieve content.
 *
 * @param documentId    UUID assigned at upload time
 * @param applicationId Business application / session grouping key
 * @param fileName      Original uploaded file name
 * @param mimeType      Detected MIME type (e.g. application/pdf)
 * @param charCount     Total characters extracted
 * @param chunkCount    Number of chunks stored (1 for the in-memory path)
 * @param pageCount     Page count if detectable, else -1
 * @param status        Processing status: PENDING / PROCESSING / INDEXED / FAILED
 * @param indexedAt     When the document was indexed (or created, if not yet indexed)
 */
public record DocumentSummary(
        String  documentId,
        String  applicationId,
        String  fileName,
        String  mimeType,
        int     charCount,
        int     chunkCount,
        int     pageCount,
        String  status,
        Instant indexedAt
) {}

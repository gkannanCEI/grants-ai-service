package com.example.agent.document;

/**
 * Result returned after a document is ingested.
 *
 * @param documentId    Generated UUID for the document — use this in follow-up agent calls
 * @param applicationId Business application ID associated with the document
 * @param fileName      Original uploaded file name
 * @param mimeType      Detected MIME type (e.g. application/pdf, text/plain)
 * @param charCount     Total characters extracted
 * @param chunkCount    Number of chunks stored
 * @param pageCount     Number of pages (if detectable, else -1)
 */
public record IngestResult(
        String documentId,
        String applicationId,
        String fileName,
        String mimeType,
        int    charCount,
        int    chunkCount,
        int    pageCount
) {}

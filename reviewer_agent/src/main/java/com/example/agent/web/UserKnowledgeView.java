package com.example.agent.web;

import java.time.Instant;
import java.util.List;

import com.example.agent.document.DocumentSummary;
import com.example.agent.reservation.UserKnowledgeService;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code GET /api/users/{cid}/knowledge}.
 */
@Schema(
        name = "UserKnowledgeView",
        description = "Everything stored for a CID: raw text entries and indexed documents "
                + "(including the RAG-indexed copies of the text entries).",
        example = """
                {
                  "cid": "CID-88421",
                  "textEntries": [
                    {
                      "id": "b1e2c3d4-...",
                      "content": "Prefers email contact. Works remotely on Tuesdays and Thursdays.",
                      "documentId": "b1e2c3d4-...",
                      "createdAt": "2026-09-28T21:10:00Z"
                    }
                  ],
                  "documents": [
                    {
                      "documentId": "f9a8b7c6-...",
                      "applicationId": "CID-88421",
                      "fileName": "resume.pdf",
                      "mimeType": "application/pdf",
                      "charCount": 2200,
                      "chunkCount": 3,
                      "pageCount": 2,
                      "status": "INDEXED",
                      "indexedAt": "2026-09-28T21:10:01Z"
                    }
                  ]
                }"""
)
public record UserKnowledgeView(
        String                    cid,
        List<TextEntry>           textEntries,
        List<DocumentSummary>     documents
) {
    /** A single stored plain-text knowledge entry. */
    public record TextEntry(String id, String content, String documentId, Instant createdAt) { }

    public static UserKnowledgeView from(UserKnowledgeService.KnowledgeView v) {
        List<TextEntry> entries = v.textEntries().stream()
                .map(t -> new TextEntry(t.getId(), t.getContent(), t.getDocumentId(), t.getCreatedAt()))
                .toList();
        return new UserKnowledgeView(v.cid(), entries, v.documents());
    }
}

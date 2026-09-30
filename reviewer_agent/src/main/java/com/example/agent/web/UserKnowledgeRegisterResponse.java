package com.example.agent.web;

import java.time.Instant;

import com.example.agent.reservation.UserKnowledgeService;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST /api/users/{cid}/knowledge}.
 */
@Schema(
        name = "UserKnowledgeRegisterResponse",
        description = "Outcome of registering plain-text and/or document knowledge for a CID.",
        example = """
                {
                  "cid": "CID-88421",
                  "registeredAt": "2026-09-28T21:10:00Z",
                  "textSubmitted": true,
                  "textDocumentId": "b1e2c3d4-...",
                  "textIndexStatus": "INDEXED",
                  "documentSubmitted": true,
                  "documentId": "f9a8b7c6-...",
                  "documentFileName": "resume.pdf",
                  "documentIndexStatus": "INDEXED"
                }"""
)
public record UserKnowledgeRegisterResponse(
        String  cid,
        Instant registeredAt,
        boolean textSubmitted,
        String  textDocumentId,
        String  textIndexStatus,
        boolean documentSubmitted,
        String  documentId,
        String  documentFileName,
        String  documentIndexStatus
) {
    public static UserKnowledgeRegisterResponse from(UserKnowledgeService.RegisterResult r) {
        return new UserKnowledgeRegisterResponse(
                r.cid(), r.registeredAt(),
                r.textSubmitted(), r.textDocumentId(), r.textIndexStatus(),
                r.documentSubmitted(), r.documentId(), r.documentFileName(), r.documentIndexStatus());
    }
}

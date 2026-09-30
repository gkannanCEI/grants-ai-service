package com.example.agent.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.agent.reservation.UserKnowledgeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the per-user (CID) knowledge base.
 *
 * <p>A CID is the user identifier supplied by the master application. Each CID
 * has its own knowledge base made of plain text and/or uploaded documents, both
 * RAG-indexed under the CID so they can later ground answers for that specific
 * user (see {@code POST /api/reservations/ask}, which accepts an optional
 * {@code cid} to blend this personal knowledge in with the reservation context).
 *
 * Endpoints:
 *   POST /api/users/{cid_user_id}/knowledge - register text and/or a document under the CID
 *   GET  /api/users/{cid_user_id}/knowledge - retrieve everything stored for the CID
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "User Knowledge Base", description = "Per-user (CID) knowledge base — register and retrieve")
public class UserKnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(UserKnowledgeController.class);

    private final UserKnowledgeService knowledgeService;

    public UserKnowledgeController(UserKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Operation(
            summary = "Register knowledge for a user (CID)",
            description = """
                    Accepts plain text and/or an uploaded document for the given CID. The text is
                    stored verbatim AND RAG-indexed (chunked + embedded); the document is RAG-indexed
                    the same way reservation documents are. Both are scoped to the CID, so they can
                    later be retrieved to ground answers for that specific user. At least one of
                    text or document must be supplied.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Knowledge registered",
                    content = @Content(
                            schema = @Schema(implementation = UserKnowledgeRegisterResponse.class),
                            examples = @ExampleObject(name = "registered", value = """
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
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Neither text nor document was supplied",
                    content = @Content)
    })
    @PostMapping(value = "/{cid_user_id}/knowledge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> registerKnowledge(
            @Parameter(description = "User identifier from the master application", example = "CID-88421")
            @PathVariable("cid_user_id") String cid,
            @Parameter(description = "Plain-text knowledge about the user (optional if a document is supplied)")
            @RequestParam(value = "text", required = false) String text,
            @Parameter(description = "A document containing knowledge about the user "
                    + "(optional if text is supplied)")
            @RequestParam(value = "document", required = false) MultipartFile document) {

        boolean hasText     = text != null && !text.isBlank();
        boolean hasDocument = document != null && !document.isEmpty();

        log.info("POST /api/users/{}/knowledge - hasText={} hasDocument={}", cid, hasText, hasDocument);

        if (!hasText && !hasDocument) {
            return ResponseEntity.badRequest()
                    .body("At least one of 'text' or 'document' must be supplied.");
        }

        UserKnowledgeService.RegisterResult result = knowledgeService.register(cid, text, document);
        return ResponseEntity.ok(UserKnowledgeRegisterResponse.from(result));
    }

    @Operation(
            summary = "Retrieve a user's (CID) knowledge base",
            description = "Returns every plain-text entry (verbatim) and every indexed document "
                    + "stored for the given CID.")
    @GetMapping("/{cid_user_id}/knowledge")
    public ResponseEntity<UserKnowledgeView> getKnowledge(
            @Parameter(description = "User identifier from the master application", example = "CID-88421")
            @PathVariable("cid_user_id") String cid) {
        log.info("GET /api/users/{}/knowledge", cid);
        UserKnowledgeService.KnowledgeView view = knowledgeService.retrieve(cid);
        return ResponseEntity.ok(UserKnowledgeView.from(view));
    }
}

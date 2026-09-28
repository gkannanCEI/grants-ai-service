package com.example.agent.web;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.agent.audit.AuditService;
import com.example.agent.context.ContextManager;
import com.example.agent.context.ConversationTurn;
import com.example.agent.core.AgentRunner;
import com.example.agent.document.DocumentIngestor;
import com.example.agent.document.DocumentRegistry;
import com.example.agent.document.DocumentSummary;
import com.example.agent.document.IngestResult;
import com.example.agent.document.SimpleDocumentIngestor;
import com.example.agent.document.SourceDocumentIndexer;
import com.example.agent.model.AgentRequest;
import com.example.agent.persistence.ReservationSummary;
import com.example.agent.reservation.ReservationSummaryService;
import com.example.agent.reservation.SupplementalQaService;
import com.example.agent.model.AgentResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST controller for the agent.
 *
 * Endpoints:
 *   POST   /api/documents/upload         - upload any document, extract text and store
 *   GET    /api/documents/{applicationId} - list documents tracked for a session (with status)
 *   POST   /api/documents/ingest-source    - fetch + index the configured source URL (dedup)
 *   POST   /api/documents/ingest-directory - index all files in the configured source dir (dedup)
 *   POST   /api/chat                      - send a message to the agent
 *   GET    /api/context/{sessionId} - retrieve conversation history
 *   DELETE /api/context/{sessionId} - clear conversation history
 *   GET    /api/health              - liveness check
 *
 * Upload mode is determined by agent.vector.enabled:
 *   true  -> DocumentIngestor  (Tika + embeddings + Milvus)
 *   false -> SimpleDocumentIngestor (Tika + in-memory store, no Milvus needed)
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Review Agent", description = "Document review, chat, and reservation index summarization")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRunner            agentRunner;
    private final ContextManager         contextManager;
    private final DocumentRegistry       documentRegistry;
    private final SourceDocumentIndexer  sourceIndexer;
    private final AuditService           audit;
    private final DocumentIngestor       vectorIngestor;  // active when vector.enabled=true
    private final SimpleDocumentIngestor simpleIngestor;  // active when vector.enabled=false
    private final ReservationSummaryService reservationSummaryService;
    private final SupplementalQaService     supplementalQaService;

    public AgentController(
            AgentRunner                                    agentRunner,
            ContextManager                                 contextManager,
            DocumentRegistry                               documentRegistry,
            SourceDocumentIndexer                          sourceIndexer,
            AuditService                                   audit,
            ReservationSummaryService                      reservationSummaryService,
            SupplementalQaService                          supplementalQaService,
            @Autowired(required = false) DocumentIngestor       vectorIngestor,
            @Autowired(required = false) SimpleDocumentIngestor simpleIngestor
    ) {
        this.agentRunner      = agentRunner;
        this.contextManager   = contextManager;
        this.documentRegistry = documentRegistry;
        this.sourceIndexer    = sourceIndexer;
        this.audit            = audit;
        this.reservationSummaryService = reservationSummaryService;
        this.supplementalQaService     = supplementalQaService;
        this.vectorIngestor   = vectorIngestor;
        this.simpleIngestor   = simpleIngestor;
    }

    // ── Document upload ──────────────────────────────────────────────────────

    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "applicationId", required = false) String applicationId) {

        log.info("POST /api/documents/upload - file={} size={}B applicationId={}",
                file.getOriginalFilename(), file.getSize(), applicationId);

        if (file.isEmpty()) {
            audit.documentUploaded(applicationId, file.getOriginalFilename(), null,
                    file.getSize(), false);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "Bad Request", "Uploaded file is empty",
                            "/api/documents/upload", Instant.now()));
        }

        try {
            // Use whichever ingestor is active based on agent.vector.enabled
            IngestResult result = null;
            if (simpleIngestor != null) {
                result = simpleIngestor.ingest(file, applicationId);
            } else if (vectorIngestor != null) {
                result = vectorIngestor.ingest(file, applicationId);
            }

            if (result == null) {
                audit.documentUploaded(applicationId, file.getOriginalFilename(), null,
                        file.getSize(), false);
                return ResponseEntity.status(503).body(
                        new ErrorResponse(503, "Service Unavailable",
                                "No document ingestor is available",
                                "/api/documents/upload", Instant.now()));
            }

            audit.documentUploaded(result.applicationId(), result.fileName(),
                    result.documentId(), file.getSize(), true);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            audit.documentUploaded(applicationId, file.getOriginalFilename(), null,
                    file.getSize(), false);
            throw e;
        }
    }

    // ── List indexed documents for a session ─────────────────────────────────

    /**
     * Lists the documents indexed under the given applicationId (the per-session
     * grouping key used at upload time), ordered oldest first. Returns metadata
     * only — use the documentId with the chat/read endpoints to fetch content.
     *
     * Returns an empty list (200) when nothing has been indexed for the id.
     */
    @GetMapping("/documents/{applicationId}")
    public ResponseEntity<List<DocumentSummary>> listDocuments(@PathVariable String applicationId) {
        List<DocumentSummary> documents = documentRegistry.listByApplicationId(applicationId);
        log.info("GET /api/documents/{} - {} document(s) tracked", applicationId, documents.size());
        return ResponseEntity.ok(documents);
    }

    // ── Manually trigger source-endpoint ingestion ──────────────────────────

    /**
     * Fetches the document from the configured {@code agent.ingest.source-url}
     * and indexes it if it has not already been processed (same fetch + de-dup
     * logic that runs at startup). Returns the outcome (INDEXED / SKIPPED / FAILED).
     */
    @PostMapping("/documents/ingest-source")
    public ResponseEntity<SourceDocumentIndexer.IngestOutcome> ingestFromSource() {
        SourceDocumentIndexer.IngestOutcome outcome = sourceIndexer.indexFromSource();
        log.info("POST /api/documents/ingest-source - {}", outcome);
        audit.sourceIngested(null, outcome.source(), outcome.status(), outcome.documentId());
        return ResponseEntity.ok(outcome);
    }

    /**
     * Indexes every document under the configured {@code agent.ingest.source-dir}
     * (recursively), filing them under the given applicationId. Files already
     * indexed with unchanged content are skipped. Returns a summary with per-file
     * outcomes.
     *
     * @param applicationId application/session key to index the documents under.
     *                      When omitted, falls back to agent.ingest.application-id.
     */
    @PostMapping("/documents/ingest-directory")
    public ResponseEntity<SourceDocumentIndexer.DirectoryIngestSummary> ingestFromDirectory(
            @RequestParam(value = "applicationId", required = false) String applicationId) {
        SourceDocumentIndexer.DirectoryIngestSummary summary =
                sourceIndexer.indexFromDirectory(applicationId);
        log.info("POST /api/documents/ingest-directory - applicationId={} {}",
                applicationId, summary);
        audit.directoryIngested(applicationId, summary.getDirectory(),
                summary.getTotal(), summary.getIndexed(),
                summary.getSkipped(), summary.getFailed());
        return ResponseEntity.ok(summary);
    }

    /**
     * Indexes every document belonging to a specific reservation. The documents
     * are expected under a reservation-named subfolder of the configured
     * {@code agent.ingest.source-dir}, i.e. {@code {source-dir}/{reservationId}}
     * (e.g. {@code D:\FHLB-DM\document_collection\RES-2026-000123}). They are
     * filed under the reservation ID as the applicationId. Files already indexed
     * with unchanged content are skipped.
     *
     * @param reservationId the reservation whose document folder should be indexed
     */
    @Operation(
            summary = "Index a reservation's documents",
            description = """
                    Indexes every document under {source-dir}/{reservationId} (recursively) and
                    files them under the reservation ID as the applicationId. Unchanged files that
                    are already indexed are skipped. Returns a per-file outcome summary.""")
    @PostMapping("/documents/ingest-directory/{reservationId}")
    public ResponseEntity<SourceDocumentIndexer.DirectoryIngestSummary> ingestReservationDirectory(
            @PathVariable String reservationId) {
        SourceDocumentIndexer.DirectoryIngestSummary summary =
                sourceIndexer.indexReservationDirectory(reservationId);
        log.info("POST /api/documents/ingest-directory/{} - {}", reservationId, summary);
        audit.directoryIngested(reservationId, summary.getDirectory(),
                summary.getTotal(), summary.getIndexed(),
                summary.getSkipped(), summary.getFailed());
        return ResponseEntity.ok(summary);
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("POST /api/chat - userId={} sessionId={} applicationId={}",
                request.userId(), request.sessionId(), request.applicationId());
        audit.chatRequest(request.userId(), request.sessionId(), request.applicationId(),
                request.message() != null ? request.message().length() : 0);

        AgentResponse result = agentRunner.run(
                new AgentRequest(request.userId(), request.sessionId(),
                        request.applicationId(), request.message())
        );

        return ResponseEntity.ok(new ChatResponse(
                result.sessionId(),
                result.response(),
                result.durationMillis(),
                result.timestamp()
        ));
    }

    // ── Reservation index summary ─────────────────────────────────────────────

    /**
     * Accepts a reservation's question/answer (key/value) pairs, uses the LLM to
     * fold every pair into a single lossless narrative summary, and stores the
     * result (status + reservationId + summary) in the reservation_summary table.
     */
    @Operation(
            summary = "Summarize a reservation index submission",
            description = """
                    Accepts a reservation ID and a JSON map of question → answer pairs, uses the
                    LLM to fold every pair into a single lossless narrative summary, and stores the
                    result (status + reservationId + summary) in the reservation_summary table.
                    Every submitted key/value is treated as critical and represented in the summary.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary generated and stored",
                    content = @Content(
                            schema = @Schema(implementation = ReservationIndexResponse.class),
                            examples = @ExampleObject(name = "completed", value = """
                                    {
                                      "id": "3f8a1c2e-9b4d-4e21-8a77-5c0b1d9e2f34",
                                      "reservationId": "RES-2026-000123",
                                      "status": "COMPLETED",
                                      "summary": "Reservation RES-2026-000123 covers the Downtown Housing Renewal project, which requests $250,000 and is planned over an 18-month timeline. The primary contact is Jane Doe (jane.doe@example.org), and the project comprises 42 units.",
                                      "errorMessage": null,
                                      "createdAt": "2026-09-28T14:22:05.123Z"
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Validation failed (blank reservationId or empty responses)",
                    content = @Content)
    })
    @PostMapping("/reservations/index")
    public ResponseEntity<ReservationIndexResponse> indexReservation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = ReservationIndexRequest.class),
                            examples = @ExampleObject(name = "sample", value = """
                                    {
                                      "reservationId": "RES-2026-000123",
                                      "responses": {
                                        "Project name": "Downtown Housing Renewal",
                                        "Requested amount": "$250,000",
                                        "Project timeline": "18 months",
                                        "Primary contact": "Jane Doe, jane.doe@example.org",
                                        "Number of units": "42"
                                      }
                                    }""")))
            @Valid @RequestBody ReservationIndexRequest request) {

        log.info("POST /api/reservations/index - reservationId={} pairs={}",
                request.reservationId(), request.responses().size());

        ReservationSummary result = reservationSummaryService.summarizeAndStore(
                request.reservationId(), request.responses());

        return ResponseEntity.ok(ReservationIndexResponse.from(result));
    }

    /**
     * Supplemental question-answering agent scoped to a reservation. Answers the
     * supplied question (with an optional hint) using the reservation's stored AI
     * summary and its indexed documents as context. The model is instructed to
     * answer only from that context.
     */
    @Operation(
            summary = "Ask a supplemental question about a reservation",
            description = """
                    Answers a follow-up question about a reservation using its stored AI summary
                    and its indexed documents (retrieved via RAG, scoped to the reservation) as
                    context. An optional hint guides how the question is interpreted. If the answer
                    is not in the context, the agent says so rather than guessing.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answers produced from reservation context",
                    content = @Content(
                            schema = @Schema(implementation = SupplementalAnswerResponse.class),
                            examples = @ExampleObject(name = "answered", value = """
                                    {
                                      "reservationId": "RES-2026-000123",
                                      "answers": [
                                        {
                                          "question": "What is the total requested funding and over what period?",
                                          "answer": "The reservation requests $250,000 over an 18-month project timeline.",
                                          "usedSummary": true,
                                          "usedDocuments": true,
                                          "documentChunks": 3
                                        }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Validation failed (blank reservationId or empty questions)",
                    content = @Content)
    })
    @PostMapping("/reservations/ask")
    public ResponseEntity<SupplementalAnswerResponse> askReservation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = SupplementalQuestionRequest.class),
                            examples = @ExampleObject(name = "sample", value = """
                                    {
                                      "reservationId": "RES-2026-000123",
                                      "questions": [
                                        {
                                          "question": "What is the total requested funding and over what period?",
                                          "hint": "Look for the requested amount and the project timeline."
                                        },
                                        {
                                          "question": "Who is the primary contact for this reservation?"
                                        }
                                      ]
                                    }""")))
            @Valid @RequestBody SupplementalQuestionRequest request) {

        log.info("POST /api/reservations/ask - reservationId={} questions={}",
                request.reservationId(), request.questions().size());

        List<SupplementalQaService.Question> questions = request.questions().stream()
                .map(q -> new SupplementalQaService.Question(q.question(), q.hint()))
                .toList();

        List<SupplementalQaService.Answer> answers =
                supplementalQaService.answerAll(request.reservationId(), questions);

        return ResponseEntity.ok(
                SupplementalAnswerResponse.from(request.reservationId(), answers));
    }

    // ── Context management ───────────────────────────────────────────────────

    @GetMapping("/context/{sessionId}")
    public ResponseEntity<List<ConversationTurn>> getContext(@PathVariable String sessionId) {
        return ResponseEntity.ok(contextManager.getHistory(sessionId));
    }

    @DeleteMapping("/context/{sessionId}")
    public ResponseEntity<Void> clearContext(@PathVariable String sessionId) {
        contextManager.clearSession(sessionId);
        audit.contextCleared(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ── Health ───────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Agent is running - active sessions: "
                + contextManager.activeSessions());
    }
}

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

import com.example.agent.context.ContextManager;
import com.example.agent.context.ConversationTurn;
import com.example.agent.core.AgentRunner;
import com.example.agent.document.DocumentIngestor;
import com.example.agent.document.IngestResult;
import com.example.agent.document.SimpleDocumentIngestor;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;

import jakarta.validation.Valid;

/**
 * REST controller for the agent.
 *
 * Endpoints:
 *   POST   /api/documents/upload    - upload any document, extract text and store
 *   POST   /api/chat                - send a message to the agent
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
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRunner            agentRunner;
    private final ContextManager         contextManager;
    private final DocumentIngestor       vectorIngestor;  // active when vector.enabled=true
    private final SimpleDocumentIngestor simpleIngestor;  // active when vector.enabled=false

    public AgentController(
            AgentRunner                                    agentRunner,
            ContextManager                                 contextManager,
            @Autowired(required = false) DocumentIngestor       vectorIngestor,
            @Autowired(required = false) SimpleDocumentIngestor simpleIngestor
    ) {
        this.agentRunner    = agentRunner;
        this.contextManager = contextManager;
        this.vectorIngestor = vectorIngestor;
        this.simpleIngestor = simpleIngestor;
    }

    // ── Document upload ──────────────────────────────────────────────────────

    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "applicationId", required = false) String applicationId) {

        log.info("POST /api/documents/upload - file={} size={}B applicationId={}",
                file.getOriginalFilename(), file.getSize(), applicationId);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "Bad Request", "Uploaded file is empty",
                            "/api/documents/upload", Instant.now()));
        }

        // Use whichever ingestor is active based on agent.vector.enabled
        if (simpleIngestor != null) {
            IngestResult result = simpleIngestor.ingest(file, applicationId);
            return ResponseEntity.ok(result);
        }

        if (vectorIngestor != null) {
            IngestResult result = vectorIngestor.ingest(file);
            return ResponseEntity.ok(result);
        }

        return ResponseEntity.status(503).body(
                new ErrorResponse(503, "Service Unavailable",
                        "No document ingestor is available",
                        "/api/documents/upload", Instant.now()));
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("POST /api/chat - userId={} sessionId={}", request.userId(), request.sessionId());

        AgentResponse result = agentRunner.run(
                new AgentRequest(request.userId(), request.sessionId(), request.message())
        );

        return ResponseEntity.ok(new ChatResponse(
                result.sessionId(),
                result.response(),
                result.durationMillis(),
                result.timestamp()
        ));
    }

    // ── Context management ───────────────────────────────────────────────────

    @GetMapping("/context/{sessionId}")
    public ResponseEntity<List<ConversationTurn>> getContext(@PathVariable String sessionId) {
        return ResponseEntity.ok(contextManager.getHistory(sessionId));
    }

    @DeleteMapping("/context/{sessionId}")
    public ResponseEntity<Void> clearContext(@PathVariable String sessionId) {
        contextManager.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ── Health ───────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Agent is running - active sessions: "
                + contextManager.activeSessions());
    }
}

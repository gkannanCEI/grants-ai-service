package com.example.agent.reservation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.agent.document.DocumentIngestor;
import com.example.agent.document.DocumentRegistry;
import com.example.agent.document.DocumentSummary;
import com.example.agent.document.IngestResult;
import com.example.agent.document.InMemoryMultipartFile;
import com.example.agent.document.SimpleDocumentIngestor;
import com.example.agent.persistence.UserKnowledgeText;
import com.example.agent.persistence.UserKnowledgeTextRepository;

/**
 * Per-CID (per-user) knowledge base.
 *
 * <p>A CID is the user identifier supplied by the master application. This
 * service registers two kinds of knowledge for a CID:
 * <ol>
 *   <li>plain text, stored verbatim AND RAG-indexed (chunked + embedded), and</li>
 *   <li>an uploaded document, RAG-indexed the same way documents already are.</li>
 * </ol>
 * Both are indexed through the existing document ingestion pipeline
 * ({@link DocumentIngestor} / {@link SimpleDocumentIngestor}), using the CID as
 * the scoping key — exactly the same mechanism {@code applicationId} already
 * uses for reservation documents. That means the CID's knowledge is retrievable
 * later via {@link com.example.agent.retrieval.RetrievalService#retrieve} by
 * passing the CID in place of an applicationId.
 */
@Service
public class UserKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(UserKnowledgeService.class);

    private final UserKnowledgeTextRepository textRepository;
    private final DocumentRegistry            documentRegistry;
    private final DocumentIngestor            vectorIngestor;  // active when vector.enabled=true
    private final SimpleDocumentIngestor       simpleIngestor;  // active when vector.enabled=false

    public UserKnowledgeService(
            UserKnowledgeTextRepository textRepository,
            DocumentRegistry            documentRegistry,
            @Autowired(required = false) DocumentIngestor       vectorIngestor,
            @Autowired(required = false) SimpleDocumentIngestor simpleIngestor) {
        this.textRepository   = textRepository;
        this.documentRegistry = documentRegistry;
        this.vectorIngestor   = vectorIngestor;
        this.simpleIngestor   = simpleIngestor;
    }

    /**
     * Registers knowledge for a CID: plain text and/or a document. At least one
     * of the two must be supplied. Both are indexed (RAG) under the CID.
     *
     * @param cid      the user identifier this knowledge belongs to
     * @param text     plain-text knowledge (may be null/blank if a document is supplied)
     * @param document an uploaded document (may be null/empty if text is supplied)
     * @return a summary of what was registered
     */
    @Transactional
    public RegisterResult register(String cid, String text, MultipartFile document) {
        boolean hasText     = text != null && !text.isBlank();
        boolean hasDocument = document != null && !document.isEmpty();

        String  textDocumentId     = null;
        String  textIndexStatus    = "SKIPPED";
        String  documentId         = null;
        String  documentFileName   = null;
        String  documentIndexStatus = "SKIPPED";

        if (hasText) {
            // 1a. Store the raw text verbatim so it can be retrieved as-is.
            UserKnowledgeText entry = new UserKnowledgeText(cid, text.trim());

            // 1b. RAG-index the same text by wrapping it as a small text file and
            //     pushing it through the existing ingestion pipeline, scoped by CID.
            try {
                MultipartFile textFile = new InMemoryMultipartFile(
                        "file", "knowledge-" + entry.getId() + ".txt",
                        "text/plain", text.trim().getBytes(StandardCharsets.UTF_8));
                IngestResult result = ingest(textFile, cid);
                entry.setDocumentId(result.documentId());
                textDocumentId  = result.documentId();
                textIndexStatus = "INDEXED";
            } catch (Exception e) {
                log.warn("Text knowledge indexing failed for cid={} — {}", cid, e.getMessage());
                textIndexStatus = "FAILED: " + e.getMessage();
            }

            textRepository.save(entry);
            log.info("Registered text knowledge — cid={} entryId={} indexStatus={}",
                    cid, entry.getId(), textIndexStatus);
        }

        if (hasDocument) {
            // 2. RAG-index the uploaded document, scoped by CID — same pipeline as
            //    reservation document ingestion, just using the CID as the tag.
            try {
                IngestResult result = ingest(document, cid);
                documentId          = result.documentId();
                documentFileName     = result.fileName();
                documentIndexStatus = "INDEXED";
            } catch (Exception e) {
                log.warn("Document knowledge indexing failed for cid={} — {}", cid, e.getMessage());
                documentIndexStatus = "FAILED: " + e.getMessage();
            }
            log.info("Registered document knowledge — cid={} documentId={} indexStatus={}",
                    cid, documentId, documentIndexStatus);
        }

        return new RegisterResult(cid, Instant.now(),
                hasText, textDocumentId, textIndexStatus,
                hasDocument, documentId, documentFileName, documentIndexStatus);
    }

    /**
     * Retrieves everything stored for a CID: the raw text entries (verbatim) and
     * the metadata of every indexed document (including the RAG-indexed text entries,
     * which are also tracked as documents).
     */
    @Transactional(readOnly = true)
    public KnowledgeView retrieve(String cid) {
        List<UserKnowledgeText> textEntries = textRepository.findByCidOrderByCreatedAtDesc(cid);
        List<DocumentSummary>   documents   = documentRegistry.listByApplicationId(cid);
        return new KnowledgeView(cid, textEntries, documents);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private IngestResult ingest(MultipartFile file, String cid) throws Exception {
        if (simpleIngestor != null) {
            return simpleIngestor.ingest(file, cid);
        } else if (vectorIngestor != null) {
            return vectorIngestor.ingest(file, cid);
        }
        throw new IllegalStateException("No document ingestor is available");
    }

    // ── Result types ─────────────────────────────────────────────────────────

    /** Outcome of registering knowledge for a CID. */
    public record RegisterResult(
            String  cid,
            Instant registeredAt,
            boolean textSubmitted,
            String  textDocumentId,
            String  textIndexStatus,
            boolean documentSubmitted,
            String  documentId,
            String  documentFileName,
            String  documentIndexStatus
    ) { }

    /** Everything currently stored for a CID. */
    public record KnowledgeView(
            String                  cid,
            List<UserKnowledgeText> textEntries,
            List<DocumentSummary>   documents
    ) { }
}

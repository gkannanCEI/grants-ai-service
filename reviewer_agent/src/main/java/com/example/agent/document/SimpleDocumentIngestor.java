package com.example.agent.document;

import java.io.InputStream;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.agent.exception.ToolExecutionException;

/**
 * Lightweight document ingestor — no Milvus, no embeddings.
 *
 * Extracts text with Apache Tika and stores it in {@link InMemoryDocumentStore}.
 * Active when: agent.vector.enabled=false
 *
 * Large document handling:
 *   - TIKA_CHAR_LIMIT caps extracted text at 10 million chars (~10 MB) to prevent OOM.
 *   - Tika streams from the MultipartFile InputStream directly — the file bytes are
 *     never fully loaded into a String or byte[] before parsing begins.
 *   - For files beyond the cap, text is truncated and a warning is logged.
 */
@Service
@ConditionalOnProperty(prefix = "agent.vector", name = "enabled", havingValue = "false")
public class SimpleDocumentIngestor {

    private static final Logger log = LoggerFactory.getLogger(SimpleDocumentIngestor.class);

    // 10 million chars ~ 10 MB of plain text. Increase if you regularly process very large docs.
    private static final int TIKA_CHAR_LIMIT = 10_000_000;

    private final InMemoryDocumentStore documentStore;

    public SimpleDocumentIngestor(InMemoryDocumentStore documentStore) {
        this.documentStore = documentStore;
    }

    /**
     * Extract text from the uploaded file and store it in memory.
     *
     * @param file          the uploaded multipart file
     * @param applicationId business application ID — pass null to use the default
     * @return IngestResult with documentId and extraction statistics
     */
    public IngestResult ingest(MultipartFile file, String applicationId) {
        String documentId = java.util.UUID.randomUUID().toString();
        String fileName   = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";

        log.info("Ingesting document - id={} applicationId={} file={} size={}B",
                documentId, applicationId, fileName, file.getSize());

        AutoDetectParser   parser   = new AutoDetectParser();
        Metadata           metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        // Streams directly from MultipartFile — no full in-memory byte[] copy
        BodyContentHandler handler = new BodyContentHandler(TIKA_CHAR_LIMIT);
        ParseContext       context  = new ParseContext();

        try (InputStream stream = file.getInputStream()) {
            parser.parse(stream, handler, metadata, context);
        } catch (org.apache.tika.exception.TikaException e) {
            // WriteLimitReachedException is a subclass of TikaException — log warning, continue with truncated text
            if (e.getMessage() != null && e.getMessage().contains("limit")) {
                log.warn("Document truncated at {} chars (TIKA_CHAR_LIMIT) for file={}", TIKA_CHAR_LIMIT, fileName);
            } else {
                throw new ToolExecutionException("ingestDocument",
                        "Text extraction failed for " + fileName + ": " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new ToolExecutionException("ingestDocument",
                    "Text extraction failed for " + fileName + ": " + e.getMessage(), e);
        }

        String text     = handler.toString();
        String mimeType = metadata.get(Metadata.CONTENT_TYPE);
        if (mimeType == null) mimeType = "application/octet-stream";

        if (text == null || text.isBlank()) {
            throw new ToolExecutionException("ingestDocument",
                    "No text could be extracted from: " + fileName);
        }

        int pageCount = -1;
        String pages  = metadata.get("xmpTPg:NPages");
        if (pages == null) pages = metadata.get("meta:page-count");
        if (pages != null) {
            try { pageCount = Integer.parseInt(pages.trim()); } catch (NumberFormatException ignored) {}
        }

        documentStore.save(documentId, applicationId, fileName, mimeType, text, pageCount);

        log.info("Ingestion complete - documentId={} applicationId={} chars={} mimeType={}",
                documentId, applicationId, text.length(), mimeType);

        // Resolve effective applicationId (may have been defaulted inside save())
        String effectiveAppId = (applicationId != null && !applicationId.isBlank())
                ? applicationId : InMemoryDocumentStore.DEFAULT_APPLICATION_ID;

        return new IngestResult(documentId, effectiveAppId, fileName, mimeType, text.length(), 1, pageCount);
    }
}

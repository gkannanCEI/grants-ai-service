package com.example.agent.document;

import java.io.InputStream;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
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

    // 2 million chars ~ 4 MB of text. Plenty for document review; caps the parse footprint.
    private static final int TIKA_CHAR_LIMIT = 2_000_000;

    private final InMemoryDocumentStore documentStore;
    private final DocumentRegistry      documentRegistry;

    public SimpleDocumentIngestor(InMemoryDocumentStore documentStore,
                                  DocumentRegistry documentRegistry) {
        this.documentStore    = documentStore;
        this.documentRegistry = documentRegistry;
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
        ParseContext       context  = buildTextOnlyContext(parser);

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

        IngestResult result =
                new IngestResult(documentId, effectiveAppId, fileName, mimeType, text.length(), 1, pageCount);
        documentRegistry.register(result);
        return result;
    }

    /**
     * Builds a ParseContext tuned for text-only extraction.
     *
     * <p>Critically, this disables PDF page image rendering and OCR. Tika's PDF parser can
     * otherwise rasterize pages into full-resolution {@link java.awt.image.BufferedImage}
     * buffers (width × height × 4 bytes) — an allocation that can reach hundreds of MB and
     * trigger {@link OutOfMemoryError} even for a tiny PDF, completely independent of file size.
     * Since this service only needs the extracted text, image extraction and OCR are turned off.
     */
    private ParseContext buildTextOnlyContext(Parser parser) {
        ParseContext context = new ParseContext();

        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);      // don't pull embedded images into memory
        pdfConfig.setExtractUniqueInlineImagesOnly(true);
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR); // no page rasterization for OCR
        context.set(PDFParserConfig.class, pdfConfig);

        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setSkipOcr(true);                   // never invoke Tesseract / image rendering
        context.set(TesseractOCRConfig.class, ocrConfig);

        // Ensure nested/embedded documents are parsed with the same text-only settings.
        context.set(Parser.class, parser);

        return context;
    }
}

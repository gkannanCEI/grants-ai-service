package com.example.agent.document;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.example.agent.embedding.EmbeddingClient;
import com.example.agent.exception.ToolExecutionException;
import com.example.agent.vector.VectorDocument;
import com.example.agent.vector.VectorStore;

/**
 * Orchestrates the full document ingestion pipeline:
 *
 *   1. Extract text from any document format using Apache Tika
 *      (PDF, DOCX, XLSX, PPTX, ODT, HTML, TXT, RTF, images with OCR, etc.)
 *   2. Split into overlapping chunks via {@link DocumentChunker}
 *   3. Embed each chunk via {@link EmbeddingClient}
 *   4. Upsert all chunks into Milvus via {@link VectorStore}
 *
 * Returns an {@link IngestResult} with the generated documentId and statistics.
 * The documentId is what the agent uses in subsequent tool calls (readDocument,
 * scoreDocument, validateDocument).
 *
 * Only active when agent.vector.enabled=true (Milvus must be available).
 */
@Service
@ConditionalOnProperty(prefix = "agent.vector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestor {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestor.class);

    // Tika's BodyContentHandler limit — cap at 2 million chars (~4 MB of text)
    // to bound the parse footprint. Increase if you regularly process very large docs.
    private static final int TIKA_CHAR_LIMIT = 2_000_000;

    private final DocumentChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final VectorStore     vectorStore;
    private final int             expectedDimension;

    public DocumentIngestor(
            DocumentChunker chunker,
            EmbeddingClient embeddingClient,
            VectorStore     vectorStore,
            com.example.agent.config.AgentProperties props
    ) {
        this.chunker           = chunker;
        this.embeddingClient   = embeddingClient;
        this.vectorStore       = vectorStore;
        this.expectedDimension = props.getVector().getDimension();
    }

    /**
     * Ingests a document uploaded as a {@link MultipartFile}.
     *
     * Supported formats: PDF, DOCX, XLSX, PPTX, ODP, ODT, HTML, TXT, RTF,
     * CSV, XML, JSON, images (requires Tesseract for OCR), and many more.
     *
     * @param file The uploaded file
     * @return IngestResult with documentId and ingestion statistics
     */
    public IngestResult ingest(MultipartFile file) {
        String documentId = UUID.randomUUID().toString();
        String fileName   = file.getOriginalFilename() != null
                            ? file.getOriginalFilename() : "unknown";

        log.info("Ingesting document — id={} file={} size={}B",
                documentId, fileName, file.getSize());

        // 1. Extract text + metadata with Tika
        ExtractionResult extraction = extractWithTika(file, fileName);

        log.info("Extraction complete — id={} chars={} mimeType={}",
                documentId, extraction.text().length(), extraction.mimeType());

        // 2. Chunk the extracted text
        List<DocumentChunk> chunks = chunker.chunk(documentId, extraction.text());
        log.info("Chunked into {} chunks — documentId={}", chunks.size(), documentId);

        if (chunks.isEmpty()) {
            throw new ToolExecutionException("ingestDocument",
                    "No text could be extracted from: " + fileName);
        }

        // 3. Embed and upsert all chunks in batches
        List<VectorDocument> vectorDocs = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            List<Float> embedding = embeddingClient.embed(chunk.text());

            // Guard: a malformed/empty embedding or a dimension mismatch with the Milvus
            // collection is a common cause of huge gRPC buffer allocations (OOM) or silent
            // corruption. Fail fast with a clear message instead.
            int actualDim = (embedding == null) ? 0 : embedding.size();
            log.debug("Embedded chunk {} — dimension={} (expected={})",
                    chunk.chunkIndex(), actualDim, expectedDimension);
            if (actualDim != expectedDimension) {
                throw new ToolExecutionException("ingestDocument",
                        "Embedding dimension mismatch for chunk " + chunk.chunkIndex()
                        + ": got " + actualDim + " floats but the Milvus collection expects "
                        + expectedDimension + ". Check agent.vector.dimension matches the "
                        + "embedding model output, and that the embedding API call succeeded.");
            }

            vectorDocs.add(VectorDocument.forInsert(
                    documentId + "_chunk_" + chunk.chunkIndex(),
                    chunk.text(),
                    embedding,
                    Map.of(
                            "source",      documentId,
                            "fileName",    fileName,
                            "mimeType",    extraction.mimeType(),
                            "chunkIndex",  String.valueOf(chunk.chunkIndex()),
                            "charOffset",  String.valueOf(chunk.charOffset())
                    )
            ));
        }

        vectorStore.upsert(vectorDocs);

        log.info("Ingestion complete — documentId={} chunks={} stored in Milvus",
                documentId, vectorDocs.size());

        return new IngestResult(
                documentId,
                InMemoryDocumentStore.DEFAULT_APPLICATION_ID,
                fileName,
                extraction.mimeType(),
                extraction.text().length(),
                chunks.size(),
                extraction.pageCount()
        );
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private ExtractionResult extractWithTika(MultipartFile file, String fileName) {
        AutoDetectParser parser  = new AutoDetectParser();
        Metadata         metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        BodyContentHandler handler = new BodyContentHandler(TIKA_CHAR_LIMIT);
        ParseContext context = buildTextOnlyContext(parser);

        try (InputStream stream = file.getInputStream()) {
            parser.parse(stream, handler, metadata, context);
        } catch (Exception e) {
            throw new ToolExecutionException("ingestDocument",
                    "Tika extraction failed for " + fileName + ": " + e.getMessage(), e);
        }

        String text     = handler.toString();
        String mimeType = metadata.get(Metadata.CONTENT_TYPE);
        if (mimeType == null) mimeType = "application/octet-stream";

        // Try to get page count from metadata (available for PDF, DOCX, etc.)
        int pageCount = -1;
        String pages  = metadata.get("xmpTPg:NPages");
        if (pages == null) pages = metadata.get("meta:page-count");
        if (pages != null) {
            try { pageCount = Integer.parseInt(pages.trim()); } catch (NumberFormatException ignored) {}
        }

        return new ExtractionResult(text, mimeType, pageCount);
    }

    /**
     * Builds a ParseContext tuned for text-only extraction — disables PDF page image rendering
     * and OCR so Tika does not rasterize pages into large in-memory image buffers (a common
     * cause of OutOfMemoryError even for tiny PDFs). Only the extracted text is needed here.
     */
    private ParseContext buildTextOnlyContext(Parser parser) {
        ParseContext context = new ParseContext();

        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setExtractUniqueInlineImagesOnly(true);
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdfConfig);

        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, ocrConfig);

        context.set(Parser.class, parser);
        return context;
    }

    /** Internal holder for Tika extraction output. */
    private record ExtractionResult(String text, String mimeType, int pageCount) {}
}

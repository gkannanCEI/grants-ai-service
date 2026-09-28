package com.example.agent.document;

import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.agent.config.AgentProperties;

/**
 * Indexes documents from configured sources — a local directory
 * ({@code agent.ingest.source-dir}) and/or a single HTTP(S) URL
 * ({@code agent.ingest.source-url}) — but only files/URLs that have not
 * already been processed.
 *
 * <p>De-duplication: each document's bytes are hashed (SHA-256). If the
 * registry already has an INDEXED record for the same {@code source + content
 * hash}, that document is skipped. This survives restarts because the registry
 * is backed by PostgreSQL. For directory files the "source" is the absolute
 * file path; for the URL source it is the URL.
 *
 * <p>Runs automatically at startup when {@code agent.ingest.enabled=true} and
 * {@code agent.ingest.run-on-startup=true}; can also be triggered on demand via
 * {@link #indexFromDirectory()} and {@link #indexFromSource()}.
 */
@Component
public class SourceDocumentIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceDocumentIndexer.class);

    private final AgentProperties.Ingest config;
    private final DocumentRegistry       registry;
    private final DocumentIngestor       vectorIngestor;  // active when vector.enabled=true
    private final SimpleDocumentIngestor simpleIngestor;  // active when vector.enabled=false
    private final RestClient             restClient = RestClient.create();

    public SourceDocumentIndexer(
            AgentProperties properties,
            DocumentRegistry registry,
            @Autowired(required = false) DocumentIngestor       vectorIngestor,
            @Autowired(required = false) SimpleDocumentIngestor simpleIngestor) {
        this.config         = properties.getIngest();
        this.registry       = registry;
        this.vectorIngestor = vectorIngestor;
        this.simpleIngestor = simpleIngestor;
    }

    // ── Startup hook ─────────────────────────────────────────────────────────

    @Override
    public void run(ApplicationArguments args) {
        if (!config.isEnabled() || !config.isRunOnStartup()) {
            log.info("Startup auto-ingest disabled (enabled={} runOnStartup={})",
                    config.isEnabled(), config.isRunOnStartup());
            return;
        }

        boolean hasDir = !isBlank(config.getSourceDir());
        boolean hasUrl = !isBlank(config.getSourceUrl());
        if (!hasDir && !hasUrl) {
            log.info("No auto-ingest source configured (source-dir and source-url both blank)");
            return;
        }

        if (hasDir) {
            try {
                DirectoryIngestSummary summary = indexFromDirectory();
                log.info("Startup directory ingest — {}", summary);
            } catch (Exception e) {
                log.error("Startup directory ingest failed for {} — {}",
                        config.getSourceDir(), e.getMessage(), e);
            }
        }

        if (hasUrl) {
            try {
                IngestOutcome outcome = indexFromSource();
                log.info("Startup URL ingest — {}", outcome);
            } catch (Exception e) {
                log.error("Startup URL ingest failed for {} — {}",
                        config.getSourceUrl(), e.getMessage(), e);
            }
        }
    }

    // ── Directory ingestion ──────────────────────────────────────────────────

    /**
     * Indexes every document found (recursively) under the configured
     * {@code agent.ingest.source-dir}. Files already indexed (unchanged content)
     * are skipped; each file's outcome is collected in the summary.
     */
    public DirectoryIngestSummary indexFromDirectory() {
        return indexFromDirectory(config.getSourceDir(), config.getApplicationId());
    }

    /**
     * Indexes the configured {@code agent.ingest.source-dir}, filing the documents
     * under the given applicationId. When {@code applicationId} is null or blank,
     * falls back to the configured {@code agent.ingest.application-id}.
     */
    public DirectoryIngestSummary indexFromDirectory(String applicationId) {
        String appId = (applicationId != null && !applicationId.isBlank())
                ? applicationId : config.getApplicationId();
        return indexFromDirectory(config.getSourceDir(), appId);
    }

    /**
     * Indexes the documents belonging to a specific reservation.
     *
     * <p>The reservation's documents live in a subfolder of the configured
     * {@code agent.ingest.source-dir} named after the reservation ID, i.e.
     * <pre>{@code {source-dir}/{reservationId}}</pre>
     * (e.g. {@code D:\FHLB-DM\document_collection\RES-2026-000123}). Every file
     * under that folder is indexed (recursively) and filed under the reservation
     * ID as the applicationId, so it can later be retrieved per reservation.
     *
     * @param reservationId the reservation whose document folder should be indexed;
     *                      also used as the applicationId grouping key
     */
    public DirectoryIngestSummary indexReservationDirectory(String reservationId) {
        if (isBlank(reservationId)) {
            DirectoryIngestSummary summary = new DirectoryIngestSummary(config.getSourceDir());
            summary.add(IngestOutcome.failed(config.getSourceDir(), "reservationId is blank"));
            return summary;
        }
        if (isBlank(config.getSourceDir())) {
            DirectoryIngestSummary summary = new DirectoryIngestSummary(null);
            summary.add(IngestOutcome.failed(null, "agent.ingest.source-dir is not configured"));
            return summary;
        }

        // Guard against path traversal: the reservationId must be a single folder
        // name, not a path that could escape the configured base directory.
        String safeId = reservationId.trim();
        if (safeId.contains("/") || safeId.contains("\\") || safeId.contains("..")) {
            DirectoryIngestSummary summary = new DirectoryIngestSummary(config.getSourceDir());
            summary.add(IngestOutcome.failed(safeId,
                    "invalid reservationId — must not contain path separators or '..'"));
            return summary;
        }

        Path base            = Path.of(config.getSourceDir()).toAbsolutePath().normalize();
        Path reservationPath  = base.resolve(safeId).normalize();

        // Defence in depth: ensure the resolved path is still inside the base dir.
        if (!reservationPath.startsWith(base)) {
            DirectoryIngestSummary summary = new DirectoryIngestSummary(reservationPath.toString());
            summary.add(IngestOutcome.failed(reservationPath.toString(),
                    "resolved path escapes the configured source-dir"));
            return summary;
        }

        log.info("Reservation directory ingest — reservationId={} dir={}", safeId, reservationPath);
        return indexFromDirectory(reservationPath.toString(), safeId);
    }

    /**
     * Indexes every document found (recursively) under {@code directoryPath},
     * filing them under {@code applicationId}.
     */
    public DirectoryIngestSummary indexFromDirectory(String directoryPath, String applicationId) {
        DirectoryIngestSummary summary = new DirectoryIngestSummary(directoryPath);

        if (isBlank(directoryPath)) {
            log.info("agent.ingest.source-dir is blank — nothing to ingest");
            return summary;
        }

        Path dir = Path.of(directoryPath);
        if (!Files.isDirectory(dir)) {
            log.warn("Configured source-dir is not a directory: {}", directoryPath);
            summary.add(IngestOutcome.failed(directoryPath, "not a directory or does not exist"));
            return summary;
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(Path::toString))
                .forEach(files::add);
        } catch (IOException e) {
            log.error("Failed to walk directory {} — {}", directoryPath, e.getMessage(), e);
            summary.add(IngestOutcome.failed(directoryPath, "directory walk failed: " + e.getMessage()));
            return summary;
        }

        log.info("Directory ingest — {} file(s) found under {}", files.size(), directoryPath);

        for (Path file : files) {
            String source = file.toAbsolutePath().toString();
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                summary.add(IngestOutcome.failed(source, "read failed: " + e.getMessage()));
                continue;
            }
            if (bytes.length == 0) {
                summary.add(IngestOutcome.skipped(source, "empty file"));
                continue;
            }
            String contentType = probeContentType(file);
            String fileName    = file.getFileName().toString();
            summary.add(indexBytes(source, fileName, contentType, bytes, applicationId));
        }

        return summary;
    }

    // ── URL ingestion ──────────────────────────────────────────────────────

    /**
     * Fetches the configured source document and indexes it if not already processed.
     */
    public IngestOutcome indexFromSource() {
        String sourceUrl = config.getSourceUrl();
        if (isBlank(sourceUrl)) {
            return IngestOutcome.skipped(null, "agent.ingest.source-url is not configured");
        }
        return indexFromSource(sourceUrl, config.getApplicationId());
    }

    /**
     * Fetches a document from an explicit URL and indexes it if not already processed.
     */
    public IngestOutcome indexFromSource(String sourceUrl, String applicationId) {
        if (isBlank(sourceUrl)) {
            return IngestOutcome.skipped(sourceUrl, "source URL is blank");
        }

        log.info("Fetching document from source endpoint — {}", sourceUrl);

        byte[]  bytes;
        String  contentType;
        String  fileName;
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(URI.create(sourceUrl))
                    .retrieve()
                    .toEntity(byte[].class);

            bytes = response.getBody();
            if (bytes == null || bytes.length == 0) {
                return IngestOutcome.skipped(sourceUrl, "source returned an empty body");
            }
            MediaType mt = response.getHeaders().getContentType();
            contentType  = mt != null ? mt.toString() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
            fileName     = deriveFileName(sourceUrl, response);
        } catch (Exception e) {
            return IngestOutcome.failed(sourceUrl, "fetch failed: " + e.getMessage());
        }

        return indexBytes(sourceUrl, fileName, contentType, bytes, applicationId);
    }

    // ── Shared indexing (dedup + route + record) ─────────────────────────────

    /**
     * Indexes a single document's bytes with de-duplication and status tracking.
     *
     * @param source        identifier used for de-dup (URL or absolute file path)
     * @param fileName       original file name (for extraction hints / display)
     * @param contentType    MIME type (best-effort)
     * @param bytes          the document bytes
     * @param applicationId  session/grouping key
     */
    private IngestOutcome indexBytes(String source, String fileName, String contentType,
                                     byte[] bytes, String applicationId) {
        String contentHash = sha256(bytes);

        if (registry.isAlreadyIndexed(applicationId, source, contentHash)) {
            log.info("Already indexed for applicationId={} (unchanged content) — skipping. source={} hash={}",
                    applicationId, source, contentHash);
            return IngestOutcome.skipped(source,
                    "already indexed for applicationId=" + applicationId + " (content unchanged)");
        }

        InMemoryMultipartFile file =
                new InMemoryMultipartFile("file", fileName, contentType, bytes);
        String documentId = null;
        try {
            IngestResult result;
            if (simpleIngestor != null) {
                result = simpleIngestor.ingest(file, applicationId);
            } else if (vectorIngestor != null) {
                result = vectorIngestor.ingest(file, applicationId);
            } else {
                return IngestOutcome.failed(source, "no document ingestor is available");
            }
            documentId = result.documentId();

            // The ingestor already registered it as INDEXED; enrich with source + hash.
            registry.register(result, source, contentHash);

            log.info("Indexed document — source={} documentId={} chunks={}",
                    source, documentId, result.chunkCount());
            return IngestOutcome.indexed(source, documentId);
        } catch (Exception e) {
            String docId = documentId != null ? documentId : UUID.randomUUID().toString();
            registry.recordFailure(docId, applicationId, source, fileName, contentHash,
                    e.getMessage());
            return IngestOutcome.failed(source, "indexing failed: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String probeContentType(Path file) {
        try {
            String ct = Files.probeContentType(file);
            if (ct != null) return ct;
        } catch (IOException ignored) { /* fall through */ }
        String ct = URLConnection.guessContentTypeFromName(file.getFileName().toString());
        return ct != null ? ct : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static String deriveFileName(String sourceUrl, ResponseEntity<byte[]> response) {
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        if (disposition != null && disposition.contains("filename=")) {
            String fn = disposition.substring(disposition.indexOf("filename=") + 9)
                    .replace("\"", "").trim();
            if (!fn.isBlank()) return fn;
        }
        try {
            String path = URI.create(sourceUrl).getPath();
            if (path != null && path.contains("/")) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank()) return last;
            }
        } catch (Exception ignored) { /* fall through */ }
        return "source-document";
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is always available; fall back to a length-based marker if not.
            return "len-" + bytes.length;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Result of an ingest attempt for a single source. */
    public record IngestOutcome(String status, String source, String documentId, String detail) {
        public static IngestOutcome indexed(String source, String docId) {
            return new IngestOutcome("INDEXED", source, docId, "indexed successfully");
        }
        public static IngestOutcome skipped(String source, String detail) {
            return new IngestOutcome("SKIPPED", source, null, detail);
        }
        public static IngestOutcome failed(String source, String detail) {
            return new IngestOutcome("FAILED", source, null, detail);
        }

        @Override
        public String toString() {
            return status + " source=" + source
                    + (documentId != null ? " documentId=" + documentId : "")
                    + " (" + detail + ")";
        }
    }

    /** Aggregated result of ingesting a whole directory. */
    public static final class DirectoryIngestSummary {
        private final String              directory;
        private final List<IngestOutcome> outcomes = new ArrayList<>();
        private int indexed;
        private int skipped;
        private int failed;

        public DirectoryIngestSummary(String directory) {
            this.directory = directory;
        }

        public void add(IngestOutcome outcome) {
            outcomes.add(outcome);
            switch (outcome.status()) {
                case "INDEXED" -> indexed++;
                case "SKIPPED" -> skipped++;
                case "FAILED"  -> failed++;
                default        -> { /* no-op */ }
            }
        }

        public String              getDirectory() { return directory; }
        public int                 getTotal()     { return outcomes.size(); }
        public int                 getIndexed()   { return indexed; }
        public int                 getSkipped()   { return skipped; }
        public int                 getFailed()    { return failed; }
        public List<IngestOutcome> getOutcomes()  { return outcomes; }

        @Override
        public String toString() {
            return "directory=" + directory + " total=" + getTotal()
                    + " indexed=" + indexed + " skipped=" + skipped + " failed=" + failed;
        }
    }
}

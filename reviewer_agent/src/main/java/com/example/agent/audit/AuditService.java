package com.example.agent.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Writes structured audit events to the dedicated "AUDIT" logger, which is
 * routed to logs/audit.log (see log4j2-spring.xml).
 *
 * <p>Audit lines are emitted as space-separated {@code key=value} pairs so they
 * are both human-readable and machine-parseable for compliance tooling, e.g.:
 *
 * <pre>
 * event=DOCUMENT_UPLOAD outcome=SUCCESS applicationId="APP-001" fileName="grant.pdf" documentId=... sizeBytes=12345
 * </pre>
 *
 * The audit trail records WHO did WHAT to WHICH resource and the OUTCOME —
 * never document contents or secrets.
 */
@Service
public class AuditService {

    private static final Logger AUDIT = LogManager.getLogger("AUDIT");

    // ── Document lifecycle ───────────────────────────────────────────────────

    public void documentUploaded(String applicationId, String fileName,
                                 String documentId, long sizeBytes, boolean success) {
        log("DOCUMENT_UPLOAD", success ? "SUCCESS" : "FAILURE",
                "applicationId", applicationId,
                "fileName",      fileName,
                "documentId",    documentId,
                "sizeBytes",     sizeBytes);
    }

    public void directoryIngested(String applicationId, String directory,
                                  int total, int indexed, int skipped, int failed) {
        log("DIRECTORY_INGEST", failed == 0 ? "SUCCESS" : "PARTIAL",
                "applicationId", applicationId,
                "directory",     directory,
                "total",         total,
                "indexed",       indexed,
                "skipped",       skipped,
                "failed",        failed);
    }

    public void sourceIngested(String applicationId, String source,
                               String status, String documentId) {
        log("SOURCE_INGEST", status,
                "applicationId", applicationId,
                "source",        source,
                "documentId",    documentId);
    }

    // ── Chat / context ─────────────────────────────────────────────────────

    public void chatRequest(String userId, String sessionId,
                            String applicationId, int messageLength) {
        log("CHAT_REQUEST", "RECEIVED",
                "userId",        userId,
                "sessionId",     sessionId,
                "applicationId", applicationId,
                "messageLength", messageLength);
    }

    public void contextCleared(String sessionId) {
        log("CONTEXT_CLEARED", "SUCCESS", "sessionId", sessionId);
    }

    // ── Generic escape hatch ─────────────────────────────────────────────────

    /** Log an arbitrary audit event with alternating key/value varargs. */
    public void event(String eventName, String outcome, Object... keyValues) {
        log(eventName, outcome, keyValues);
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private void log(String eventName, String outcome, Object... keyValues) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("event=").append(eventName)
          .append(" outcome=").append(outcome);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object val = keyValues[i + 1];
            sb.append(' ').append(key).append('=').append(format(val));
        }
        AUDIT.info(sb.toString());
    }

    /** Quote strings (so values with spaces stay one token); leave numbers bare. */
    private static String format(Object value) {
        if (value == null) return "\"\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        String s = value.toString().replace("\"", "'");
        return "\"" + s + "\"";
    }
}

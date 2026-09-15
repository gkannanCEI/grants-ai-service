package com.example.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.agent.core.AgentTool;
import com.example.agent.exception.ToolExecutionException;
import com.google.adk.tools.Annotations.Schema;

/**
 * Tool: validateDocument — inactive until agent.scoring.enabled=true
 */
@Component
@ConditionalOnProperty(prefix = "agent.scoring", name = "enabled", havingValue = "true")
public class ValidationTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ValidationTool.class);

    // Example injected dependency:
    // private final ValidationRuleEngine ruleEngine;
    // public ValidationTool(ValidationRuleEngine ruleEngine) { this.ruleEngine = ruleEngine; }

    @Schema(description = "Validate extracted document data against business rules and return pass/fail with details")
    public Map<String, Object> validateDocument(
            @Schema(name = "documentId",    description = "Unique identifier of the document being validated")
            String documentId,

            @Schema(name = "extractedText", description = "The raw text extracted from the document")
            String extractedText,

            @Schema(name = "pageCount",     description = "Number of pages in the document")
            int pageCount
    ) {
        log.info("validateDocument — documentId={} pageCount={}", documentId, pageCount);

        if (documentId == null || documentId.isBlank()) {
            throw new ToolExecutionException("validateDocument", "documentId must not be blank");
        }

        try {
            List<String> errors   = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // ── Structural checks ──
            if (extractedText == null || extractedText.isBlank()) {
                errors.add("extractedText is empty — document may not have been read correctly");
            }
            if (pageCount <= 0) {
                errors.add("pageCount must be greater than 0");
            } else if (pageCount > 500) {
                warnings.add("Document exceeds 500 pages — review may take longer than usual");
            }

            // ── TODO: Domain-specific rules ──
            //   - Required section checks  (title, date, signature block)
            //   - Regex field validation   (IDs, amounts, reference numbers)
            //   - Cross-field consistency
            //   - Schema validation against a known template
            if (extractedText != null && !extractedText.toLowerCase().contains("signature")) {
                warnings.add("No signature section detected in the document");
            }

            return Map.of(
                    "documentId", documentId,
                    "passed",     errors.isEmpty(),
                    "errors",     String.join("; ", errors),
                    "warnings",   String.join("; ", warnings)
            );
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("validateDocument", "Validation failed for: " + documentId, e);
        }
    }
}

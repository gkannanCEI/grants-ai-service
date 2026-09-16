package com.example.agent.tools;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.agent.core.AgentTool;
import com.example.agent.exception.ToolExecutionException;
import com.example.agent.scoring.ScoringEngineClient;
import com.example.agent.scoring.ScoringEngineResponse;
import com.google.adk.tools.Annotations.Schema;

/**
 * Tool: scoreDocument — inactive until agent.scoring.enabled=true
 */
@Component
@ConditionalOnProperty(prefix = "agent.scoring", name = "enabled", havingValue = "true")
public class ScoringTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ScoringTool.class);

    private final ScoringEngineClient scoringEngineClient;

    public ScoringTool(ScoringEngineClient scoringEngineClient) {
        this.scoringEngineClient = scoringEngineClient;
    }

    /**
     * Scores an application by calling the external scoring engine.
     *
     * @param applicationId The application identifier to score
     * @return Map with keys: applicationId, score, grade, rationale, and optionally details
     */
    @Schema(description = "Score an application by calling the external scoring engine service. The applicationId is the business/application identifier provided by the user (e.g. APP-001) — it is NOT the document UUID from the upload step. Returns a numeric score, grade, and rationale.")
    public Map<String, Object> scoreDocument(
            @Schema(name = "applicationId",
                    description = "The business application identifier (e.g. APP-001) provided by the user — NOT the document UUID from the upload")
            String applicationId
    ) {
        log.info("scoreDocument — calling scoring engine for applicationId={}", applicationId);

        if (applicationId == null || applicationId.isBlank()) {
            throw new ToolExecutionException("scoreDocument", "applicationId must not be blank");
        }

        ScoringEngineResponse response = scoringEngineClient.score(applicationId);

        log.info("scoreDocument — score={} grade={} for applicationId={}",
                response.score(), response.grade(), applicationId);

        Map<String, Object> result = new HashMap<>();
        result.put("applicationId", response.applicationId());
        result.put("score",         response.score());
        result.put("grade",         response.grade());
        result.put("rationale",     response.rationale());
        if (response.details() != null) {
            result.put("details", response.details());
        }
        return result;
    }
}

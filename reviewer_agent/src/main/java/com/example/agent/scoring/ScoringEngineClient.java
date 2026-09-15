package com.example.agent.scoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.agent.config.AgentProperties;
import com.example.agent.exception.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for the external scoring engine — inactive until agent.scoring.enabled=true
 */
@Component
@ConditionalOnProperty(prefix = "agent.scoring", name = "enabled", havingValue = "true")
public class ScoringEngineClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringEngineClient.class);

    private final AgentProperties.Scoring config;
    private final ObjectMapper            mapper;
    private HttpClient                    httpClient;

    public ScoringEngineClient(AgentProperties props) {
        this.config = props.getScoring();
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
        log.info("ScoringEngineClient initialized - url={} timeout={}s retries={}",
                config.getUrl(), config.getTimeoutSeconds(), config.getRetryAttempts());
    }

    /**
     * Calls the external scoring engine with the given applicationId.
     *
     * @param applicationId The application identifier to score
     * @return Parsed response from the scoring engine
     */
    public ScoringEngineResponse score(String applicationId) {
        int maxAttempts = Math.max(1, config.getRetryAttempts() + 1);
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doScore(applicationId, attempt);
            } catch (ToolExecutionException e) {
                if (e.getMessage().contains("HTTP 4")) throw e; // no retry on 4xx
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("Scoring attempt {}/{} failed - retrying. Reason: {}",
                            attempt, maxAttempts, e.getMessage());
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("Scoring attempt {}/{} failed - retrying. Reason: {}",
                            attempt, maxAttempts, e.getMessage());
                }
            }
        }

        throw new ToolExecutionException("scoreDocument",
                "Scoring engine unreachable after " + maxAttempts + " attempts: "
                + (lastException != null ? lastException.getMessage() : "unknown error"),
                lastException);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private ScoringEngineResponse doScore(String applicationId, int attempt) throws Exception {
        String requestBody = mapper.writeValueAsString(
                Map.of("applicationId", applicationId)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        log.info("Calling scoring engine — applicationId={} url={} attempt={}",
                applicationId, config.getUrl(), attempt);

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );

        int statusCode = response.statusCode();
        log.info("Scoring engine responded — applicationId={} status={}", applicationId, statusCode);

        if (statusCode >= 400) {
            throw new ToolExecutionException("scoreDocument",
                    "Scoring engine returned HTTP " + statusCode
                    + " for applicationId=" + applicationId
                    + ". Response: " + truncate(response.body(), 300));
        }

        return parseResponse(response.body(), applicationId);
    }

    private ScoringEngineResponse parseResponse(String body, String applicationId) {
        try {
            JsonNode root      = mapper.readTree(body);
            double   score     = root.path("score").asDouble(0.0);
            String   grade     = root.path("grade").asText("").isBlank()
                                 ? deriveGrade(score)
                                 : root.path("grade").asText();
            String   rationale = root.path("rationale").asText("");
            JsonNode detailsNode = root.path("details");
            String   details   = detailsNode.isMissingNode() ? null
                                 : mapper.writeValueAsString(detailsNode);

            return new ScoringEngineResponse(applicationId, score, grade, rationale, details);
        } catch (Exception e) {
            throw new ToolExecutionException("scoreDocument",
                    "Failed to parse scoring engine response: " + e.getMessage()
                    + ". Body: " + truncate(body, 200), e);
        }
    }

    private static String deriveGrade(double score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

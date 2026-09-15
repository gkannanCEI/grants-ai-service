package com.example.agent.scoring;

/**
 * Parsed response from the external scoring engine.
 *
 * @param applicationId  The application that was scored
 * @param score          Numeric score returned by the engine
 * @param grade          Letter grade — derived from score if not provided by the engine
 * @param rationale      Explanation of the score
 * @param details        Raw JSON string of any extra fields returned by the engine (nullable)
 */
public record ScoringEngineResponse(
        String applicationId,
        double score,
        String grade,
        String rationale,
        String details
) {}

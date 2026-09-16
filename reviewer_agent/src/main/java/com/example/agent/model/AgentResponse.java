package com.example.agent.model;

import java.time.Instant;

/**
 * Generic agent response returned by {@link com.example.agent.core.AgentRunner}.
 *
 * @param sessionId      Session used — return to the client for follow-up turns
 * @param response       Agent's final text response
 * @param durationMillis Time taken to produce the response
 * @param timestamp      Server-side response time
 */
public record AgentResponse(
        String  sessionId,
        String  response,
        long    durationMillis,
        Instant timestamp
) {
    public static AgentResponse of(String sessionId, String response, long durationMillis) {
        return new AgentResponse(sessionId, response, durationMillis, Instant.now());
    }
}

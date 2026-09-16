package com.example.agent.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Generic agent request used by {@link com.example.agent.core.AgentRunner}.
 *
 * @param userId    Caller's user identifier (required)
 * @param sessionId Optional — pass a prior sessionId to continue a multi-turn conversation
 * @param message   The user's message or instruction (required)
 */
public record AgentRequest(

        @NotBlank(message = "userId must not be blank")
        String userId,

        String sessionId,

        @NotBlank(message = "message must not be blank")
        String message
) {
    /** Convenience factory for stateless single-turn calls. */
    public static AgentRequest of(String userId, String message) {
        return new AgentRequest(userId, null, message);
    }
}

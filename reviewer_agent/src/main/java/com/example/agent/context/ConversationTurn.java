package com.example.agent.context;

import java.time.Instant;

/**
 * A single exchange in a conversation — one user message and the agent's reply.
 *
 * @param userMessage   What the user said
 * @param agentResponse What the agent replied
 * @param timestamp     When this turn occurred
 */
public record ConversationTurn(
        String  userMessage,
        String  agentResponse,
        Instant timestamp
) {
    public static ConversationTurn of(String userMessage, String agentResponse) {
        return new ConversationTurn(userMessage, agentResponse, Instant.now());
    }
}

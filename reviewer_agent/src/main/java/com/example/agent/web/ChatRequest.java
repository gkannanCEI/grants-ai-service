package com.example.agent.web;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP request body for POST /api/chat.
 *
 * @param userId    Caller's user ID (required)
 * @param sessionId Pass a prior sessionId for multi-turn conversations (optional)
 * @param message   The message to send to the agent (required)
 */
public record ChatRequest(

        @NotBlank(message = "userId must not be blank")
        String userId,

        String sessionId,

        @NotBlank(message = "message must not be blank")
        String message
) {}

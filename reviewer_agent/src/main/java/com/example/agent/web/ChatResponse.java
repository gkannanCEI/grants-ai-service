package com.example.agent.web;

import java.time.Instant;

/**
 * HTTP response body for POST /api/chat.
 *
 * Return {@code sessionId} to the client — pass it back in the next request
 * to continue the same conversation.
 */
public record ChatResponse(
        String  sessionId,
        String  response,
        long    durationMillis,
        Instant timestamp
) {}

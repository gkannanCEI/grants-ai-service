package com.example.agent.core;

import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Manages ADK session lifecycle.
 *
 * Centralises session create/reuse so all runners go through one place.
 * Replace with a Redis-backed or database-backed implementation for
 * persistent multi-turn sessions across restarts.
 */
@Component
public class AgentSessionManager {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionManager.class);

    /**
     * Returns an existing session if {@code sessionId} is non-blank and found,
     * otherwise creates a fresh session for the given user.
     */
    public Session resolveSession(InMemoryRunner runner, String userId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            Session existing = runner.sessionService()
                    .getSession(runner.appName(), userId, sessionId, Optional.empty())
                    .blockingGet();

            if (existing != null) {
                log.debug("Reusing session sessionId={} userId={}", sessionId, userId);
                return existing;
            }
            log.warn("Session not found — sessionId={}, creating new for userId={}", sessionId, userId);
        }

        Session fresh = runner.sessionService()
                .createSession(runner.appName(), userId)
                .blockingGet();

        log.info("New session created — sessionId={} userId={}", fresh.id(), userId);
        return fresh;
    }
}

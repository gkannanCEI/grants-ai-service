package com.example.agent.context;

import com.example.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-session conversation context.
 *
 * Responsibilities:
 *   - Creates and stores a {@link SessionContext} for each session
 *   - Records each completed turn (user message + agent response)
 *   - Produces a formatted history string to inject into the next prompt
 *   - Enforces the configured max-turns window (oldest turns evicted first)
 *
 * Storage: in-memory ConcurrentHashMap — replace with a Redis or DB
 * implementation for persistence across restarts.
 *
 * Config:
 *   agent.context.max-turns=20
 *   agent.context.include-system-summary=true
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final int     maxTurns;
    private final boolean includeSystemSummary;

    /** sessionId → SessionContext */
    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

    public ContextManager(AgentProperties props) {
        this.maxTurns             = props.getContext().getMaxTurns();
        this.includeSystemSummary = props.getContext().isIncludeSystemSummary();
        log.info("ContextManager initialized — maxTurns={} summary={}", maxTurns, includeSystemSummary);
    }

    /**
     * Builds the context-enriched message to send to the agent.
     *
     * If prior history exists for this session, it is prepended so the LLM
     * has full context of what was discussed. On the first turn, the raw
     * message is returned unchanged.
     *
     * @param sessionId  Session identifier
     * @param userMessage The current user message
     * @return The message with conversation history prepended (if any)
     */
    public String buildContextualMessage(String sessionId, String userMessage) {
        SessionContext ctx = sessions.get(sessionId);

        if (ctx == null || ctx.size() == 0) {
            return userMessage;
        }

        String history = ctx.formatForPrompt();
        String enriched = history + "Current request: " + userMessage;

        log.debug("Context injected for sessionId={} turns={}", sessionId, ctx.size());
        return enriched;
    }

    /**
     * Records a completed turn into the session's history.
     * Creates the session context automatically if it doesn't exist yet.
     *
     * @param sessionId     The session this turn belongs to
     * @param userMessage   The message the user sent
     * @param agentResponse The agent's final response
     */
    public void recordTurn(String sessionId, String userMessage, String agentResponse) {
        SessionContext ctx = sessions.computeIfAbsent(
                sessionId,
                id -> new SessionContext(id, maxTurns)
        );
        ctx.addTurn(userMessage, agentResponse);
        log.debug("Recorded turn — sessionId={} totalTurns={}", sessionId, ctx.size());
    }

    /**
     * Returns the full turn history for a session (read-only).
     */
    public List<ConversationTurn> getHistory(String sessionId) {
        SessionContext ctx = sessions.get(sessionId);
        return ctx == null ? List.of() : ctx.getTurns();
    }

    /**
     * Clears the conversation history for a session.
     * Useful when the user explicitly wants to start fresh.
     */
    public void clearSession(String sessionId) {
        SessionContext ctx = sessions.remove(sessionId);
        if (ctx != null) {
            log.info("Cleared context for sessionId={}", sessionId);
        }
    }

    /**
     * Returns the number of active sessions currently tracked.
     */
    public int activeSessions() {
        return sessions.size();
    }
}

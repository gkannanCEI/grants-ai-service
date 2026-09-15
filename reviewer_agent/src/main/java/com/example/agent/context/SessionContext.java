package com.example.agent.context;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Holds the conversation history for one session.
 *
 * Internally uses a bounded deque — oldest turns are dropped when
 * the window exceeds {@code maxTurns}.
 *
 * Thread-safe: synchronized on the deque for concurrent access.
 */
public class SessionContext {

    private final String              sessionId;
    private final int                 maxTurns;
    private final Deque<ConversationTurn> history;

    public SessionContext(String sessionId, int maxTurns) {
        this.sessionId = sessionId;
        this.maxTurns  = maxTurns;
        this.history   = new ArrayDeque<>();
    }

    /**
     * Appends a completed turn and evicts the oldest if over the window limit.
     */
    public synchronized void addTurn(String userMessage, String agentResponse) {
        history.addLast(ConversationTurn.of(userMessage, agentResponse));
        while (history.size() > maxTurns) {
            history.pollFirst();
        }
    }

    /**
     * Returns all turns in chronological order (oldest first).
     */
    public synchronized List<ConversationTurn> getTurns() {
        return Collections.unmodifiableList(List.copyOf(history));
    }

    /**
     * Formats the conversation history as a plain-text block suitable for
     * injection into a system prompt or user message prefix.
     *
     * Example output:
     *   [Turn 1]
     *   User: Review DOC-001
     *   Agent: I read DOC-001. Score is 85/100 (B)...
     *
     *   [Turn 2]
     *   User: What were the validation warnings?
     *   Agent: There were two warnings: ...
     */
    public synchronized String formatForPrompt() {
        if (history.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Conversation History ===\n");
        int i = 1;
        for (ConversationTurn turn : history) {
            sb.append("[Turn ").append(i++).append("]\n");
            sb.append("User: ").append(turn.userMessage()).append("\n");
            sb.append("Agent: ").append(turn.agentResponse()).append("\n\n");
        }
        sb.append("===========================\n\n");
        return sb.toString();
    }

    public String getSessionId() { return sessionId; }
    public int size()            { return history.size(); }
    public void clear()          { history.clear(); }
}

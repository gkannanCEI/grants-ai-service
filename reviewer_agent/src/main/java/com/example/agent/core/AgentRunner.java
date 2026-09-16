package com.example.agent.core;

import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;

/**
 * Core abstraction for running an agent turn.
 *
 * The REST layer and any other entry point depend on this interface only.
 * Swap {@link BaseAgentRunner} for a different execution strategy
 * (distributed, persistent sessions, streaming) by providing an
 * alternative {@code @Primary} implementation.
 */
public interface AgentRunner {

    /**
     * Executes one agent turn and returns the final response.
     *
     * @param request contains userId, optional sessionId, and the user message
     * @return the agent's response with session and timing metadata
     */
    AgentResponse run(AgentRequest request);
}

package com.example.agent.core;

import com.example.agent.context.ContextManager;
import com.example.agent.exception.AgentException;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link AgentRunner} backed by Google ADK's {@link InMemoryRunner}.
 *
 * On each turn:
 *   1. Resolves or creates an ADK session via {@link AgentSessionManager}
 *   2. Enriches the user message with conversation history via {@link ContextManager}
 *   3. Runs the agent and collects the final response
 *   4. Records the completed turn back into {@link ContextManager}
 *
 * To use a different execution backend, implement {@link AgentRunner},
 * annotate with {@code @Component @Primary}, and this bean steps aside.
 */
@Component
public class BaseAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(BaseAgentRunner.class);

    private final InMemoryRunner      runner;
    private final AgentSessionManager sessionManager;
    private final ContextManager      contextManager;

    public BaseAgentRunner(
            BaseAgent            rootAgent,
            AgentSessionManager  sessionManager,
            ContextManager       contextManager
    ) {
        this.runner         = new InMemoryRunner(rootAgent);
        this.sessionManager = sessionManager;
        this.contextManager = contextManager;
        log.info("BaseAgentRunner ready — agent={}", rootAgent.name());
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        long start = System.currentTimeMillis();

        // 1. Resolve or create ADK session
        Session session = sessionManager.resolveSession(
                runner, request.userId(), request.sessionId()
        );
        String sessionId = session.id();
        log.info("Agent turn — userId={} sessionId={}", request.userId(), sessionId);

        // 2. Enrich message with conversation history
        String contextualMessage = contextManager.buildContextualMessage(
                sessionId, request.message()
        );

        try {
            // 3. Run agent
            Content userContent = Content.fromParts(Part.fromText(contextualMessage));
            StringBuilder responseText = new StringBuilder();

            runner.runAsync(
                    session.userId(),
                    sessionId,
                    userContent,
                    RunConfig.builder().build()
            ).blockingForEach(event -> {
                if (event.finalResponse()) {
                    responseText.append(event.stringifyContent());
                }
            });

            String finalResponse = responseText.toString();
            long   duration      = System.currentTimeMillis() - start;

            // 4. Record turn into context
            contextManager.recordTurn(sessionId, request.message(), finalResponse);

            log.info("Agent turn complete — sessionId={} duration={}ms turns={}",
                    sessionId, duration, contextManager.getHistory(sessionId).size());

            return AgentResponse.of(sessionId, finalResponse, duration);

        } catch (Exception e) {
            log.error("Agent turn failed — sessionId={}", sessionId, e);
            throw new AgentException("Agent execution failed: " + e.getMessage(), e);
        }
    }
}

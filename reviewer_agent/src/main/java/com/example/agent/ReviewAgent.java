package com.example.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.agent.config.AgentProperties;
import com.example.agent.config.LlmModelFactory;
import com.example.agent.tools.DocumentReaderTool;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;

/**
 * Domain agent wiring — Document Review Agent.
 *
 * ── How to build a new agent on this base ──
 *   1. Swap or add tools: create a @Component in com.example.agent.tools that implements AgentTool
 *   2. Inject it here and add FunctionTool.create(yourTool, "methodName") below
 *   3. Update agent.* in application.properties (name, instruction, model, provider)
 *
 * Everything else (running, session management, REST, error handling, vector store,
 * embeddings, multi-model) is handled by the base layer automatically.
 */
@Configuration
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    private final AgentProperties   props;
    private final LlmModelFactory    modelFactory;
    private final DocumentReaderTool documentReaderTool;

    public ReviewAgent(
            AgentProperties              props,
            LlmModelFactory              modelFactory,
            DocumentReaderTool           documentReaderTool
    ) {
        this.props              = props;
        this.modelFactory       = modelFactory;
        this.documentReaderTool = documentReaderTool;
    }

    /**
     * Builds and exposes the ADK BaseAgent as a Spring bean.
     * Consumed by {@link com.example.agent.core.BaseAgentRunner}.
     */
    @Bean
    public BaseAgent rootAgent() {
        String modelString = modelFactory.resolveModelString();
        log.info("Building ReviewAgent — model={}", modelString);

        BaseTool readDocumentTool      = FunctionTool.create(documentReaderTool, "readDocument");
        BaseTool readByApplicationTool = FunctionTool.create(documentReaderTool, "readDocumentByApplicationId");

        return LlmAgent.builder()
                .name(props.getName())
                .description(props.getDescription())
                .instruction(props.getInstruction())
                .model(modelString)
                .tools(readDocumentTool, readByApplicationTool)
                .build();
    }
}

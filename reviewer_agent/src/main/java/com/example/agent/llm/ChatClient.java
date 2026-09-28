package com.example.agent.llm;

/**
 * Minimal single-turn chat abstraction that talks to the configured LLM provider
 * directly over its REST API.
 *
 * <p>This exists because ADK for Java (1.6.0) only natively supports Gemini and
 * Claude — a plain {@code ollama/...} model string is rejected by ADK's
 * {@code LlmRegistry} with "Unsupported model". For simple prompt → text calls
 * (e.g. reservation summarization) this client bypasses ADK entirely and works
 * across providers, selected by the single {@code agent.provider.type} switch.
 */
public interface ChatClient {

    /**
     * Sends a system instruction + user prompt to the LLM and returns the text reply.
     *
     * @param systemInstruction guidance for the model (may be null/blank)
     * @param userPrompt        the user content to act on
     * @return the model's text response (never null; may be empty)
     */
    String complete(String systemInstruction, String userPrompt);
}

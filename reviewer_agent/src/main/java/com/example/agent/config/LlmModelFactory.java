package com.example.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the ADK/LiteLLM model string and configures the runtime environment
 * for the chosen provider — all from a single {@code agent.model} property.
 *
 * ── How the provider is detected ────────────────────────────────────────────
 * The model string itself encodes the provider via its prefix:
 *
 *   "gemini-2.0-flash"              → Google Gemini   (no prefix)
 *   "ollama/llama3"                 → Ollama           (ollama/ prefix)
 *   "openai/gpt-4o"                 → OpenAI           (openai/ prefix)
 *   "anthropic/claude-3-5-sonnet"   → Anthropic        (anthropic/ prefix)
 *
 * ── What this class does per provider ───────────────────────────────────────
 * Gemini   : nothing extra — ADK reads GOOGLE_API_KEY from env automatically.
 * Ollama   : sets OLLAMA_API_BASE system property (default http://localhost:11434).
 * OpenAI   : sets OPENAI_API_BASE + OPENAI_API_KEY system properties.
 * Anthropic: sets ANTHROPIC_API_KEY system property.
 *
 * All base URLs and API keys can be overridden via:
 *   agent.provider.base-url=...
 *   agent.provider.api-key=...
 */
@Component
public class LlmModelFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmModelFactory.class);

    private static final String OLLAMA_DEFAULT_BASE    = "http://localhost:11434";
    private static final String OPENAI_DEFAULT_BASE    = "https://api.openai.com/v1";
    private static final String ANTHROPIC_DEFAULT_BASE = "https://api.anthropic.com";

    // Per-provider default chat models — used when agent.model is left blank.
    private static final String GEMINI_DEFAULT_MODEL    = "gemini-3.6-flash";
    private static final String OLLAMA_DEFAULT_MODEL    = "ollama/gemma3:1b";
    private static final String OPENAI_DEFAULT_MODEL    = "openai/gpt-4o";
    private static final String ANTHROPIC_DEFAULT_MODEL = "anthropic/claude-3-5-sonnet";

    private final AgentProperties props;

    public LlmModelFactory(AgentProperties props) {
        this.props = props;
    }

    /**
     * Returns the ADK-compatible model string and configures the runtime
     * environment for the detected provider.
     *
     * Call once at startup (from {@code ReviewAgent#rootAgent()}).
     */
    public String resolveModelString() {
        String baseUrl = props.getProvider().getBaseUrl();
        String apiKey  = props.getProvider().getApiKey();

        // Resolve the effective model. The one-word agent.provider.type wins:
        // when agent.model is blank, we substitute the selected provider's
        // default model so the whole stack switches from a single property.
        String model = resolveModel(props);

        if (model.startsWith("ollama/")) {
            String base = baseUrl.isBlank() ? OLLAMA_DEFAULT_BASE : baseUrl;
            setIfAbsent("OLLAMA_API_BASE", base);
            log.info("Provider=Ollama  model={}  base={}", model, base);

        } else if (model.startsWith("openai/")) {
            String base = baseUrl.isBlank() ? OPENAI_DEFAULT_BASE : baseUrl;
            setIfAbsent("OPENAI_API_BASE", base);
            if (!apiKey.isBlank()) setIfAbsent("OPENAI_API_KEY", apiKey);
            log.info("Provider=OpenAI  model={}  base={}", model, base);

        } else if (model.startsWith("anthropic/")) {
            String base = baseUrl.isBlank() ? ANTHROPIC_DEFAULT_BASE : baseUrl;
            setIfAbsent("ANTHROPIC_API_BASE", base);
            if (!apiKey.isBlank()) setIfAbsent("ANTHROPIC_API_KEY", apiKey);
            log.info("Provider=Anthropic  model={}  base={}", model, base);

        } else {
            // Gemini or any other ADK-native model — no extra setup needed
            log.info("Provider=Gemini  model={}", model);
        }

        return model;
    }

    // ── Provider resolution (shared with the embedding client) ────────────────

    /** Normalized provider name: gemini | ollama | openai | anthropic. */
    public static String resolveProvider(AgentProperties props) {
        String type = props.getProvider().getType();
        if (type != null && !type.isBlank()) {
            return switch (type.trim().toLowerCase()) {
                case "ollama"    -> "ollama";
                case "openai"    -> "openai";
                case "anthropic" -> "anthropic";
                default          -> "gemini";
            };
        }
        // Backwards-compatible fallback: infer from the agent.model prefix.
        String model = props.getModel();
        if (model != null) {
            if (model.startsWith("ollama/"))    return "ollama";
            if (model.startsWith("openai/"))    return "openai";
            if (model.startsWith("anthropic/")) return "anthropic";
        }
        return "gemini";
    }

    /**
     * The effective chat model string. Honors an explicit agent.model; otherwise
     * returns the default model for the selected provider.
     */
    public static String resolveModel(AgentProperties props) {
        String model = props.getModel();
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return switch (resolveProvider(props)) {
            case "ollama"    -> OLLAMA_DEFAULT_MODEL;
            case "openai"    -> OPENAI_DEFAULT_MODEL;
            case "anthropic" -> ANTHROPIC_DEFAULT_MODEL;
            default          -> GEMINI_DEFAULT_MODEL;
        };
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Sets the system property only if not already set by the OS environment.
     * This lets real env vars take precedence over application.properties values.
     */
    private void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null && System.getenv(key) == null) {
            System.setProperty(key, value);
            log.debug("Set system property {}={}", key, key.contains("KEY") ? "***" : value);
        }
    }
}

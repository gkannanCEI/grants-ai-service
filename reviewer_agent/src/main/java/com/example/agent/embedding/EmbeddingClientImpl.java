package com.example.agent.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.agent.config.AgentProperties;
import com.example.agent.exception.AgentException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * Unified embedding client — provider is detected from the agent.model prefix.
 *
 *   gemini-*   → Google Gemini REST API  (text-embedding-004, dim=768)
 *   ollama/*   → Local Ollama REST API   (nomic-embed-text,   dim=768)
 *   openai/*   → OpenAI REST API         (text-embedding-3-small, dim=1536)
 *
 * All providers are called via plain HTTP so there is no SDK version dependency.
 */
@Component
public class EmbeddingClientImpl implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClientImpl.class);

    private static final String GEMINI_EMBED_MODEL  = "gemini-embedding-001";
    private static final String OLLAMA_EMBED_MODEL  = "nomic-embed-text";
    private static final String OPENAI_EMBED_MODEL  = "text-embedding-3-small";

    private static final String GEMINI_DEFAULT_BASE = "https://generativelanguage.googleapis.com";
    private static final String OLLAMA_DEFAULT_BASE = "http://localhost:11434";
    private static final String OPENAI_DEFAULT_BASE = "https://api.openai.com/v1";

    private final AgentProperties props;
    private final ObjectMapper    mapper = new ObjectMapper();

    private String     detectedProvider;
    private String     embedModel;
    private String     providerBaseUrl;
    private HttpClient httpClient;

    public EmbeddingClientImpl(AgentProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        String model   = props.getModel();
        String baseUrl = props.getProvider().getBaseUrl();

        if (model != null && model.startsWith("ollama/")) {
            detectedProvider = "ollama";
            embedModel       = OLLAMA_EMBED_MODEL;
            providerBaseUrl  = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : OLLAMA_DEFAULT_BASE;
        } else if (model != null && (model.startsWith("openai/") || model.startsWith("anthropic/"))) {
            detectedProvider = "openai";
            embedModel       = OPENAI_EMBED_MODEL;
            providerBaseUrl  = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : OPENAI_DEFAULT_BASE;
        } else {
            // Default: Gemini
            detectedProvider = "gemini";
            embedModel       = GEMINI_EMBED_MODEL;
            providerBaseUrl  = GEMINI_DEFAULT_BASE;
        }

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("EmbeddingClientImpl ready — provider={} model={}", detectedProvider, embedModel);
    }

    @Override
    public List<Float> embed(String text) {
        return switch (detectedProvider) {
            case "gemini" -> embedWithGemini(text);
            case "ollama" -> embedWithOllama(text);
            default       -> embedWithOpenAi(text);
        };
    }

    // ── Gemini — REST API ────────────────────────────────────────────────────

    private List<Float> embedWithGemini(String text) {
        try {
            String apiKey = resolveApiKey("GOOGLE_API_KEY", "GEMINI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new AgentException("Gemini API key not found. Set GOOGLE_API_KEY/GEMINI_API_KEY environment variable or agent.provider.apiKey in properties.");
            }

            // POST https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent?key={apiKey}
            String url  = providerBaseUrl + "/v1beta/models/" + embedModel + ":embedContent?key=" + apiKey;
            String body = mapper.writeValueAsString(
                    Map.of("content", Map.of("parts", List.of(Map.of("text", text))))
            );

            HttpResponse<String> resp = post(url, body, null);
            if (resp.statusCode() != 200) {
                throw new AgentException("Gemini embedding HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 200));
            }

            // Response: { "embedding": { "values": [0.1, 0.2, ...] } }
            JsonNode values = mapper.readTree(resp.body()).path("embedding").path("values");
            return parseFloatArray(values);

        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("Gemini embedding failed: " + e.getMessage(), e);
        }
    }

    // ── Ollama — REST API ────────────────────────────────────────────────────

    private List<Float> embedWithOllama(String text) {
        try {
            String body = mapper.writeValueAsString(
                    Map.of("model", embedModel, "prompt", text)
            );
            HttpResponse<String> resp = post(providerBaseUrl + "/api/embeddings", body, null);
            if (resp.statusCode() != 200) {
                throw new AgentException("Ollama embedding HTTP " + resp.statusCode());
            }
            // Response: { "embedding": [0.1, 0.2, ...] }
            return parseFloatArray(mapper.readTree(resp.body()).path("embedding"));
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("Ollama embedding failed: " + e.getMessage(), e);
        }
    }

    // ── OpenAI-compatible — REST API ─────────────────────────────────────────

    private List<Float> embedWithOpenAi(String text) {
        try {
            String apiKey = resolveApiKey("OPENAI_API_KEY");
            String body   = mapper.writeValueAsString(
                    Map.of("model", embedModel, "input", text)
            );
            String authHeader = (apiKey != null && !apiKey.isBlank()) ? "Bearer " + apiKey : null;

            HttpResponse<String> resp = post(providerBaseUrl + "/embeddings", body, authHeader);
            if (resp.statusCode() != 200) {
                throw new AgentException("OpenAI embedding HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 200));
            }
            // Response: { "data": [ { "embedding": [0.1, 0.2, ...] } ] }
            JsonNode embNode = mapper.readTree(resp.body()).path("data").get(0).path("embedding");
            return parseFloatArray(embNode);
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("OpenAI embedding failed: " + e.getMessage(), e);
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────────
    private String resolveApiKey(String... envVars) {
        for (String envVar : envVars) {
            String fromEnv = System.getenv(envVar);
            if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        }
        String fromProp = props.getProvider().getApiKey();
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        return null;
    }

    private HttpResponse<String> post(String url, String body, String authHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<Float> parseFloatArray(JsonNode node) {
        List<Float> result = new ArrayList<>();
        if (node != null) {
            for (JsonNode v : node) result.add(v.floatValue());
        }
        return result;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
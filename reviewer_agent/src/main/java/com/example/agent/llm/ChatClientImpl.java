package com.example.agent.llm;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.agent.config.AgentProperties;
import com.example.agent.config.LlmModelFactory;
import com.example.agent.exception.AgentException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * Provider-agnostic chat client. The provider is selected by the single
 * {@code agent.provider.type} switch (falling back to the {@code agent.model}
 * prefix), exactly like {@link com.example.agent.embedding.EmbeddingClientImpl}.
 *
 *   gemini → POST {base}/v1beta/models/{model}:generateContent
 *   ollama → POST {base}/api/chat
 *   openai → POST {base}/chat/completions
 *
 * All providers are called over plain HTTP so there is no dependency on ADK's
 * limited native model support.
 */
@Component
public class ChatClientImpl implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClientImpl.class);

    private static final String GEMINI_DEFAULT_BASE = "https://generativelanguage.googleapis.com";
    private static final String OLLAMA_DEFAULT_BASE = "http://localhost:11434";
    private static final String OPENAI_DEFAULT_BASE = "https://api.openai.com/v1";

    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024; // 8 MB safety cap

    private final AgentProperties props;
    private final ObjectMapper    mapper = new ObjectMapper();

    private String     provider;
    private String     model;       // API model name (no provider prefix)
    private String     baseUrl;
    private HttpClient httpClient;

    public ChatClientImpl(AgentProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        provider = LlmModelFactory.resolveProvider(props);
        String configuredBase = props.getProvider().getBaseUrl();

        // Effective model, with any provider prefix stripped for the REST call.
        String resolved = LlmModelFactory.resolveModel(props);
        model = stripPrefix(resolved);

        baseUrl = switch (provider) {
            case "ollama" -> notBlank(configuredBase) ? configuredBase : OLLAMA_DEFAULT_BASE;
            case "openai" -> notBlank(configuredBase) ? configuredBase : OPENAI_DEFAULT_BASE;
            default       -> GEMINI_DEFAULT_BASE;
        };

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("ChatClientImpl ready — provider={} model={}", provider, model);
    }

    @Override
    public String complete(String systemInstruction, String userPrompt) {
        return switch (provider) {
            case "ollama" -> completeOllama(systemInstruction, userPrompt);
            case "openai" -> completeOpenAi(systemInstruction, userPrompt);
            default       -> completeGemini(systemInstruction, userPrompt);
        };
    }

    // ── Gemini — generateContent ───────────────────────────────────────────────

    private String completeGemini(String systemInstruction, String userPrompt) {
        try {
            String apiKey = resolveApiKey("GOOGLE_API_KEY", "GEMINI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new AgentException("Gemini API key not found. Set GOOGLE_API_KEY/GEMINI_API_KEY "
                        + "or agent.provider.api-key.");
            }

            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("contents", List.of(
                    Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
            if (notBlank(systemInstruction)) {
                payload.put("systemInstruction",
                        Map.of("parts", List.of(Map.of("text", systemInstruction))));
            }

            String url  = baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            String body = mapper.writeValueAsString(payload);

            HttpResponse<String> resp = post(url, body, null);
            if (resp.statusCode() != 200) {
                throw new AgentException("Gemini chat HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 300));
            }

            // { "candidates": [ { "content": { "parts": [ { "text": "..." } ] } } ] }
            JsonNode parts = mapper.readTree(resp.body())
                    .path("candidates").path(0).path("content").path("parts");
            return joinTextParts(parts);
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("Gemini chat failed: " + e.getMessage(), e);
        }
    }

    // ── Ollama — /api/chat ─────────────────────────────────────────────────────

    private String completeOllama(String systemInstruction, String userPrompt) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            if (notBlank(systemInstruction)) {
                messages.add(Map.of("role", "system", "content", systemInstruction));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", messages,
                    "stream", false));

            HttpResponse<String> resp = post(baseUrl + "/api/chat", body, null);
            if (resp.statusCode() != 200) {
                throw new AgentException("Ollama chat HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 300));
            }

            // { "message": { "role": "assistant", "content": "..." } }
            return mapper.readTree(resp.body()).path("message").path("content").asText("");
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("Ollama chat failed: " + e.getMessage(), e);
        }
    }

    // ── OpenAI-compatible — /chat/completions ──────────────────────────────────

    private String completeOpenAi(String systemInstruction, String userPrompt) {
        try {
            String apiKey = resolveApiKey("OPENAI_API_KEY");

            List<Map<String, String>> messages = new ArrayList<>();
            if (notBlank(systemInstruction)) {
                messages.add(Map.of("role", "system", "content", systemInstruction));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            String body = mapper.writeValueAsString(Map.of("model", model, "messages", messages));
            String authHeader = notBlank(apiKey) ? "Bearer " + apiKey : null;

            HttpResponse<String> resp = post(baseUrl + "/chat/completions", body, authHeader);
            if (resp.statusCode() != 200) {
                throw new AgentException("OpenAI chat HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body(), 300));
            }

            // { "choices": [ { "message": { "content": "..." } } ] }
            return mapper.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("");
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("OpenAI chat failed: " + e.getMessage(), e);
        }
    }

    // ── HTTP + helpers ──────────────────────────────────────────────────────────

    private HttpResponse<String> post(String url, String body, String authHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<InputStream> resp =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes = readBounded(resp.body(), MAX_RESPONSE_BYTES);
        String text  = new String(bytes, StandardCharsets.UTF_8);
        log.debug("Chat HTTP {} — read {} bytes from {}", resp.statusCode(), bytes.length, url);
        return new StringBodyResponse(resp, text);
    }

    private byte[] readBounded(InputStream in, long maxBytes) throws Exception {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new AgentException("Chat response exceeded " + maxBytes
                            + " bytes and was aborted to protect memory.");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String joinTextParts(JsonNode parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null && parts.isArray()) {
            for (JsonNode p : parts) sb.append(p.path("text").asText(""));
        }
        return sb.toString();
    }

    private String resolveApiKey(String... envVars) {
        for (String envVar : envVars) {
            String fromEnv = System.getenv(envVar);
            if (notBlank(fromEnv)) return fromEnv;
        }
        String fromProp = props.getProvider().getApiKey();
        return notBlank(fromProp) ? fromProp : null;
    }

    /** Strips a leading provider prefix (ollama/, openai/, anthropic/) from a model string. */
    private static String stripPrefix(String model) {
        if (model == null) return "";
        int slash = model.indexOf('/');
        return slash >= 0 ? model.substring(slash + 1) : model;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Minimal HttpResponse<String> adapter reusing the original response's status/headers. */
    private record StringBodyResponse(HttpResponse<InputStream> delegate, String bodyText)
            implements HttpResponse<String> {
        @Override public int statusCode()                 { return delegate.statusCode(); }
        @Override public HttpRequest request()            { return delegate.request(); }
        @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return delegate.headers(); }
        @Override public String body()                    { return bodyText; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return delegate.sslSession(); }
        @Override public URI uri()                        { return delegate.uri(); }
        @Override public HttpClient.Version version()     { return delegate.version(); }
    }
}

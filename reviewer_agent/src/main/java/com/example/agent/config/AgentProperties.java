package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds all "agent.*" entries from application.properties into typed fields.
 *
 * This class contains NO hardcoded defaults — every value must be explicitly
 * set in application.properties (or overridden by an environment variable /
 * Spring profile). That way application.properties is the single source of
 * truth and there is never any ambiguity about which value is active.
 *
 * See application.properties for the full reference with descriptions.
 */
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String   model;
    private String   name;
    private String   description;
    private String   instruction;
    private Provider provider = new Provider();
    private Context  context  = new Context();
    private Vector   vector   = new Vector();
    private Scoring  scoring  = new Scoring();
    private Ingest   ingest   = new Ingest();

    // ── Nested config classes ────────────────────────────────────────────────

    public static class Provider {
        /**
         * Single-word provider switch: {@code gemini} or {@code ollama}
         * (also accepts {@code openai} / {@code anthropic}). Changing this one
         * value reconfigures the LLM model, the embedding model, the base URL,
         * and the vector dimension together. When blank, the provider is inferred
         * from the {@code agent.model} prefix for backwards compatibility.
         */
        private String type;
        private String baseUrl;
        private String apiKey;

        public String getType()                { return type; }
        public void   setType(String v)        { this.type = v; }
        public String getBaseUrl()             { return baseUrl; }
        public void   setBaseUrl(String v)     { this.baseUrl = v; }
        public String getApiKey()              { return apiKey; }
        public void   setApiKey(String v)      { this.apiKey = v; }
    }

    public static class Context {
        private int     maxTurns;
        private boolean includeSystemSummary;

        public int     getMaxTurns()                    { return maxTurns; }
        public void    setMaxTurns(int v)               { this.maxTurns = v; }
        public boolean isIncludeSystemSummary()         { return includeSystemSummary; }
        public void    setIncludeSystemSummary(boolean v) { this.includeSystemSummary = v; }
    }

    public static class Vector {
        private boolean enabled;
        private String  host;
        private int     port;
        private String  collection;
        private int     dimension;
        private boolean recreateOnStartup;

        public boolean isEnabled()           { return enabled; }
        public void    setEnabled(boolean v) { this.enabled = v; }
        public String  getHost()             { return host; }
        public void    setHost(String v)     { this.host = v; }
        public int     getPort()             { return port; }
        public void    setPort(int v)        { this.port = v; }
        public String  getCollection()       { return collection; }
        public void    setCollection(String v) { this.collection = v; }
        public int     getDimension()        { return dimension; }
        public void    setDimension(int v)   { this.dimension = v; }
        public boolean isRecreateOnStartup()        { return recreateOnStartup; }
        public void    setRecreateOnStartup(boolean v) { this.recreateOnStartup = v; }
    }

    public static class Scoring {
        private String url;
        private int    timeoutSeconds;
        private int    retryAttempts;

        public String getUrl()                   { return url; }
        public void   setUrl(String v)           { this.url = v; }
        public int    getTimeoutSeconds()        { return timeoutSeconds; }
        public void   setTimeoutSeconds(int v)   { this.timeoutSeconds = v; }
        public int    getRetryAttempts()         { return retryAttempts; }
        public void   setRetryAttempts(int v)    { this.retryAttempts = v; }
    }

    public static class Ingest {
        private boolean enabled;
        private boolean runOnStartup;
        private String  sourceUrl;
        private String  sourceDir;
        private String  applicationId;

        public boolean isEnabled()             { return enabled; }
        public void    setEnabled(boolean v)   { this.enabled = v; }
        public boolean isRunOnStartup()        { return runOnStartup; }
        public void    setRunOnStartup(boolean v) { this.runOnStartup = v; }
        public String  getSourceUrl()          { return sourceUrl; }
        public void    setSourceUrl(String v)  { this.sourceUrl = v; }
        public String  getSourceDir()          { return sourceDir; }
        public void    setSourceDir(String v)  { this.sourceDir = v; }
        public String  getApplicationId()      { return applicationId; }
        public void    setApplicationId(String v) { this.applicationId = v; }
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String   getModel()               { return model; }
    public void     setModel(String v)       { this.model = v; }

    public String   getName()                { return name; }
    public void     setName(String v)        { this.name = v; }

    public String   getDescription()         { return description; }
    public void     setDescription(String v) { this.description = v; }

    public String   getInstruction()         { return instruction; }
    public void     setInstruction(String v) { this.instruction = v; }

    public Provider getProvider()            { return provider; }
    public void     setProvider(Provider v)  { this.provider = v; }

    public Context  getContext()             { return context; }
    public void     setContext(Context v)    { this.context = v; }

    public Vector   getVector()              { return vector; }
    public void     setVector(Vector v)      { this.vector = v; }

    public Scoring  getScoring()             { return scoring; }
    public void     setScoring(Scoring v)    { this.scoring = v; }

    public Ingest   getIngest()              { return ingest; }
    public void     setIngest(Ingest v)      { this.ingest = v; }

    // ── Derived config ────────────────────────────────────────────────────────

    /** Default embedding dimensions per provider. */
    private static final int GEMINI_DIM = 3072;
    private static final int OLLAMA_DIM = 768;
    private static final int OPENAI_DIM = 1536;

    /**
     * The effective embedding/vector dimension. Honors an explicit
     * {@code agent.vector.dimension} (> 0); otherwise derives it from the
     * selected {@code agent.provider.type} so a single-word provider switch
     * also fixes the Milvus collection dimension.
     */
    public int resolveVectorDimension() {
        int configured = vector.getDimension();
        if (configured > 0) {
            return configured;
        }
        return switch (LlmModelFactory.resolveProvider(this)) {
            case "ollama" -> OLLAMA_DIM;
            case "openai" -> OPENAI_DIM;
            default       -> GEMINI_DIM;
        };
    }
}

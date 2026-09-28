package com.example.agent;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point — Document Review Agent (Spring Boot).
 *
 * ── Start ──
 *   mvn spring-boot:run
 *
 * ── Chat endpoint ──
 *   POST http://localhost:8080/api/chat
 *   { "userId": "u1", "message": "Review document DOC-001" }
 *
 *   Pass sessionId from a prior response to continue the conversation:
 *   { "userId": "u1", "sessionId": "...", "message": "What was the score?" }
 *
 * ── UI ──
 *   http://localhost:8080
 *
 * ── Health ──
 *   GET http://localhost:8080/api/health
 *
 * ── Switch LLM provider ──
 *   application.properties → agent.provider=OLLAMA | OPENAI | GEMINI
 *
 * ── Disable vector store ──
 *   application.properties → agent.vector.enabled=false
 */
@SpringBootApplication
@EnableConfigurationProperties
public class ReviewAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgentApplication.class);

    public static void main(String[] args) {
        // Make the log destination unmistakable at boot. Resolves the same way the
        // Log4j2 config does: -DLOG_DIR sys prop, then LOG_DIR env var, else "logs".
        String logDir = System.getProperty("LOG_DIR",
                System.getenv().getOrDefault("LOG_DIR", "logs"));
        Path resolved = Path.of(logDir).toAbsolutePath().normalize();
        log.info("Log4j2: writing logs to {}", resolved);

        SpringApplication.run(ReviewAgentApplication.class, args);
    }
}

package com.example.agent;

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

    public static void main(String[] args) {
        SpringApplication.run(ReviewAgentApplication.class, args);
    }
}

package com.example.agent.core;

/**
 * Marker interface for all ADK agent tools.
 *
 * Every Spring bean that acts as an ADK tool should implement this interface.
 * It enables framework-level discovery, auditing, and registration.
 *
 * <pre>
 *   {@literal @}Component
 *   public class MyTool implements AgentTool {
 *       {@literal @}Schema(description = "...")
 *       public Map{@literal <}String, Object{@literal >} doSomething(String input) { ... }
 *   }
 * </pre>
 */
public interface AgentTool {
    // Marker — no methods required.
}

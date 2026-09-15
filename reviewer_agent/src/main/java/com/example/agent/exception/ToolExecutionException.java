package com.example.agent.exception;

/**
 * Thrown when an agent tool fails during execution.
 * Caught by {@link com.example.agent.web.GlobalExceptionHandler} → HTTP 422.
 *
 * Usage in tools:
 * <pre>
 *   throw new ToolExecutionException("readDocument", "File not found: " + path, cause);
 * </pre>
 */
public class ToolExecutionException extends AgentException {

    private final String toolName;

    public ToolExecutionException(String toolName, String message) {
        super("[" + toolName + "] " + message);
        this.toolName = toolName;
    }

    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super("[" + toolName + "] " + message, cause);
        this.toolName = toolName;
    }

    public String getToolName() { return toolName; }
}

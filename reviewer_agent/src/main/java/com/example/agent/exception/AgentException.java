package com.example.agent.exception;

/**
 * Base runtime exception for all agent errors.
 * Caught by {@link com.example.agent.web.GlobalExceptionHandler} → HTTP 500.
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}

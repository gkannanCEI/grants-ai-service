package com.example.agent.exception;

/**
 * Thrown when a vector store operation fails (insert, search, connect, etc.).
 * Caught by {@link com.example.agent.web.GlobalExceptionHandler} → HTTP 500.
 */
public class VectorStoreException extends AgentException {

    public VectorStoreException(String message) {
        super(message);
    }

    public VectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

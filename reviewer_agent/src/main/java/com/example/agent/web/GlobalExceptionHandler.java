package com.example.agent.web;

import com.example.agent.exception.AgentException;
import com.example.agent.exception.ToolExecutionException;
import com.example.agent.exception.VectorStoreException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Centralised exception handling — maps all agent exceptions to consistent
 * {@link ErrorResponse} HTTP payloads.
 *
 *   Validation errors        → 400 Bad Request
 *   ToolExecutionException   → 422 Unprocessable Entity
 *   VectorStoreException     → 503 Service Unavailable
 *   AgentException           → 500 Internal Server Error
 *   Uncaught exceptions      → 500 Internal Server Error
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {

        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        log.warn("Validation failed [{}]: {}", req.getRequestURI(), detail);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "Validation Failed", detail, req.getRequestURI()));
    }

    @ExceptionHandler(ToolExecutionException.class)
    public ResponseEntity<ErrorResponse> handleToolExecution(
            ToolExecutionException ex, HttpServletRequest req) {

        log.error("Tool execution failed [tool={}]: {}", ex.getToolName(), ex.getMessage(), ex);
        return ResponseEntity.unprocessableEntity()
                .body(ErrorResponse.of(422, "Tool Execution Failed", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(VectorStoreException.class)
    public ResponseEntity<ErrorResponse> handleVectorStore(
            VectorStoreException ex, HttpServletRequest req) {

        log.error("Vector store error [{}]: {}", req.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(503)
                .body(ErrorResponse.of(503, "Vector Store Unavailable", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ErrorResponse> handleAgent(
            AgentException ex, HttpServletRequest req) {

        log.error("Agent error [{}]: {}", req.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(500, "Agent Error", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex, HttpServletRequest req) {

        log.error("Unhandled exception [{}]: {}", req.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI()));
    }
}

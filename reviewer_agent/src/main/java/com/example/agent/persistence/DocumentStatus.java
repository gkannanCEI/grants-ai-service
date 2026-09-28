package com.example.agent.persistence;

/**
 * Processing lifecycle of a tracked document.
 *
 * <ul>
 *   <li>{@link #PENDING}    — recorded, not yet indexed</li>
 *   <li>{@link #PROCESSING} — ingestion in progress</li>
 *   <li>{@link #INDEXED}    — successfully extracted and stored (Milvus / in-memory)</li>
 *   <li>{@link #FAILED}     — ingestion attempted but failed (see {@code errorMessage})</li>
 * </ul>
 */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    INDEXED,
    FAILED
}

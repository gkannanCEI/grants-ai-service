package com.example.agent.persistence;

/**
 * Lifecycle status of a reservation-index summary.
 *
 * <ul>
 *   <li>{@link #PENDING}   — request accepted, summary not yet produced</li>
 *   <li>{@link #COMPLETED} — LLM produced the summary and it was stored</li>
 *   <li>{@link #FAILED}    — summarization attempted but failed (see {@code errorMessage})</li>
 * </ul>
 */
public enum SummaryStatus {
    PENDING,
    COMPLETED,
    FAILED
}

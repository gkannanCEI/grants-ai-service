package com.example.agent.reservation;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.agent.exception.AgentException;
import com.example.agent.llm.ChatClient;
import com.example.agent.persistence.ReservationSummary;
import com.example.agent.persistence.ReservationSummaryRepository;

/**
 * Turns the question/answer (key/value) pairs submitted for a reservation index
 * into a single narrative summary using the LLM, then persists the result
 * (status + reservationId + summary).
 *
 * <p>Summarization is a plain prompt → text call, so it goes through
 * {@link ChatClient} (a direct provider REST client) rather than ADK. This keeps
 * it working across providers via the single {@code agent.provider.type} switch —
 * ADK for Java 1.6.0 does not natively support Ollama model strings.
 */
@Service
public class ReservationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ReservationSummaryService.class);

    private static final String INSTRUCTION = """
            You are a summarization assistant for grant reservation submissions.

            You will be given a numbered list of question/answer pairs for a single
            reservation. Produce ONE cohesive, well-written summary in plain prose that
            captures EVERY question and its answer.

            Strict rules:
            - Do NOT omit, skip, or drop any pair. Every question and every answer is
              critical and MUST be represented in the summary.
            - Do NOT invent, infer, or add any information that is not present in the
              provided pairs.
            - Preserve all specific values exactly: names, numbers, dates, amounts,
              identifiers, addresses, and any other concrete detail.
            - If an answer is empty or missing, state that the answer was not provided
              for that question rather than skipping it.
            - Return only the summary text. Do not add headings, preamble, or commentary.
            """;

    private final ReservationSummaryRepository repository;
    private final ChatClient                   chatClient;

    public ReservationSummaryService(ReservationSummaryRepository repository,
                                     ChatClient chatClient) {
        this.repository = repository;
        this.chatClient = chatClient;
    }

    /**
     * Summarizes the given question/answer pairs for a reservation and stores the
     * result. A record is created up-front as PENDING and updated to COMPLETED or
     * FAILED so every submission is durably tracked.
     *
     * @param reservationId the reservation this index summary comes from
     * @param responses     question → answer/response pairs (all are required content)
     * @return the persisted {@link ReservationSummary}
     */
    @Transactional
    public ReservationSummary summarizeAndStore(String reservationId, Map<String, String> responses) {
        ReservationSummary record = repository.save(ReservationSummary.pending(reservationId));
        log.info("Summarizing reservation index — reservationId={} pairs={} recordId={}",
                reservationId, responses.size(), record.getId());

        try {
            String prompt  = buildPrompt(reservationId, responses);
            String summary = chatClient.complete(INSTRUCTION, prompt);

            if (summary == null || summary.isBlank()) {
                throw new AgentException("LLM returned an empty summary");
            }

            record.markCompleted(summary.trim());
            log.info("Reservation summary stored — reservationId={} recordId={} status=COMPLETED",
                    reservationId, record.getId());
        } catch (Exception e) {
            record.markFailed(e.getMessage());
            log.error("Reservation summary failed — reservationId={} recordId={}",
                    reservationId, record.getId(), e);
        }

        return repository.save(record);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Serializes every key/value pair into a numbered, unambiguous prompt. */
    private String buildPrompt(String reservationId, Map<String, String> responses) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize the following ").append(responses.size())
          .append(" question/answer pairs for reservation ").append(reservationId)
          .append(". Include every pair without omission.\n\n");

        int i = 1;
        for (Map.Entry<String, String> entry : responses.entrySet()) {
            String question = entry.getKey() == null ? "" : entry.getKey().trim();
            String answer   = entry.getValue();
            sb.append(i++).append(". Question: ").append(question).append('\n');
            sb.append("   Answer: ")
              .append(answer == null || answer.isBlank() ? "(no answer provided)" : answer.trim())
              .append("\n\n");
        }
        return sb.toString();
    }
}

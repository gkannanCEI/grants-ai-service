package com.example.agent.reservation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.agent.exception.AgentException;
import com.example.agent.llm.ChatClient;
import com.example.agent.persistence.ReservationSummary;
import com.example.agent.persistence.ReservationSummaryRepository;
import com.example.agent.retrieval.RetrievalService;

/**
 * Supplemental question-answering agent scoped to a single reservation.
 *
 * <p>Answers a follow-up question about a reservation using two context sources
 * that already exist for it:
 * <ol>
 *   <li>the stored AI summary ({@code reservation_summary}), and</li>
 *   <li>the reservation's indexed documents, retrieved via RAG
 *       ({@link RetrievalService}, scoped to the reservation ID as applicationId).</li>
 * </ol>
 * The optional hint is passed to the model as extra guidance on how to interpret
 * or where to look for the answer. The model is instructed to answer only from
 * the provided context.
 */
@Service
public class SupplementalQaService {

    private static final Logger log = LoggerFactory.getLogger(SupplementalQaService.class);

    private static final String INSTRUCTION = """
            You are a supplemental question-answering assistant for a single grant reservation.

            Answer the user's question using ONLY the context provided below, which consists of:
              (1) an AI-generated SUMMARY of the reservation, and
              (2) excerpts from the reservation's INDEXED DOCUMENTS.

            Rules:
            - Base your answer strictly on the provided summary and document excerpts.
            - If a HINT is provided, use it to guide how you interpret the question or where to
              look, but do not treat the hint as a fact unless the context supports it.
            - If the answer cannot be found in the context, say clearly that the information is
              not available in this reservation's summary or documents. Do NOT invent an answer.
            - Be concise and specific; preserve exact values (names, numbers, dates, amounts, IDs).
            - Return only the answer text.
            """;

    private final ReservationSummaryRepository summaryRepository;
    private final RetrievalService             retrievalService;
    private final ChatClient                   chatClient;

    public SupplementalQaService(ReservationSummaryRepository summaryRepository,
                                 RetrievalService retrievalService,
                                 ChatClient chatClient) {
        this.summaryRepository = summaryRepository;
        this.retrievalService  = retrievalService;
        this.chatClient        = chatClient;
    }

    /**
     * Answers a collection of supplemental questions about a reservation.
     *
     * <p>The reservation's stored AI summary is loaded once and reused for every
     * question. Document retrieval is performed per question, because the most
     * relevant excerpts depend on the specific question being asked.
     *
     * @param reservationId the reservation providing the context
     * @param questions     the questions to answer (each with an optional hint)
     * @return one answer per question, in the same order
     */
    @Transactional(readOnly = true)
    public List<Answer> answerAll(String reservationId, List<Question> questions) {
        log.info("Supplemental QA batch — reservationId={} questions={}",
                reservationId, questions.size());

        // Latest stored AI summary for the reservation (loaded once, reused).
        String summaryText = summaryRepository
                .findByReservationIdOrderByCreatedAtDesc(reservationId)
                .stream()
                .map(ReservationSummary::getSummary)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);

        List<Answer> answers = new java.util.ArrayList<>(questions.size());
        for (Question q : questions) {
            answers.add(answerOne(reservationId, summaryText, q.question(), q.hint()));
        }
        return answers;
    }

    /** Answers a single question against the already-loaded reservation summary. */
    private Answer answerOne(String reservationId, String summaryText,
                             String question, String hint) {
        // Relevant document excerpts for THIS question (RAG scoped to the reservation).
        RetrievalService.RetrievalResult retrieval =
                retrievalService.retrieve(reservationId, question);
        String documentContext = retrieval.hasContext() ? retrieval.contextBlock() : null;

        if (summaryText == null && documentContext == null) {
            log.info("No context available for reservationId={} question=\"{}\"",
                    reservationId, question);
            return new Answer(question,
                    "No context is available for reservation " + reservationId
                            + ". There is no stored summary and no indexed documents to answer from.",
                    false, false, 0);
        }

        String prompt = buildPrompt(reservationId, question, hint, summaryText, documentContext);
        String answer = chatClient.complete(INSTRUCTION, prompt);

        if (answer == null || answer.isBlank()) {
            throw new AgentException("LLM returned an empty answer for question: " + question);
        }

        log.info("Supplemental QA answered — reservationId={} usedSummary={} docChunks={}",
                reservationId, summaryText != null, retrieval.chunkCount());

        return new Answer(question, answer.trim(),
                summaryText != null, documentContext != null, retrieval.chunkCount());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildPrompt(String reservationId, String question, String hint,
                               String summaryText, String documentContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reservation: ").append(reservationId).append("\n\n");

        sb.append("=== RESERVATION SUMMARY ===\n");
        sb.append(summaryText != null ? summaryText : "(no summary available)").append("\n\n");

        sb.append("=== INDEXED DOCUMENT EXCERPTS ===\n");
        sb.append(documentContext != null ? documentContext
                : "(no relevant document excerpts found)").append("\n\n");

        if (hint != null && !hint.isBlank()) {
            sb.append("=== HINT ===\n").append(hint.trim()).append("\n\n");
        }

        sb.append("=== QUESTION ===\n").append(question.trim()).append('\n');
        return sb.toString();
    }

    /** A single supplemental question with an optional hint. */
    public record Question(String question, String hint) { }

    /**
     * The answer to one question plus provenance about which context was used.
     *
     * @param question       the question that was asked
     * @param answer         the model's answer
     * @param usedSummary    whether the stored summary was available and included
     * @param usedDocuments  whether document excerpts were retrieved and included
     * @param documentChunks number of document chunks used as context
     */
    public record Answer(String question, String answer,
                          boolean usedSummary, boolean usedDocuments, int documentChunks) {
    }
}

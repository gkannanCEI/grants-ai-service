package com.example.agent.web;

import java.util.List;

import com.example.agent.reservation.SupplementalQaService;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST /api/reservations/ask}.
 *
 * <p>Returns one answer per submitted question, in the same order, along with
 * provenance about which reservation context was used for each.
 *
 * @param reservationId the reservation asked about
 * @param answers       the answers, one per submitted question (same order)
 */
@Schema(
        name = "SupplementalAnswerResponse",
        description = "Answers to the submitted questions, one per question, with per-answer "
                + "provenance about which reservation context was used.",
        example = """
                {
                  "reservationId": "RES-2026-000123",
                  "answers": [
                    {
                      "question": "What is the total requested funding and over what period?",
                      "answer": "The reservation requests $250,000 over an 18-month project timeline.",
                      "usedSummary": true,
                      "usedDocuments": true,
                      "documentChunks": 3,
                      "usedUserKnowledge": false,
                      "userKnowledgeChunks": 0,
                      "reasoning": "The funding amount and timeline are stated directly in the indexed document excerpt.",
                      "confidence": 9,
                      "sources": [
                        { "type": "DOCUMENT", "label": "grant-application.pdf#2", "snippet": "Requested amount: $250,000 over 18 months." }
                      ]
                    },
                    {
                      "question": "What contact method does this user prefer?",
                      "answer": "This user prefers email contact.",
                      "usedSummary": false,
                      "usedDocuments": false,
                      "documentChunks": 0,
                      "usedUserKnowledge": true,
                      "userKnowledgeChunks": 1,
                      "reasoning": "The user's personal notes state their preferred contact method.",
                      "confidence": 8,
                      "sources": [
                        { "type": "USER_KNOWLEDGE", "label": "personal-notes#1", "snippet": "Preferred contact: email only, no phone calls." }
                      ]
                    }
                  ]
                }"""
)
public record SupplementalAnswerResponse(

        @Schema(description = "The reservation asked about", example = "RES-2026-000123")
        String reservationId,

        @Schema(description = "One answer per submitted question, in the same order")
        List<AnswerItem> answers
) {

    /**
     * A single answer with provenance.
     *
     * @param question            the question that was asked
     * @param answer              the grounded answer
     * @param usedSummary         whether the stored AI summary was available and used
     * @param usedDocuments       whether reservation document excerpts were used
     * @param documentChunks      number of reservation document chunks used as context
     * @param usedUserKnowledge   whether the logged-in user's personal knowledge base was used
     * @param userKnowledgeChunks number of personal knowledge chunks used as context
     * @param reasoning           short explanation of why this answer follows from the context,
     *                            or null when the model reply wasn't structured
     * @param sources             precise citations the answer was derived from
     * @param confidence          0–10 score of how well the verified context supports the answer
     */
    @Schema(name = "AnswerItem", description = "An answer to a single question, with provenance.")
    public record AnswerItem(
            String  question,
            String  answer,
            boolean usedSummary,
            boolean usedDocuments,
            int     documentChunks,
            boolean usedUserKnowledge,
            int     userKnowledgeChunks,
            String  reasoning,
            List<SourceItem> sources,
            @Schema(description = "0-10 score of how well the verified context supports the "
                    + "answer: the model's self-rating capped by the strength of the citations "
                    + "that could be verified against retrieved content",
                    minimum = "0", maximum = "10", example = "8")
            int     confidence
    ) {
        public static AnswerItem from(SupplementalQaService.Answer a) {
            return new AnswerItem(a.question(), a.answer(),
                    a.usedSummary(), a.usedDocuments(), a.documentChunks(),
                    a.usedUserKnowledge(), a.userKnowledgeChunks(),
                    a.reasoning(),
                    a.sources() == null ? List.of()
                            : a.sources().stream().map(SourceItem::from).toList(),
                    a.confidence());
        }
    }

    /**
     * A precise citation for one piece of context an answer relied on.
     *
     * @param type    where this came from: SUMMARY, DOCUMENT, USER_KNOWLEDGE, or HINT
     * @param label   short identifier, e.g. "invoice.pdf#2" or "Reservation AI summary"
     * @param snippet verbatim excerpt of the actual source text, or null when unavailable
     */
    @Schema(name = "SourceItem", description = "A precise citation for one piece of context "
            + "an answer relied on.")
    public record SourceItem(String type, String label, String snippet) {
        public static SourceItem from(SupplementalQaService.SourceRef s) {
            return new SourceItem(s.type(), s.label(), s.snippet());
        }
    }

    public static SupplementalAnswerResponse from(String reservationId,
                                                  List<SupplementalQaService.Answer> answers) {
        return new SupplementalAnswerResponse(
                reservationId,
                answers.stream().map(AnswerItem::from).toList());
    }
}

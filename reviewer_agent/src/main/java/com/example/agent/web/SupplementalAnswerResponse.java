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
                      "documentChunks": 3
                    },
                    {
                      "question": "Who is the primary contact for this reservation?",
                      "answer": "The primary contact is Jane Doe (jane.doe@example.org).",
                      "usedSummary": true,
                      "usedDocuments": false,
                      "documentChunks": 0
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
     * @param question       the question that was asked
     * @param answer         the grounded answer
     * @param usedSummary    whether the stored AI summary was available and used
     * @param usedDocuments  whether indexed document excerpts were used
     * @param documentChunks number of document chunks used as context
     */
    @Schema(name = "AnswerItem", description = "An answer to a single question, with provenance.")
    public record AnswerItem(
            String  question,
            String  answer,
            boolean usedSummary,
            boolean usedDocuments,
            int     documentChunks
    ) {
        public static AnswerItem from(SupplementalQaService.Answer a) {
            return new AnswerItem(a.question(), a.answer(),
                    a.usedSummary(), a.usedDocuments(), a.documentChunks());
        }
    }

    public static SupplementalAnswerResponse from(String reservationId,
                                                  List<SupplementalQaService.Answer> answers) {
        return new SupplementalAnswerResponse(
                reservationId,
                answers.stream().map(AnswerItem::from).toList());
    }
}

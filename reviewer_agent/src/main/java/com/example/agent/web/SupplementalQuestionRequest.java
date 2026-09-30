package com.example.agent.web;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * Request body for {@code POST /api/reservations/ask}.
 *
 * <p>Carries one reservation plus a collection of supplemental questions, each
 * with an optional hint. Every question is answered from the same reservation
 * context (its AI summary + indexed documents).
 *
 * @param reservationId the reservation whose context should be used (required)
 * @param questions     the questions to answer (required, at least one)
 */
@Schema(
        name = "SupplementalQuestionRequest",
        description = "A reservation plus a collection of supplemental questions, each answered "
                + "from the reservation's AI summary and indexed documents.",
        example = """
                {
                  "reservationId": "RES-2026-000123",
                  "cid": "CID-88421",
                  "questions": [
                    {
                      "question": "What is the total requested funding and over what period?",
                      "hint": "Look for the requested amount and the project timeline."
                    },
                    {
                      "question": "Who is the primary contact for this reservation?"
                    }
                  ]
                }"""
)
public record SupplementalQuestionRequest(

        @Schema(description = "Reservation providing the context", example = "RES-2026-000123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "reservationId must not be blank")
        String reservationId,

        @Schema(description = "Logged-in user's identifier. When supplied, their personal "
                + "knowledge base (registered via POST /api/users/{cid}/knowledge) is blended "
                + "into the answer alongside the reservation context.",
                example = "CID-88421", nullable = true)
        String cid,

        @Schema(description = "The collection of questions to answer",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "questions must contain at least one item")
        @Valid
        List<QuestionItem> questions
) {

    /**
     * A single supplemental question with an optional hint.
     *
     * @param question the supplemental question to answer (required)
     * @param hint     optional guidance for interpreting the question or where to look
     */
    @Schema(name = "QuestionItem", description = "A supplemental question with an optional hint.")
    public record QuestionItem(

            @Schema(description = "The supplemental question to answer",
                    example = "What is the total requested funding and over what period?",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "question must not be blank")
            String question,

            @Schema(description = "Optional guidance for interpreting the question or where to look",
                    example = "Look for the requested amount and the project timeline.",
                    nullable = true)
            String hint
    ) { }
}

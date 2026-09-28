package com.example.agent.web;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * Request body for {@code POST /api/reservations/index}.
 *
 * @param reservationId the reservation this index summary comes from (required)
 * @param responses     question → answer/response pairs; every entry is critical
 *                      and must be reflected in the generated summary (required, non-empty)
 */
@Schema(
        name = "ReservationIndexRequest",
        description = "A reservation's question/answer pairs to be folded into a lossless summary.",
        example = """
                {
                  "reservationId": "RES-2026-000123",
                  "responses": {
                    "Project name": "Downtown Housing Renewal",
                    "Requested amount": "$250,000",
                    "Project timeline": "18 months",
                    "Primary contact": "Jane Doe, jane.doe@example.org",
                    "Number of units": "42"
                  }
                }"""
)
public record ReservationIndexRequest(

        @Schema(description = "Identifier of the reservation the index summary comes from",
                example = "RES-2026-000123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "reservationId must not be blank")
        String reservationId,

        @Schema(description = "Question → answer/response pairs. Every entry is critical and "
                + "will be represented in the summary.",
                example = "{\"Project name\":\"Downtown Housing Renewal\","
                        + "\"Requested amount\":\"$250,000\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "responses must contain at least one question/answer pair")
        Map<String, String> responses
) { }

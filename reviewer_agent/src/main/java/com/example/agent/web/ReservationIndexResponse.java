package com.example.agent.web;

import java.time.Instant;

import com.example.agent.persistence.ReservationSummary;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST /api/reservations/index}.
 *
 * @param id            identifier of the stored summary record
 * @param reservationId the reservation the summary comes from
 * @param status        PENDING / COMPLETED / FAILED
 * @param summary       the generated narrative summary (null when not COMPLETED)
 * @param errorMessage  failure detail when status is FAILED (otherwise null)
 * @param createdAt     when the record was created
 */
@Schema(
        name = "ReservationIndexResponse",
        description = "The stored reservation summary result.",
        example = """
                {
                  "id": "3f8a1c2e-9b4d-4e21-8a77-5c0b1d9e2f34",
                  "reservationId": "RES-2026-000123",
                  "status": "COMPLETED",
                  "summary": "Reservation RES-2026-000123 covers the Downtown Housing Renewal project, which requests $250,000 and is planned over an 18-month timeline. The primary contact is Jane Doe (jane.doe@example.org), and the project comprises 42 units.",
                  "errorMessage": null,
                  "createdAt": "2026-09-28T14:22:05.123Z"
                }"""
)
public record ReservationIndexResponse(

        @Schema(description = "Identifier of the stored summary record",
                example = "3f8a1c2e-9b4d-4e21-8a77-5c0b1d9e2f34")
        String  id,

        @Schema(description = "The reservation the summary comes from", example = "RES-2026-000123")
        String  reservationId,

        @Schema(description = "Processing status", example = "COMPLETED",
                allowableValues = {"PENDING", "COMPLETED", "FAILED"})
        String  status,

        @Schema(description = "The generated narrative summary covering every submitted pair; "
                + "null unless status is COMPLETED")
        String  summary,

        @Schema(description = "Failure detail when status is FAILED; otherwise null", nullable = true)
        String  errorMessage,

        @Schema(description = "When the record was created", example = "2026-09-28T14:22:05.123Z")
        Instant createdAt
) {
    public static ReservationIndexResponse from(ReservationSummary s) {
        return new ReservationIndexResponse(
                s.getId(),
                s.getReservationId(),
                s.getStatus().name(),
                s.getSummary(),
                s.getErrorMessage(),
                s.getCreatedAt()
        );
    }
}

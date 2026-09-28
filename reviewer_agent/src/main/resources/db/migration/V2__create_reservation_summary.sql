-- =============================================================
-- V2 — reservation index summaries
--
-- Stores an LLM-generated narrative summary for each reservation
-- index submission. The summary is produced from the full set of
-- question/answer (key/value) pairs sent to POST /api/reservations/index
-- and is meant to capture every submitted value without loss.
--
-- Column types/lengths mirror com.example.agent.persistence.ReservationSummary
-- so Hibernate (spring.jpa.hibernate.ddl-auto=validate) validates cleanly.
-- =============================================================

CREATE TABLE reservation_summary (
    id             VARCHAR(64)    NOT NULL,
    reservation_id VARCHAR(256)   NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    summary        TEXT,
    error_message  VARCHAR(2048),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_reservation_summary PRIMARY KEY (id)
);

-- Listing / lookup of summaries for a given reservation (newest first).
CREATE INDEX idx_res_reservation_id ON reservation_summary (reservation_id);

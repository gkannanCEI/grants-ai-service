package com.example.agent.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Durable record of a reservation-index summary.
 *
 * <p>A summary is produced by an LLM from the full set of question/answer
 * (key/value) pairs submitted for a reservation. It captures every submitted
 * value in a single narrative text so no information is lost.
 *
 * <p>Columns mirror {@code src/main/resources/db/migration/V2__create_reservation_summary.sql}
 * so Hibernate ({@code spring.jpa.hibernate.ddl-auto=validate}) validates cleanly.
 */
@Entity
@Table(
        name = "reservation_summary",
        indexes = {
                @Index(name = "idx_res_reservation_id", columnList = "reservation_id")
        }
)
public class ReservationSummary {

    /** UUID assigned when the summary record is created. */
    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    /** Identifier of the reservation this index summary comes from. */
    @Column(name = "reservation_id", nullable = false, length = 256)
    private String reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SummaryStatus status;

    /**
     * The LLM-generated narrative summary covering every submitted key/value.
     * Mapped to PostgreSQL {@code TEXT} (not a large-object/CLOB) to match the
     * V2 migration and pass {@code ddl-auto=validate}.
     */
    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ReservationSummary() {
        // for JPA
    }

    private ReservationSummary(String id, String reservationId, SummaryStatus status) {
        this.id            = id;
        this.reservationId = reservationId;
        this.status        = status;
        this.createdAt     = Instant.now();
    }

    /** Creates a new PENDING summary record for the given reservation. */
    public static ReservationSummary pending(String reservationId) {
        return new ReservationSummary(UUID.randomUUID().toString(), reservationId,
                SummaryStatus.PENDING);
    }

    /** Records a successfully generated summary and stamps the time. */
    public void markCompleted(String summary) {
        this.summary      = summary;
        this.status       = SummaryStatus.COMPLETED;
        this.errorMessage = null;
        this.updatedAt    = Instant.now();
    }

    /** Records a failed summarization attempt. */
    public void markFailed(String errorMessage) {
        this.status       = SummaryStatus.FAILED;
        this.errorMessage = truncate(errorMessage, 2048);
        this.updatedAt    = Instant.now();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getId()                    { return id; }
    public void   setId(String v)            { this.id = v; }

    public String getReservationId()         { return reservationId; }
    public void   setReservationId(String v) { this.reservationId = v; }

    public SummaryStatus getStatus()         { return status; }
    public void   setStatus(SummaryStatus v) { this.status = v; }

    public String getSummary()               { return summary; }
    public void   setSummary(String v)       { this.summary = v; }

    public String getErrorMessage()          { return errorMessage; }
    public void   setErrorMessage(String v)  { this.errorMessage = v; }

    public Instant getCreatedAt()            { return createdAt; }
    public void    setCreatedAt(Instant v)   { this.createdAt = v; }

    public Instant getUpdatedAt()            { return updatedAt; }
    public void    setUpdatedAt(Instant v)   { this.updatedAt = v; }
}

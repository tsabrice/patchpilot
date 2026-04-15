package ca.uqam.patchpilot.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

/**
 * Durable delivery buffer for incoming SonarQube webhook payloads.
 *
 * The webhook controller inserts one row here and immediately returns 200.
 * A @Scheduled poller then picks up PENDING rows and drives the fix pipeline.
 * This decoupling means a slow Claude or GitHub call never causes SonarQube
 * to retry the webhook delivery.
 *
 * sonar_task_id is UNIQUE — ON CONFLICT DO NOTHING makes delivery idempotent.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sonar_task_id", nullable = false, unique = true, length = 64)
    private String sonarTaskId;

    // @Enumerated(STRING) stores the enum name ("PENDING") not its ordinal (0).
    // Ordinal storage breaks whenever enum values are reordered.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WebhookEventStatus status = WebhookEventStatus.PENDING;

    // columnDefinition = "jsonb" tells Hibernate to create a jsonb column.
    // @JdbcTypeCode(JSON) tells Hibernate 6 to bind this String as JSON, not varchar.
    // The value is stored and retrieved as a plain String — Hibernate does
    // not parse it, so the raw SonarQube payload is preserved exactly.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt = OffsetDateTime.now();

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    // ── Constructors ──────────────────────────────────────────────────────────

    protected WebhookEvent() {}

    public WebhookEvent(String sonarTaskId, String rawPayload) {
        this.sonarTaskId = sonarTaskId;
        this.rawPayload = rawPayload;
    }

    // ── Getters and setters ───────────────────────────────────────────────────

    public Long getId() { return id; }

    public String getSonarTaskId() { return sonarTaskId; }

    public WebhookEventStatus getStatus() { return status; }
    public void setStatus(WebhookEventStatus status) { this.status = status; }

    public String getRawPayload() { return rawPayload; }

    public OffsetDateTime getReceivedAt() { return receivedAt; }

    public OffsetDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(OffsetDateTime processedAt) { this.processedAt = processedAt; }
}

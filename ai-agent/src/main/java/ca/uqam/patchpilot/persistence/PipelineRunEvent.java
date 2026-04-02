package ca.uqam.patchpilot.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

/**
 * Append-only audit log of every meaningful state transition in a pipeline run.
 *
 * Rules:
 *  - Never UPDATE rows in this table — only INSERT.
 *  - findingId is nullable: null means a run-level event, non-null means a
 *    finding-level event. It is stored as a plain Long (not a @ManyToOne)
 *    so that deleting a finding row does not cascade-delete audit history.
 *
 * Event types:
 *  Run-level:     PIPELINE_STARTED, PIPELINE_COMPLETED, PIPELINE_FAILED
 *  Finding-level: FINDING_QUEUED, FILE_FETCHED, CLAUDE_CALLED,
 *                 BRANCH_CREATED, PR_OPENED, FINDING_COMPLETED, FINDING_FAILED
 */
@Entity
@Table(name = "pipeline_run_events")
public class PipelineRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private PipelineRun run;

    // Nullable — null for run-level events, finding id for finding-level events.
    // Plain Long, not @ManyToOne, so audit rows survive finding deletion.
    @Column(name = "finding_id")
    private Long findingId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    // Optional JSON context: {"durationMs": 4200, "prUrl": "...", "errorMsg": "..."}
    // @JdbcTypeCode tells Hibernate 6 to bind this String as JSON, not varchar —
    // without it Hibernate sends the value as character varying and PostgreSQL rejects it.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt = OffsetDateTime.now();

    // ── Constructors ──────────────────────────────────────────────────────────

    protected PipelineRunEvent() {}

    public PipelineRunEvent(PipelineRun run, String eventType) {
        this.run = run;
        this.eventType = eventType;
    }

    public PipelineRunEvent(PipelineRun run, Long findingId, String eventType, String payload) {
        this.run = run;
        this.findingId = findingId;
        this.eventType = eventType;
        this.payload = payload;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long getId() { return id; }

    public PipelineRun getRun() { return run; }

    public Long getFindingId() { return findingId; }

    public String getEventType() { return eventType; }

    public String getPayload() { return payload; }

    public OffsetDateTime getOccurredAt() { return occurredAt; }
}

package ca.uqam.patchpilot.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * One pipeline run = one Jenkins build + SonarQube scan cycle.
 *
 * No aggregate columns (findingsCount, fixesAttempted, etc.) — those numbers
 * are computed on read via SQL COUNT queries. Storing them as columns would
 * require keeping them in sync with child table writes, which introduces bugs.
 */
@Entity
@Table(name = "pipeline_runs")
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The webhook_events row that triggered this run.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_event_id", nullable = false)
    private WebhookEvent webhookEvent;

    @Column(name = "sonar_task_id", nullable = false, unique = true, length = 64)
    private String sonarTaskId;

    @Column(name = "project_key", nullable = false)
    private String projectKey;

    // SonarQube Community Edition scans one branch at a time.
    // Stored for display in the dashboard only.
    @Column(nullable = false)
    private String branch = "main";

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PipelineRunStatus status = PipelineRunStatus.PENDING;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    // ── Constructors ──────────────────────────────────────────────────────────

    protected PipelineRun() {}

    public PipelineRun(WebhookEvent webhookEvent, String sonarTaskId,
                       String projectKey, String branch, String commitSha) {
        this.webhookEvent = webhookEvent;
        this.sonarTaskId = sonarTaskId;
        this.projectKey = projectKey;
        this.branch = branch;
        this.commitSha = commitSha;
    }

    // ── Getters and setters ───────────────────────────────────────────────────

    public Long getId() { return id; }

    public WebhookEvent getWebhookEvent() { return webhookEvent; }

    public String getSonarTaskId() { return sonarTaskId; }

    public String getProjectKey() { return projectKey; }

    public String getBranch() { return branch; }

    public String getCommitSha() { return commitSha; }

    public PipelineRunStatus getStatus() { return status; }
    public void setStatus(PipelineRunStatus status) { this.status = status; }

    public OffsetDateTime getStartedAt() { return startedAt; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}

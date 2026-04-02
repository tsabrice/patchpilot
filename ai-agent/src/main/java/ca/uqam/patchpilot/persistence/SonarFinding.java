package ca.uqam.patchpilot.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * One row per SonarQube finding in a scan.
 *
 * pipelineStatus is the crash recovery checkpoint. If the agent restarts,
 * the @Scheduled poller re-queues any finding not in a terminal state
 * (COMPLETED, FAILED, SKIPPED) by reading this column.
 *
 * One @Async task is dispatched per finding — not one per run. This ensures
 * that a slow or failing finding does not block all the other findings.
 */
@Entity
@Table(name = "sonar_findings")
public class SonarFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private PipelineRun run;

    // SonarQube's own stable identifier for this issue across scans.
    // UNIQUE — the same issue appearing in two consecutive scans is one row.
    @Column(name = "sonar_issue_key", nullable = false, unique = true, length = 64)
    private String sonarIssueKey;

    // e.g. "java:S2077" (SQL injection), "java:S2068" (hardcoded password)
    @Column(name = "rule_key", nullable = false, length = 128)
    private String ruleKey;

    // BLOCKER | CRITICAL | MAJOR | MINOR | INFO
    @Column(nullable = false, length = 32)
    private String severity;

    // File path within the project, e.g. "src/main/java/.../UserService.java"
    @Column(nullable = false)
    private String component;

    // Line number of the finding — nullable for file-level issues
    @Column
    private Integer line;

    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "pipeline_status", nullable = false, length = 32)
    private FindingPipelineStatus pipelineStatus = FindingPipelineStatus.QUEUED;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // ── Constructors ──────────────────────────────────────────────────────────

    protected SonarFinding() {}

    public SonarFinding(PipelineRun run, String sonarIssueKey, String ruleKey,
                        String severity, String component, Integer line, String message) {
        this.run = run;
        this.sonarIssueKey = sonarIssueKey;
        this.ruleKey = ruleKey;
        this.severity = severity;
        this.component = component;
        this.line = line;
        this.message = message;
    }

    // ── Getters and setters ───────────────────────────────────────────────────

    public Long getId() { return id; }

    public PipelineRun getRun() { return run; }

    public String getSonarIssueKey() { return sonarIssueKey; }

    public String getRuleKey() { return ruleKey; }

    public String getSeverity() { return severity; }

    public String getComponent() { return component; }

    public Integer getLine() { return line; }

    public String getMessage() { return message; }

    public FindingPipelineStatus getPipelineStatus() { return pipelineStatus; }
    public void setPipelineStatus(FindingPipelineStatus pipelineStatus) {
        this.pipelineStatus = pipelineStatus;
    }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}

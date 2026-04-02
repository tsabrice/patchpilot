package ca.uqam.patchpilot.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Records what Claude produced for a given finding.
 *
 * Immutable after creation — the fix content never changes once generated.
 * Separated from GithubPr because they have different lifecycles:
 * a generation can exist without a PR (if the GitHub API call failed after
 * Claude succeeded). Splitting them lets each fail independently.
 *
 * The actual patched file text lives in AiGenerationContent (separate table)
 * to keep this table's rows small for fast aggregate queries.
 */
@Entity
@Table(name = "ai_generations")
public class AiGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finding_id", nullable = false)
    private SonarFinding finding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private PipelineRun run;

    // The Claude model string used for this generation.
    // Stored so older records remain interpretable after a model upgrade.
    @Column(name = "model_used", nullable = false, length = 64)
    private String modelUsed = "claude-haiku-4-5-20251001";

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    // Claude-assigned confidence score, 0.000–1.000.
    // Used by the dashboard to surface low-confidence fixes for extra review.
    @Column(name = "confidence_score", precision = 4, scale = 3)
    private BigDecimal confidenceScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiGenerationStatus status = AiGenerationStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // ── Constructors ──────────────────────────────────────────────────────────

    protected AiGeneration() {}

    public AiGeneration(SonarFinding finding, PipelineRun run) {
        this.finding = finding;
        this.run = run;
    }

    // ── Getters and setters ───────────────────────────────────────────────────

    public Long getId() { return id; }

    public SonarFinding getFinding() { return finding; }

    public PipelineRun getRun() { return run; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(BigDecimal confidenceScore) { this.confidenceScore = confidenceScore; }

    public AiGenerationStatus getStatus() { return status; }
    public void setStatus(AiGenerationStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}

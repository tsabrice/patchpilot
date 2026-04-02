package ca.uqam.patchpilot.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * The GitHub PR created from an AiGeneration.
 *
 * Mutable — status changes as reviewers interact with the PR
 * (PENDING → PR_OPEN → PR_MERGED or PR_CLOSED).
 *
 * Branch names follow the pattern: "patchpilot/fix-{sonar_issue_key}"
 * This makes branch creation idempotent: if GitHub returns 409 (branch exists),
 * the task skips branch creation and proceeds directly to the file update.
 *
 * PR titles and bodies are stored in both English and French.
 * French is the differentiating factor for this portfolio project.
 */
@Entity
@Table(name = "github_prs")
public class GithubPr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generation_id", nullable = false)
    private AiGeneration generation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finding_id", nullable = false)
    private SonarFinding finding;

    // "patchpilot/fix-{sonar_issue_key}" — unique per finding
    @Column(name = "branch_name", nullable = false)
    private String branchName;

    // GitHub PR number (null until PR is successfully created)
    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url")
    private String prUrl;

    @Column(name = "pr_title_en")
    private String prTitleEn;

    @Column(name = "pr_title_fr")
    private String prTitleFr;

    @Column(name = "pr_body_en")
    private String prBodyEn;

    @Column(name = "pr_body_fr")
    private String prBodyFr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GithubPrStatus status = GithubPrStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // ── Constructors ──────────────────────────────────────────────────────────

    protected GithubPr() {}

    public GithubPr(AiGeneration generation, SonarFinding finding, String branchName) {
        this.generation = generation;
        this.finding = finding;
        this.branchName = branchName;
    }

    // ── Getters and setters ───────────────────────────────────────────────────

    public Long getId() { return id; }

    public AiGeneration getGeneration() { return generation; }

    public SonarFinding getFinding() { return finding; }

    public String getBranchName() { return branchName; }

    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }

    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }

    public String getPrTitleEn() { return prTitleEn; }
    public void setPrTitleEn(String prTitleEn) { this.prTitleEn = prTitleEn; }

    public String getPrTitleFr() { return prTitleFr; }
    public void setPrTitleFr(String prTitleFr) { this.prTitleFr = prTitleFr; }

    public String getPrBodyEn() { return prBodyEn; }
    public void setPrBodyEn(String prBodyEn) { this.prBodyEn = prBodyEn; }

    public String getPrBodyFr() { return prBodyFr; }
    public void setPrBodyFr(String prBodyFr) { this.prBodyFr = prBodyFr; }

    public GithubPrStatus getStatus() { return status; }
    public void setStatus(GithubPrStatus status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}

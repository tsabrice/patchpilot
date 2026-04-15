package ca.uqam.patchpilot.api;

import ca.uqam.patchpilot.persistence.FindingPipelineStatus;
import ca.uqam.patchpilot.persistence.GithubPrStatus;

import java.math.BigDecimal;

/**
 * One row in the findings table on the run detail page.
 *
 * Nullable fields (confidenceScore, prNumber, prUrl, prStatus, prTitleEn) are
 * null when the pipeline has not yet reached that stage for this finding —
 * e.g. Claude hasn't run yet, or the GitHub API call failed before PR creation.
 */
public record FindingDetailDto(
        Long id,
        String sonarIssueKey,
        String ruleKey,
        String severity,        // BLOCKER | CRITICAL | MAJOR | MINOR | INFO
        String component,       // file path within the project
        Integer line,           // null for file-level issues
        String message,
        FindingPipelineStatus pipelineStatus,
        BigDecimal confidenceScore, // null until Claude generation succeeds
        Integer prNumber,           // null until PR is created on GitHub
        String prUrl,
        GithubPrStatus prStatus,    // null until PR is created
        String prTitleEn            // English PR title for display
) {}

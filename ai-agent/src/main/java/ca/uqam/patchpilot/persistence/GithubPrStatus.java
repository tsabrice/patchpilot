package ca.uqam.patchpilot.persistence;

public enum GithubPrStatus {
    PENDING,   // PR not yet created
    PR_OPEN,   // PR successfully opened on GitHub
    PR_MERGED, // PR was reviewed and merged
    PR_CLOSED, // PR was closed without merging
    FAILED     // GitHub API call failed
}

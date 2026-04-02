package ca.uqam.patchpilot.persistence;

public enum PipelineRunStatus {
    PENDING,      // run record created, findings not yet fetched from SonarQube
    IN_PROGRESS,  // at least one finding task is running
    COMPLETED,    // all finding tasks finished (some may have FAILED individually)
    FAILED        // run-level failure before any finding was processed
}

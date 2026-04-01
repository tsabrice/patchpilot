package ca.uqam.patchpilot.persistence;

public enum FindingPipelineStatus {
    QUEUED,         // persisted, waiting for an @Async task to pick it up
    FETCHING_FILE,  // fetching file content from GitHub
    CALLING_CLAUDE, // waiting for Claude to return the fix
    CREATING_PR,    // creating branch + committing fix + opening PR on GitHub
    COMPLETED,      // PR opened successfully — terminal success state
    FAILED,         // unrecoverable error — terminal failure state
    SKIPPED         // finding skipped (e.g. file not found on GitHub)
}

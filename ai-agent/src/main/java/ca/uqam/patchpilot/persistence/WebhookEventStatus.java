package ca.uqam.patchpilot.persistence;

public enum WebhookEventStatus {
    PENDING,     // received, not yet picked up by the poller
    PROCESSING,  // poller is actively working on it
    DONE,        // all findings dispatched successfully
    FAILED       // processing threw an unrecoverable error
}

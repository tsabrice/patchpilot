package ca.uqam.patchpilot.persistence;

public enum AiGenerationStatus {
    PENDING,     // task created, Claude not yet called
    GENERATING,  // Claude API call in flight
    COMPLETED,   // fix received and persisted to ai_generation_content
    FAILED       // Claude call failed or returned unusable output
}

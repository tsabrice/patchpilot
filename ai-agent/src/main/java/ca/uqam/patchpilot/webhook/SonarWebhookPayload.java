package ca.uqam.patchpilot.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Jackson deserialisation target for the SonarQube webhook POST body.
 *
 * SonarQube fires one webhook per analysis task with this shape:
 * {
 *   "taskId": "AXo...",
 *   "status": "SUCCESS",   // or "FAILED"
 *   "revision": "abc123",  // git commit SHA
 *   "project": { "key": "demo-app", "name": "Demo App" },
 *   "branch":  { "name": "main" }
 * }
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) — SonarQube adds new fields
 * across versions (e.g. qualityGate, properties). Ignoring unknowns prevents
 * deserialization failures when the SonarQube version is upgraded.
 *
 * Jackson 2.14+ handles Java records natively — no @JsonDeserialize needed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SonarWebhookPayload(
        String taskId,
        String status,
        String revision,
        Project project,
        Branch branch
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(String key, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Branch(String name) {}

    /**
     * SonarQube only fires webhooks on SUCCESS or FAILED task completion.
     * We only start the fix pipeline on SUCCESS — a failed analysis has
     * incomplete findings and is not worth attempting to fix.
     */
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    public String projectKey() {
        return project != null ? project.key() : null;
    }

    public String branchName() {
        return branch != null ? branch.name() : "main";
    }
}

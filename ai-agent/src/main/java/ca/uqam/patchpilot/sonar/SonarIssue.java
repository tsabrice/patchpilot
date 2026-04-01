package ca.uqam.patchpilot.sonar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One issue from GET /api/issues/search.
 *
 * The "component" field from SonarQube is "{projectKey}:{filePath}",
 * e.g. "demo-app:src/main/java/ca/uqam/patchpilot/demo/UserController.java".
 * Use filePath(projectKey) to strip the prefix — that is the path the
 * GitHub Contents API expects.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SonarIssue(
        String key,        // SonarQube's stable issue identifier — used as idempotency key
        String rule,       // e.g. "java:S2077" (SQL injection)
        String severity,   // BLOCKER | CRITICAL | MAJOR | MINOR | INFO
        String component,  // "demo-app:src/main/java/.../UserService.java"
        Integer line,      // nullable for file-level issues
        String message     // human-readable description of the finding
) {
    /**
     * Strips the SonarQube project key prefix to get the bare file path.
     * "demo-app:src/main/java/..." → "src/main/java/..."
     */
    public String filePath(String projectKey) {
        var prefix = projectKey + ":";
        return component != null && component.startsWith(prefix)
                ? component.substring(prefix.length())
                : component;
    }
}

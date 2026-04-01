package ca.uqam.patchpilot.claude;

import ca.uqam.patchpilot.persistence.SonarFinding;

/**
 * Abstraction over the two Claude integration modes.
 *
 * Why an interface with two implementations instead of one class with an if/else?
 * Spring's @ConditionalOnProperty selects exactly one bean at startup based on
 * the CLAUDE_MODE env var. FindingFixService depends on this interface — it never
 * knows which implementation it's talking to. Swapping modes is a config change,
 * not a code change.
 *
 * Implementations:
 *   ClaudeApiClient  — active when CLAUDE_MODE=api  (Azure, production)
 *   ClaudeCliClient  — active when CLAUDE_MODE=max  (local dev, default)
 *
 * Trade-off: two implementations to maintain. Accepted because the two modes
 * have fundamentally different mechanics (HTTP vs process execution) and
 * different environments — conflating them in one class would add noise.
 */
public interface ClaudeClient {

    /**
     * Generates a security fix for a single SonarQube finding.
     *
     * @param finding     the finding row — provides rule key, severity, message,
     *                    file path, and line number for the prompt
     * @param fileContent the raw Java source file the finding was detected in
     * @return            structured fix result including patched file and bilingual PR copy
     * @throws RuntimeException if Claude is unavailable or returns unparseable output
     */
    FixResult generateFix(SonarFinding finding, String fileContent);
}

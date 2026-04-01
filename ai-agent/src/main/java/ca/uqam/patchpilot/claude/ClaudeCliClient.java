package ca.uqam.patchpilot.claude;

import ca.uqam.patchpilot.persistence.SonarFinding;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Generates a security fix by shelling out to the Claude CLI.
 *
 * Active when CLAUDE_MODE=max (the default for local development).
 * Uses the Claude Max subscription authenticated on the host — no API key needed.
 *
 * Why shell out instead of HTTP? The Claude CLI handles authentication and model
 * routing transparently via the Max subscription. For local dev this avoids
 * burning API credits; for Azure we switch to ClaudeApiClient.
 *
 * How it works:
 *   1. Build the prompt (same structure as ClaudeApiClient)
 *   2. Start `claude --print` as a subprocess via ProcessBuilder
 *   3. Write the prompt to the process stdin (avoids ARG_MAX limits and
 *      shell injection — ProcessBuilder arg lists never go through a shell)
 *   4. Read stdout for Claude's response, parse the JSON object from it
 *
 * Limitations vs ClaudeApiClient:
 *   - No token counts (CLI does not report them) — stored as 0
 *   - No circuit breaker (local dev only — fail fast is fine)
 *   - Requires `claude` on PATH and an active Max session
 *
 * Prerequisite: `claude` CLI must be installed and authenticated.
 * Run `claude --version` to verify. Auth: `claude auth login`.
 */
@Service
@ConditionalOnProperty(name = "claude.mode", havingValue = "max", matchIfMissing = true)
public class ClaudeCliClient implements ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliClient.class);

    // 90 s covers large files; the HTTP API client has a 30 s timeout via
    // Resilience4j TimeLimiter, but the CLI can be slower on first invocation.
    private static final int TIMEOUT_SECONDS = 90;

    private final ObjectMapper objectMapper;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeJsonOutput(
            String patched_file,
            String title_en,
            String title_fr,
            String body_en,
            String body_fr,
            double confidence
    ) {}

    public ClaudeCliClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public FixResult generateFix(SonarFinding finding, String fileContent) {
        log.info("Calling Claude CLI: rule={} findingId={}", finding.getRuleKey(), finding.getId());

        var prompt = buildPrompt(finding, fileContent);
        var rawText = invokeCli(prompt, finding.getId());
        var parsed = parseClaudeJson(rawText, finding.getId());

        log.info("Claude CLI fix generated: findingId={} confidence={}",
                finding.getId(), parsed.confidence());

        // Token counts are not available from the CLI — stored as 0.
        return new FixResult(
                parsed.patched_file(),
                parsed.title_en(),
                parsed.title_fr(),
                parsed.body_en(),
                parsed.body_fr(),
                parsed.confidence(),
                0,
                0
        );
    }

    // ── CLI invocation ────────────────────────────────────────────────────────

    private String invokeCli(String prompt, Long findingId) {
        Process process;
        try {
            // `claude --print` reads the prompt from stdin and writes the
            // response to stdout. ProcessBuilder takes a list of args — no
            // shell involved, so no escaping and no injection risk.
            process = new ProcessBuilder("claude", "--print")
                    .redirectErrorStream(true)  // merge stderr into stdout for logging
                    .start();
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to start claude CLI — is it installed and on PATH? " +
                    "Run: claude --version", e);
        }

        // Write the prompt to stdin, then close to signal EOF.
        try (var stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write prompt to claude CLI stdin", e);
        }

        String output;
        try {
            output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read claude CLI stdout", e);
        }

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Claude CLI interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException(
                    "Claude CLI timed out after " + TIMEOUT_SECONDS + " s — findingId=" + findingId);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Claude CLI exited with code " + exitCode + " — findingId=" + findingId
                    + " output=" + output.substring(0, Math.min(300, output.length())));
        }

        log.debug("Claude CLI response length: {} chars", output.length());
        return output;
    }

    // ── Prompt construction (same structure as ClaudeApiClient) ──────────────

    private String buildPrompt(SonarFinding finding, String fileContent) {
        return """
                You are a Java security expert specialised in fixing SonarQube findings.
                Your task is to produce a corrected version of a Java source file.

                Rules:
                - Fix ONLY the reported finding. Do not refactor unrelated code.
                - Return the COMPLETE corrected file, not a diff or a snippet.
                - Do not add comments explaining the change unless they clarify non-obvious security decisions.
                - Respond with ONLY a JSON object — no markdown fences, no preamble, no explanation outside the JSON.

                The JSON must have exactly these fields:
                {
                  "patched_file": "<complete corrected Java source>",
                  "title_en": "<concise PR title in English, max 72 chars>",
                  "title_fr": "<concise PR title in French, max 72 chars>",
                  "body_en": "<PR description in English Markdown explaining what was fixed and why>",
                  "body_fr": "<même description en Markdown français>",
                  "confidence": <float 0.0-1.0, your confidence the fix is correct and complete>
                }

                ## Finding details
                - Rule:     %s
                - Severity: %s
                - Message:  %s
                - File:     %s
                - Line:     %s

                ## Source file
                ```java
                %s
                ```
                """.formatted(
                finding.getRuleKey(),
                finding.getSeverity(),
                finding.getMessage(),
                finding.getComponent(),
                finding.getLine() != null ? finding.getLine() : "N/A",
                fileContent
        );
    }

    // ── JSON parsing (same logic as ClaudeApiClient) ──────────────────────────

    private ClaudeJsonOutput parseClaudeJson(String rawText, Long findingId) {
        var start = rawText.indexOf('{');
        var end = rawText.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            throw new RuntimeException(
                    "Claude CLI response contains no JSON object — findingId=" + findingId
                    + " response=" + rawText.substring(0, Math.min(200, rawText.length())));
        }
        var json = rawText.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, ClaudeJsonOutput.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to parse Claude CLI JSON — findingId=" + findingId, e);
        }
    }
}

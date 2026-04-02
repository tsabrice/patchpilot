package ca.uqam.patchpilot.claude;

import ca.uqam.patchpilot.persistence.SonarFinding;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Calls the Anthropic Messages API to generate a security fix.
 *
 * Active when CLAUDE_MODE=api — used in Azure where the Claude CLI is not
 * available. Uses claude.model (default: claude-haiku-4-5-20251001) which
 * can be overridden via the CLAUDE_MODEL env var without touching code.
 *
 * Why @ConditionalOnProperty? Spring registers exactly one ClaudeClient bean.
 * This one activates on havingValue="api"; ClaudeCliClient activates otherwise.
 * FindingFixService depends on ClaudeClient — it never knows which is active.
 *
 * Circuit breaker: after 5 failures in a 10-call window the circuit opens for
 * 30 s. Configured in application.yml under resilience4j.circuitbreaker.
 * Circuit state is a Micrometer metric — visible in Grafana.
 *
 * API docs: https://docs.anthropic.com/en/api/messages
 * Model list: https://docs.anthropic.com/en/docs/about-claude/models
 */
@Service
@ConditionalOnProperty(name = "claude.mode", havingValue = "api")
public class ClaudeApiClient implements ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeApiClient.class);

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    // ── Anthropic Messages API request / response shapes ─────────────────────

    private record MessagesRequest(
            String model,
            int max_tokens,
            String system,
            List<Message> messages
    ) {}

    private record Message(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessagesResponse(List<ContentBlock> content, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContentBlock(String type, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(int input_tokens, int output_tokens) {}

    // ── Claude's structured JSON output (embedded in the response text) ───────

    /**
     * The JSON object we ask Claude to return inside its text response.
     * Fields map directly to FixResult — Claude fills all of them.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeJsonOutput(
            String patched_file,
            String title_en,
            String title_fr,
            String body_en,
            String body_fr,
            double confidence
    ) {}

    // ── Constructor ───────────────────────────────────────────────────────────

    public ClaudeApiClient(
            RestClient.Builder builder,
            @Value("${claude.api-key}") String apiKey,
            @Value("${claude.model}") String model,
            ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.restClient = builder
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
    }

    // ── ClaudeClient implementation ───────────────────────────────────────────

    @Override
    @CircuitBreaker(name = "claude-api", fallbackMethod = "generateFixFallback")
    public FixResult generateFix(SonarFinding finding, String fileContent) {
        log.info("Calling Claude API: model={} rule={} findingId={}",
                model, finding.getRuleKey(), finding.getId());

        var systemPrompt = buildSystemPrompt();
        var userPrompt = buildUserPrompt(finding, fileContent);

        var request = new MessagesRequest(
                model,
                4096,        // max output tokens — a full Java file fits comfortably
                systemPrompt,
                List.of(new Message("user", userPrompt))
        );

        var response = restClient.post()
                .uri("/v1/messages")
                .body(request)
                .retrieve()
                .body(MessagesResponse.class);

        // content[0].text contains Claude's full text response
        var rawText = response.content().getFirst().text();
        log.debug("Claude raw response length: {} chars", rawText.length());

        var parsed = parseClaudeJson(rawText, finding.getId());

        log.info("Claude fix generated: findingId={} confidence={} inputTokens={} outputTokens={}",
                finding.getId(), parsed.confidence(),
                response.usage().input_tokens(), response.usage().output_tokens());

        return new FixResult(
                parsed.patched_file(),
                parsed.title_en(),
                parsed.title_fr(),
                parsed.body_en(),
                parsed.body_fr(),
                parsed.confidence(),
                response.usage().input_tokens(),
                response.usage().output_tokens()
        );
    }

    public FixResult generateFixFallback(SonarFinding finding, String fileContent, Throwable t) {
        log.error("Claude circuit breaker open — generateFix failed: findingId={}",
                finding.getId(), t);
        throw new RuntimeException("Claude API unavailable (circuit breaker open)", t);
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private String buildSystemPrompt() {
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
                """;
    }

    private String buildUserPrompt(SonarFinding finding, String fileContent) {
        return """
                Fix the following SonarQube finding in the Java file below.

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

    // ── JSON parsing ──────────────────────────────────────────────────────────

    /**
     * Extracts the JSON object from Claude's text response.
     *
     * Claude sometimes adds preamble or markdown fences despite instructions.
     * We find the outermost { ... } and parse that substring, which is robust
     * to any leading/trailing text.
     */
    private ClaudeJsonOutput parseClaudeJson(String rawText, Long findingId) {
        var start = rawText.indexOf('{');
        var end = rawText.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            throw new RuntimeException(
                    "Claude response contains no JSON object — findingId=" + findingId
                    + " response=" + rawText.substring(0, Math.min(200, rawText.length())));
        }
        var json = rawText.substring(start, end + 1);
        try {
            return objectMapper.readValue(json, ClaudeJsonOutput.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to parse Claude JSON — findingId=" + findingId, e);
        }
    }
}

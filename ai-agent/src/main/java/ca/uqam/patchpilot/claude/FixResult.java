package ca.uqam.patchpilot.claude;

/**
 * The structured output both ClaudeClient implementations must return.
 *
 * Why a record? Records are immutable value objects — perfect for data
 * flowing out of an external API call that should never be mutated.
 *
 * patchedContent  — the full fixed Java file (not a diff; GitHub's PUT
 *                   endpoint replaces the whole file, so we send the whole file)
 * titleEn/titleFr — bilingual PR title; French is a differentiator for this project
 * bodyEn/bodyFr   — bilingual PR body in Markdown
 * confidence      — Claude's self-assessed fix confidence, 0.0–1.0;
 *                   stored in ai_generations.confidence_score for the dashboard
 * promptTokens    — input tokens billed; 0 when using the CLI (not reported)
 * completionTokens — output tokens billed; 0 when using the CLI
 */
public record FixResult(
        String patchedContent,
        String titleEn,
        String titleFr,
        String bodyEn,
        String bodyFr,
        double confidence,
        int promptTokens,
        int completionTokens
) {}

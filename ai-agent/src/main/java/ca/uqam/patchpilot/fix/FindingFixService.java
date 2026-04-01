package ca.uqam.patchpilot.fix;

import ca.uqam.patchpilot.claude.ClaudeClient;
import ca.uqam.patchpilot.github.GitHubApiClient;
import ca.uqam.patchpilot.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Handles the fix work for a single SonarQube finding.
 *
 * Why a separate bean from FixPipelineService?
 * @Async does not work on self-calls — a method calling another @Async method
 * on the same bean bypasses the Spring proxy and runs synchronously on the
 * same thread. FixPipelineService dispatches per-finding tasks by calling
 * this bean, so every call goes through the proxy and lands on its own thread.
 *
 * Transaction strategy: no @Transactional on fixFindingAsync itself.
 * Holding a transaction open across GitHub API calls (which can take seconds)
 * would lock DB rows for too long. Instead:
 *  - findByIdWithRun() uses JOIN FETCH to load the PipelineRun eagerly in its
 *    own short repository transaction.
 *  - Each save() call on a Spring Data repository opens and closes its own
 *    transaction (SimpleJpaRepository.save() is @Transactional by default).
 *  - GitHub API calls run outside any transaction.
 *
 * Day 11: fetch source file from GitHub + open PR (with original content as stub)
 * Day 12: call ClaudeClient to generate a real fix before opening the PR
 */
@Service
public class FindingFixService {

    private static final Logger log = LoggerFactory.getLogger(FindingFixService.class);

    private final SonarFindingRepository sonarFindingRepository;
    private final PipelineRunEventRepository pipelineRunEventRepository;
    private final AiGenerationRepository aiGenerationRepository;
    private final AiGenerationContentRepository aiGenerationContentRepository;
    private final GithubPrRepository githubPrRepository;
    private final GitHubApiClient gitHubApiClient;
    private final ClaudeClient claudeClient;

    public FindingFixService(SonarFindingRepository sonarFindingRepository,
                             PipelineRunEventRepository pipelineRunEventRepository,
                             AiGenerationRepository aiGenerationRepository,
                             AiGenerationContentRepository aiGenerationContentRepository,
                             GithubPrRepository githubPrRepository,
                             GitHubApiClient gitHubApiClient,
                             ClaudeClient claudeClient) {
        this.sonarFindingRepository = sonarFindingRepository;
        this.pipelineRunEventRepository = pipelineRunEventRepository;
        this.aiGenerationRepository = aiGenerationRepository;
        this.aiGenerationContentRepository = aiGenerationContentRepository;
        this.githubPrRepository = githubPrRepository;
        this.gitHubApiClient = gitHubApiClient;
        this.claudeClient = claudeClient;
    }

    /**
     * Entry point for per-finding fix work. Called by FixPipelineService after
     * the transaction that persisted the SonarFinding row has committed.
     *
     * @Async runs this on Spring's task executor — one thread per finding,
     * all findings in a run run concurrently. A failure here only affects
     * this one finding; other findings proceed independently.
     */
    @Async
    public void fixFindingAsync(Long findingId) {
        log.info("Fix task started — findingId={}", findingId);

        // JOIN FETCH loads the parent PipelineRun eagerly in one query.
        // This avoids LazyInitializationException when reading run fields
        // (projectKey, branch) later, outside any open transaction.
        var finding = sonarFindingRepository.findByIdWithRun(findingId).orElse(null);
        if (finding == null) {
            log.warn("Finding not found — skipping: findingId={}", findingId);
            return;
        }

        // Guard against double-processing. The crash-recovery poller may
        // re-dispatch a task for a finding that another thread is already
        // handling. Non-QUEUED status means: skip silently.
        if (finding.getPipelineStatus() != FindingPipelineStatus.QUEUED) {
            log.info("Finding is not QUEUED (status={}) — skipping: findingId={}",
                    finding.getPipelineStatus(), findingId);
            return;
        }

        var run = finding.getRun();          // safe — loaded via JOIN FETCH
        var projectKey = run.getProjectKey();
        var branch = run.getBranch();

        // SonarQube stores the component as "{projectKey}:{filePath}".
        // Strip the prefix to get the path the GitHub Contents API expects.
        var filePath = stripProjectKeyPrefix(finding.getComponent(), projectKey);

        try {
            // ── Phase 1: fetch source file ────────────────────────────────────
            setStatus(finding, FindingPipelineStatus.FETCHING_FILE);

            var fileContent = gitHubApiClient.getFileContent(filePath, branch);

            if (fileContent == null) {
                // File missing on GitHub — can happen with generated files or
                // files deleted between the SonarQube scan and our fetch.
                log.warn("Source file not found on GitHub — skipping: path={} findingId={}",
                        filePath, findingId);
                setStatus(finding, FindingPipelineStatus.SKIPPED);
                appendEvent(run, findingId, "FINDING_SKIPPED",
                        "{\"reason\":\"file_not_found\",\"path\":\"%s\"}".formatted(filePath));
                return;
            }

            appendEvent(run, findingId, "FILE_FETCHED",
                    "{\"path\":\"%s\"}".formatted(filePath));

            // ── Phase 2: generate fix (Claude) ───────────────────────────────
            setStatus(finding, FindingPipelineStatus.CALLING_CLAUDE);

            // Create the AiGeneration row before calling Claude so we have a
            // PK to reference. Status GENERATING while the call is in flight.
            var generation = new AiGeneration(finding, run);
            generation.setStatus(AiGenerationStatus.GENERATING);
            aiGenerationRepository.save(generation);

            // ClaudeClient is either ClaudeApiClient (CLAUDE_MODE=api) or
            // ClaudeCliClient (CLAUDE_MODE=max). Both return the same FixResult.
            var fixResult = claudeClient.generateFix(finding, fileContent.content());

            // Update the generation row with token counts and confidence score.
            generation.setStatus(AiGenerationStatus.COMPLETED);
            generation.setPromptTokens(fixResult.promptTokens());
            generation.setCompletionTokens(fixResult.completionTokens());
            generation.setConfidenceScore(
                    BigDecimal.valueOf(fixResult.confidence()).setScale(3, RoundingMode.HALF_UP));
            aiGenerationRepository.save(generation);

            // Persist the patched file in the vertical partition table.
            // Kept separate from ai_generations to avoid bloating the hot query path.
            aiGenerationContentRepository.save(
                    new AiGenerationContent(generation, fixResult.patchedContent()));

            appendEvent(run, findingId, "CLAUDE_CALLED",
                    "{\"promptTokens\":%d,\"completionTokens\":%d,\"confidence\":%.3f}"
                            .formatted(fixResult.promptTokens(), fixResult.completionTokens(),
                                    fixResult.confidence()));

            // ── Phase 3: create branch, commit fix, open PR ───────────────────
            setStatus(finding, FindingPipelineStatus.CREATING_PR);

            // Branch name is deterministic per finding — if a previous attempt
            // already created it, createBranch() silently continues (422 → skip).
            var branchName = "patchpilot/fix-" + finding.getSonarIssueKey();
            gitHubApiClient.createBranch(branchName, branch);

            appendEvent(run, findingId, "BRANCH_CREATED",
                    "{\"branch\":\"%s\"}".formatted(branchName));

            var commitMessage = "fix: address %s in %s [PatchPilot]"
                    .formatted(finding.getRuleKey(), filePath);
            gitHubApiClient.updateFile(
                    filePath, fixResult.patchedContent(), fileContent.sha(), branchName, commitMessage);

            // Use Claude's bilingual titles and bodies for the PR.
            var titleEn = fixResult.titleEn();
            var titleFr = fixResult.titleFr();
            var bodyEn = fixResult.bodyEn();
            var bodyFr = fixResult.bodyFr();

            var createdPr = gitHubApiClient.createPullRequest(
                    branchName, branch, titleEn, titleFr, bodyEn, bodyFr);

            appendEvent(run, findingId, "PR_OPENED",
                    "{\"prNumber\":%d,\"prUrl\":\"%s\"}"
                            .formatted(createdPr.prNumber(), createdPr.prUrl()));

            // ── Persist the PR record ─────────────────────────────────────────
            var githubPr = new GithubPr(generation, finding, branchName);
            githubPr.setPrNumber(createdPr.prNumber());
            githubPr.setPrUrl(createdPr.prUrl());
            githubPr.setPrTitleEn(titleEn);
            githubPr.setPrTitleFr(titleFr);
            githubPr.setPrBodyEn(bodyEn);
            githubPr.setPrBodyFr(bodyFr);
            githubPr.setStatus(GithubPrStatus.PR_OPEN);
            githubPrRepository.save(githubPr);

            // ── Done ──────────────────────────────────────────────────────────
            setStatus(finding, FindingPipelineStatus.COMPLETED);
            appendEvent(run, findingId, "FINDING_COMPLETED",
                    "{\"prNumber\":%d,\"prUrl\":\"%s\"}"
                            .formatted(createdPr.prNumber(), createdPr.prUrl()));

            log.info("Fix task completed — findingId={} PR=#{} url={}",
                    findingId, createdPr.prNumber(), createdPr.prUrl());

        } catch (Exception e) {
            log.error("Fix task failed — findingId={}", findingId, e);
            // Write the failure status and an audit event before giving up.
            // The finding stays in FAILED — the crash-recovery poller will NOT
            // re-queue FAILED findings (only non-terminal ones).
            finding.setPipelineStatus(FindingPipelineStatus.FAILED);
            sonarFindingRepository.save(finding);
            appendEvent(run, finding.getId(), "FINDING_FAILED",
                    "{\"error\":\"%s\"}"
                            .formatted(sanitiseForJson(e.getMessage())));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Updates pipelineStatus and immediately persists it. */
    private void setStatus(SonarFinding finding, FindingPipelineStatus status) {
        finding.setPipelineStatus(status);
        sonarFindingRepository.save(finding);
        log.debug("Finding {} → {}", finding.getId(), status);
    }

    /** Appends one row to the append-only pipeline_run_events audit log. */
    private void appendEvent(PipelineRun run, Long findingId,
                             String eventType, String payload) {
        pipelineRunEventRepository.save(
                new PipelineRunEvent(run, findingId, eventType, payload));
    }

    /**
     * Strips the SonarQube project key prefix from a component string.
     * "demo-app:src/main/java/Foo.java" → "src/main/java/Foo.java"
     */
    private String stripProjectKeyPrefix(String component, String projectKey) {
        var prefix = projectKey + ":";
        return component != null && component.startsWith(prefix)
                ? component.substring(prefix.length())
                : component;
    }

    /** Escapes double quotes in error messages so they embed safely in JSON. */
    private String sanitiseForJson(String message) {
        return message != null ? message.replace("\"", "'") : "unknown";
    }
}

package ca.uqam.patchpilot.fix;

import ca.uqam.patchpilot.persistence.*;
import ca.uqam.patchpilot.sonar.SonarIssue;
import ca.uqam.patchpilot.sonar.SonarQubeApiClient;
import ca.uqam.patchpilot.webhook.SonarWebhookPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Orchestrates the pipeline for one SonarQube analysis event:
 *   1. Load the webhook_event row and parse its JSON payload
 *   2. Create a PipelineRun and mark it IN_PROGRESS
 *   3. Call SonarQube API to fetch all open findings
 *   4. Persist one SonarFinding row per issue
 *   5. Dispatch one FindingFixService.fixFindingAsync() task per finding
 *      (dispatched after commit so the findings are visible in the DB)
 *
 * Both the webhook controller (live path) and the crash-recovery poller call
 * processWebhookEventAsync() — it must be safe to call twice for the same taskId.
 */
@Service
public class FixPipelineService {

    private static final Logger log = LoggerFactory.getLogger(FixPipelineService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineRunEventRepository pipelineRunEventRepository;
    private final SonarFindingRepository sonarFindingRepository;
    private final SonarQubeApiClient sonarApiClient;
    private final FindingFixService findingFixService;
    private final ObjectMapper objectMapper;

    public FixPipelineService(WebhookEventRepository webhookEventRepository,
                              PipelineRunRepository pipelineRunRepository,
                              PipelineRunEventRepository pipelineRunEventRepository,
                              SonarFindingRepository sonarFindingRepository,
                              SonarQubeApiClient sonarApiClient,
                              FindingFixService findingFixService,
                              ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineRunEventRepository = pipelineRunEventRepository;
        this.sonarFindingRepository = sonarFindingRepository;
        this.sonarApiClient = sonarApiClient;
        this.findingFixService = findingFixService;
        this.objectMapper = objectMapper;
    }

    /**
     * Main entry point. Called by WebhookController and WebhookEventPoller.
     *
     * @Async — runs on a background thread. The HTTP request thread (and
     * therefore SonarQube) gets its 200 response before this starts.
     *
     * @Transactional — wraps all DB writes in one transaction. The transaction
     * commits when this method returns. Per-finding tasks are dispatched via
     * TransactionSynchronization.afterCommit() so they only start after the
     * findings are visible in the DB.
     *
     * Why @Async + @Transactional on the same method works:
     * The @Async proxy runs first (submits to executor), then on the background
     * thread, the @Transactional proxy wraps the method execution normally.
     */
    @Async
    @Transactional
    public void processWebhookEventAsync(String sonarTaskId) {

        // ── Double-processing guard ───────────────────────────────────────────
        // If a PipelineRun already exists for this taskId, we are being called
        // twice (poller + controller race, or crash restart after run was created).
        // All downstream work is idempotent but there is no reason to redo it.
        if (pipelineRunRepository.findBySonarTaskId(sonarTaskId).isPresent()) {
            log.info("PipelineRun already exists for taskId={} — skipping duplicate dispatch",
                    sonarTaskId);
            return;
        }

        // ── Load the webhook event ────────────────────────────────────────────
        var webhookEvent = webhookEventRepository.findBySonarTaskId(sonarTaskId)
                .orElseThrow(() -> new IllegalStateException(
                        "WebhookEvent not found for taskId=" + sonarTaskId));

        // ── Parse the project key from the stored raw payload ─────────────────
        SonarWebhookPayload payload;
        try {
            payload = objectMapper.readValue(webhookEvent.getRawPayload(), SonarWebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse raw webhook payload for taskId={}", sonarTaskId, e);
            webhookEvent.setStatus(WebhookEventStatus.FAILED);
            return;
        }

        // ── Create the pipeline run ───────────────────────────────────────────
        webhookEvent.setStatus(WebhookEventStatus.PROCESSING);

        var run = pipelineRunRepository.save(
                new PipelineRun(webhookEvent, sonarTaskId,
                        payload.projectKey(), payload.branchName(), payload.revision()));
        run.setStatus(PipelineRunStatus.IN_PROGRESS);
        // Hibernate dirty-checks the managed entity — no explicit save() needed for status update

        pipelineRunEventRepository.save(new PipelineRunEvent(run, "PIPELINE_STARTED"));
        log.info("Pipeline run created — runId={} project={} branch={}",
                run.getId(), payload.projectKey(), payload.branchName());

        // ── Fetch findings from SonarQube ─────────────────────────────────────
        List<SonarIssue> issues;
        try {
            issues = sonarApiClient.getFindings(payload.projectKey());
        } catch (Exception e) {
            log.error("Failed to fetch findings from SonarQube for runId={}", run.getId(), e);
            run.setStatus(PipelineRunStatus.FAILED);
            pipelineRunEventRepository.save(new PipelineRunEvent(run, "PIPELINE_FAILED"));
            webhookEvent.setStatus(WebhookEventStatus.FAILED);
            return;
        }

        // ── Handle empty scans ────────────────────────────────────────────────
        if (issues.isEmpty()) {
            log.info("No open findings for project '{}' — run completed immediately",
                    payload.projectKey());
            run.setStatus(PipelineRunStatus.COMPLETED);
            pipelineRunEventRepository.save(new PipelineRunEvent(run, "PIPELINE_COMPLETED"));
            webhookEvent.setStatus(WebhookEventStatus.DONE);
            webhookEvent.setProcessedAt(OffsetDateTime.now());
            return;
        }

        // ── Persist one SonarFinding row per issue ────────────────────────────
        // saveAll issues a single batch INSERT — more efficient than individual saves.
        var findings = sonarFindingRepository.saveAll(
                issues.stream()
                        .map(issue -> new SonarFinding(
                                run,
                                issue.key(),
                                issue.rule(),
                                issue.severity(),
                                issue.component(),   // stored as-is; filePath() strips prefix on use
                                issue.line(),
                                issue.message()))
                        .toList());

        log.info("Persisted {} finding(s) for runId={}", findings.size(), run.getId());

        webhookEvent.setStatus(WebhookEventStatus.DONE);
        webhookEvent.setProcessedAt(OffsetDateTime.now());

        // ── Dispatch per-finding tasks AFTER commit ───────────────────────────
        // Problem: if we called findingFixService.fixFindingAsync() here directly,
        // the async tasks might execute before this @Transactional method commits —
        // and find no SonarFinding rows in the DB (they haven't been committed yet).
        //
        // Solution: TransactionSynchronization.afterCommit() runs the lambda only
        // after the transaction commits successfully. The findings are then visible
        // to all threads before any fix task reads them.
        var findingIds = findings.stream().map(SonarFinding::getId).toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Transaction committed — dispatching {} fix task(s) for runId={}",
                        findingIds.size(), run.getId());
                for (var findingId : findingIds) {
                    findingFixService.fixFindingAsync(findingId);
                }
            }
        });
    }
}

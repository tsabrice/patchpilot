package ca.uqam.patchpilot.fix;

import ca.uqam.patchpilot.persistence.PipelineRunEventRepository;
import ca.uqam.patchpilot.persistence.SonarFindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Handles the fix work for a single SonarQube finding.
 *
 * This is a separate bean from FixPipelineService for one specific reason:
 * @Async does not work on self-calls (calls within the same bean bypass the
 * Spring proxy). FixPipelineService dispatches one task per finding by calling
 * this bean — each call goes through the proxy and lands on a separate thread.
 *
 * Day 11 — fetch source file from GitHub + open PR
 * Day 12 — call Claude to generate the fix
 */
@Service
public class FindingFixService {

    private static final Logger log = LoggerFactory.getLogger(FindingFixService.class);

    private final SonarFindingRepository sonarFindingRepository;
    private final PipelineRunEventRepository pipelineRunEventRepository;

    public FindingFixService(SonarFindingRepository sonarFindingRepository,
                             PipelineRunEventRepository pipelineRunEventRepository) {
        this.sonarFindingRepository = sonarFindingRepository;
        this.pipelineRunEventRepository = pipelineRunEventRepository;
    }

    /**
     * Entry point for per-finding fix work.
     *
     * Called by FixPipelineService after the transaction that created the
     * SonarFinding row has committed (via TransactionSynchronization.afterCommit),
     * so the finding is guaranteed to be visible in the DB when this runs.
     *
     * @Async puts this on Spring's task executor — one thread per finding,
     * running concurrently. Failure here does not affect other findings.
     */
    @Async
    public void fixFindingAsync(Long findingId) {
        log.info("Finding fix task started — findingId={}", findingId);

        // TODO Day 11: load the finding, fetch the source file from GitHub API
        // TODO Day 12: call ClaudeClient to generate the patched file content
        // TODO Day 11: open a GitHub PR with the patch
        // TODO: update finding.pipelineStatus → COMPLETED or FAILED
        //        append FINDING_COMPLETED or FINDING_FAILED to pipeline_run_events
    }
}

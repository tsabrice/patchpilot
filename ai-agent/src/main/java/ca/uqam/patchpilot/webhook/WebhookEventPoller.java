package ca.uqam.patchpilot.webhook;

import ca.uqam.patchpilot.fix.FixPipelineService;
import ca.uqam.patchpilot.persistence.WebhookEventRepository;
import ca.uqam.patchpilot.persistence.WebhookEventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Crash-recovery poller.
 *
 * On normal flow, the webhook controller calls FixPipelineService directly
 * after inserting a PENDING row. This poller exists for one purpose: if the
 * agent restarts mid-pipeline, any PENDING rows that were never dispatched
 * will be picked up here and re-queued.
 *
 * FixPipelineService.processWebhookEventAsync() guards against double-processing:
 * it checks whether a PipelineRun already exists for the taskId before doing
 * any work, so poller and controller dispatching the same taskId is safe.
 *
 * fixedDelay (not fixedRate): the next poll starts 60s after the previous one
 * completes, not 60s after it starts. This prevents overlapping poll cycles
 * if processing takes longer than expected.
 */
@Component
public class WebhookEventPoller {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventPoller.class);

    private final WebhookEventRepository webhookEventRepository;
    private final FixPipelineService fixPipelineService;

    public WebhookEventPoller(WebhookEventRepository webhookEventRepository,
                              FixPipelineService fixPipelineService) {
        this.webhookEventRepository = webhookEventRepository;
        this.fixPipelineService = fixPipelineService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void pollPendingEvents() {
        var pending = webhookEventRepository.findByStatus(WebhookEventStatus.PENDING);

        if (pending.isEmpty()) {
            return; // Nothing to do — no log spam on the happy path
        }

        log.info("Poller found {} PENDING webhook event(s) — re-dispatching", pending.size());

        for (var event : pending) {
            log.info("Re-dispatching taskId={} (received at {})",
                    event.getSonarTaskId(), event.getReceivedAt());
            fixPipelineService.processWebhookEventAsync(event.getSonarTaskId());
        }
    }
}

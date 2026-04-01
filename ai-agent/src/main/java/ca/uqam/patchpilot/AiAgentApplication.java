package ca.uqam.patchpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PatchPilot AI Agent — entry point.
 *
 * @EnableAsync     activates @Async on service methods. Without this,
 *                  @Async methods run synchronously on the caller's thread
 *                  and the webhook controller would block on every fix task.
 *
 * @EnableScheduling activates @Scheduled on the webhook event poller.
 *                  Without this, the poller never runs and PENDING webhook
 *                  events are never processed.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentApplication.class, args);
    }
}

package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /**
     * Idempotent insert — returns 1 if a new row was created, 0 if sonar_task_id
     * already exists (duplicate webhook delivery). The caller must check the return
     * value and skip processing when 0.
     *
     * ON CONFLICT DO NOTHING is the idiomatic PostgreSQL pattern here: no extra
     * SELECT needed, no application-level locking, and it is safe under concurrent
     * webhook deliveries from SonarQube's retry mechanism.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO webhook_events (sonar_task_id, raw_payload, status)
            VALUES (:taskId, :payload::jsonb, 'PENDING')
            ON CONFLICT (sonar_task_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("taskId") String taskId, @Param("payload") String payload);

    // Used by FixPipelineService to load the raw payload after the async task starts.
    Optional<WebhookEvent> findBySonarTaskId(String sonarTaskId);

    /**
     * Used by the crash-recovery poller. The partial index
     * idx_webhook_events_pending makes this query a near-instant index scan —
     * it only touches rows where status = 'PENDING'.
     */
    List<WebhookEvent> findByStatus(WebhookEventStatus status);
}

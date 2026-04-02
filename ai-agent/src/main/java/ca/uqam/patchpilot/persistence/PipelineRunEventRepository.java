package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineRunEventRepository extends JpaRepository<PipelineRunEvent, Long> {

    // Ordered ascending so callers get a chronological audit trail.
    List<PipelineRunEvent> findByRunIdOrderByOccurredAtAsc(Long runId);
}

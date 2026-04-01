package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SonarFindingRepository extends JpaRepository<SonarFinding, Long> {

    List<SonarFinding> findByRunId(Long runId);

    /**
     * Crash recovery: returns every finding not yet in a terminal state.
     * Called on agent restart to re-queue any finding that was interrupted
     * mid-pipeline (e.g. agent killed while calling Claude).
     *
     * This hits the partial index idx_sonar_findings_active_pipeline.
     */
    @Query("SELECT f FROM SonarFinding f WHERE f.pipelineStatus NOT IN " +
           "(ca.uqam.patchpilot.persistence.FindingPipelineStatus.COMPLETED, " +
           " ca.uqam.patchpilot.persistence.FindingPipelineStatus.FAILED, " +
           " ca.uqam.patchpilot.persistence.FindingPipelineStatus.SKIPPED)")
    List<SonarFinding> findAllActive();
}

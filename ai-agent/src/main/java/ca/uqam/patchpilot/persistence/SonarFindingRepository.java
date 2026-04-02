package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SonarFindingRepository extends JpaRepository<SonarFinding, Long> {

    List<SonarFinding> findByRunId(Long runId);

    /**
     * Loads a finding together with its parent PipelineRun in one query.
     *
     * Why JOIN FETCH? SonarFinding.run is LAZY-loaded. FindingFixService runs
     * inside an @Async thread with no surrounding transaction. Accessing
     * finding.getRun() after the session closes would throw
     * LazyInitializationException. JOIN FETCH forces the run to be loaded
     * while the session is still open, so external API calls can read run
     * fields (projectKey, branch) without holding a transaction open.
     */
    @Query("SELECT f FROM SonarFinding f JOIN FETCH f.run WHERE f.id = :id")
    Optional<SonarFinding> findByIdWithRun(@Param("id") Long id);

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

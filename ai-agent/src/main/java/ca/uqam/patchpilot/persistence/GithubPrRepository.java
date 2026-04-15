package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GithubPrRepository extends JpaRepository<GithubPr, Long> {

    Optional<GithubPr> findByGenerationId(Long generationId);

    List<GithubPr> findByFindingId(Long findingId);

    /**
     * Loads all PRs for a run with their parent findings eagerly fetched.
     *
     * Same reason as AiGenerationRepository.findByRunIdWithFinding — we need
     * gp.getFinding().getId() outside a transaction to build the findingId→GithubPr map.
     * The WHERE clause traverses finding → run to filter by run without an explicit join.
     */
    @Query("SELECT gp FROM GithubPr gp JOIN FETCH gp.finding WHERE gp.finding.run.id = :runId")
    List<GithubPr> findByRunId(@Param("runId") Long runId);

    long countByStatus(GithubPrStatus status);
}

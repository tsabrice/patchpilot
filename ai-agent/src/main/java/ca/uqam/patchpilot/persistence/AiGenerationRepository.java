package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiGenerationRepository extends JpaRepository<AiGeneration, Long> {

    List<AiGeneration> findByRunId(Long runId);

    Optional<AiGeneration> findByFindingId(Long findingId);

    /**
     * Loads all generations for a run with their parent findings eagerly fetched.
     *
     * Why JOIN FETCH? AiGeneration.finding is LAZY. The run detail endpoint builds
     * a Map<findingId, AiGeneration> outside any @Transactional boundary, so the
     * session is closed by the time we call ag.getFinding().getId(). JOIN FETCH
     * forces the finding to load while the session is still open.
     */
    @Query("SELECT ag FROM AiGeneration ag JOIN FETCH ag.finding WHERE ag.run.id = :runId")
    List<AiGeneration> findByRunIdWithFinding(@Param("runId") Long runId);

    /**
     * Mean confidence score across all generations that have one.
     * Returns null when the table is empty or no generation has a score yet.
     */
    @Query("SELECT AVG(ag.confidenceScore) FROM AiGeneration ag WHERE ag.confidenceScore IS NOT NULL")
    Double avgConfidenceScore();
}

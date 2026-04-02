package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiGenerationRepository extends JpaRepository<AiGeneration, Long> {

    List<AiGeneration> findByRunId(Long runId);

    Optional<AiGeneration> findByFindingId(Long findingId);
}

package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GithubPrRepository extends JpaRepository<GithubPr, Long> {

    Optional<GithubPr> findByGenerationId(Long generationId);

    List<GithubPr> findByFindingId(Long findingId);
}

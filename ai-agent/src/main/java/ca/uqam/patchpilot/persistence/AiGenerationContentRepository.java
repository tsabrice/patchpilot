package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

// generation_id is both PK and FK — Spring Data uses it as the ID type.
public interface AiGenerationContentRepository extends JpaRepository<AiGenerationContent, Long> {
}

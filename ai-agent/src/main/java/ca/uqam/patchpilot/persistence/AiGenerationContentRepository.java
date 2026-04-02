package ca.uqam.patchpilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// generation_id is both PK and FK — Spring Data uses it as the ID type.
public interface AiGenerationContentRepository extends JpaRepository<AiGenerationContent, Long> {

    /**
     * Direct INSERT bypassing JPA entity lifecycle.
     *
     * Why native query instead of save(new AiGenerationContent(...))?
     * AiGenerationContent uses @MapsId on its @OneToOne to AiGeneration.
     * Hibernate 6 unconditionally cascades persist() onto a @MapsId association
     * when saving a new entity. But each repository.save() runs in its own
     * transaction — any AiGeneration reference loaded in a prior transaction is
     * detached by the time the next transaction opens, causing
     * "detached entity passed to persist". A native INSERT sidesteps the cascade
     * entirely: we only need the FK value (generation_id), not a managed entity.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO ai_generation_content (generation_id, suggested_fix) VALUES (:genId, :fix)",
            nativeQuery = true)
    void insertContent(@Param("genId") Long generationId, @Param("fix") String suggestedFix);
}

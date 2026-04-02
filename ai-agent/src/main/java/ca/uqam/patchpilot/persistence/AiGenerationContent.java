package ca.uqam.patchpilot.persistence;

import jakarta.persistence.*;

/**
 * Vertical partition for the patched file text produced by Claude.
 *
 * The suggested fix can be hundreds of lines of Java — keeping it in a
 * separate table means the ai_generations table stays narrow and fast to
 * scan for status queries and aggregate counts.
 *
 * This content is loaded only when the dashboard requests it via:
 *   GET /api/runs/{id}/fixes/{fixId}/content
 *
 * The primary key of this table is also the foreign key to ai_generations
 * (a shared-primary-key one-to-one). @MapsId wires this up: when the entity
 * is persisted, JPA copies the AiGeneration's id into this table's PK column.
 */
@Entity
@Table(name = "ai_generation_content")
public class AiGenerationContent {

    // PK = FK to ai_generations.id — no separate surrogate key needed.
    @Id
    @Column(name = "generation_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "generation_id")
    private AiGeneration generation;

    // Full patched file content returned by Claude.
    @Column(name = "suggested_fix", nullable = false)
    private String suggestedFix;

    // ── Constructors ──────────────────────────────────────────────────────────

    protected AiGenerationContent() {}

    public AiGenerationContent(AiGeneration generation, String suggestedFix) {
        this.generation = generation;
        this.suggestedFix = suggestedFix;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Long getId() { return id; }

    public AiGeneration getGeneration() { return generation; }

    public String getSuggestedFix() { return suggestedFix; }
}

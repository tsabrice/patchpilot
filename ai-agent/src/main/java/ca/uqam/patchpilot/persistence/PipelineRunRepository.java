package ca.uqam.patchpilot.persistence;

import ca.uqam.patchpilot.api.RunSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {

    Optional<PipelineRun> findBySonarTaskId(String sonarTaskId);

    /**
     * Paginated run list with aggregate counts computed via scalar subqueries.
     *
     * Why subqueries instead of JOINs?
     * Joining SonarFinding (sf) and AiGeneration (ag) both directly to PipelineRun
     * produces an sf×ag cross-product (10×10=100 rows per run), causing SUM() on
     * GithubPr to overcount. Scalar subqueries each execute once per run row and
     * return a single value — no cross-product, no GROUP BY needed.
     *
     * openPrsCount: only GithubPr rows with status PR_OPEN count — closed/merged PRs
     * are excluded so the dashboard shows current open PR count, not lifetime count.
     */
    @Query("""
            SELECT new ca.uqam.patchpilot.api.RunSummaryDto(
                pr.id,
                pr.status,
                pr.projectKey,
                pr.branch,
                pr.startedAt,
                pr.finishedAt,
                (SELECT COUNT(sf) FROM SonarFinding sf WHERE sf.run.id = pr.id),
                (SELECT COUNT(ag) FROM AiGeneration ag WHERE ag.run.id = pr.id),
                (SELECT COUNT(gp) FROM GithubPr gp
                    WHERE gp.generation.run.id = pr.id
                    AND gp.status = ca.uqam.patchpilot.persistence.GithubPrStatus.PR_OPEN)
            )
            FROM PipelineRun pr
            ORDER BY pr.startedAt DESC
            """)
    Page<RunSummaryDto> findAllSummaries(Pageable pageable);
}

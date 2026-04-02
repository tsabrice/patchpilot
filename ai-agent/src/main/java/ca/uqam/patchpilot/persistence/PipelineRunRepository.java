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
     * Paginated run list with aggregate counts computed in a single query.
     *
     * Why LEFT JOIN + COUNT(DISTINCT): a run may have zero findings (if SonarQube
     * found nothing), so LEFT JOIN prevents those runs from disappearing from the list.
     * DISTINCT avoids double-counting when multiple JOINs are in play.
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
                COUNT(DISTINCT sf.id),
                COUNT(DISTINCT ag.id),
                SUM(CASE WHEN gp.status = ca.uqam.patchpilot.persistence.GithubPrStatus.PR_OPEN THEN 1L ELSE 0L END)
            )
            FROM PipelineRun pr
            LEFT JOIN SonarFinding sf ON sf.run.id = pr.id
            LEFT JOIN AiGeneration ag ON ag.run.id = pr.id
            LEFT JOIN GithubPr gp ON gp.generation.id = ag.id
            GROUP BY pr.id, pr.status, pr.projectKey, pr.branch, pr.startedAt, pr.finishedAt
            ORDER BY pr.startedAt DESC
            """)
    Page<RunSummaryDto> findAllSummaries(Pageable pageable);
}

package ca.uqam.patchpilot.api;

import ca.uqam.patchpilot.persistence.AiGenerationRepository;
import ca.uqam.patchpilot.persistence.FindingPipelineStatus;
import ca.uqam.patchpilot.persistence.GithubPrRepository;
import ca.uqam.patchpilot.persistence.GithubPrStatus;
import ca.uqam.patchpilot.persistence.PipelineRunRepository;
import ca.uqam.patchpilot.persistence.PipelineRunStatus;
import ca.uqam.patchpilot.persistence.SonarFindingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate stats consumed by the dashboard summary view.
 *
 * GET /api/stats/summary runs 6 lightweight COUNT/AVG queries against
 * separate tables — no joins needed since we only need totals.
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final PipelineRunRepository runRepository;
    private final SonarFindingRepository findingRepository;
    private final GithubPrRepository prRepository;
    private final AiGenerationRepository generationRepository;

    public StatsController(PipelineRunRepository runRepository,
                           SonarFindingRepository findingRepository,
                           GithubPrRepository prRepository,
                           AiGenerationRepository generationRepository) {
        this.runRepository = runRepository;
        this.findingRepository = findingRepository;
        this.prRepository = prRepository;
        this.generationRepository = generationRepository;
    }

    @GetMapping("/summary")
    public ResponseEntity<StatsSummaryDto> summary() {
        return ResponseEntity.ok(new StatsSummaryDto(
                runRepository.count(),
                runRepository.countByStatus(PipelineRunStatus.COMPLETED),
                runRepository.countByStatus(PipelineRunStatus.FAILED),
                findingRepository.count(),
                findingRepository.countByPipelineStatus(FindingPipelineStatus.COMPLETED),
                prRepository.countByStatus(GithubPrStatus.PR_OPEN),
                generationRepository.avgConfidenceScore()
        ));
    }
}

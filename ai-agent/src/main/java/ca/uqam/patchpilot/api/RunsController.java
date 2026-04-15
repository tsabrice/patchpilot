package ca.uqam.patchpilot.api;

import ca.uqam.patchpilot.persistence.AiGenerationRepository;
import ca.uqam.patchpilot.persistence.GithubPrRepository;
import ca.uqam.patchpilot.persistence.PipelineRunRepository;
import ca.uqam.patchpilot.persistence.SonarFindingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * REST endpoints consumed by the Angular dashboard.
 *
 * All routes require a valid Auth0 JWT — enforced by SecurityConfig.
 * Pagination follows Spring Data's Page convention so the Angular
 * HttpClient can deserialize it directly into PageResponse<T>.
 */
@RestController
@RequestMapping("/api/runs")
public class RunsController {

    private final PipelineRunRepository runRepository;
    private final SonarFindingRepository findingRepository;
    private final AiGenerationRepository generationRepository;
    private final GithubPrRepository prRepository;

    public RunsController(PipelineRunRepository runRepository,
                          SonarFindingRepository findingRepository,
                          AiGenerationRepository generationRepository,
                          GithubPrRepository prRepository) {
        this.runRepository = runRepository;
        this.findingRepository = findingRepository;
        this.generationRepository = generationRepository;
        this.prRepository = prRepository;
    }

    /**
     * GET /api/runs?page=0&size=20
     *
     * Returns a paginated list of pipeline runs with aggregate counts.
     * Sorting is handled in the JPQL query (ORDER BY startedAt DESC) —
     * the Pageable sort is ignored in the native query but page/size are used.
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Cap page size at 100 to prevent accidental full-table scans.
        var pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(runRepository.findAllSummaries(pageable));
    }

    /**
     * GET /api/runs/{id}
     *
     * Returns the full detail for one pipeline run: header fields plus every
     * finding with its generation confidence score and GitHub PR link.
     *
     * Uses 4 database queries total (not N+1):
     *   1. SELECT pipeline_run by id
     *   2. SELECT sonar_findings WHERE run_id = ?
     *   3. SELECT ai_generations JOIN finding WHERE run_id = ?
     *   4. SELECT github_prs JOIN finding WHERE finding.run_id = ?
     *
     * Generations and PRs are indexed by finding ID in Java, then zipped onto
     * the findings list. If a finding has no generation yet (pipeline still
     * running), the confidence/PR fields are null in the response.
     *
     * Returns 404 if the run ID does not exist.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return runRepository.findById(id).map(run -> {
            var findings = findingRepository.findByRunId(id);

            // Index by finding ID so we can look up in O(1) while building DTOs.
            // If the same finding somehow has multiple generations (re-run edge case),
            // the merge function (a, b) -> a keeps the first one (lowest ID = oldest).
            var genByFinding = generationRepository.findByRunIdWithFinding(id)
                    .stream()
                    .collect(Collectors.toMap(
                            ag -> ag.getFinding().getId(),
                            ag -> ag,
                            (a, b) -> a));

            var prByFinding = prRepository.findByRunId(id)
                    .stream()
                    .collect(Collectors.toMap(
                            gp -> gp.getFinding().getId(),
                            gp -> gp,
                            (a, b) -> a));

            var findingDtos = findings.stream().map(sf -> {
                var gen = genByFinding.get(sf.getId());
                var pr  = prByFinding.get(sf.getId());
                return new FindingDetailDto(
                        sf.getId(),
                        sf.getSonarIssueKey(),
                        sf.getRuleKey(),
                        sf.getSeverity(),
                        sf.getComponent(),
                        sf.getLine(),
                        sf.getMessage(),
                        sf.getPipelineStatus(),
                        gen != null ? gen.getConfidenceScore() : null,
                        pr  != null ? pr.getPrNumber()        : null,
                        pr  != null ? pr.getPrUrl()           : null,
                        pr  != null ? pr.getStatus()          : null,
                        pr  != null ? pr.getPrTitleEn()       : null
                );
            }).toList();

            return ResponseEntity.ok(new RunDetailDto(
                    run.getId(),
                    run.getStatus(),
                    run.getProjectKey(),
                    run.getBranch(),
                    run.getCommitSha(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    findingDtos
            ));
        }).orElse(ResponseEntity.notFound().build());
    }
}

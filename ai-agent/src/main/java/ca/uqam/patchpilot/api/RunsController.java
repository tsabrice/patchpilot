package ca.uqam.patchpilot.api;

import ca.uqam.patchpilot.persistence.PipelineRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public RunsController(PipelineRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    /**
     * GET /api/runs?page=0&size=20&sort=startedAt,desc
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
}

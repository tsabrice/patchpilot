package ca.uqam.patchpilot.api;

import ca.uqam.patchpilot.persistence.PipelineRunStatus;
import java.time.OffsetDateTime;

/**
 * Paginated list item returned by GET /api/runs.
 *
 * Counts are computed by a single GROUP BY query — never stored as columns
 * in pipeline_runs. This keeps the schema simple and counts always accurate.
 */
public record RunSummaryDto(
        Long id,
        PipelineRunStatus status,
        String projectKey,
        String branch,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        long findingsCount,
        long generationsCount,
        long openPrsCount
) {}

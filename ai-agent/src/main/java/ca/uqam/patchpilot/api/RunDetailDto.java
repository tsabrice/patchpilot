package ca.uqam.patchpilot.api;

import ca.uqam.patchpilot.persistence.PipelineRunStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Full detail for a single pipeline run, including every finding and its fix status.
 *
 * Returned by GET /api/runs/{id}.
 * The findings list is ordered by finding ID (insertion order = SonarQube report order).
 */
public record RunDetailDto(
        Long id,
        PipelineRunStatus status,
        String projectKey,
        String branch,
        String commitSha,           // 40-char SHA — links to the triggering commit
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,  // null while still in progress
        List<FindingDetailDto> findings
) {}

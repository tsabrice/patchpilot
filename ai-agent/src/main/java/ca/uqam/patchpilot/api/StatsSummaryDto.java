package ca.uqam.patchpilot.api;

/**
 * Aggregate numbers shown on the dashboard stats view.
 *
 * Returned by GET /api/stats/summary.
 * All counts are computed live from the DB — no cached columns.
 *
 * fixRate: percentage of findings that reached COMPLETED pipeline status,
 *          rounded to one decimal. Null when totalFindings is 0.
 * avgConfidence: mean confidence_score across all COMPLETED ai_generations,
 *                0.000–1.000. Null when no generations exist yet.
 */
public record StatsSummaryDto(
        long totalRuns,
        long completedRuns,
        long failedRuns,
        long totalFindings,
        long fixedFindings,   // pipeline_status = COMPLETED
        long openPrs,
        Double avgConfidence  // null until at least one generation exists
) {}

-- V3: Finalise pipeline runs that are stuck IN_PROGRESS because all their
-- sonar_findings reached a terminal state before finaliseRunIfAllDone() was
-- deployed. The backfill logic mirrors FindingFixService.finaliseRunIfAllDone():
--   • FAILED  if any finding ended in FAILED
--   • COMPLETED otherwise (all COMPLETED or SKIPPED)
-- finished_at is set to NOW() as a best-effort approximation
-- (no exact finish time was recorded for these runs).

UPDATE pipeline_runs pr
SET
    status      = CASE
                    WHEN EXISTS (
                        SELECT 1
                        FROM sonar_findings sf
                        WHERE sf.run_id = pr.id
                          AND sf.pipeline_status = 'FAILED'
                    ) THEN 'FAILED'
                    ELSE 'COMPLETED'
                  END,
    finished_at = NOW()
WHERE pr.status = 'IN_PROGRESS'
  AND NOT EXISTS (
      SELECT 1
      FROM sonar_findings sf
      WHERE sf.run_id = pr.id
        AND sf.pipeline_status NOT IN ('COMPLETED', 'FAILED', 'SKIPPED')
  );

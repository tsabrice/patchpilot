-- V2: Change sonar_findings unique constraint from global to per-run.
--
-- Why: The same SonarQube issue key appears in every analysis scan until the
-- finding is actually fixed in the codebase. The pipeline must be able to create
-- a new SonarFinding row for the same issue in each pipeline run. The correct
-- uniqueness guarantee is: one row per (issue, run), not one row globally.
--
-- Before: UNIQUE(sonar_issue_key)      -- global — blocks re-processing
-- After:  UNIQUE(sonar_issue_key, run_id) -- per-run — correct semantics

ALTER TABLE sonar_findings
    DROP CONSTRAINT sonar_findings_sonar_issue_key_key;

ALTER TABLE sonar_findings
    ADD CONSTRAINT sonar_findings_sonar_issue_key_run_id_key
        UNIQUE (sonar_issue_key, run_id);

-- =============================================================================
-- PatchPilot — V1 Initial Schema
-- =============================================================================
-- 7 tables. Read ARCHITECTURE_REVIEW.md for the reasoning behind each decision.
-- NEVER modify this file. Add a V2__*.sql for any future changes.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- TABLE 1: webhook_events
-- Durable delivery buffer + idempotency key.
-- The webhook controller does ONE thing: insert here and return 200.
-- A @Scheduled poller picks up PENDING rows separately.
-- sonar_task_id UNIQUE ensures duplicate webhook deliveries are silently ignored
-- via ON CONFLICT DO NOTHING.
-- -----------------------------------------------------------------------------
CREATE TABLE webhook_events (
    id              BIGSERIAL PRIMARY KEY,
    sonar_task_id   VARCHAR(64) UNIQUE NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- PENDING | PROCESSING | DONE | FAILED
    raw_payload     JSONB NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

-- Partial index — the poller only ever queries PENDING rows
CREATE INDEX idx_webhook_events_pending
    ON webhook_events(status)
    WHERE status = 'PENDING';

-- -----------------------------------------------------------------------------
-- TABLE 2: pipeline_runs
-- One row per Jenkins build + SonarQube scan cycle.
-- No aggregate columns (findings_count etc.) — those are computed on read.
-- raw_webhook payload lives in webhook_events.raw_payload, not here.
-- -----------------------------------------------------------------------------
CREATE TABLE pipeline_runs (
    id               BIGSERIAL PRIMARY KEY,
    webhook_event_id BIGINT NOT NULL REFERENCES webhook_events(id),
    sonar_task_id    VARCHAR(64) UNIQUE NOT NULL,
    project_key      VARCHAR(255) NOT NULL,
    branch           VARCHAR(255) NOT NULL DEFAULT 'main',
    commit_sha       VARCHAR(40),
    status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- PENDING | IN_PROGRESS | COMPLETED | FAILED
    started_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at      TIMESTAMPTZ
);

CREATE INDEX idx_pipeline_runs_status     ON pipeline_runs(status);
CREATE INDEX idx_pipeline_runs_started_at ON pipeline_runs(started_at DESC);

-- -----------------------------------------------------------------------------
-- TABLE 3: pipeline_run_events
-- Append-only audit log. Never UPDATE rows here — only INSERT.
-- Every meaningful state transition leaves a record.
-- Used for step timing, Grafana drill-downs, and debugging stuck pipelines.
-- -----------------------------------------------------------------------------
CREATE TABLE pipeline_run_events (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    finding_id  BIGINT,             -- NULL for run-level events
    event_type  VARCHAR(64) NOT NULL,
    -- Run-level:     PIPELINE_STARTED, PIPELINE_COMPLETED, PIPELINE_FAILED
    -- Finding-level: FINDING_QUEUED, FILE_FETCHED, CLAUDE_CALLED,
    --                BRANCH_CREATED, PR_OPENED, FINDING_COMPLETED, FINDING_FAILED
    payload     JSONB,
    -- context: {"durationMs": 4200, "prUrl": "...", "errorMsg": "..."}
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pipeline_run_events_run_id ON pipeline_run_events(run_id);
CREATE INDEX idx_pipeline_run_events_type   ON pipeline_run_events(event_type);

-- -----------------------------------------------------------------------------
-- TABLE 4: sonar_findings
-- One row per SonarQube finding in a scan.
-- pipeline_status tracks per-finding progress — this is the crash recovery
-- checkpoint. On restart, the poller re-queues any finding not in a terminal
-- state (COMPLETED, FAILED, SKIPPED).
-- -----------------------------------------------------------------------------
CREATE TABLE sonar_findings (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    sonar_issue_key  VARCHAR(64) UNIQUE NOT NULL,
    rule_key         VARCHAR(128) NOT NULL,
    severity         VARCHAR(32) NOT NULL,
    -- BLOCKER | CRITICAL | MAJOR | MINOR | INFO
    component        TEXT NOT NULL,     -- file path within the project
    line             INT,
    message          TEXT NOT NULL,
    pipeline_status  VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    -- QUEUED | FETCHING_FILE | CALLING_CLAUDE | CREATING_PR
    -- | COMPLETED | FAILED | SKIPPED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sonar_findings_run_id          ON sonar_findings(run_id);
CREATE INDEX idx_sonar_findings_severity        ON sonar_findings(severity);
-- Partial index — poller only queries non-terminal findings
CREATE INDEX idx_sonar_findings_active_pipeline
    ON sonar_findings(pipeline_status)
    WHERE pipeline_status NOT IN ('COMPLETED', 'FAILED', 'SKIPPED');

-- -----------------------------------------------------------------------------
-- TABLE 5: ai_generations
-- What Claude produced. Immutable after creation.
-- Separated from github_prs because generation and PR have different lifecycles:
-- a generation can exist without a PR (if GitHub call failed).
-- -----------------------------------------------------------------------------
CREATE TABLE ai_generations (
    id                BIGSERIAL PRIMARY KEY,
    finding_id        BIGINT NOT NULL REFERENCES sonar_findings(id) ON DELETE CASCADE,
    run_id            BIGINT NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    model_used        VARCHAR(64) NOT NULL DEFAULT 'claude-haiku-4-5-20251001',
    prompt_tokens     INT,
    completion_tokens INT,
    confidence_score  NUMERIC(4,3),    -- 0.000 – 1.000
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- PENDING | GENERATING | COMPLETED | FAILED
    error_message     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_generations_run_id     ON ai_generations(run_id);
CREATE INDEX idx_ai_generations_finding_id ON ai_generations(finding_id);

-- -----------------------------------------------------------------------------
-- TABLE 6: ai_generation_content
-- Vertical partition for large text — keeps ai_generations rows small and fast
-- to scan. The patched file content is only loaded when explicitly requested
-- via GET /api/runs/{id}/fixes/{fixId}/content.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_generation_content (
    generation_id   BIGINT PRIMARY KEY
        REFERENCES ai_generations(id) ON DELETE CASCADE,
    suggested_fix   TEXT NOT NULL       -- full patched file content from Claude
);

-- -----------------------------------------------------------------------------
-- TABLE 7: github_prs
-- The GitHub PR created from an ai_generation. Mutable — status changes as
-- reviewers interact with the PR (open → merged or closed).
-- branch_name is derived from sonar_issue_key so branch creation is idempotent:
-- a 409 from GitHub means the branch exists — skip creation, proceed to update.
-- This table gets extended in Phase 2 for PR outcome tracking.
-- -----------------------------------------------------------------------------
CREATE TABLE github_prs (
    id              BIGSERIAL PRIMARY KEY,
    generation_id   BIGINT NOT NULL REFERENCES ai_generations(id) ON DELETE CASCADE,
    finding_id      BIGINT NOT NULL REFERENCES sonar_findings(id) ON DELETE CASCADE,
    branch_name     TEXT NOT NULL,      -- "patchpilot/fix-{sonar_issue_key}"
    pr_number       INT,
    pr_url          TEXT,
    pr_title_en     TEXT,
    pr_title_fr     TEXT,
    pr_body_en      TEXT,
    pr_body_fr      TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- PENDING | PR_OPEN | PR_MERGED | PR_CLOSED | FAILED
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_github_prs_finding_id ON github_prs(finding_id);
CREATE INDEX idx_github_prs_status     ON github_prs(status);

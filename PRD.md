# PatchPilot — Product Requirements Document

> **Version:** 1.1
> **Author:** Generated from requirements interview
> **Last updated:** 2026-03-26
> **Status:** Ready for implementation
> **Change in v1.1:** Azure cloud deployment added as a first-class target alongside local Docker Compose (Option 3 — hybrid managed services).

---

## 1. Product Overview

**PatchPilot** is an AI-powered DevSecOps pipeline that automatically detects
security vulnerabilities and code quality issues in a Java Spring Boot application,
then generates and submits targeted fix pull requests on GitHub — without any
manual developer intervention.

The pipeline is: `git push → Jenkins build → SonarQube scan → AI Agent reads
findings → Claude generates fix → GitHub PR opened → human reviews and merges`.

### Core problem it solves

SonarQube findings pile up and get ignored under delivery pressure. Developers
deprioritize fixing `CRITICAL` security hotspots because the fix requires
context-switching and the remediation is manual. At Montreal banks (Desjardins,
BNC, Intact, iA Financial), this technical debt accumulates across hundreds of
microservices. PatchPilot closes the loop automatically: every finding that
SonarQube flags gets a machine-generated fix candidate submitted as a PR within
minutes of the scan completing.

### What makes it different

Most DevSecOps tools stop at *detection*. PatchPilot goes one step further —
it *acts*. The AI agent maps each SonarQube finding to the exact source lines,
constructs a targeted prompt with the code context, calls Claude, and opens a
real GitHub PR with a bilingual (FR/EN) explanation of the fix. The full
pipeline runs in under 5 minutes from `git push` to open PR.

---

## 2. Target Users and Personas

| Persona | Description | Primary need |
|---------|-------------|--------------|
| **Developer (primary)** | Junior/intermediate dev on a squad at a Montreal bank | Stop spending time on routine security fixes; get fix suggestions as PRs |
| **Tech Lead / Reviewer** | Approves PRs, owns code quality | See the AI's reasoning; decide quickly whether to merge or discard |
| **Security/DevOps Engineer** | Owns SonarQube config and pipeline health | Dashboard showing fix rate, issue trends, pipeline health |
| **Recruiter / Interviewer** | Reviews intern portfolios (indirect user) | See a real, end-to-end system demo that touches every tool in the bank stack |

---

## 3. Feature Phases

### MVP (Week 1–3) — Everything in this document

The MVP is a fully working end-to-end demo with **two deployment targets that run
identically**: a local Docker Compose environment for development/demo recording,
and an Azure cloud environment for the live portfolio link on your CV.

| # | Feature | Description |
|---|---------|-------------|
| M1 | **Local Dockerized infrastructure** | All services (Jenkins, SonarQube, Artifactory, Prometheus, Grafana, PostgreSQL) launched with `docker compose up` |
| M2 | **Vulnerable demo app** | Spring Boot 3 app with intentional OWASP-mapped vulnerabilities for SonarQube to detect |
| M3 | **Jenkins pipeline** | Groovy `Jenkinsfile`: checkout → Maven build → test → `sonar:sonar` → deploy to Artifactory → deploy to Azure |
| M4 | **SonarQube webhook** | Fires to AI Agent when scan analysis is complete |
| M5 | **AI Agent — finding fetch** | Spring Boot service reads SonarQube REST API to get all findings from the completed scan |
| M6 | **AI Agent — code fetch** | Fetches the offending source file from GitHub API using the component path from SonarQube |
| M7 | **AI Agent — Claude call** | Builds prompt (finding metadata + source code context) → calls Claude API → parses response |
| M8 | **AI Agent — PR creation** | Opens a GitHub PR with: fix branch, patched file, bilingual FR/EN PR body explaining the change |
| M9 | **PostgreSQL persistence** | Stores pipeline runs, SonarQube findings, and fix attempts with full audit trail |
| M10 | **Angular dashboard** | Authenticated via Auth0; shows runs list, findings, PR status, confidence scores |
| M11 | **Prometheus + Grafana** | Metrics scraped from Spring Boot Actuator + AI Agent; Grafana dashboard shows pipeline health |
| M12 | **Bilingual output** | All AI-generated PR titles, descriptions, and dashboard labels in French and English |
| M13 | **Azure cloud deployment** | Hybrid Azure deployment: AI Agent on Container Apps, Angular on Static Web Apps, PostgreSQL on Azure Flexible Server, Jenkins/SonarQube/Artifactory on Container Instances, monitoring via Azure Monitor + Managed Grafana |
| M14 | **Azure IaC (Bicep)** | All Azure resources defined as code in `infrastructure/azure/main.bicep`; one-command provisioning via `az deployment group create` |

### Phase 2 (Week 4–6, after internship applications sent)

- Multi-repository support (configure multiple repos in the dashboard)
- Slack webhook notification when a PR is opened
- Confidence threshold — skip PR creation if Claude scores below a threshold
- PR outcome tracking — did the reviewer accept or close the PR? Feed back into metrics
- Grafana alerting rules (email alert if critical finding unfixed > 48h)
- Azure API Management in front of the AI Agent API (rate limiting, key management)

### Phase 3 (Future)

- Auth0 multi-user / role-based access (admin vs read-only)
- Support for Python and JavaScript projects (not just Java)
- Auto-merge for low-risk, high-confidence fixes (behind a feature flag)
- GitHub Actions alternative to Jenkins
- SonarQube custom quality gate rules per repo

---

## 4. Architecture

There are two deployment targets. The architecture is identical — only the
hosting layer changes.

### 4a. Local — Docker Compose (development + demo recording)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  DEVELOPER LAPTOP — Docker Compose network: patchpilot-net                   │
│                                                                              │
│  ┌──────────────┐   git push    ┌──────────────────────────────────────┐    │
│  │   Developer  │ ────────────► │         GitHub (cloud)               │    │
│  │  (localhost) │               │  - demo-app repo                     │    │
│  └──────┬───────┘               │  - webhook → Jenkins                 │    │
│         │                       └────────────────┬─────────────────────┘    │
│         │ browser                                │ webhook POST             │
│         ▼                                        ▼                          │
│  ┌──────────────┐         ┌─────────────────────────────────┐              │
│  │   Angular    │◄───────►│      Jenkins  :8080             │              │
│  │  Dashboard   │  REST   │  Jenkinsfile:                   │              │
│  │  :4200       │  +JWT   │  checkout → mvn build → test    │              │
│  │  (Auth0)     │         │  → sonar:sonar → mvn deploy     │              │
│  └──────────────┘         └────────────┬────────────────────┘              │
│         ▲                              │ mvn deploy                        │
│         │                              ▼                                    │
│         │                 ┌────────────────────────────┐                   │
│         │                 │   Artifactory OSS  :8082    │                   │
│         │                 │   Maven repository          │                   │
│         │                 └────────────────────────────┘                   │
│         │                              │ sonar:sonar                       │
│         │                              ▼                                    │
│         │                 ┌────────────────────────────┐                   │
│         │                 │   SonarQube CE  :9000       │◄──────────────┐  │
│         │                 │   Scans Java code           │               │  │
│         │                 └────────────┬───────────────┘               │  │
│         │                              │ webhook (scan done)            │  │
│         │                              ▼                                │  │
│         │                 ┌────────────────────────────┐               │  │
│         │                 │   AI Agent Service  :8081   │               │  │
│         │  GET /api/*     │   Spring Boot 3 / Java 21   │               │  │
│         └─────────────────┤   - fetch findings (SQ API) │               │  │
│                           │   - fetch code (GitHub API) │               │  │
│                           │   - call Claude API          │               │  │
│                           │   - open GitHub PR           │               │  │
│                           │   - persist to PostgreSQL    │               │  │
│                           └────────┬──────────┬──────────┘               │  │
│                                    │          │ SonarQube                 │  │
│                                    │ JPA      │ REST API                  │  │
│                                    ▼          └───────────────────────────┘  │
│                           ┌────────────────┐                               │
│                           │  PostgreSQL 16 │                               │
│                           │  :5432         │                               │
│                           └────────────────┘                               │
│                                                                              │
│  ┌─────────────────────────────────────────────────┐                        │
│  │  Observability stack                            │                        │
│  │                                                 │                        │
│  │  Prometheus :9090  ◄── scrapes /actuator/       │                        │
│  │                         prometheus on :8081     │                        │
│  │  Grafana :3000      ◄── queries Prometheus      │                        │
│  └─────────────────────────────────────────────────┘                        │
│                                                                              │
│  External calls (outbound only):                                             │
│    AI Agent → api.anthropic.com  (Claude API)                               │
│    AI Agent → api.github.com      (GitHub REST API)                         │
│    Angular  → <tenant>.auth0.com  (Auth0 OIDC)                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 4b. Azure Cloud — Hybrid Managed Services (live portfolio link)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  AZURE — Resource Group: rg-patchpilot                                       │
│                                                                              │
│  ┌──────────────┐   git push    ┌──────────────────────────────────────┐    │
│  │   Developer  │ ────────────► │         GitHub (cloud)               │    │
│  │  (anywhere)  │               │  - demo-app repo                     │    │
│  └──────┬───────┘               │  - webhook → Jenkins ACI             │    │
│         │                       └────────────────┬─────────────────────┘    │
│         │ browser                                │ webhook POST             │
│         ▼                                        ▼                          │
│  ┌──────────────────────┐    ┌─────────────────────────────────────────┐    │
│  │  Azure Static        │    │  Jenkins  (Azure Container Instance)    │    │
│  │  Web Apps            │    │  patchpilot-jenkins.azurecontainer.io   │    │
│  │  (Angular dashboard) │    │  Jenkinsfile: build → sonar →           │    │
│  │  Auth0-authenticated │    │  deploy Artifactory → deploy Azure      │    │
│  └──────────┬───────────┘    └────────────┬────────────────────────────┘    │
│             │                             │ mvn deploy                      │
│             │ REST + JWT                  ▼                                  │
│             │                ┌────────────────────────────┐                 │
│             │                │  Artifactory (ACI)          │                 │
│             │                │  Maven repository           │                 │
│             │                └────────────────────────────┘                 │
│             │                             │ sonar:sonar                     │
│             │                             ▼                                  │
│             │                ┌────────────────────────────┐                 │
│             │                │  SonarQube CE (ACI)         │◄────────────┐  │
│             │                │  patchpilot-sonar.azure...  │             │  │
│             │                └────────────┬───────────────┘             │  │
│             │                             │ webhook                     │  │
│             │                             ▼                              │  │
│             │                ┌────────────────────────────┐             │  │
│             │                │  AI Agent                   │             │  │
│             │                │  Azure Container Apps       │             │  │
│             │  GET /api/*    │  (auto-scales 0→N)          │             │  │
│             └────────────────┤  - fetch findings (SQ API)  │             │  │
│                              │  - fetch code (GitHub API)  │             │  │
│                              │  - call Claude API           │             │  │
│                              │  - open GitHub PR            │             │  │
│                              │  - persist to PostgreSQL     │             │  │
│                              └────────┬──────────┬──────────┘             │  │
│                                       │          │ SonarQube API          │  │
│                                       │ JPA      └────────────────────────┘  │
│                                       ▼                                      │
│                              ┌────────────────────────────┐                 │
│                              │  Azure Database for         │                 │
│                              │  PostgreSQL Flexible Server │                 │
│                              │  (managed — no ops)         │                 │
│                              └────────────────────────────┘                 │
│                                                                              │
│  ┌─────────────────────────────────────────────┐                            │
│  │  Observability                              │                            │
│  │                                             │                            │
│  │  Azure Monitor ◄── Container Apps metrics   │                            │
│  │  Azure Managed Grafana ◄── Azure Monitor    │                            │
│  └─────────────────────────────────────────────┘                            │
│                                                                              │
│  External calls (outbound only):                                             │
│    AI Agent → api.anthropic.com   (Claude API)                              │
│    AI Agent → api.github.com       (GitHub REST API)                        │
│    Angular  → <tenant>.auth0.com   (Auth0 OIDC)                             │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Why these Azure service choices:**

| Service | Choice | Why not alternatives |
|---------|--------|----------------------|
| AI Agent hosting | **Azure Container Apps** | Auto-scales to zero (free when idle). No Kubernetes management. Built-in ingress, health probes, env secrets. Better than App Service for event-driven workloads. |
| Angular hosting | **Azure Static Web Apps** | Free tier. Global CDN. GitHub Actions deploy integration. Better than a Storage Account static site (no CDN) or App Service (overkill). |
| PostgreSQL | **Azure Database for PostgreSQL Flexible Server** | Fully managed — no patching, automated backups. Burstable B1ms SKU is ~$12/month. Better than running PostgreSQL on a VM. |
| Jenkins, SonarQube, Artifactory | **Azure Container Instances (ACI)** | These are dev/CI tools — they don't need auto-scaling or load balancing. ACI runs a container on demand for pennies per hour. Better than AKS (too complex) or VMs (too heavy). |
| Monitoring | **Azure Monitor + Azure Managed Grafana** | Managed Grafana connects to Azure Monitor natively. Same dashboard JSON as local Grafana. Free for basic usage. |

### 4c. Service Mapping: Local ↔ Azure

| Service | Local (Docker Compose) | Azure |
|---------|----------------------|-------|
| AI Agent | `ai-agent` container on `patchpilot-net` | Azure Container Apps |
| Angular | `angular` container or `ng serve` | Azure Static Web Apps |
| PostgreSQL | `postgres:16-alpine` | Azure Database for PostgreSQL Flexible Server |
| Jenkins | `jenkins/jenkins:lts` container | Azure Container Instance |
| SonarQube | `sonarqube:community` container | Azure Container Instance |
| Artifactory | `jfrog/artifactory-oss` container | Azure Container Instance |
| Prometheus | `prom/prometheus` container | Azure Monitor (built-in) |
| Grafana | `grafana/grafana` container | Azure Managed Grafana |

The AI Agent's `application.yml` uses environment variables for all
connection strings — the same container image runs locally and in Azure
with zero code changes. Only the env vars differ.

### Data Flow — Happy Path

```
1.  dev pushes commit to GitHub main branch
2.  GitHub webhook fires POST to Jenkins
3.  Jenkins runs Jenkinsfile:
      a. mvn clean verify          (compile + unit tests)
      b. mvn sonar:sonar           (sends results to SonarQube)
      c. mvn deploy                (pushes JAR to Artifactory)
4.  SonarQube finishes analysis → fires webhook POST /api/webhooks/sonarqube
      a. Webhook controller validates HMAC signature
      b. Writes one row to webhook_events (sonar_task_id, raw_payload, status=PENDING)
         using ON CONFLICT DO NOTHING — duplicate deliveries are silently discarded
      c. Returns HTTP 200 immediately — no processing happens in the request thread
5.  @Scheduled poller (every 10s) picks up PENDING rows from webhook_events:
      a. Marks webhook_event status = PROCESSING
      b. Creates a pipeline_run row (status = IN_PROGRESS)
      c. Calls SonarQube REST API  GET /api/issues/search → persists sonar_findings rows
         (each finding gets pipeline_status = QUEUED)
      d. Dispatches one independent @Async task per CRITICAL/MAJOR finding
         (failure of one finding does not block others)
6.  Per-finding @Async task (runs concurrently, rate-limited by Resilience4j):
      a. Sets finding pipeline_status = FETCHING_FILE
         calls GitHub API      GET /repos/{owner}/{repo}/contents/{path}
         decodes base64 file content
      b. Sets finding pipeline_status = CALLING_CLAUDE
         builds Claude prompt  (finding + full file + bilingual instructions)
         calls Claude API      POST /v1/messages  (circuit breaker: opens after 5 failures)
         parses patched file from response
         persists ai_generation row + ai_generation_content row
      c. Sets finding pipeline_status = CREATING_PR
         branch name = "patchpilot/fix-{sonar_issue_key}"  (idempotent — safe to retry)
         calls GitHub API      POST /repos/.../git/refs  (409 = branch exists → skip)
         calls GitHub API      PUT  /repos/.../contents/{path}
         calls GitHub API      POST /repos/.../pulls
         persists github_prs row
      d. Sets finding pipeline_status = COMPLETED
         appends FINDING_COMPLETED event to pipeline_run_events
      e. On any failure: sets pipeline_status = FAILED, appends FINDING_FAILED event
7.  After all per-finding tasks complete:
      pipeline_run status → COMPLETED
      appends PIPELINE_COMPLETED event to pipeline_run_events
8.  Angular dashboard (authenticated via Auth0):
      - opens GET /api/runs/stream  (Server-Sent Events — persistent connection)
      - server pushes a JSON event whenever a run or finding status changes
      - no polling — updates appear within 1 second of the state change
9.  Grafana shows metrics:
      - fix rate per run (computed on read from child rows — no cached counters)
      - Claude API latency (p50/p95) via Micrometer Timer
      - pipeline duration trend from pipeline_run_events timestamps
      - Resilience4j circuit breaker state (CLOSED/OPEN/HALF-OPEN)
```

---

## 5. Tech Stack

| Component | Technology | Version | Justification |
|-----------|-----------|---------|---------------|
| Demo app + AI Agent | Spring Boot | 3.3.x | Standard enterprise Java at Desjardins, BNC, Intact. Maven-first ecosystem matches SonarQube and Artifactory natively |
| Language | Java | 21 LTS | Virtual threads (Project Loom) available; latest LTS; matches student's current skills |
| Frontend | Angular | 17+ (standalone) | Student is comfortable; Angular is the default at Desjardins and iA Financial; strong TypeScript support |
| CI/CD | Jenkins | LTS (2.x) | Industry standard at all 7 target employers; open source; Docker-native |
| Code quality | SonarQube | Community Edition | Free; richest Java rule set; Maven plugin is first-class |
| Artifact registry | JFrog Artifactory | OSS | Free; Maven/Gradle native; exact tool used at target banks |
| Observability | Prometheus + Grafana | Latest stable | 100% free; Micrometer integration trivial in Spring Boot; preferred at Intact and TMX |
| Database | PostgreSQL | 16 | Free, open source; strong Spring Data JPA support; industry standard |
| Authentication | Auth0 | Free tier | PKCE flow for Angular SPA; JWT validation in Spring Boot resource server; free up to 7,500 active users |
| AI model | Claude API (claude-haiku-4-5) | Latest | Cheapest model; capable for code fixes; ~$0.80 per 1,000 calls; sub-$3 for entire project |
| Build tool | Maven | 3.9.x | Matches SonarQube plugin; Artifactory deploy plugin is first-class Maven |
| Container runtime | Docker + Docker Compose | Latest stable | Runs all services locally with one command; zero cloud cost during development |
| Version control | GitHub | cloud | Free; GitHub API used for PR creation; industry standard |
| Cloud platform | Microsoft Azure | — | Container Apps (AI Agent), Static Web Apps (Angular), PostgreSQL Flexible Server, ACI (dev tools), Azure Monitor + Managed Grafana |
| Azure IaC | Bicep | Latest | Azure-native IaC; more readable than ARM; no provider to manage vs Terraform; `az deployment group create` one-liner |

**Why Prometheus + Grafana over Datadog:**
Datadog has a 14-day free trial then costs $15+/month. Prometheus is open source forever.
Spring Boot's Micrometer library supports both with zero code change — just swap the dependency.
Grafana looks identical in a demo. Some target employers (Intact, TMX) use Grafana internally.

**Why haiku-4-5 over sonnet-4-6:**
For generating targeted code fixes (localized to one file, well-scoped prompt),
haiku-4-5 performs comparably to Sonnet at 5x lower cost. This project makes
~200–300 API calls during development and testing. Total Claude cost: ~$2–3.
The model can be swapped to Sonnet in one constant if quality is insufficient.

---

## 6. Key API Endpoints (AI Agent Service — port 8081)

All endpoints are prefixed `/api`. All require a valid Auth0 JWT in
`Authorization: Bearer <token>` except the SonarQube webhook endpoint, which
is secured by a shared secret in the `X-Sonar-Webhook-HMAC-SHA256` header
(SonarQube's built-in webhook security).

### Webhook (unauthenticated — uses HMAC)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/webhooks/sonarqube` | Receives SonarQube scan-completed event. Validates HMAC, persists run, triggers async fix pipeline |

### Pipeline Runs

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs` | List all runs, paginated. Query params: `page`, `size`, `status` |
| `GET` | `/api/runs/{id}` | Get a single run with its findings and fix attempts. Aggregates computed on read — no cached counters. |
| `GET` | `/api/runs/{id}/findings` | Get all SonarQube findings for a run, including per-finding `pipeline_status` |
| `GET` | `/api/runs/{id}/fixes` | Get all fix results for a run (composed from `ai_generations` + `github_prs`) |
| `GET` | `/api/runs/{id}/fixes/{fixId}/content` | Stream the patched file content on demand (loaded from `ai_generation_content`) |
| `GET` | `/api/runs/stream` | **Server-Sent Events** — push updates to the dashboard whenever run or finding status changes. No JWT required on the connection; auth is validated at open time. |

### Summary / Dashboard stats

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/stats/summary` | Returns: total runs, total findings, total PRs opened, avg fixes per run, last 7 days trend |

### Actuator (Prometheus scrape)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/health` | Health check — used by Docker Compose `healthcheck` |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape endpoint (secured to internal network only) |

### Request/Response examples

**POST /api/webhooks/sonarqube** (SonarQube sends this)
```json
{
  "taskId": "AY1234",
  "status": "SUCCESS",
  "analysedAt": "2026-03-26T14:30:00+0000",
  "project": { "key": "demo-app", "name": "Demo Banking App" },
  "branch": { "name": "main", "type": "LONG", "isMain": true },
  "qualityGate": { "status": "ERROR" }
}
```

**GET /api/runs/{id}** (response)
```json
{
  "id": 42,
  "sonarTaskId": "AY1234",
  "projectKey": "demo-app",
  "branch": "main",
  "commitSha": "abc123",
  "status": "COMPLETED",
  "startedAt": "2026-03-26T14:30:05Z",
  "finishedAt": "2026-03-26T14:32:18Z",
  "findingsCount": 7,
  "fixesAttempted": 5,
  "fixesPrOpened": 4,
  "findings": [ "..." ],
  "fixes": [ "..." ]
}
```

> Note: `findingsCount`, `fixesAttempted`, and `fixesPrOpened` are **not stored columns** —
> they are computed at query time by aggregating child rows. The response shape is
> identical to consumers; only the derivation has moved to the DB query.

**GET /api/runs/{id}/fixes** (one item — composed from `ai_generations` + `github_prs`)
```json
{
  "generationId": 101,
  "findingId": 77,
  "rule": "java:S2076",
  "severity": "CRITICAL",
  "component": "src/main/java/ca/demo/PaymentService.java",
  "line": 42,
  "message": "Potential OS command injection",
  "modelUsed": "claude-haiku-4-5-20251001",
  "promptTokens": 1842,
  "completionTokens": 387,
  "confidenceScore": 0.92,
  "contentUrl": "/api/runs/42/fixes/101/content",
  "pr": {
    "id": 55,
    "prNumber": 15,
    "prUrl": "https://github.com/owner/demo-app/pull/15",
    "branchName": "patchpilot/fix-AY-finding-001",
    "titleEn": "[PatchPilot] Fix: OS command injection in PaymentService.java",
    "titleFr": "[PatchPilot] Correction : injection de commande OS dans PaymentService.java",
    "status": "PR_OPEN",
    "createdAt": "2026-03-26T14:31:44Z"
  }
}
```

**GET /api/runs/stream** (SSE — push event format)
```
event: run-update
data: {"runId":42,"status":"IN_PROGRESS","findingsCount":7,"fixesPrOpened":2}

event: finding-update
data: {"runId":42,"findingId":77,"pipelineStatus":"COMPLETED","prUrl":"https://github.com/..."}

event: run-update
data: {"runId":42,"status":"COMPLETED","findingsCount":7,"fixesPrOpened":4}
```

---

## 7. Database Schema

All tables in schema `public`, PostgreSQL 16.
This schema implements all 9 recommendations from `docs/ARCHITECTURE_REVIEW.md`.

### Entity relationships

```
webhook_events  1 ──< pipeline_runs  1 ──< sonar_findings  1 ──< ai_generations  1 ── ai_generation_content
                                     └──< pipeline_run_events        └──< github_prs (1:1 in MVP)
```

### DDL

```sql
-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE 1: webhook_events
-- Implements: Issue 1 (durable delivery) + Issue 2 (idempotency).
-- The webhook controller writes here and returns 200 immediately.
-- A @Scheduled poller processes PENDING rows separately.
-- sonar_task_id UNIQUE + ON CONFLICT DO NOTHING = safe retry from SonarQube.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE webhook_events (
    id              BIGSERIAL PRIMARY KEY,
    sonar_task_id   VARCHAR(64) UNIQUE NOT NULL,   -- idempotency key
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- PENDING | PROCESSING | DONE | FAILED
    raw_payload     JSONB NOT NULL,                 -- full SonarQube webhook body
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ                     -- set when status → DONE or FAILED
);

-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE 2: pipeline_runs
-- One row per Jenkins build + SonarQube scan cycle.
-- Implements: Issue 4 — no derived aggregate columns (findings_count etc.).
-- Counts are always computed on read via SQL aggregates. raw_webhook removed
-- (payload lives in webhook_events.raw_payload).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE pipeline_runs (
    id               BIGSERIAL PRIMARY KEY,
    webhook_event_id BIGINT NOT NULL REFERENCES webhook_events(id),
    sonar_task_id    VARCHAR(64) UNIQUE NOT NULL,
    project_key      VARCHAR(255) NOT NULL,
    branch           VARCHAR(255) NOT NULL DEFAULT 'main',
    commit_sha       VARCHAR(40),
    status           VARCHAR(32) NOT NULL,
    -- PENDING | IN_PROGRESS | COMPLETED | FAILED
    started_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at      TIMESTAMPTZ
    -- NO findings_count / fixes_attempted / fixes_pr_opened columns.
    -- Query sonar_findings and github_prs directly for accurate, always-consistent counts.
);

-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE 3: pipeline_run_events
-- Implements: Issue 7 (append-only event log alongside mutable status).
-- Every meaningful state transition appends a row here — history is never lost.
-- Use this for step timing, Grafana drill-downs, and operational debugging.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE pipeline_run_events (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    finding_id  BIGINT,                             -- NULL for run-level events
    event_type  VARCHAR(64) NOT NULL,
    -- Run-level:    PIPELINE_STARTED, PIPELINE_COMPLETED, PIPELINE_FAILED
    -- Finding-level: FINDING_QUEUED, FILE_FETCHED, CLAUDE_CALLED,
    --                BRANCH_CREATED, PR_OPENED, FINDING_COMPLETED, FINDING_FAILED
    payload     JSONB,
    -- context: {"durationMs": 4200, "prUrl": "...", "errorMsg": "..."}
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE 4: sonar_findings
-- One row per SonarQube finding in a scan.
-- Implements: Issue 3 (per-finding pipeline_status enables crash recovery).
-- The @Scheduled poller re-queues any finding not in COMPLETED/FAILED on startup.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE sonar_findings (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    sonar_issue_key  VARCHAR(64) UNIQUE NOT NULL,   -- SonarQube's own issue key
    rule_key         VARCHAR(128) NOT NULL,          -- e.g. "java:S2076"
    severity         VARCHAR(32) NOT NULL,           -- BLOCKER | CRITICAL | MAJOR | MINOR | INFO
    component        TEXT NOT NULL,                  -- file path within the project
    line             INT,
    message          TEXT NOT NULL,
    pipeline_status  VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    -- QUEUED | FETCHING_FILE | CALLING_CLAUDE | CREATING_PR | COMPLETED | FAILED | SKIPPED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE 5: ai_generations
-- Implements: Issue 9 (split fix_attempts into two tables with different lifecycles).
-- This table owns what Claude produced. It is immutable after creation.
-- A generation can exist without a PR (e.g., if GitHub PR creation failed).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ai_generations (
    id                BIGSERIAL PRIMARY KEY,
    finding_id        BIGINT NOT NULL REFERENCES sonar_findings(id) ON DELETE CASCADE,
    run_id            BIGINT NOT NULL REFERENCES pipeline_runs(id) ON DELETE CASCADE,
    model_used        VARCHAR(64) NOT NULL DEFAULT 'claude-haiku-4-5-20251001',
    prompt_tokens     INT,
    completion_tokens INT,
    confidence_score  NUMERIC(4,3),                  -- 0.000 – 1.000
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    -- PENDING | GENERATING | COMPLETED | FAILED
    error_message     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE 6: ai_generation_content
-- Implements: Issue 6 (vertical partition — large text off the hot query path).
-- The main ai_generations table stays small and fast to scan.
-- Patched file content is only loaded when explicitly requested via
-- GET /api/runs/{id}/fixes/{fixId}/content.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ai_generation_content (
    generation_id   BIGINT PRIMARY KEY REFERENCES ai_generations(id) ON DELETE CASCADE,
    suggested_fix   TEXT NOT NULL                    -- full patched file from Claude
);

-- ─────────────────────────────────────────────────────────────────────────────
-- TABLE 7: github_prs
-- Implements: Issue 9 (PR state has its own lifecycle — mutable; generation is immutable).
-- branch_name is derived from sonar_issue_key → GitHub branch creation is idempotent
-- (a 409 Conflict means the branch already exists; skip creation and proceed).
-- This table is what gets updated in Phase 2 when tracking PR outcomes.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE github_prs (
    id              BIGSERIAL PRIMARY KEY,
    generation_id   BIGINT NOT NULL REFERENCES ai_generations(id) ON DELETE CASCADE,
    finding_id      BIGINT NOT NULL REFERENCES sonar_findings(id) ON DELETE CASCADE,
    branch_name     TEXT NOT NULL,                   -- "patchpilot/fix-{sonar_issue_key}"
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

-- ─────────────────────────────────────────────────────────────────────────────
-- INDEXES
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_webhook_events_status         ON webhook_events(status)
    WHERE status = 'PENDING';                        -- partial index — poller only scans PENDING

CREATE INDEX idx_pipeline_runs_status          ON pipeline_runs(status);
CREATE INDEX idx_pipeline_runs_started_at      ON pipeline_runs(started_at DESC);
CREATE INDEX idx_pipeline_run_events_run_id    ON pipeline_run_events(run_id);
CREATE INDEX idx_pipeline_run_events_type      ON pipeline_run_events(event_type);

CREATE INDEX idx_sonar_findings_run_id         ON sonar_findings(run_id);
CREATE INDEX idx_sonar_findings_severity       ON sonar_findings(severity);
CREATE INDEX idx_sonar_findings_pipeline_status ON sonar_findings(pipeline_status)
    WHERE pipeline_status NOT IN ('COMPLETED', 'SKIPPED');  -- partial index for poller

CREATE INDEX idx_ai_generations_run_id         ON ai_generations(run_id);
CREATE INDEX idx_ai_generations_finding_id     ON ai_generations(finding_id);
CREATE INDEX idx_github_prs_finding_id         ON github_prs(finding_id);
CREATE INDEX idx_github_prs_status             ON github_prs(status);
```

### Aggregate query replacing removed columns

Wherever the API response requires `findingsCount`, `fixesAttempted`, and `fixesPrOpened`,
compute them on read (never cache in a column):

```sql
SELECT
    pr.id,
    pr.status,
    pr.started_at,
    pr.finished_at,
    COUNT(DISTINCT sf.id)                                         AS findings_count,
    COUNT(DISTINCT ag.id)                                         AS fixes_attempted,
    COUNT(DISTINCT gp.id) FILTER (WHERE gp.status = 'PR_OPEN')   AS fixes_pr_opened
FROM pipeline_runs pr
LEFT JOIN sonar_findings sf   ON sf.run_id  = pr.id
LEFT JOIN ai_generations ag   ON ag.run_id  = pr.id
LEFT JOIN github_prs gp       ON gp.finding_id = sf.id
WHERE pr.id = :runId
GROUP BY pr.id;
```

---

## 8. Testing Strategy

### What to unit test (fast, no Docker needed)

- `SonarQubeWebhookParser` — parsing the webhook JSON payload into a `PipelineRun` object. Test edge cases: missing fields, malformed JSON, wrong status values.
- `PromptBuilder` — the class that assembles the Claude prompt from a `SonarFinding` + source code string. Test that the prompt contains the rule key, the file content, and the bilingual instructions.
- `FixResponseParser` — parsing Claude's response to extract the patched file content. Test cases: well-formed response, response with no code block, response in wrong language.
- `HmacValidator` — validating SonarQube's HMAC-SHA256 header. Test valid signature, invalid signature, missing header.
- `ConfidenceScorer` — any scoring logic applied to Claude's response.

### What to integration test (requires Docker / Testcontainers)

Use **Testcontainers** (`org.testcontainers:postgresql`) for database tests.

- `PipelineRunRepository` — persist a run, fetch it back, verify relationships with findings and fix attempts.
- `FixAttemptRepository` — query by status, verify cascade deletes.
- `SonarQubeWebhookController` (with MockMvc + WireMock) — POST a real webhook payload, verify the run is persisted and the async fix pipeline is triggered.
- `GitHubApiClient` (with WireMock) — mock the GitHub REST API; verify branch creation, file update, and PR creation calls are made with correct payloads.
- `ClaudeApiClient` (with WireMock) — mock the Claude API; verify prompt structure and that the response is parsed correctly.

### What NOT to test in MVP

- End-to-end integration with live SonarQube/Jenkins (manual demo only in MVP)
- Angular unit tests (Jasmine/Karma) — add in Phase 2
- Load/performance testing — not relevant for a portfolio demo

### Test tooling

```xml
<!-- pom.xml test dependencies -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>  <!-- JUnit 5, Mockito, MockMvc -->
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>com.github.tomakehurst</groupId>
  <artifactId>wiremock-jre8-standalone</artifactId>
  <scope>test</scope>
</dependency>
```

### Coverage target for MVP

Unit tests: aim for 80%+ on the `agent` and `webhook` packages.
Integration tests: cover the happy path and one error path per external call.
Do not chase 100% coverage — it slows you down and tests internals that change.

---

## 9. CI/CD Pipeline (Phased)

### Week 1 — Jenkinsfile (MVP)

```groovy
// Only add a check when the thing it checks EXISTS.
// Week 1: build + test + sonar + deploy. No more.
pipeline {
    agent any
    tools { maven 'Maven-3.9' }

    stages {
        stage('Build & Test') {
            steps {
                sh 'mvn clean verify'          // compile + unit tests
            }
            post {
                always {
                    junit 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh 'mvn sonar:sonar'
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 3, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: false
                    // abortPipeline: false so the pipeline continues even if QG fails.
                    // We WANT failures — they trigger the AI agent.
                }
            }
        }

        stage('Publish to Artifactory') {
            steps {
                // Only runs if build succeeded. Uploads JAR to Artifactory OSS.
                sh 'mvn deploy -DskipTests'
            }
        }
    }
}
```

### Week 2 — Add integration test stage (once tests exist)

```groovy
stage('Integration Tests') {
    steps {
        sh 'mvn verify -Pintegration-tests'  // Testcontainers spins up PostgreSQL
    }
}
```

### Phase 2 — Add after demo is recorded

```groovy
stage('Docker Build & Push') {
    steps {
        sh 'docker build -t patchpilot/ai-agent:${BUILD_NUMBER} .'
        // Push to Artifactory Docker registry (requires Artifactory Pro — skip for MVP)
    }
}
```

---

---

## 10. Azure Infrastructure

### Resource Group Layout

All Azure resources live in a single resource group: `rg-patchpilot`.
Everything is defined in `infrastructure/azure/main.bicep` and deployed with:

```bash
az deployment group create \
  --resource-group rg-patchpilot \
  --template-file infrastructure/azure/main.bicep \
  --parameters @infrastructure/azure/parameters/dev.bicepparam
```

### Bicep module structure

```
infrastructure/azure/
├── main.bicep                    # Entry point — wires modules together
├── modules/
│   ├── container-apps.bicep      # AI Agent (Container Apps Environment + App)
│   ├── postgres.bicep            # Azure Database for PostgreSQL Flexible Server
│   ├── static-web-app.bicep      # Angular dashboard (Static Web Apps)
│   └── container-instances.bicep # Jenkins + SonarQube + Artifactory (ACI)
└── parameters/
    └── dev.bicepparam            # Parameter values (non-secret; secrets come from Key Vault or CLI args)
```

### Azure services provisioned

| Resource | SKU / Tier | Estimated monthly cost |
|---------|-----------|----------------------|
| Container Apps Environment | Consumption plan | ~$0 (pay per request) |
| Container App — ai-agent | Consumption (0.5 vCPU, 1 GiB) | ~$5–15 depending on load |
| Azure Database for PostgreSQL | Burstable B1ms, 32 GiB | ~$12 |
| Azure Static Web Apps | Free tier | $0 |
| Azure Container Instance — Jenkins | 2 vCPU, 4 GiB | ~$0.10/hr (run only during CI) |
| Azure Container Instance — SonarQube | 2 vCPU, 4 GiB | ~$0.10/hr (run only during CI) |
| Azure Container Instance — Artifactory | 1 vCPU, 2 GiB | ~$0.05/hr (run only during CI) |
| Azure Monitor | Basic logs | ~$0–3 |
| Azure Managed Grafana | Essential tier | ~$0 (free for first workspace) |
| **Total estimate** | | **~$20–30/month** |

> To keep costs near zero during development, stop the Container Instances
> when not running a demo: `az container stop --name patchpilot-jenkins --resource-group rg-patchpilot`

### Jenkins deployment stage for Azure

The Jenkinsfile adds a final stage that pushes the AI Agent Docker image to
Azure Container Registry and triggers a Container Apps revision update:

```groovy
stage('Deploy to Azure') {
    when {
        branch 'main'   // only deploy on main branch builds
    }
    steps {
        // Login to Azure Container Registry
        sh '''
            az acr login --name patchpilotacr
            docker build -t patchpilotacr.azurecr.io/ai-agent:${BUILD_NUMBER} ./ai-agent
            docker push patchpilotacr.azurecr.io/ai-agent:${BUILD_NUMBER}
        '''
        // Update Container Apps to the new image revision
        sh '''
            az containerapp update \
              --name patchpilot-ai-agent \
              --resource-group rg-patchpilot \
              --image patchpilotacr.azurecr.io/ai-agent:${BUILD_NUMBER}
        '''
    }
}
```

### Environment variable strategy — local vs Azure

The AI Agent reads all config from env vars. The same application.yml works in
both environments — only the values differ.

| Variable | Local source | Azure source |
|---------|-------------|-------------|
| `SPRING_DATASOURCE_URL` | `.env` → Docker Compose | Container Apps secret (points to Flexible Server FQDN) |
| `CLAUDE_API_KEY` | `.env` | Container Apps secret |
| `GITHUB_TOKEN` | `.env` | Container Apps secret |
| `SONARQUBE_URL` | `http://sonarqube:9000` (Docker network) | `http://<aci-fqdn>:9000` |
| `AUTH0_DOMAIN` | `.env` | Container Apps environment variable |

Never use the same PostgreSQL password locally and in Azure. Use a strong
random password for the Azure Flexible Server instance; store it as a
Container Apps secret, not a plain env var.

---

## 11. Project Scope

### In scope for MVP (3 weeks)

- Single GitHub repository (the demo app)
- Java / Spring Boot projects only
- SonarQube Community Edition rules only (no custom rules)
- Fix attempts for CRITICAL and MAJOR severity findings only (skip MINOR/INFO)
- One fix attempt per finding per scan (no retry logic)
- PR always opened — no auto-merge
- Single-user Auth0 (your own account)
- **Two deployment targets: local Docker Compose + Azure (hybrid managed services)**
- **Bicep IaC for all Azure resources**
- **Jenkins pipeline deploys to Azure Container Apps on main branch builds**
- Bilingual FR/EN PR descriptions
- Grafana dashboard (local: Grafana container; Azure: Azure Managed Grafana) with 4 panels: fix rate, Claude latency, findings by severity, runs per day

### Explicit out of scope for MVP

- Multi-repository support
- Pull request / branch builds (main branch only)
- Support for Python, JS, or other languages
- Slack / email notifications
- Auto-merge (even if confidence is high)
- Role-based access control (RBAC)
- SonarQube Quality Profile customization
- Re-scanning after a PR is merged
- Feedback loop (did the reviewer accept the PR?)
- Production hardening beyond: managed PostgreSQL, Container Apps secrets, HTTPS via Static Web Apps

---

## 11. Open Questions (validate before building)

| # | Question | Risk if wrong | How to validate |
|---|----------|--------------|-----------------|
| Q1 | Does Auth0 free tier support the Angular PKCE flow + Spring Boot JWT validation simultaneously? | Auth flow broken for dashboard | Check Auth0 docs: free tier supports SPA + API (M2M) with up to 7,500 active users. Low risk. |
| Q2 | Does SonarQube CE webhook include the `branch` name in the payload? | Can't associate run with a git branch | Test with a real scan. Fallback: read branch from GitHub API using commit SHA. |
| Q3 | Does the Claude API impose rate limits that would slow down fixing 10+ findings per scan? | Fix pipeline takes too long for demo | haiku-4-5 allows 50 req/min on free tier. For a demo with 7–10 findings, process them sequentially with no delay. If needed, use a 200ms sleep between calls. |
| Q4 | Can the AI Agent write back to the GitHub repo using a Personal Access Token with `repo` scope? | PR creation fails | Test manually before Week 2. PAT with `repo` scope is sufficient. No OAuth app needed for a single-user demo. |
| Q5 | Does Artifactory OSS support Maven `mvn deploy` without an Enterprise license? | Artifacts not stored | Yes — Artifactory OSS supports Maven, Gradle, npm, Docker. Confirmed for OSS edition. |

---

## 13. Environment Variables Reference

See `CLAUDE.md` for the full local `.env` reference. Additional variables
needed for the Azure deployment:

```bash
# Azure — needed before Week 3 Day 3 (Azure deployment stage in Jenkinsfile)
AZURE_SUBSCRIPTION_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
AZURE_RESOURCE_GROUP=rg-patchpilot
AZURE_LOCATION=canadaeast            # closest region to Montreal

# Azure Container Registry (created by Bicep)
ACR_NAME=patchpilotacr               # must be globally unique; lowercase, no hyphens
ACR_LOGIN_SERVER=patchpilotacr.azurecr.io

# Azure Container Apps (created by Bicep)
CONTAINER_APP_NAME=patchpilot-ai-agent

# Azure Database for PostgreSQL Flexible Server
# Use a different password than local — never reuse secrets across environments
AZURE_POSTGRES_HOST=patchpilot-pg.postgres.database.azure.com
AZURE_POSTGRES_PASSWORD=<strong-random-password>

# Local vars still needed (same keys, different values for local vs Azure)
CLAUDE_API_KEY          — from console.anthropic.com
GITHUB_TOKEN            — PAT with repo + workflow scopes
GITHUB_OWNER            — your GitHub username or org
GITHUB_REPO             — the demo app repo name
SONARQUBE_TOKEN         — generated in SonarQube admin → My Account → Security
SONARQUBE_WEBHOOK_SECRET — any random 32-char string; must match Jenkins config
AUTH0_DOMAIN            — <your-tenant>.auth0.com
AUTH0_AUDIENCE          — the API identifier you set in Auth0 dashboard
```

---

*End of PRD v1.1*

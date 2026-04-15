# PatchPilot

**An AI-powered DevSecOps pipeline that automatically generates GitHub pull requests to fix SonarQube security findings.**

`git push` triggers a Jenkins build. SonarQube scans the code. The AI Agent picks up each finding, asks Claude to write a fix, and opens a bilingual (English / French) pull request — one per finding, in parallel, within minutes.

---

## Pipeline Overview

```
GitHub Push
     │
     ▼
 Jenkins (8080)
 Checkout → Build & Test → SonarQube Scan → Publish to Artifactory
     │
     │  scan complete → fires HMAC-signed webhook
     ▼
 SonarQube (9000)
     │
     │  POST /api/webhooks/sonarqube
     ▼
 AI Agent (8081)  ─────────────────────── PostgreSQL (5432)
 Spring Boot service                       7-table schema
 Idempotent webhook receiver               Flyway migrations
 @Async per-finding fix tasks
     │
     ├── SonarQube API ──── fetches all open findings for the project
     │
     ├── Claude API / CLI ── generates patched file content
     │                        + bilingual PR title and body
     │                        + confidence score (0–1)
     │
     └── GitHub API ──────── creates fix branch
                              commits patched file
                              opens pull request
                              "patchpilot/fix-{sonar-issue-key}"

 Angular Dashboard (4200)       Prometheus (9090)
 Auth0 PKCE authentication       scrapes /actuator/prometheus
 Runs list + run detail           every 15 seconds
 Stats summary view
                                Grafana (3000)
                                 7-panel live dashboard
                                 circuit breaker state
                                 confidence score trends
```

---

## Features

- **Automatic PR generation** — one pull request per SonarQube finding, opened concurrently via `@Async` tasks
- **Bilingual output** — every PR title and description is generated in both English and French
- **Two Claude modes** — `CLAUDE_MODE=max` uses the Claude CLI with a Max subscription (free for local dev); `CLAUDE_MODE=api` calls the Anthropic HTTP API (required for cloud deployment)
- **Resilience4j circuit breakers** — protects against Claude API and GitHub API failures; circuit state exposed as a Prometheus metric
- **Idempotent pipeline** — webhook deduplication, branch-exists handling, and PR-exists handling make every fix task safely re-runnable after a crash
- **Crash recovery** — each finding tracks its own `pipeline_status`; a `@Scheduled` poller re-queues any non-terminal findings after a restart
- **Auth0-secured dashboard** — Angular 17 SPA with PKCE flow, `AuthHttpInterceptor`, and route guards
- **Live observability** — Grafana dashboard with run counters, confidence gauge, circuit breaker state, and time-series panels; all provisioned automatically on `docker compose up`
- **One-command local stack** — all eight services start with `docker compose up -d`

---

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| CI/CD | Jenkins | LTS (JDK 21 image) |
| Code quality | SonarQube Community Edition | Latest |
| Artifact registry | JFrog Artifactory OSS | 7.55.14 |
| AI Agent | Spring Boot | 3.3.x |
| Language | Java | 21 LTS |
| Database | PostgreSQL | 16-alpine |
| Schema migrations | Flyway | 10.x |
| AI integration | Anthropic Claude | haiku-4-5 (default) |
| Fault tolerance | Resilience4j | 2.2.0 |
| Auth | Auth0 | Free tier (PKCE + JWT) |
| Dashboard | Angular | 17 (standalone components) |
| Styling | Tailwind CSS | 3.4.x |
| Metrics | Micrometer + Prometheus | Latest |
| Dashboards | Grafana | Latest |
| Runtime | Docker + Compose | Latest |
| Build | Maven | 3.9.x |

---

## Repository Structure

```
patchpilot/
├── demo-app/                    # Spring Boot app with intentional vulnerabilities
│   ├── src/
│   └── pom.xml
├── ai-agent/                    # Spring Boot service: webhook → Claude → GitHub PR
│   ├── src/main/java/ca/uqam/patchpilot/
│   │   ├── webhook/             # SonarQube webhook receiver (HMAC validation)
│   │   ├── sonar/               # SonarQube REST API client
│   │   ├── github/              # GitHub REST API client
│   │   ├── claude/              # ClaudeClient interface + CLI and API implementations
│   │   ├── fix/                 # Pipeline orchestration (FixPipelineService + FindingFixService)
│   │   ├── persistence/         # JPA entities + repositories (7 tables)
│   │   ├── api/                 # REST controllers (runs list, run detail, stats)
│   │   └── config/              # Security (Auth0 resource server), async executor
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/        # Flyway SQL migrations (V1, V2, V3)
├── dashboard/                   # Angular 17 SPA
│   └── src/app/
│       ├── runs/                # RunListComponent, RunDetailComponent
│       ├── stats/               # StatsComponent
│       └── shared/              # AuthGuard, API types
├── infrastructure/
│   ├── docker-compose.yml       # All 8 services — one command to start
│   ├── prometheus/prometheus.yml
│   ├── grafana/
│   │   ├── dashboards/patchpilot.json
│   │   └── provisioning/
│   └── azure/                   # Bicep IaC (in progress — see Roadmap)
├── Jenkinsfile                  # 4-stage declarative pipeline
├── .env.example                 # All required environment variables
└── docs/
    ├── PRD.md
    ├── DATA_MODEL.md
    └── ARCHITECTURE_REVIEW.md
```

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Docker + Docker Compose | 8 GB RAM minimum; 15 GB recommended for all services |
| Java 21 + Maven 3.9 | Required to build the AI Agent and demo-app JARs before `docker compose build` |
| Node.js 22 + npm | Required to build the Angular dashboard |
| Git | Any recent version |
| Auth0 account | Free tier — one SPA application + one API registration |
| GitHub personal access token | `repo` + `workflow` scopes; used by the AI Agent to open PRs |
| Claude Max subscription **or** Anthropic API key | Max subscription enables `CLAUDE_MODE=max` (no per-token cost); API key enables `CLAUDE_MODE=api` |

**Linux only — SonarQube requires:**
```bash
sudo sysctl -w vm.max_map_count=262144
# Make permanent:
echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf
```

---

## Quick Start (Local)

### 1. Clone and configure

```bash
git clone https://github.com/<your-fork>/patchpilot.git
cd patchpilot
cp .env.example .env
```

Edit `.env` and fill in every blank value. See [Configuration](#configuration) for the full reference.

### 2. Build the JARs

The AI Agent Dockerfile copies a pre-built JAR — Maven does not run inside Docker.

```bash
mvn -pl ai-agent package -DskipTests
```

### 3. Start the stack

```bash
docker compose -f infrastructure/docker-compose.yml --env-file .env up -d
```

First run pulls approximately 3 GB of images and takes 5–10 minutes. SonarQube takes an additional ~90 seconds to initialize its Elasticsearch index.

```bash
# Check all services are healthy
docker compose -f infrastructure/docker-compose.yml ps
```

### 4. Configure Jenkins (one-time)

After Jenkins is healthy at `http://localhost:8080`:

1. **Manage Jenkins → Global Tool Configuration**
   - Add Maven installation: name = `Maven-3.9`, install from Apache
2. **Manage Jenkins → Configure System → SonarQube servers**
   - Name = `SonarQube`, URL = `http://sonarqube:9000`
   - Add a SonarQube token as a Secret Text credential and link it
3. **Manage Jenkins → Credentials → Global**
   - Add Username/Password credential: ID = `artifactory-credentials`
   - Username: `admin`, Password: value of `ARTIFACTORY_PASSWORD` from `.env`
4. **New Item → Pipeline**
   - Definition: Pipeline script from SCM
   - Branch: `dev`
   - Script path: `Jenkinsfile`
5. **Add a GitHub webhook** on the source repo pointing to `http://<host>:8080/github-webhook/`

### 5. Configure SonarQube (one-time)

After SonarQube is healthy at `http://localhost:9000` (default credentials: `admin` / `admin` — change immediately):

1. **Administration → Security → Generate Token** — copy the token to `SONARQUBE_TOKEN` in `.env`
2. **Administration → Configuration → Webhooks → Create**
   - Name: `patchpilot`
   - URL: `http://ai-agent:8081/api/webhooks/sonarqube`
   - Secret: value of `SONARQUBE_WEBHOOK_SECRET` from `.env`

### 6. Configure Auth0 (one-time)

1. Create a **Single Page Application** in Auth0
   - Allowed Callback URLs: `http://localhost:4200`
   - Allowed Logout URLs: `http://localhost:4200`
   - Copy the **Domain** and **Client ID** to `dashboard/src/environments/`
2. Create an **API** in Auth0
   - Identifier: `https://patchpilot-api` (must match `AUTH0_AUDIENCE` in `.env`)
3. Set `AUTH0_DOMAIN` and `AUTH0_AUDIENCE` in `.env`

### 7. Start the Angular dashboard

```bash
cd dashboard
npm install
ng serve
```

The dashboard is available at `http://localhost:4200`.

### 8. Trigger the pipeline

Push any commit to the repository tracked by the Jenkins job. The pipeline runs automatically:

- Jenkins builds and scans the `demo-app`
- SonarQube detects the five seeded vulnerabilities
- The AI Agent receives the webhook and dispatches one fix task per finding
- Claude generates a patched file and bilingual PR description for each finding
- GitHub pull requests appear in the configured repo within a few minutes

---

## Configuration

Copy `.env.example` to `.env`. All values in `.env` are gitignored.

### Required for all deployments

| Variable | Description |
|---|---|
| `GITHUB_TOKEN` | Personal access token with `repo` and `workflow` scopes |
| `GITHUB_OWNER` | GitHub username that owns the target repository |
| `GITHUB_REPO` | Repository name the pipeline will open PRs against (default: `demo-app`) |
| `SONARQUBE_TOKEN` | SonarQube token with "Execute Analysis" permission |
| `SONARQUBE_WEBHOOK_SECRET` | Shared secret for HMAC-SHA256 webhook signature validation (any 32-char random string) |
| `AUTH0_DOMAIN` | Auth0 tenant domain, e.g. `your-tenant.auth0.com` |
| `AUTH0_AUDIENCE` | Auth0 API identifier, e.g. `https://patchpilot-api` |

### Claude mode

| Variable | Description |
|---|---|
| `CLAUDE_MODE` | `max` (default) — uses the `claude` CLI with a Max subscription. `api` — calls the Anthropic HTTP API |
| `CLAUDE_API_KEY` | Required only when `CLAUDE_MODE=api`. From [console.anthropic.com](https://console.anthropic.com) |
| `CLAUDE_MODEL` | Model used in `api` mode. Default: `claude-haiku-4-5-20251001`. Switch to `claude-sonnet-4-6` for higher-quality fixes |

**`CLAUDE_MODE=max` (local development):** The `claude` CLI must be installed and authenticated on the host. Docker Compose mounts `~/.claude` from the host into the container so the CLI can use the active session.

**`CLAUDE_MODE=api` (Azure / CI):** No CLI required. Uses `CLAUDE_API_KEY` and `CLAUDE_MODEL`.

### Local Docker Compose

| Variable | Default | Description |
|---|---|---|
| `POSTGRES_DB` | `patchpilot` | Database name |
| `POSTGRES_USER` | `patchpilot` | Database user |
| `POSTGRES_PASSWORD` | — | Database password |
| `JENKINS_PORT` | `8080` | Jenkins host port |
| `SONARQUBE_PORT` | `9000` | SonarQube host port |
| `ARTIFACTORY_PORT` | `8082` | Artifactory host port |
| `AI_AGENT_PORT` | `8081` | AI Agent host port |
| `PROMETHEUS_PORT` | `9090` | Prometheus host port |
| `GRAFANA_PORT` | `3000` | Grafana host port |
| `POSTGRES_PORT` | `5432` | PostgreSQL host port |

If a local PostgreSQL instance is already running on port 5432, set `POSTGRES_PORT=5433`.

---

## Service URLs

| Service | URL | Default credentials |
|---|---|---|
| Jenkins | `http://localhost:8080` | (setup wizard disabled) |
| SonarQube | `http://localhost:9000` | `admin` / `admin` (change on first login) |
| Artifactory | `http://localhost:8082` | `admin` / (set in `.env`) |
| AI Agent API | `http://localhost:8081` | JWT required |
| Angular Dashboard | `http://localhost:4200` | Auth0 login |
| Prometheus | `http://localhost:9090` | — |
| Grafana | `http://localhost:3000` | `admin` / `admin` |
| PostgreSQL | `localhost:5432` | Values from `.env` |

---

## API Reference

All endpoints except `/actuator/*` require a valid Auth0 JWT in the `Authorization: Bearer <token>` header.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/webhooks/sonarqube` | SonarQube webhook receiver. Validates HMAC-SHA256 signature, persists event idempotently, dispatches async processing. Returns 200 immediately. |
| `GET` | `/api/runs` | Paginated list of pipeline runs with aggregate counts. Query params: `page` (default 0), `size` (default 20, max 100). |
| `GET` | `/api/runs/{id}` | Single run with full findings table, generation results, confidence scores, and PR links. |
| `GET` | `/api/stats/summary` | Aggregate stats: total runs, completed/failed counts, total findings, fixed findings, open PRs, average confidence score. |
| `GET` | `/actuator/health` | Spring Boot health check. Used by Docker Compose and load balancers. No auth required. |
| `GET` | `/actuator/prometheus` | Micrometer Prometheus scrape endpoint. Scraped by Prometheus every 15 seconds. Internal only. |

---

## Database Schema

Seven tables managed by Flyway. The schema is the source of truth — Hibernate is set to `validate` only.

```
webhook_events  1 ──< pipeline_runs  1 ──< sonar_findings  1 ──< ai_generations  1 ── ai_generation_content
                                     └──< pipeline_run_events        └──< github_prs
```

| Table | Purpose |
|---|---|
| `webhook_events` | Durable delivery buffer. `sonar_task_id UNIQUE` enforces idempotency via `ON CONFLICT DO NOTHING`. |
| `pipeline_runs` | One row per Jenkins build + SonarQube scan cycle. No pre-computed aggregate columns — counts are computed on read. |
| `pipeline_run_events` | Append-only audit log. Every state transition is recorded here. Never updated, only inserted. |
| `sonar_findings` | One row per SonarQube issue. `pipeline_status` is the crash recovery checkpoint — the poller re-queues any finding not in a terminal state after a restart. |
| `ai_generations` | What Claude produced: model used, token counts, confidence score, status. Immutable after creation. |
| `ai_generation_content` | Vertical partition for large text. The patched file content is stored here to keep `ai_generations` rows small and fast to scan. |
| `github_prs` | The GitHub PR created for a finding. Mutable — status updates as the PR is reviewed and merged. Stores bilingual titles and bodies. |

---

## Observability

The Grafana dashboard at `http://localhost:3000` provisions automatically on `docker compose up`. It connects to Prometheus as a data source without any manual configuration.

**Dashboard panels:**

| Panel | Type | What it shows |
|---|---|---|
| Completed Runs | Stat | Total `patchpilot_pipeline_runs_total{status="completed"}` |
| Failed Runs | Stat | Total `patchpilot_pipeline_runs_total{status="failed"}` — turns red when ≥ 1 |
| Findings Fixed | Stat | Total `patchpilot_findings_total{status="completed"}` |
| Avg Confidence Score | Gauge | `confidence_sum / confidence_count × 100` — green ≥ 80 %, yellow ≥ 50 %, red below |
| Circuit Breaker State | Stat | State of `claude-api` and `github-api` circuit breakers (CLOSED / OPEN) |
| Findings Over Time | Time series | Completed, failed, and skipped findings using `increase()` over `$__rate_interval` |
| Confidence Over Time | Time series | Rolling 5-minute mean confidence score |

**Custom Micrometer meters (exposed at `/actuator/prometheus`):**

```
patchpilot_pipeline_runs_total{status="completed|failed"}
patchpilot_findings_total{status="completed|failed|skipped"}
patchpilot_confidence_count / _sum / _max
resilience4j_circuitbreaker_state{name="claude-api|github-api", state="closed|open"}
```

**Resilience4j circuit breaker configuration:**

| Setting | claude-api | github-api |
|---|---|---|
| Sliding window | 10 calls | 10 calls |
| Failure threshold | 50 % | 50 % |
| Open state wait | 30 s | 30 s |
| Half-open test calls | 2 | 2 |
| Timeout | 30 s | 15 s |

---

## Demo App — Seeded Vulnerabilities

`demo-app` is a Spring Boot application with five intentional security vulnerabilities. These are detected by SonarQube and trigger the fix pipeline.

| Class | Rule | Vulnerability |
|---|---|---|
| `UserService` | `java:S2077` | SQL injection — department filter built via string concatenation |
| `CryptoService` | `java:S4426` | Weak cipher — DES used instead of AES-256 |
| `DiagnosticsController` | `java:S2076` | OS command injection — `Runtime.exec("ping -c 1 " + host)` |
| `FileController` | `java:S2083` | Path traversal — file path constructed without canonicalization |
| `ReportController` | `java:S2755` | XXE injection — `DocumentBuilderFactory` with external entities enabled |

---

## Jenkins Pipeline

```
Stage 1: Checkout          — clones the repository
Stage 2: Build & Test      — mvn verify (compile + unit tests + SpotBugs + OWASP check)
Stage 3: SonarQube Analysis — mvn sonar:sonar → SonarQube fires webhook to AI Agent
Stage 4: Publish to Artifactory — mvn deploy → JAR uploaded to libs-release-local
Stage 5: Deploy to Azure   — (in progress; see Roadmap)
```

---

## Docker Compose Reference

```bash
# Start all services (first run: ~5–10 min to pull images)
docker compose -f infrastructure/docker-compose.yml --env-file .env up -d

# Check service health
docker compose -f infrastructure/docker-compose.yml ps

# Stream logs for a specific service
docker compose -f infrastructure/docker-compose.yml logs -f ai-agent

# Stop all services (data volumes are preserved)
docker compose -f infrastructure/docker-compose.yml down

# Destroy all data and start fresh
docker compose -f infrastructure/docker-compose.yml down -v

# Rebuild and restart the AI Agent after a code change
mvn -pl ai-agent package -DskipTests
docker compose -f infrastructure/docker-compose.yml build ai-agent
docker compose -f infrastructure/docker-compose.yml up -d ai-agent
```

---

## Forking and Self-Hosting

To run this pipeline against a different target repository:

1. Fork this repository
2. Replace `demo-app/` with the Spring Boot application to be scanned, or keep `demo-app` as-is to test the pipeline
3. Set `GITHUB_OWNER` and `GITHUB_REPO` in `.env` to point to the target repository
4. Set `SONARQUBE_TOKEN` with a token from a SonarQube project configured for that repository
5. Configure the SonarQube project key to match the `sonar.projectKey` in the target `pom.xml`
6. Start the stack and push a commit

The AI Agent will open fix PRs against whatever repository `GITHUB_REPO` points to. No code changes are required.

**To use a different Claude model:**

```bash
CLAUDE_MODE=api
CLAUDE_MODEL=claude-sonnet-4-6   # higher quality, ~4x cost vs haiku
```

---

## Roadmap

The project is in **Week 3 of a planned 3-week build**. Local deployment is fully operational.

| Feature | Status |
|---|---|
| Docker Compose full local stack | Complete |
| Jenkins CI pipeline (4 stages) | Complete |
| SonarQube → AI Agent webhook pipeline | Complete |
| Claude fix generation (CLI + API modes) | Complete |
| GitHub PR creation (bilingual EN/FR) | Complete |
| Resilience4j circuit breakers | Complete |
| Crash recovery + idempotent re-runs | Complete |
| Angular dashboard (Auth0 + runs list + detail + stats) | Complete |
| Prometheus metrics + Grafana dashboard | Complete |
| Azure Bicep IaC (Container Apps, Flexible Server, Static Web Apps, ACR) | In progress |
| Jenkins Azure deploy stage | In progress |
| End-to-end Azure smoke test | Planned |

---

## Project Structure — Key Design Decisions

**Per-finding `@Async` tasks, not per-run** — each finding runs in its own thread. One finding's failure does not block the others, and `pipeline_status` on each row is the crash recovery checkpoint.

**`webhook_events` as a durable buffer** — the webhook controller does one thing: insert a row and return 200. A `@Scheduled` poller processes the queue separately. This decouples SonarQube's delivery timing from processing latency.

**`ON CONFLICT DO NOTHING` for idempotency** — duplicate webhook deliveries are silently ignored without extra tables or distributed locks.

**`TransactionSynchronization.afterCommit()`** — fix tasks are dispatched only after the parent transaction commits, ensuring findings are visible to the worker threads when they start.

**Vertical partition for `ai_generation_content`** — patched file content (potentially hundreds of kilobytes) is stored in a separate table so the hot `ai_generations` scan path stays narrow.

**Two Claude implementations behind one interface** — `ClaudeCliClient` (default, uses Claude Max subscription, no API cost) and `ClaudeApiClient` (HTTP call, required for cloud deployment) are selected at startup via `@ConditionalOnProperty`. Switching modes requires only an environment variable change.

**No aggregate columns in `pipeline_runs`** — `findingsCount`, `fixedCount`, and `openPrCount` are computed on read via JPQL scalar subqueries, eliminating a class of update anomalies.

---

## Contributing

Pull requests are welcome. Before opening one:

1. Run `mvn verify` on `ai-agent` and `demo-app` — both must pass
2. Run `ng build` in `dashboard/` — must complete without errors
3. Follow the existing code conventions: Java records for DTOs, `var` for obvious types, SLF4J for logging, no `@Service` → `@Repository` → `@DTO` chains without genuine complexity

The branch strategy is: feature branches merge into `dev`; `dev` merges into `main` via pull request after passing the Jenkins pipeline.

---

## License

MIT License. See [LICENSE](LICENSE) for details.

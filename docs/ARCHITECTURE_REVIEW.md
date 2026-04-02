# PatchPilot — Architecture Review

> Written from the perspective of *Designing Data-Intensive Applications* (DDIA)
> by Martin Kleppmann. Each section identifies a decision in the PRD, explains
> the risk it carries according to DDIA principles, and offers 3 alternatives
> with a recommendation for this project.
>
> **Ground rules:** Tool choices (Jenkins, SonarQube, Artifactory, Angular,
> PostgreSQL, Azure) are fixed for CV reasons and are not questioned here.
> Everything else is fair game.

---

## Issue 1 — Webhook delivery is not durable (fire-and-forget HTTP)

### The current decision
SonarQube fires a single HTTP POST to the AI Agent. The AI Agent returns 200
and hands off to `@Async`. If the AI Agent is down, restarting, or doing a
rolling deploy at the exact moment SonarQube fires, the event is lost entirely.
SonarQube will retry a few times if it gets no 200, but there is no delivery
guarantee beyond that window.

### What DDIA says
Chapter 11 (*Stream Processing*) opens with this exact problem. Kleppmann
contrasts "direct messaging" (one service calling another over HTTP) against
"message brokers" (durable logs). Direct messaging has one critical flaw:
*the sender must know whether the receiver is up and available at this exact
moment*. Any downtime in that window = silent data loss.

The chapter also introduces the log-based message broker model (Kafka's core
idea): events are written to a durable, ordered log first. The consumer reads
from the log at its own pace. A crash and restart just means replaying from the
last committed offset — no events are lost.

Chapter 8 (*The Trouble with Distributed Systems*) makes the same point
differently: any call over a network can fail for reasons invisible to the
caller. You cannot distinguish "the service is down" from "the service
processed my request but the response was lost." Treating a webhook as reliable
is an assumption you cannot prove.

### 3 alternatives

**Option A — Idempotent webhook + retry table (pragmatic minimum)**
When the webhook arrives, write it immediately to a `webhook_events` table
(the POST handler does *only* this — one INSERT, nothing else). A separate
scheduled Spring `@Scheduled` job polls that table every 10 seconds, picks up
`PENDING` rows, and processes them. If the AI Agent crashes mid-processing,
the row stays `PENDING` and gets retried on the next poll.
- Pros: No new infrastructure. Pure Spring Boot + PostgreSQL. Retry is
  automatic. Survives restarts. The webhook controller is always fast.
- Cons: Up to 10-second delay between webhook arrival and processing starting.
  Still no replay if the webhook was dropped before the INSERT.
- Complexity: Low — one table, one `@Scheduled` method.

**Option B — In-process queue with persistence (Spring Boot + DB-backed queue)**
Use Spring's `TaskExecutor` but back the queue with the database. Before
dispatching to `@Async`, persist a `pipeline_task` row. On application startup,
re-enqueue any `PENDING` or `IN_PROGRESS` tasks that were interrupted.
- Pros: Survives restarts. Still no new infrastructure.
- Cons: Requires careful startup recovery logic. Concurrent workers need
  row-level locking (`SELECT ... FOR UPDATE SKIP LOCKED`) to prevent
  double-processing. More code than Option A.
- Complexity: Medium.

**Option C — Azure Service Bus (proper durable queue)**
SonarQube fires webhook → AI Agent immediately enqueues a message to Azure
Service Bus → a Service Bus listener in the same AI Agent processes the
message. Service Bus guarantees at-least-once delivery and holds unprocessed
messages for up to 14 days. A dead-letter queue captures messages that fail
after N retries.
- Pros: Proper durable delivery. Built-in dead-letter handling. Azure Service
  Bus is on Azure CVs and is used at banks. Scales to multiple consumers.
- Cons: New Azure service to provision and pay for (~$0.10/million messages —
  negligible). Adds a dependency. Slight added complexity in Bicep + Spring
  config.
- Complexity: Medium-low (Spring has a first-class Azure Service Bus starter).

### Recommendation for PatchPilot
**Option A.** It is the right architectural instinct (the webhook becomes an
immutable record; processing is decoupled from arrival) without introducing
new infrastructure. It directly solves the data-loss problem. If you want to
impress an interviewer, you can explain: "I considered a message broker but
for the throughput here — one event per pipeline run — a DB-backed retry table
gives me the same at-least-once guarantee without operational overhead." That
is exactly what Kleppmann means when he discusses choosing the simplest tool
that satisfies the reliability requirement.

---

## Issue 2 — The webhook receiver is not idempotent

### The current decision
The PRD has `sonar_task_id VARCHAR(64) UNIQUE NOT NULL` in `pipeline_runs`,
which prevents duplicate *rows*. But the fix pipeline itself — fetching
findings, calling Claude, opening PRs — is triggered by the `@Async` dispatch.
If SonarQube retries the webhook (which it does after a timeout), two separate
`@Async` tasks could be in flight for the same `taskId` simultaneously.
The UNIQUE constraint will make the second INSERT fail, but by then the first
task is already calling Claude and creating GitHub branches.

### What DDIA says
Chapter 11 distinguishes *at-least-once delivery* (a message may be processed
more than once) from *exactly-once semantics* (requires explicit idempotency
at the consumer). The conclusion is blunt: *"you cannot assume that messages
will be delivered exactly once. At-least-once delivery is the practical
guarantee. Therefore, your consumer must be idempotent."*

Chapter 7 (*Transactions*) discusses idempotency keys: if every operation
carries a unique ID, the receiver can check "have I already processed this ID?"
before doing any work. This is the standard pattern for webhook handlers.

### 3 alternatives

**Option A — Check-then-act with a DB lock (SELECT FOR UPDATE)**
When the webhook arrives, immediately run:
```sql
INSERT INTO pipeline_runs (sonar_task_id, status, ...)
VALUES (?, 'PENDING', ...)
ON CONFLICT (sonar_task_id) DO NOTHING
RETURNING id;
```
If `RETURNING id` is empty, the run already exists — return 200 immediately
without dispatching any async work. This is a single atomic operation;
no race window.
- Pros: Zero added infrastructure. Upsert-style idempotency. Correct.
- Cons: None for this scale.
- Complexity: Very low — one SQL change.

**Option B — Idempotency key in a dedicated table**
Maintain a separate `idempotency_keys (key TEXT PRIMARY KEY, created_at TIMESTAMPTZ)`
table. Before dispatching, insert the `sonar_task_id`. On conflict, abort.
More explicit and easier to query ("which webhook deliveries were deduplicated?")
- Pros: Explicit audit of duplicate deliveries. Separation of concerns.
- Cons: Slightly more code than Option A. Requires its own Flyway migration.
- Complexity: Low.

**Option C — Optimistic locking on the status column**
Only dispatch the async work if the current `status = 'PENDING'` and a
`status = 'IN_PROGRESS'` UPDATE returns exactly 1 row affected. If 0 rows
affected, another process already claimed it.
- Pros: Works without extra columns or tables.
- Cons: Two round-trips to the DB (check + update). Introduces a small race
  window between them (mitigated by a transaction, but complex).
- Complexity: Medium.

### Recommendation for PatchPilot
**Option A.** The `ON CONFLICT DO NOTHING RETURNING id` pattern is idiomatic
PostgreSQL, requires no extra tables, and is covered in DDIA Chapter 7 as
the canonical idempotency implementation. It is also the most explainable in
an interview.

---

## Issue 3 — The fix pipeline has no crash recovery

### The current decision
The fix pipeline is a multi-step linear sequence running in a single `@Async`
thread:

```
fetch findings → for each finding: fetch file → call Claude → parse response
→ create branch → update file → open PR → persist result
```

If the JVM crashes, a thread is killed by the container orchestrator, or Claude
API returns a non-retryable error after 3 API calls have already succeeded,
there is no mechanism to resume from the last successful step. The run stays
`IN_PROGRESS` forever. A restart triggers no recovery.

### What DDIA says
Chapter 7 (*Transactions*) introduces the concept of *atomicity* in distributed
workflows: the ability to either commit all side effects or roll them back.
But here, external side effects (GitHub branches created, Claude tokens spent)
cannot be rolled back. This is a classic *saga* problem.

Chapter 11 returns to this: a multi-step pipeline that interacts with external
systems is not a transaction — it is a *saga*. Each step is individually
committed, and failure requires either compensation (undo the steps that
succeeded) or resumption (pick up from where you left off).

The practical consequence for PatchPilot: a pipeline that crashes after
creating 3 GitHub branches but before writing fix_attempt rows leaves orphaned
branches in GitHub and a run permanently stuck at `IN_PROGRESS`. When you
restart, those branches exist but your DB has no record of them.

### 3 alternatives

**Option A — Per-finding status column + idempotent steps (checkpoint pattern)**
Give `sonar_findings` a `pipeline_status` column:
`QUEUED | FETCHING_FILE | CALLING_CLAUDE | CREATING_PR | COMPLETED | FAILED`.
On restart, the scheduler (from Issue 1's Option A) re-enqueues only findings
that are not `COMPLETED` or `FAILED`. Each step checks whether it has already
produced its output before re-executing.

For the GitHub step specifically, make branch creation idempotent: use the
finding's `sonar_issue_key` as the branch name. A `409 Conflict` from GitHub
means the branch already exists — skip creation, proceed to file update.
- Pros: No new infrastructure. Granular visibility into where a pipeline
  stalled. Self-healing on restart.
- Cons: Each step must be individually idempotent. More state columns to manage.
- Complexity: Medium.

**Option B — Compensating transactions (saga with undo steps)**
If any step after the first GitHub call fails, run a cleanup: delete any
branches created during this run (call GitHub API DELETE /git/refs/heads/...).
Log the cleanup as a `COMPENSATED` status.
- Pros: GitHub stays clean. Consistent view between DB and GitHub.
- Cons: Compensation is not always possible (tokens are spent; Claude calls
  cannot be undone). Your undo step can also fail, leaving you in a half-
  compensated state.
- Complexity: High — you are now maintaining two code paths for every step.

**Option C — Treat each finding as an independent unit of work (queue per finding)**
Instead of one `@Async` task per run, dispatch one task per finding.
Each task is: fetch file → call Claude → open PR → persist.
If one fails, the others continue. A failed task can be retried independently
without re-running the whole pipeline.
- Pros: Failure isolation. Natural parallelism within a run (with a rate
  limiter to respect Claude's API limits). Simpler recovery — just re-queue
  the failed finding.
- Cons: Slightly more complex dispatcher logic (split one task into N tasks).
  Requires the rate limiter to be implemented (see Issue 5).
- Complexity: Medium.

### Recommendation for PatchPilot
**Option C** combined with the idempotent branch naming from Option A.
Dispatching one task per finding is a better data model: findings are
independent, their fixes do not depend on each other, and failure of one
should not block the others. This also directly enables the parallelism
improvement in Issue 5. The branch name being derived from `sonar_issue_key`
makes the GitHub step naturally idempotent.

---

## Issue 4 — Derived/aggregated columns in `pipeline_runs` will drift

### The current decision
```sql
findings_count  INT NOT NULL DEFAULT 0,
fixes_attempted INT NOT NULL DEFAULT 0,
fixes_pr_opened INT NOT NULL DEFAULT 0,
```
These are *derived values* — they summarize child rows in `sonar_findings`
and `fix_attempts`. They must be kept in sync manually by the application:
increment them at the right moment, remember to handle failure cases. If a bug
causes an inconsistency, there is no way to know which source of truth to trust.

### What DDIA says
Chapter 3 (*Storage and Retrieval*) distinguishes *primary data* (the facts
you store) from *derived data* (computed from primary data). The book is clear:
*"derived data is redundant — it duplicates existing information. It can usually
be recreated from the original data if it is lost."* Storing derived data in
the primary store creates a dual-write problem and violates the single-source-of-
truth principle.

Chapter 11 reinforces this: derived views should be computed from the primary
log, not maintained in parallel. The two diverge under failure.

### 3 alternatives

**Option A — Compute on read with a SQL aggregate (simplest)**
Remove the three columns. When the API serves `GET /api/runs/{id}`, run:
```sql
SELECT
  COUNT(sf.id)                              AS findings_count,
  COUNT(fa.id)                              AS fixes_attempted,
  COUNT(fa.id) FILTER (WHERE fa.status = 'PR_OPEN') AS fixes_pr_opened
FROM pipeline_runs pr
LEFT JOIN sonar_findings sf ON sf.run_id = pr.id
LEFT JOIN fix_attempts fa ON fa.run_id = pr.id
WHERE pr.id = ?
GROUP BY pr.id;
```
- Pros: Single source of truth. Counts are always correct. No sync logic.
- Cons: Slightly heavier query than a column read. Not an issue at this scale.
- Complexity: Very low — remove columns, update query.

**Option B — Materialized view refreshed on write**
Define a PostgreSQL materialized view `pipeline_run_stats` with the
aggregates. Refresh it with `REFRESH MATERIALIZED VIEW CONCURRENTLY` after
each batch of fix_attempt inserts (via a Flyway-registered trigger or from
the service).
- Pros: Fast reads. DB owns the derivation logic — application cannot
  introduce inconsistencies. Good PostgreSQL knowledge to show on a CV.
- Cons: `CONCURRENTLY` refresh requires some latency. Overkill for
  the volume here.
- Complexity: Medium.

**Option C — Keep columns, add a reconciliation job**
Keep the columns but run a `@Scheduled` reconciliation every 5 minutes that
re-computes them from the child rows and corrects any drift.
- Pros: Fast reads. Self-healing.
- Cons: Still has a consistency window. Still two sources of truth.
  The reconciliation job can itself have bugs.
- Complexity: Medium-low, but for worse outcomes than Option A.

### Recommendation for PatchPilot
**Option A.** This is the textbook DDIA answer. The aggregate query on a
single-digit-million-row table is nanoseconds slower than a column read —
immeasurable in practice. You eliminate an entire class of bugs (the
"counter is wrong" bug) and have a system where the data is always consistent
by construction. During an interview you can explain: "I removed the
denormalized summary columns because they were derived data — the source of
truth is always the child rows. The aggregates are computed on read."

---

## Issue 5 — The dashboard polls. Events should be pushed.

### The current decision
```
Angular dashboard polls GET /api/runs every 30s
```
The Angular dashboard polls the AI Agent every 30 seconds to check for new
pipeline runs and fix status updates.

### What DDIA says
Chapter 11 (*Stream Processing*) draws a direct parallel between database
*change data capture* and UI updates: if your data is changing, the client
should receive updates when they happen, not check repeatedly. Polling is
described as a "pull" model with two costs: latency (up to 30s before the
UI reflects reality) and waste (most requests return "nothing changed").

Chapter 12 (*The Future of Data Systems*) argues that moving from a
request/response model to an event-driven model — where interested parties
subscribe to a stream of changes — is architecturally cleaner and more scalable.
The UI is just another consumer of the event stream.

For a DevSecOps pipeline demo specifically: when an interviewer watches the
demo, a 30-second delay between "PR opened on GitHub" and "dashboard shows it"
looks like a bug. The expectation in 2026 is live updates.

### 3 alternatives

**Option A — Server-Sent Events (SSE)**
Add a `GET /api/runs/stream` endpoint that returns
`Content-Type: text/event-stream`. The Spring controller holds the connection
open using `SseEmitter` and pushes a JSON payload whenever a run's status
changes or a fix attempt is created. Angular's `EventSource` API handles
reconnection automatically.
- Pros: Unidirectional (server→client only — perfect for this case). Works
  over HTTP/1.1, no WebSocket handshake required. Automatic reconnect built
  into the browser. Spring Boot has first-class `SseEmitter` support.
- Cons: Each open tab holds an HTTP connection open on the server. At 1
  active user this is irrelevant.
- Complexity: Low — one new endpoint, one Angular service change.

**Option B — WebSocket (bidirectional)**
Use Spring WebSocket (`@EnableWebSocketMessageBroker` + STOMP). The server
pushes run updates to a `/topic/runs` channel. Angular subscribes with the
`@stomp/rx-stomp` library.
- Pros: Bidirectional — if you later add the ability to cancel a run from the
  dashboard, you already have the channel for it.
- Cons: More setup than SSE (STOMP protocol, SockJS fallback). Overkill for a
  one-way feed.
- Complexity: Medium.

**Option C — Reduce poll interval to 5s with conditional GET (`ETag`/`Last-Modified`)**
Keep polling but add HTTP caching headers. The server responds with `304 Not Modified`
when nothing has changed. The browser skips parsing the body.
- Pros: Zero new code on the Angular side. Minimal server change.
- Cons: Still polling. Still 5-second latency. `ETag` implementation requires
  computing a hash of the response, or tracking a `last_updated` timestamp.
- Complexity: Low, but worst outcome of the three.

### Recommendation for PatchPilot
**Option A — SSE.** It is the right tool: unidirectional, HTTP-native, no
extra protocol. The implementation is small (one `SseEmitter` per connected
client, stored in a `ConcurrentHashMap`, notified from the service layer after
each status change). It makes the demo *feel* real-time, which matters when
recording a 5-minute video. DDIA's framing: you are treating the pipeline state
as a stream of change events, and the UI is a subscriber to that stream.

---

## Issue 6 — `suggested_fix TEXT` stores large content in the wrong place

### The current decision
```sql
suggested_fix TEXT  -- the patched file content from Claude
```
The full patched file content (potentially 500–5,000 lines of Java code) is
stored as a TEXT column directly in the `fix_attempts` table. Every query
that touches `fix_attempts` — including the list query for the dashboard —
loads this content into memory even when it is not needed.

### What DDIA says
Chapter 3 (*Storage and Retrieval*) discusses row-oriented storage: when you
query a table, the storage engine reads *entire rows*, even if you only need
two columns. Storing large BLOBs inline with small metadata columns causes
the storage engine to read all that data off disk on every scan. For
OLTP workloads (the dashboard's list view), this is pure overhead.

Chapter 3 also discusses *column-oriented storage* as the alternative, but
that is OLAP territory. The simpler fix for OLTP is to store large values out-
of-band and keep only a reference in the row — the classic "pointer to blob
storage" pattern.

PostgreSQL's TOAST (The Oversized-Attribute Storage Technique) does compress
and externalize TEXT over 2KB automatically, but the data still lives in the
same tablespace, and the main row still carries a pointer that affects index
scan performance.

### 3 alternatives

**Option A — Store in Azure Blob Storage (or local volume), keep a path reference**
Instead of the content itself, store a path:
```sql
suggested_fix_path TEXT  -- e.g., "runs/42/findings/77/fix.java"
```
The AI Agent writes the fix to Azure Blob Storage (or a Docker volume locally).
The API endpoint `GET /api/runs/{id}/fixes/{fixId}/content` streams it on demand.
- Pros: `fix_attempts` rows are small and fast to scan. The blob is only
  fetched when explicitly requested. Azure Blob Storage is free up to 5GB.
  This is the industry-standard pattern (think GitHub storing file contents
  separately from metadata).
- Cons: Two storage systems to manage. The blob must be cleaned up if the
  fix_attempt row is deleted (or use a TTL policy in blob storage).
- Complexity: Medium — new Azure Blob Storage container in Bicep, one
  additional service class.

**Option B — Keep TEXT but add a separate content table (vertical partitioning)**
```sql
CREATE TABLE fix_attempt_content (
    fix_attempt_id BIGINT PRIMARY KEY REFERENCES fix_attempts(id),
    suggested_fix  TEXT NOT NULL
);
```
The main `fix_attempts` table never loads the large TEXT. Content is fetched
only via explicit JOIN or separate query.
- Pros: No new infrastructure. PostgreSQL only. Clean column layout. The large
  text is still in PostgreSQL but not scanned on every `fix_attempts` query.
- Cons: The data is still in PostgreSQL, which means it counts toward your
  Azure Flexible Server storage quota. Slightly more complex query when the
  content is needed.
- Complexity: Low — one extra table, one migration.

**Option C — Keep `suggested_fix TEXT` but exclude it from all list queries**
Do nothing structurally. Instead, ensure that `GET /api/runs` and
`GET /api/runs/{id}/fixes` use a Spring Data JPA projection interface that
explicitly excludes `suggestedFix`. The full content is only loaded by
`GET /api/runs/{id}/fixes/{fixId}` (single-item endpoint).
- Pros: Zero schema changes. Zero infrastructure changes. Works today.
- Cons: Depends entirely on developer discipline — anyone who adds
  `findAll()` will accidentally load all the large text. PostgreSQL
  still reads the TOAST data for MVCC purposes even with projections.
- Complexity: Very low, but least robust.

### Recommendation for PatchPilot
**Option B** for now, **Option A** later. Vertical partitioning (Option B)
requires a single Flyway migration and removes the column from the hot query
path immediately. No new infrastructure. When you add Azure to the project
(Week 3), upgrading to Option A (Azure Blob Storage) is a natural extension
that adds another Azure service to your Bicep and CV. During an interview:
"I noticed that storing large generated code in the same table as metadata
would hurt scan performance, so I vertically partitioned it. In production I'd
move it to blob storage to decouple the storage tier."

---

## Issue 7 — Pipeline state is mutable; history is lost

### The current decision
```sql
status VARCHAR(32) NOT NULL  -- PENDING | IN_PROGRESS | COMPLETED | FAILED
```
Both `pipeline_runs` and `fix_attempts` track state with a single mutable
`status` column. When a run goes from `PENDING` → `IN_PROGRESS` → `COMPLETED`,
the intermediate states are gone. You cannot answer: how long did the run
spend in `IN_PROGRESS`? At what time did it transition to `FAILED`?

### What DDIA says
Chapter 11 introduces *event sourcing*: instead of storing the current state,
store the *sequence of events that caused that state*. The current state is
derived by replaying the events. Kleppmann argues this is a more natural model
for systems where "what happened" matters as much as "what is true now."

For an AI agent pipeline, "what happened" is extremely valuable:
- How long did the Claude API call take on this specific finding?
- Did this run retry, and if so why?
- Was `IN_PROGRESS` for 3 minutes (normal) or 30 minutes (something was hanging)?

None of these questions can be answered from a mutable status column.

### 3 alternatives

**Option A — Add `status_changed_at` + status history table (audit log)**
```sql
CREATE TABLE pipeline_run_events (
    id         BIGSERIAL PRIMARY KEY,
    run_id     BIGINT NOT NULL REFERENCES pipeline_runs(id),
    event_type VARCHAR(64) NOT NULL,  -- e.g. 'PIPELINE_STARTED', 'FINDING_QUEUED', 'PR_OPENED'
    payload    JSONB,                  -- optional context (finding id, PR url, error msg, etc.)
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
Keep the mutable `status` column for fast current-state reads. Append to
`pipeline_run_events` at every meaningful transition. The Grafana dashboard
and any "how long did this step take?" query reads from the events table.
- Pros: Current state is still a fast column read. Full history is preserved.
  The events table doubles as the audit log. No fundamental change to the
  existing query patterns.
- Cons: Every status change requires two writes (update `status` column +
  insert event row). Both must be in the same transaction.
- Complexity: Low — one new table, updated service methods.

**Option B — Full event sourcing (status derived from events only)**
Remove the `status` column entirely. Current state is computed by reading
the latest event for a given run. A PostgreSQL view or a Spring service method
performs the derivation on every read.
- Pros: Single source of truth (the event log). DDIA-pure. You can replay
  events to reconstruct any past state.
- Cons: Every "what is the current status?" query now requires an aggregate.
  Complex to reason about in a team. Overkill for a 3-week MVP.
- Complexity: High.

**Option C — Add timestamped columns for each transition**
```sql
pipeline_started_at   TIMESTAMPTZ,
findings_fetched_at   TIMESTAMPTZ,
pipeline_finished_at  TIMESTAMPTZ,
```
- Pros: Simple. Trivial to compute "how long did the findings fetch take?"
- Cons: Adding a new pipeline step requires a schema migration. Does not
  capture failure messages at each step. Does not generalize.
- Complexity: Very low, but brittle.

### Recommendation for PatchPilot
**Option A.** Append-only event log alongside the mutable status column is
the pragmatic production pattern at banks (DDIA calls it "the dual model").
It is also what makes your Grafana dashboard actually interesting: instead of
just showing "status: COMPLETED", you can show a step timeline ("findings
fetched at T+2s, Claude responded at T+18s, PR opened at T+21s"). That
specificity is what makes the demo compelling. During an interview: "I kept
the status column for the API's current-state reads but added an append-only
events table so I could answer operational questions about the pipeline without
modifying history."

---

## Issue 8 — No circuit breaker on external API calls

### The current decision
The AI Agent calls three external APIs in the hot path: SonarQube, Claude,
and GitHub. There is no failure budget, no timeout beyond the HTTP client
default, and no circuit breaker. If Claude API is degraded and responds in
30 seconds instead of 3, each `@Async` thread blocks for 30 seconds. With
10 findings per run and multiple concurrent runs, the thread pool fills up
and all incoming requests — including health checks — queue behind blocked
threads.

### What DDIA says
Chapter 8 (*The Trouble with Distributed Systems*) is dedicated to this class
of failure. The key insight: *"A slow network call is often more dangerous
than a fast failure."* A fast failure is detected and handled. A slow call
holds a thread (or a socket, or a connection pool slot) hostage for the
duration. This is how cascading failures propagate — one slow dependency makes
your service slow, which makes *you* a slow dependency for your callers.

Chapter 8 also discusses *timeouts*: the only defense against a slow dependency
is a deadline. Without a deadline, a call can hang indefinitely.

### 3 alternatives

**Option A — Explicit HTTP timeouts + `@Retryable` with backoff (minimum viable)**
Configure connect/read timeouts on `RestClient`:
```java
RestClient.builder()
    .requestFactory(new HttpComponentsClientHttpRequestFactory(
        HttpClientBuilder.create()
            .setConnectionRequestTimeout(2000)
            .setResponseTimeout(30000)  // Claude can be slow; tune per-client
            .build()
    ))
    .build();
```
Add `@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))`
on Claude and GitHub calls. On final failure, persist `status = FAILED` with
the error message.
- Pros: No new library. Spring Retry is already in the Spring Boot ecosystem.
  Timeouts prevent indefinite hangs. Retry with backoff handles transient
  failures.
- Cons: No state tracking across retries — if the service restarts mid-retry,
  the retry sequence restarts. No adaptive behaviour (if Claude is fully down,
  you still try 3 times per finding × 7 findings before giving up).
- Complexity: Low.

**Option B — Resilience4j circuit breaker**
Add Resilience4j (the Spring Boot 3 standard for fault tolerance):
```java
@CircuitBreaker(name = "claude-api", fallbackMethod = "claudeFallback")
@TimeLimiter(name = "claude-api")
public FixResponse callClaude(String prompt) { ... }
```
Configure: after 5 failures in a 10-second window, open the circuit for 30
seconds. During open state, calls fail immediately without attempting Claude.
After 30 seconds, try one probe call; if it succeeds, close the circuit.
- Pros: Prevents cascade failure. Adaptive — stops hammering a down dependency.
  Circuit state is a Micrometer metric, so Grafana can show it. Resilience4j
  is the industry standard in Spring Boot shops.
- Cons: Requires understanding circuit breaker state machine (CLOSED/OPEN/HALF-OPEN).
  Configuration takes thought (what are the right thresholds?).
- Complexity: Medium-low. The library is well-documented.

**Option C — Timeout at the finding level with a deadline**
Rather than per-call timeouts, set a deadline for the entire per-finding
pipeline (e.g., 60 seconds total). Use Java 21's `StructuredTaskScope` to
run the multi-step pipeline with an overall timeout. If any step causes the
scope to miss the deadline, the whole finding is marked `FAILED`.
- Pros: Clean Java 21 virtual thread idiom. Demonstrates knowledge of
  Project Loom. Prevents any single finding from blocking others indefinitely.
- Cons: `StructuredTaskScope` is a preview API in Java 21 (finalized in
  Java 23). Not recommended for production code yet.
- Complexity: Medium.

### Recommendation for PatchPilot
**Option A** now, **Option B** in Week 3 polish. Start with explicit timeouts
(zero overhead, prevents the worst case). Add Resilience4j when you have time —
it adds a circuit breaker panel to Grafana, which looks great in the demo and
is something every bank's engineering standards document requires. During an
interview: "I used Resilience4j to add circuit breakers on the Claude and
GitHub API clients. You can see the circuit state on the Grafana dashboard —
if Claude's API is having issues, the circuit opens and findings are marked
FAILED immediately instead of blocking the thread pool."

---

## Issue 9 — The data model conflates two different things in `fix_attempts`

### The current decision
`fix_attempts` stores both the *AI generation result* (prompt tokens,
completion tokens, confidence score, `suggested_fix`) and the *GitHub PR
state* (pr_url, pr_number, pr_title_en/fr, pr_body_en/fr, status). These are
two distinct concepts: generating a fix, and publishing it as a PR. They have
different lifecycles — the generation happens once, the PR status changes
over time as reviewers comment and close it.

### What DDIA says
Chapter 2 (*Data Models and Query Languages*) discusses the principle that
a good data model should reflect *real-world entities* and their natural
lifecycles, not be shaped by the first query you happen to need. Mixing two
concepts in one table creates update anomalies: what does it mean to
update `suggested_fix` after the PR is open? Is the PR now stale?

Chapter 2 also discusses *normalization*: represent each fact in exactly one
place. PR state belongs to the PR entity, not to the AI generation entity.

### 3 alternatives

**Option A — Split into `ai_generations` and `github_prs` tables**
```sql
-- What Claude produced
CREATE TABLE ai_generations (
    id             BIGSERIAL PRIMARY KEY,
    finding_id     BIGINT NOT NULL REFERENCES sonar_findings(id),
    model_used     VARCHAR(64) NOT NULL,
    prompt_tokens  INT,
    completion_tokens INT,
    confidence_score  NUMERIC(4,3),
    suggested_fix_path TEXT,        -- reference to blob storage (see Issue 6)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The GitHub PR created from a generation
CREATE TABLE github_prs (
    id              BIGSERIAL PRIMARY KEY,
    generation_id   BIGINT NOT NULL REFERENCES ai_generations(id),
    pr_number       INT NOT NULL,
    pr_url          TEXT NOT NULL,
    pr_title_en     TEXT,
    pr_title_fr     TEXT,
    pr_body_en      TEXT,
    pr_body_fr      TEXT,
    status          VARCHAR(32) NOT NULL,  -- PR_OPEN | PR_MERGED | PR_CLOSED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
- Pros: Each table has one responsibility. PR status can be updated without
  touching AI generation data. Adding a PR outcome tracking feature (Phase 2)
  only requires adding columns to `github_prs`. Conceptually clean.
- Cons: More tables to JOIN. More Flyway migrations. Slightly more service code.
- Complexity: Medium — one extra table, updated JPA entities.

**Option B — Keep one table but add a clear separation comment + use projections**
Keep `fix_attempts` as is but document it as two logical sections in the DDL,
and ensure no query updates the "AI generation" section after the PR is created.
- Pros: Zero schema changes.
- Cons: Relies entirely on developer discipline. Future developers (or future you)
  will violate the boundary.
- Complexity: None — but no actual improvement.

**Option C — Embed PR state as JSONB on `fix_attempts`**
```sql
pr_state JSONB  -- { "number": 15, "url": "...", "status": "PR_OPEN", "title_en": "...", ... }
```
The PR state is a document that evolves over time; update the whole JSONB.
- Pros: Flexible — adding new PR fields requires no migration.
- Cons: No column-level constraints on the PR fields. Querying inside JSONB
  requires GIN indexes. Mixing structured (generation) and semi-structured (PR)
  in one row is confusing.
- Complexity: Low schema change, but the queryability is worse.

### Recommendation for PatchPilot
**Option A.** The split is conceptually correct and directly enables the Phase 2
feature of PR outcome tracking (merges, closes, reviewer comments) — you just
add columns to `github_prs`. The fact that you planned this in the data model
from the start is a strong interview signal. The migration is one extra SQL
file. During an interview: "I separated AI generation results from GitHub PR
state because they have different lifecycles. The generation is immutable once
created — it's a fact about what Claude produced. The PR state evolves as
reviewers interact with it."

---

## Summary of recommendations

| # | Issue | Current approach | Recommended fix | DDIA chapter |
|---|-------|-----------------|-----------------|--------------|
| 1 | Webhook durability | Fire-and-forget HTTP | Webhook events table + retry scheduler | Ch. 11 |
| 2 | Webhook idempotency | UNIQUE constraint (too late) | `ON CONFLICT DO NOTHING RETURNING id` | Ch. 7, 11 |
| 3 | Pipeline crash recovery | No recovery | Per-finding tasks + idempotent branch names | Ch. 7, 11 |
| 4 | Derived aggregates in schema | `findings_count` / `fixes_pr_opened` columns | Compute on read via SQL aggregate | Ch. 3 |
| 5 | Dashboard polling | `GET /api/runs` every 30s | SSE (`SseEmitter`) for push updates | Ch. 11, 12 |
| 6 | Large text in hot table | `suggested_fix TEXT` inline | Vertical partition into separate table | Ch. 3 |
| 7 | Mutable state, lost history | Single `status` column | Append-only events table alongside status | Ch. 11 |
| 8 | No failure budget | No timeouts or circuit breakers | HTTP timeouts + Resilience4j circuit breaker | Ch. 8 |
| 9 | Mixed concerns in `fix_attempts` | AI result + PR state in one table | Split into `ai_generations` + `github_prs` | Ch. 2 |

### What to fix before writing any code (high-leverage, low-effort)
Issues **2**, **4**, and **9** are schema decisions. Fix them now in the
Flyway V1 migration before a single line of application code is written —
they are cheap to change in DDL, expensive to change after the entities exist.

Issues **1** and **3** (webhook durability and crash recovery) should be done
in Week 2 alongside the AI Agent — they shape the `@Async` dispatch architecture.

Issues **5**, **7**, and **8** (SSE, event log, Resilience4j) are Week 3 polish —
they make the demo and the Grafana dashboard significantly better without
changing the core data model.

Issue **6** (vertical partitioning) is a safe Week 2 addition — one table,
one migration, removes a latent performance bug before it becomes visible.

---

*Last updated: 2026-03-26*

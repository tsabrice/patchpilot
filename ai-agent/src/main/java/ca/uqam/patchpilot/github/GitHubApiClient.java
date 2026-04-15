package ca.uqam.patchpilot.github;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Thin wrapper around four GitHub REST API endpoints:
 *   GET  /repos/{owner}/{repo}/contents/{path}       — fetch file + SHA
 *   GET  /repos/{owner}/{repo}/git/refs/heads/{ref}  — resolve branch HEAD SHA
 *   POST /repos/{owner}/{repo}/git/refs              — create branch
 *   PUT  /repos/{owner}/{repo}/contents/{path}       — commit patched file
 *   POST /repos/{owner}/{repo}/pulls                 — open pull request
 *
 * Why RestClient (not WebClient or RestTemplate)?
 * - RestTemplate is deprecated in Spring Boot 3.
 * - WebClient is reactive (non-blocking). Our @Async tasks are already on
 *   their own threads, so blocking IO is fine and keeps the code simpler.
 * - RestClient is the modern blocking client introduced in Spring Boot 3.2.
 *   Same fluent API style as WebClient but synchronous.
 *
 * Every public method carries @CircuitBreaker(name = "github-api").
 * After 5 failures in a 10-call sliding window the circuit opens for 30 s.
 * Circuit state is a Micrometer metric — visible in Grafana.
 *
 * API version pinned to 2022-11-28 to avoid silent breaking changes.
 * Docs: https://docs.github.com/en/rest/repos/contents
 */
@Service
public class GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);

    private final RestClient restClient;
    private final String owner;
    private final String repo;

    // ── Internal JSON shapes (private — not part of the public API) ──────────

    /** Relevant fields from GET /repos/.../contents/{path} response. */
    private record GetFileResponse(String content, String sha) {}

    /**
     * GET /repos/.../git/refs/heads/{branch} returns an object whose
     * "object" field contains the commit SHA at the branch tip.
     */
    private record GetRefResponse(RefObject object) {}
    private record RefObject(String sha) {}

    /** Body for POST /repos/.../git/refs (branch creation). */
    private record CreateRefRequest(String ref, String sha) {}

    /**
     * Body for PUT /repos/.../contents/{path}.
     * "content" must be base64-encoded. "sha" is the current blob SHA —
     * GitHub uses it as an optimistic lock (stale SHA → 409 Conflict).
     */
    private record UpdateFileRequest(String message, String content,
                                     String sha, String branch) {}

    /** Body for POST /repos/.../pulls. */
    private record CreatePrRequest(String title, String body,
                                   String head, String base) {}

    /** Fields from POST /repos/.../pulls response that we persist. */
    private record CreatePrResponse(int number, String html_url) {}

    /** One entry from GET /repos/.../pulls list — only fields we need. */
    private record PrListItem(int number, String html_url) {}

    // ── Public return types ───────────────────────────────────────────────────

    /**
     * Decoded file content + the blob SHA the PUT endpoint needs.
     * Keep the SHA untouched — passing a stale or modified SHA causes 409.
     */
    public record FileContent(String path, String content, String sha) {}

    /** PR number (stored in github_prs) and the GitHub web URL. */
    public record CreatedPr(int prNumber, String prUrl) {}

    // ── Constructor ───────────────────────────────────────────────────────────

    public GitHubApiClient(
            RestClient.Builder builder,
            @Value("${github.token}") String token,
            @Value("${github.owner}") String owner,
            @Value("${github.repo}") String repo) {
        this.owner = owner;
        this.repo = repo;
        // All GitHub calls share these headers — configure once on the client.
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches a file's UTF-8 content and its current blob SHA.
     *
     * The SHA is required by updateFile() — the PUT endpoint rejects updates
     * that don't include the exact current SHA (GitHub's optimistic locking).
     *
     * @param filePath bare path within the repo, e.g. "src/main/java/Foo.java"
     * @param branch   branch to read from, e.g. "main"
     * @return FileContent, or null if the path does not exist on that branch
     *         (caller must check null and mark the finding as SKIPPED)
     */
    @CircuitBreaker(name = "github-api", fallbackMethod = "getFileContentFallback")
    public FileContent getFileContent(String filePath, String branch) {
        log.debug("Fetching file: path={} branch={}", filePath, branch);
        try {
            var response = restClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}?ref={branch}",
                            owner, repo, filePath, branch)
                    .retrieve()
                    .body(GetFileResponse.class);

            if (response == null) {
                log.warn("GitHub returned empty body for file: path={} branch={}", filePath, branch);
                return null;
            }

            // GitHub encodes file content as base64 with line breaks every 60 chars.
            // MIME decoder handles those embedded newlines; standard decoder does not.
            var decoded = new String(Base64.getMimeDecoder().decode(response.content()), StandardCharsets.UTF_8);
            log.debug("Fetched file: path={} sha={}", filePath, response.sha());
            return new FileContent(filePath, decoded, response.sha());

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("File not found on GitHub: path={} branch={}", filePath, branch);
                return null;
            }
            throw e;
        }
    }

    public FileContent getFileContentFallback(String filePath, String branch, Throwable t) {
        log.error("GitHub circuit breaker open — getFileContent failed: path={}", filePath, t);
        throw new RuntimeException("GitHub API unavailable (circuit breaker open)", t);
    }

    /**
     * Creates "refs/heads/{branchName}" from the tip of fromBranch.
     *
     * Idempotent: GitHub returns 422 if the ref already exists.
     * We catch that and continue — the branch was created by an earlier
     * attempt of this task, so re-using it is safe.
     *
     * @param branchName e.g. "patchpilot/fix-AY3k..."
     * @param fromBranch the branch to fork from, e.g. "main"
     */
    @CircuitBreaker(name = "github-api", fallbackMethod = "createBranchFallback")
    public void createBranch(String branchName, String fromBranch) {
        log.debug("Creating branch: {} from {}", branchName, fromBranch);

        // Resolve the HEAD commit SHA of fromBranch — the refs API requires it.
        var baseSha = getRefSha(fromBranch);

        try {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                    .body(new CreateRefRequest("refs/heads/" + branchName, baseSha))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Branch created: {}", branchName);

        } catch (HttpClientErrorException e) {
            // 422 Unprocessable Entity = ref already exists — continue safely.
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                log.info("Branch already exists — skipping creation: {}", branchName);
                return;
            }
            throw e;
        }
    }

    public void createBranchFallback(String branchName, String fromBranch, Throwable t) {
        log.error("GitHub circuit breaker open — createBranch failed: {}", branchName, t);
        throw new RuntimeException("GitHub API unavailable (circuit breaker open)", t);
    }

    /**
     * Commits patchedContent to filePath on branch.
     *
     * currentSha is the blob SHA from getFileContent() — pass it back unchanged.
     * A stale SHA (another commit on the branch between our GET and PUT)
     * causes GitHub to return 409 Conflict.
     */
    @CircuitBreaker(name = "github-api", fallbackMethod = "updateFileFallback")
    public void updateFile(String filePath, String patchedContent,
                           String currentSha, String branch, String commitMessage) {
        log.debug("Committing file: path={} branch={}", filePath, branch);

        // PUT body requires base64-encoded content (standard encoding, no line breaks).
        var encoded = Base64.getEncoder().encodeToString(patchedContent.getBytes(StandardCharsets.UTF_8));

        restClient.put()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                .body(new UpdateFileRequest(commitMessage, encoded, currentSha, branch))
                .retrieve()
                .toBodilessEntity();

        log.debug("File committed: path={} branch={}", filePath, branch);
    }

    public void updateFileFallback(String filePath, String patchedContent,
                                   String currentSha, String branch,
                                   String commitMessage, Throwable t) {
        log.error("GitHub circuit breaker open — updateFile failed: path={}", filePath, t);
        throw new RuntimeException("GitHub API unavailable (circuit breaker open)", t);
    }

    /**
     * Opens a pull request with a bilingual title and body.
     *
     * Title format: "{titleEn} | {titleFr}" — both languages visible in the
     * PR list without opening the PR. Body: English first, French below a
     * separator. French content differentiates this portfolio project.
     *
     * @return CreatedPr with the GitHub PR number and html_url
     */
    @CircuitBreaker(name = "github-api", fallbackMethod = "createPullRequestFallback")
    public CreatedPr createPullRequest(String head, String base,
                                       String titleEn, String titleFr,
                                       String bodyEn, String bodyFr) {
        log.debug("Creating PR: {} → {}", head, base);

        var combinedBody = """
                %s

                ---

                **Version française / French version**

                %s
                """.formatted(bodyEn, bodyFr);

        try {
            var response = restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                    .body(new CreatePrRequest(titleEn + " | " + titleFr, combinedBody, head, base))
                    .retrieve()
                    .body(CreatePrResponse.class);

            if (response == null) {
                throw new RuntimeException("GitHub API returned empty response for PR creation: " + head);
            }
            log.info("PR created: #{} — {}", response.number(), response.html_url());
            return new CreatedPr(response.number(), response.html_url());

        } catch (HttpClientErrorException e) {
            // 422 = a PR already exists for this head → base pair. This happens
            // when the pipeline re-runs for the same finding (crash recovery or
            // repeated Jenkins builds). Fetch the existing PR rather than failing.
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                log.info("PR already exists for branch {} — fetching existing PR", head);
                return findExistingPullRequest(head, base);
            }
            throw e;
        }
    }

    public CreatedPr createPullRequestFallback(String head, String base,
                                               String titleEn, String titleFr,
                                               String bodyEn, String bodyFr,
                                               Throwable t) {
        log.error("GitHub circuit breaker open — createPullRequest failed: {}", head, t);
        throw new RuntimeException("GitHub API unavailable (circuit breaker open)", t);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Finds an open PR whose head branch matches. Called when createPullRequest()
     * receives 422 (PR already exists). Returns the first matching open PR.
     */
    private CreatedPr findExistingPullRequest(String head, String base) {
        var prs = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls?head={owner}:{head}&base={base}&state=open",
                        owner, repo, owner, head, base)
                .retrieve()
                .body(PrListItem[].class);
        if (prs != null && prs.length > 0) {
            log.info("Found existing PR: #{} — {}", prs[0].number(), prs[0].html_url());
            return new CreatedPr(prs[0].number(), prs[0].html_url());
        }
        throw new RuntimeException("PR already exists for branch " + head + " but could not be found via list endpoint");
    }

    /**
     * Returns the HEAD commit SHA of a branch by reading its ref.
     * Called internally by createBranch() — not exposed publicly because
     * callers have no use for the raw SHA.
     */
    private String getRefSha(String branch) {
        var response = restClient.get()
                .uri("/repos/{owner}/{repo}/git/refs/heads/{branch}",
                        owner, repo, branch)
                .retrieve()
                .body(GetRefResponse.class);
        if (response == null) {
            throw new RuntimeException("GitHub API returned empty ref for branch: " + branch);
        }
        return response.object().sha();
    }
}

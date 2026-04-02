package ca.uqam.patchpilot.sonar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the SonarQube REST API.
 *
 * Only one operation is needed: fetch all open (unresolved) issues for a
 * given project key. SonarQube caps page size at 500, so this client
 * paginates automatically until all issues are fetched.
 *
 * Authentication: SonarQube tokens are sent as a Bearer token in the
 * Authorization header (supported since SonarQube 10.0). For older SQ
 * versions, the token can also be sent as HTTP Basic with the token as
 * the username and an empty password — but Bearer is the modern approach.
 *
 * Endpoint verified against:
 *   https://next.sonarqube.com/sonarqube/web_api/api/issues/search
 */
@Component
public class SonarQubeApiClient {

    private static final Logger log = LoggerFactory.getLogger(SonarQubeApiClient.class);

    // SonarQube CE's hard cap per page; higher values are silently clamped.
    private static final int PAGE_SIZE = 500;

    private final RestClient restClient;

    // RestClient is configured once at construction — baseUrl and auth header
    // are fixed for the lifetime of the application.
    public SonarQubeApiClient(
            @Value("${sonarqube.url}") String baseUrl,
            @Value("${sonarqube.token}") String token) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    /**
     * Fetches all open (unresolved) issues for a SonarQube project.
     *
     * Paginates automatically — each page requests 500 issues. For the
     * demo app with ~6 intentional findings, this is always one round trip.
     *
     * @param projectKey SonarQube project key (e.g. "demo-app")
     * @return all open issues; empty list if the project has no findings
     * @throws org.springframework.web.client.RestClientException on HTTP errors
     */
    public List<SonarIssue> getFindings(String projectKey) {
        var allIssues = new ArrayList<SonarIssue>();
        int page = 1;

        while (true) {
            final int currentPage = page;
            var response = restClient.get()
                    .uri(uri -> uri
                            .path("/api/issues/search")
                            .queryParam("componentKeys", projectKey)
                            .queryParam("resolved", "false")
                            .queryParam("ps", PAGE_SIZE)
                            .queryParam("p", currentPage)
                            .build())
                    .retrieve()
                    .body(IssueSearchResponse.class);

            if (response == null || response.issues() == null || response.issues().isEmpty()) {
                break;
            }

            allIssues.addAll(response.issues());
            log.debug("SonarQube page {}: got {} issue(s) (total={})",
                    currentPage, response.issues().size(),
                    response.paging() != null ? response.paging().total() : "?");

            // Stop when we have fetched everything
            if (response.paging() == null
                    || (long) currentPage * PAGE_SIZE >= response.paging().total()) {
                break;
            }
            page++;
        }

        log.info("SonarQube: {} finding(s) fetched for project '{}'", allIssues.size(), projectKey);
        return allIssues;
    }

    // ── Response shape ─────────────────────────────────────────────────────────
    // Inner records: only used for deserialization — no need for top-level classes.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueSearchResponse(Paging paging, List<SonarIssue> issues) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Paging(int pageIndex, int pageSize, int total) {}
}

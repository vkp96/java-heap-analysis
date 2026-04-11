package org.test.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Creates a GitHub Issue from an {@link AnalysisResult} and assigns it to the
 * Copilot Coding Agent, which will automatically open a fix PR.
 *
 * <p>Usage example:
 * <pre>{@code
 * // After AnalyzerPipeline completes:
 * GitHubIssueCreator issueCreator = new GitHubIssueCreator("vkp96", "java-heap-analysis");
 * GitHubIssueCreator.CreatedIssue issue = issueCreator.createFromAnalysis(result);
 * System.out.println("Issue created: " + issue.htmlUrl());
 * // Copilot Coding Agent will automatically pick up the issue and create a fix PR
 * }</pre>
 *
 * <p>The GitHub API token is read from the {@code GITHUB_TOKEN} or {@code GH_TOKEN}
 * environment variable when not supplied directly. A token with {@code repo} scope
 * (or at least {@code issues:write}) is required.
 */
public class GitHubIssueCreator {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubIssueCreator.class);

    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final String LABEL_NAME = "heap-leak-fix";

    private final String owner;
    private final String repo;
    private final String token;
    private final String apiBaseUrl;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    //  Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code GitHubIssueCreator} that reads the API token from the
     * {@code GITHUB_TOKEN} or {@code GH_TOKEN} environment variable.
     *
     * @param owner GitHub repository owner (user or organization)
     * @param repo  GitHub repository name
     * @throws IllegalStateException if neither {@code GITHUB_TOKEN} nor
     *                               {@code GH_TOKEN} is set
     */
    public GitHubIssueCreator(String owner, String repo) {
        this(owner, repo, resolveToken(), DEFAULT_API_BASE_URL);
    }

    /**
     * Creates a {@code GitHubIssueCreator} with an explicit API token and the
     * default {@code https://api.github.com} base URL.
     *
     * @param owner GitHub repository owner
     * @param repo  GitHub repository name
     * @param token GitHub personal access token or {@code GITHUB_TOKEN}
     */
    public GitHubIssueCreator(String owner, String repo, String token) {
        this(owner, repo, token, DEFAULT_API_BASE_URL);
    }

    /**
     * Creates a {@code GitHubIssueCreator} with full configuration, including a
     * custom API base URL for GitHub Enterprise support.
     *
     * @param owner      GitHub repository owner
     * @param repo       GitHub repository name
     * @param token      GitHub API token
     * @param apiBaseUrl GitHub API base URL (e.g. {@code https://github.example.com/api/v3})
     */
    public GitHubIssueCreator(String owner, String repo, String token, String apiBaseUrl) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo must not be blank");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.apiBaseUrl = apiBaseUrl != null && !apiBaseUrl.isBlank() ? apiBaseUrl : DEFAULT_API_BASE_URL;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Formats an {@link AnalysisResult} as a GitHub Issue and creates it via the
     * GitHub REST API. The issue is assigned to {@code copilot} so the Copilot
     * Coding Agent automatically picks it up and opens a fix PR.
     *
     * @param result the analysis result produced by {@code AnalyzerPipeline}
     * @return a {@link CreatedIssue} containing the new issue number and URL
     * @throws GitHubIssueException if the GitHub API call fails
     */
    public CreatedIssue createFromAnalysis(AnalysisResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }

        String title = buildTitle(result);
        String body  = buildBody(result);

        LOGGER.info("Creating GitHub issue: {}", title);

        try {
            // Ensure the label exists before referencing it
            ensureLabelExists();

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("title", title);
            payload.put("body", body);

            ArrayNode assignees = payload.putArray("assignees");
            assignees.add("copilot");

            ArrayNode labels = payload.putArray("labels");
            labels.add(LABEL_NAME);

            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/repos/" + owner + "/" + repo + "/issues"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 201) {
                String msg = "GitHub API returned HTTP " + response.statusCode()
                        + " when creating issue. Body: " + response.body();
                LOGGER.error(msg);
                throw new GitHubIssueException(msg);
            }

            ObjectNode responseJson = (ObjectNode) objectMapper.readTree(response.body());
            int issueNumber = responseJson.get("number").asInt();
            String htmlUrl  = responseJson.get("html_url").asText();

            LOGGER.info("GitHub issue #{} created successfully: {}", issueNumber, htmlUrl);
            return new CreatedIssue(issueNumber, htmlUrl);

        } catch (GitHubIssueException e) {
            throw e;
        } catch (Exception e) {
            String msg = "Failed to create GitHub issue: " + e.getMessage();
            LOGGER.error(msg, e);
            throw new GitHubIssueException(msg, e);
        }
    }

    // -------------------------------------------------------------------------
    //  Formatting helpers
    // -------------------------------------------------------------------------

    private static String buildTitle(AnalysisResult result) {
        if (result.rootCause == null) {
            return "🔴 Memory Leak Fix: (unknown root cause)";
        }
        AnalysisResult.RootCause rc = result.rootCause;
        String cls    = nvl(rc.responsibleClass, "UnknownClass");
        String method = nvl(rc.responsibleMethod, "unknownMethod");
        String type   = nvl(rc.leakPatternType, "UNKNOWN_PATTERN");
        return "🔴 Memory Leak Fix: " + cls + "." + method + " — " + type;
    }

    private static String buildBody(AnalysisResult result) {
        StringBuilder sb = new StringBuilder();

        // Overview
        sb.append("## Overview\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| **Summary** | ").append(escMd(nvl(result.summary, "—"))).append(" |\n");
        sb.append("| **Confidence** | ").append(escMd(nvl(result.confidence, "—"))).append(" |\n");
        sb.append("| **Estimated Leak Size (MB)** | ")
                .append(result.estimatedLeakSizeMb != null ? result.estimatedLeakSizeMb : "—")
                .append(" |\n");
        sb.append("| **Heap Dump** | `").append(escMd(nvl(result.heapDumpPath, "—"))).append("` |\n");
        sb.append("| **Analyzed At** | ").append(escMd(nvl(result.analyzedAt, "—"))).append(" |\n");
        sb.append("\n");

        // Root cause
        if (result.rootCause != null) {
            AnalysisResult.RootCause rc = result.rootCause;
            sb.append("## Root Cause\n\n");
            sb.append("| Field | Value |\n");
            sb.append("|---|---|\n");
            sb.append("| **Description** | ").append(escMd(nvl(rc.description, "—"))).append(" |\n");
            sb.append("| **Responsible Class** | `").append(escMd(nvl(rc.responsibleClass, "—"))).append("` |\n");
            sb.append("| **Responsible Method** | `").append(escMd(nvl(rc.responsibleMethod, "—"))).append("` |\n");
            sb.append("| **Leak Pattern** | `").append(escMd(nvl(rc.leakPatternType, "—"))).append("` |\n");
            sb.append("\n");

            if (rc.detailedExplanation != null && !rc.detailedExplanation.isBlank()) {
                sb.append("### Detailed Explanation\n\n");
                sb.append(rc.detailedExplanation.trim()).append("\n\n");
            }

            if (rc.codeSearchKeywords != null && !rc.codeSearchKeywords.isEmpty()) {
                sb.append("### Code Search Keywords\n\n");
                for (String kw : rc.codeSearchKeywords) {
                    sb.append("- `").append(escMd(kw)).append("`\n");
                }
                sb.append("\n");
            }
        }

        // Remediation
        if (result.remediation != null && !result.remediation.isEmpty()) {
            sb.append("## Remediation Steps\n\n");
            int i = 1;
            for (String step : result.remediation) {
                sb.append(i++).append(". ").append(step).append("\n");
            }
            sb.append("\n");
        }

        // Top retained objects
        if (result.topRetainedObjects != null && !result.topRetainedObjects.isEmpty()) {
            sb.append("## Top Retained Objects\n\n");
            sb.append("| Class | Retained Bytes | % of Heap | Suspect |\n");
            sb.append("|---|---:|---:|:---:|\n");
            for (AnalysisResult.RetainedObject obj : result.topRetainedObjects) {
                String suspect = Boolean.TRUE.equals(obj.isSuspect) ? "⚠️ Yes" : "No";
                sb.append("| `").append(escMd(nvl(obj.className, "?")))
                        .append("` | ")
                        .append(obj.retainedHeapBytes != null ? obj.retainedHeapBytes : "—")
                        .append(" | ")
                        .append(obj.retainedHeapPct != null
                                ? String.format("%.2f%%", obj.retainedHeapPct) : "—")
                        .append(" | ").append(suspect).append(" |\n");
            }
            sb.append("\n");
        }

        // Footer
        sb.append("---\n");
        sb.append("> ⚙️ This issue was auto-generated from heap dump analysis by `HeapDumpWatcher` / `AnalyzerPipeline`.\n");
        sb.append("> Assign this issue to **Copilot** to trigger an automatic fix PR.\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    //  Label helper
    // -------------------------------------------------------------------------

    /**
     * Creates the {@value #LABEL_NAME} label in the target repository if it does
     * not already exist. Failures are logged but do not abort issue creation.
     */
    private void ensureLabelExists() {
        try {
            // Check if label exists
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/repos/" + owner + "/" + repo + "/labels/" + LABEL_NAME))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<String> getResponse =
                    httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());

            if (getResponse.statusCode() == 200) {
                LOGGER.debug("Label '{}' already exists.", LABEL_NAME);
                return;
            }

            // Create the label
            ObjectNode labelPayload = objectMapper.createObjectNode();
            labelPayload.put("name", LABEL_NAME);
            labelPayload.put("color", "e11d48");
            labelPayload.put("description", "Auto-generated heap leak fix — handled by Copilot Coding Agent");

            HttpRequest createRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/repos/" + owner + "/" + repo + "/labels"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(labelPayload)))
                    .build();

            HttpResponse<String> createResponse =
                    httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString());

            if (createResponse.statusCode() == 201) {
                LOGGER.info("Label '{}' created in {}/{}.", LABEL_NAME, owner, repo);
            } else {
                LOGGER.warn("Could not create label '{}' (HTTP {}). The issue will be created without it.",
                        LABEL_NAME, createResponse.statusCode());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to ensure label '{}' exists: {}. Continuing without label.",
                    LABEL_NAME, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    //  Utility helpers
    // -------------------------------------------------------------------------

    private static String resolveToken() {
        String t = System.getenv("GITHUB_TOKEN");
        if (t == null || t.isBlank()) {
            t = System.getenv("GH_TOKEN");
        }
        if (t == null || t.isBlank()) {
            throw new IllegalStateException(
                    "No GitHub token found. Set the GITHUB_TOKEN or GH_TOKEN environment variable.");
        }
        return t;
    }

    /** Null-safe value-or-default. */
    private static String nvl(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /** Escape pipe characters so they don't break Markdown tables. */
    private static String escMd(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    // -------------------------------------------------------------------------
    //  Result type
    // -------------------------------------------------------------------------

    /**
     * Represents a successfully created GitHub Issue.
     *
     * @param issueNumber the GitHub issue number
     * @param htmlUrl     the browser URL of the newly created issue
     */
    public record CreatedIssue(int issueNumber, String htmlUrl) {}

    // -------------------------------------------------------------------------
    //  Exception type
    // -------------------------------------------------------------------------

    /**
     * Thrown when the GitHub API call to create an issue fails.
     */
    public static class GitHubIssueException extends RuntimeException {
        public GitHubIssueException(String message) {
            super(message);
        }
        public GitHubIssueException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package org.test.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

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
     * <p>If an open issue with the same title and label already exists, this method
     * returns the existing issue instead of creating a duplicate.
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
            // Check for duplicate open issue with same title
            CreatedIssue existing = findExistingOpenIssue(title);
            if (existing != null) {
                LOGGER.info("Duplicate open issue found: #{} — {}. Skipping creation.",
                        existing.issueNumber(), existing.htmlUrl());
                return existing;
            }

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

        // GC root chains
        if (result.gcRootChains != null && !result.gcRootChains.isEmpty()) {
            sb.append("## GC Root Chains\n\n");
            for (AnalysisResult.GcRootChain chain : result.gcRootChains) {
                sb.append("**").append(escMd(nvl(chain.chainLabel, "Chain"))).append("**");
                if (chain.retainedHeapBytes != null) {
                    sb.append(" — retained ").append(chain.retainedHeapBytes).append(" bytes");
                }
                sb.append("\n");
                sb.append("- Root type: `").append(escMd(nvl(chain.rootType, "?"))).append("`\n");
                sb.append("- Root object: `").append(escMd(nvl(chain.rootObject, "?"))).append("`\n");
                if (chain.referencePath != null && !chain.referencePath.isEmpty()) {
                    sb.append("- Path: ");
                    for (int i = 0; i < chain.referencePath.size(); i++) {
                        AnalysisResult.ReferenceStep step = chain.referencePath.get(i);
                        if (i > 0) sb.append(" → ");
                        sb.append("`").append(escMd(nvl(step.from, "?")))
                                .append(".").append(escMd(nvl(step.viaField, "?")))
                                .append("`");
                    }
                    sb.append(" → `").append(escMd(nvl(chain.suspectObject, "?"))).append("`\n");
                }
                sb.append("\n");
            }
        }

        // Dominant allocator stacks
        if (result.dominantAllocatorStacks != null && !result.dominantAllocatorStacks.isEmpty()) {
            sb.append("## Dominant Allocator Stacks\n\n");
            for (AnalysisResult.AllocatorStack stack : result.dominantAllocatorStacks) {
                sb.append("**`").append(escMd(nvl(stack.allocatorMethod, "?"))).append("`**");
                if (stack.objectCount != null) {
                    sb.append(" — ").append(stack.objectCount).append(" objects");
                }
                if (stack.retainedHeapBytes != null) {
                    sb.append(", ").append(stack.retainedHeapBytes).append(" bytes retained");
                }
                sb.append("\n");
                if (stack.leakPattern != null && !stack.leakPattern.isBlank()) {
                    sb.append("- Pattern: ").append(stack.leakPattern).append("\n");
                }
                if (stack.stackFrames != null && !stack.stackFrames.isEmpty()) {
                    sb.append("- Stack:\n");
                    sb.append("  ```\n");
                    for (String frame : stack.stackFrames) {
                        sb.append("  ").append(frame).append("\n");
                    }
                    sb.append("  ```\n");
                }
                sb.append("\n");
            }
        }

        // Instructions for Copilot Coding Agent
        sb.append("## Instructions for Copilot\n\n");
        sb.append("You are the Copilot Coding Agent. This issue was auto-generated from a heap dump analysis ");
        sb.append("that detected an OutOfMemoryError. Your task is to **find and fix the memory leak** ");
        sb.append("described above by opening a Pull Request.\n\n");

        sb.append("### What to fix\n\n");
        if (result.rootCause != null) {
            AnalysisResult.RootCause rc = result.rootCause;
            if (rc.responsibleClass != null && !rc.responsibleClass.isBlank()) {
                sb.append("- **Primary target class**: `").append(escMd(rc.responsibleClass)).append("`\n");
            }
            if (rc.responsibleMethod != null && !rc.responsibleMethod.isBlank()) {
                sb.append("- **Primary target method**: `").append(escMd(rc.responsibleMethod)).append("`\n");
            }
            if (rc.leakPatternType != null && !rc.leakPatternType.isBlank()) {
                sb.append("- **Leak pattern**: `").append(escMd(rc.leakPatternType)).append("`\n");
            }
            sb.append("\n");
        }

        if (result.remediation != null && !result.remediation.isEmpty()) {
            sb.append("### Checklist of changes\n\n");
            for (String step : result.remediation) {
                sb.append("- [ ] ").append(step).append("\n");
            }
            sb.append("\n");
        }

        if (result.rootCause != null && result.rootCause.codeSearchKeywords != null
                && !result.rootCause.codeSearchKeywords.isEmpty()) {
            sb.append("### Search hints\n\n");
            sb.append("Use these keywords to locate relevant code in the repository:\n");
            for (String kw : result.rootCause.codeSearchKeywords) {
                sb.append("- `").append(escMd(kw)).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("### Guidelines\n\n");
        sb.append("1. Keep changes **minimal and focused** on fixing the memory leak.\n");
        sb.append("2. Do not refactor unrelated code.\n");
        sb.append("3. Add or update unit tests if applicable.\n");
        sb.append("4. Title the PR with prefix `[OOM Fix]`.\n");
        sb.append("5. Reference this issue in the PR body.\n\n");

        // Footer
        sb.append("---\n");
        sb.append("> ⚙️ This issue was auto-generated from heap dump analysis by `HeapDumpWatcher` / `AnalyzerPipeline`.\n");
        sb.append("> Assigned to **Copilot** for automatic fix PR generation.\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    //  Duplicate detection
    // -------------------------------------------------------------------------

    /**
     * Searches for an existing open issue with the same title and label.
     *
     * @param title the exact issue title to search for
     * @return the existing issue if found, or {@code null}
     */
    private CreatedIssue findExistingOpenIssue(String title) {
        try {
            String responsibleClass = extractResponsibleClassFromTitle(title);
            if (responsibleClass == null) {
                return null;
            }

            String query = "repo:" + owner + "/" + repo
                    + " label:" + LABEL_NAME
                    + " is:open is:issue"
                    + " in:title " + responsibleClass;

            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/search/issues?q=" + encodedQuery + "&per_page=5"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warn("Duplicate issue search returned HTTP {}. Proceeding with creation.", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) {
                return null;
            }

            for (JsonNode item : items) {
                String existingTitle = item.path("title").asText("");
                if (existingTitle.equals(title)) {
                    int number = item.path("number").asInt();
                    String htmlUrl = item.path("html_url").asText("");
                    return new CreatedIssue(number, htmlUrl);
                }
            }

            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to check for duplicate issues: {}. Proceeding with creation.", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the responsible class name from the issue title for duplicate matching.
     */
    private static String extractResponsibleClassFromTitle(String title) {
        if (title == null || !title.contains("Memory Leak Fix:")) {
            return null;
        }
        int start = title.indexOf("Memory Leak Fix:") + "Memory Leak Fix:".length();
        int end = title.indexOf(" — ", start);
        if (end < 0) {
            end = title.length();
        }
        String classMethod = title.substring(start, end).trim();
        int lastDot = classMethod.lastIndexOf('.');
        if (lastDot > 0) {
            return classMethod.substring(0, lastDot);
        }
        return classMethod.isBlank() ? null : classMethod;
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

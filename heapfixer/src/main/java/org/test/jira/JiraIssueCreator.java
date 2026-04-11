package org.test.jira;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates a Jira Story from an {@link AnalysisResult} by communicating with an
 * MCP (Model Context Protocol) enabled Jira server via JSON-RPC 2.0.
 *
 * <p>The class sends a {@code tools/call} request to the MCP server, which then
 * creates the Jira issue on behalf of the caller. No direct Jira REST API calls
 * are made.
 *
 * <p>Usage examples:
 * <pre>{@code
 * // Reads MCP server URL from JIRA_MCP_SERVER_URL and project key from JIRA_PROJECT_KEY env vars:
 * JiraIssueCreator jiraCreator = new JiraIssueCreator();
 *
 * // Explicit project key (MCP server URL still read from JIRA_MCP_SERVER_URL):
 * JiraIssueCreator jiraCreator = new JiraIssueCreator("HEAPFIX");
 *
 * // Explicit MCP server URL and project key:
 * JiraIssueCreator jiraCreator = new JiraIssueCreator("http://localhost:3000", "HEAPFIX");
 *
 * JiraIssueCreator.CreatedJiraIssue issue = jiraCreator.createFromAnalysis(result);
 * System.out.println("Jira story created: " + issue.issueKey());  // e.g., HEAPFIX-123
 * System.out.println("Browse: " + issue.browseUrl());
 * }</pre>
 *
 * <p>Configuration via environment variables:
 * <ul>
 *   <li>{@code JIRA_MCP_SERVER_URL} — MCP server URL (e.g. {@code http://localhost:3000})</li>
 *   <li>{@code JIRA_PROJECT_KEY}    — Jira project key (e.g. {@code HEAPFIX})</li>
 * </ul>
 */
public class JiraIssueCreator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JiraIssueCreator.class);

    private static final String DEFAULT_MCP_SERVER_URL = "http://localhost:3000";
    private static final String DEFAULT_ISSUE_TYPE = "Story";
    private static final String DEFAULT_PRIORITY = "High";
    private static final List<String> DEFAULT_LABELS =
            Arrays.asList("memory-leak", "heap-analysis", "auto-generated");

    /** Monotonically increasing JSON-RPC request ID. */
    private static final AtomicLong REQUEST_ID_COUNTER = new AtomicLong(1);

    private final String mcpServerUrl;
    private final String projectKey;
    private final String issueType;
    private final String priority;
    private final List<String> labels;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    //  Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code JiraIssueCreator} that reads both the MCP server URL and
     * project key from environment variables.
     *
     * @throws IllegalStateException if {@code JIRA_PROJECT_KEY} is not set
     */
    public JiraIssueCreator() {
        this(resolveMcpServerUrl(), resolveProjectKey());
    }

    /**
     * Creates a {@code JiraIssueCreator} that reads the MCP server URL from the
     * {@code JIRA_MCP_SERVER_URL} environment variable (falling back to
     * {@code http://localhost:3000}) and uses the supplied project key.
     *
     * @param projectKey Jira project key (e.g. {@code "HEAPFIX"})
     */
    public JiraIssueCreator(String projectKey) {
        this(resolveMcpServerUrl(), projectKey);
    }

    /**
     * Creates a {@code JiraIssueCreator} with an explicit MCP server URL and
     * project key, using the default issue type ({@code "Story"}), priority
     * ({@code "High"}), and labels.
     *
     * @param mcpServerUrl base URL of the MCP server (e.g. {@code "http://localhost:3000"})
     * @param projectKey   Jira project key (e.g. {@code "HEAPFIX"})
     */
    public JiraIssueCreator(String mcpServerUrl, String projectKey) {
        this(mcpServerUrl, projectKey, DEFAULT_ISSUE_TYPE, DEFAULT_PRIORITY, DEFAULT_LABELS);
    }

    /**
     * Creates a {@code JiraIssueCreator} with full configuration.
     *
     * @param mcpServerUrl base URL of the MCP server
     * @param projectKey   Jira project key
     * @param issueType    Jira issue type (e.g. {@code "Story"}, {@code "Bug"})
     * @param priority     Jira priority (e.g. {@code "High"}, {@code "Medium"})
     * @param labels       list of Jira labels to attach to the issue
     */
    public JiraIssueCreator(String mcpServerUrl, String projectKey,
                             String issueType, String priority, List<String> labels) {
        if (mcpServerUrl == null || mcpServerUrl.isBlank()) {
            throw new IllegalArgumentException("mcpServerUrl must not be blank");
        }
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException("projectKey must not be blank");
        }
        this.mcpServerUrl = mcpServerUrl;
        this.projectKey = projectKey;
        this.issueType = issueType != null && !issueType.isBlank() ? issueType : DEFAULT_ISSUE_TYPE;
        this.priority = priority != null && !priority.isBlank() ? priority : DEFAULT_PRIORITY;
        this.labels = labels != null ? labels : DEFAULT_LABELS;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Formats an {@link AnalysisResult} as a Jira Story and creates it on the
     * MCP-enabled Jira server by sending a JSON-RPC 2.0 {@code tools/call}
     * request to the MCP server.
     *
     * @param result the analysis result produced by {@code AnalyzerPipeline}
     * @return a {@link CreatedJiraIssue} containing the new issue key, ID, and URLs
     * @throws JiraIssueException if the MCP server call fails or returns an error
     */
    public CreatedJiraIssue createFromAnalysis(AnalysisResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }

        String summary = buildSummary(result);
        String description = buildDescription(result);

        LOGGER.info("Creating Jira {} via MCP: {}", issueType, summary);

        try {
            // Build MCP tool arguments
            ObjectNode arguments = objectMapper.createObjectNode();
            arguments.put("project", projectKey);
            arguments.put("summary", summary);
            arguments.put("description", description);
            arguments.put("issuetype", issueType);
            arguments.put("priority", priority);

            ArrayNode labelsNode = arguments.putArray("labels");
            for (String label : labels) {
                labelsNode.add(label);
            }

            // Build MCP JSON-RPC 2.0 request
            long requestId = REQUEST_ID_COUNTER.getAndIncrement();
            ObjectNode mcpRequest = objectMapper.createObjectNode();
            mcpRequest.put("jsonrpc", "2.0");
            mcpRequest.put("id", requestId);
            mcpRequest.put("method", "tools/call");

            ObjectNode params = mcpRequest.putObject("params");
            params.put("name", "jira_create_issue");
            params.set("arguments", arguments);

            String requestBody = objectMapper.writeValueAsString(mcpRequest);
            LOGGER.debug("Sending MCP request (id={}): {}", requestId, requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mcpServerUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-MCP-Protocol", "json-rpc-2.0")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            LOGGER.debug("MCP server responded with HTTP {}: {}", response.statusCode(), response.body());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String msg = "MCP server returned HTTP " + response.statusCode()
                        + " when calling jira_create_issue. Body: " + response.body();
                LOGGER.error(msg);
                throw new JiraIssueException(msg);
            }

            return parseMcpResponse(response.body(), summary);

        } catch (JiraIssueException e) {
            throw e;
        } catch (Exception e) {
            String msg = "Failed to create Jira issue via MCP: " + e.getMessage();
            LOGGER.error(msg, e);
            throw new JiraIssueException(msg, e);
        }
    }

    // -------------------------------------------------------------------------
    //  MCP response parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a JSON-RPC 2.0 response from the MCP server and extracts the
     * created Jira issue details.
     *
     * <p>The MCP server wraps the Jira REST API response in a {@code result.content}
     * array. Each element has a {@code type} and {@code text} field. The text
     * field contains a JSON string with the Jira issue details ({@code id},
     * {@code key}, {@code self}).
     */
    private CreatedJiraIssue parseMcpResponse(String responseBody, String summary) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // Check for JSON-RPC error
        if (root.has("error")) {
            JsonNode error = root.get("error");
            int code = error.path("code").asInt(0);
            String message = error.path("message").asText("Unknown MCP error");
            String msg = "MCP server returned JSON-RPC error (code=" + code + "): " + message;
            LOGGER.error(msg);
            throw new JiraIssueException(msg);
        }

        JsonNode resultNode = root.path("result");

        // MCP result content is an array of {type, text} objects
        JsonNode contentArray = resultNode.path("content");
        String issueJson = null;
        if (contentArray.isArray()) {
            for (JsonNode item : contentArray) {
                if ("text".equals(item.path("type").asText())) {
                    issueJson = item.path("text").asText(null);
                    break;
                }
            }
        }

        // Fall back to a plain result object if content is not present
        if (issueJson == null && resultNode.has("key")) {
            issueJson = resultNode.toString();
        }

        if (issueJson == null || issueJson.isBlank()) {
            String msg = "MCP server returned an unexpected result format. Body: " + responseBody;
            LOGGER.error(msg);
            throw new JiraIssueException(msg);
        }

        JsonNode issueNode = objectMapper.readTree(issueJson);
        String issueKey = issueNode.path("key").asText(null);
        String issueId  = issueNode.path("id").asText(null);
        String selfUrl  = issueNode.path("self").asText(null);

        if (issueKey == null || issueKey.isBlank()) {
            String msg = "MCP response did not contain an issue key. Parsed issue JSON: " + issueJson;
            LOGGER.error(msg);
            throw new JiraIssueException(msg);
        }

        // Derive the browse URL from the self URL's base, or construct a reasonable default
        String browseUrl = deriveBrowseUrl(selfUrl, issueKey);

        LOGGER.info("Jira {} created: {} — {}", issueType, issueKey, browseUrl);
        return new CreatedJiraIssue(issueKey, issueId != null ? issueId : "", selfUrl != null ? selfUrl : "", browseUrl);
    }

    /**
     * Derives a human-readable Jira browse URL from the Jira self URL and issue key.
     * E.g. given {@code https://jira.example.com/rest/api/2/issue/10001} and
     * {@code HEAPFIX-123}, returns {@code https://jira.example.com/browse/HEAPFIX-123}.
     */
    private static String deriveBrowseUrl(String selfUrl, String issueKey) {
        if (selfUrl == null || selfUrl.isBlank()) {
            return "";
        }
        try {
            // Strip everything from /rest/ onward to get the base URL
            int restIdx = selfUrl.indexOf("/rest/");
            if (restIdx > 0) {
                return selfUrl.substring(0, restIdx) + "/browse/" + issueKey;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not derive browse URL from selfUrl '{}': {}", selfUrl, e.getMessage());
        }
        return selfUrl;
    }

    // -------------------------------------------------------------------------
    //  Formatting helpers
    // -------------------------------------------------------------------------

    /**
     * Formats the Jira Story summary line from the analysis result.
     * Pattern: {@code [Heap Leak] Memory Leak Fix: {class}.{method} — {leakPatternType}}
     */
    private static String buildSummary(AnalysisResult result) {
        if (result.rootCause == null) {
            return "[Heap Leak] Memory Leak Fix: (unknown root cause)";
        }
        AnalysisResult.RootCause rc = result.rootCause;
        String cls    = nvl(rc.responsibleClass, "UnknownClass");
        String method = nvl(rc.responsibleMethod, "unknownMethod");
        String type   = nvl(rc.leakPatternType, "UNKNOWN_PATTERN");
        return String.format("[Heap Leak] Memory Leak Fix: %s.%s \u2014 %s", cls, method, type);
    }

    /**
     * Formats a rich Jira Story description using Jira wiki markup.
     * Includes overview, root cause, code search keywords, remediation steps,
     * top retained objects table, and a traceability footer.
     */
    private static String buildDescription(AnalysisResult result) {
        StringBuilder sb = new StringBuilder();

        // Overview table
        sb.append("h2. Overview\n\n");
        sb.append("||Field||Value||\n");
        sb.append("|*Summary*|").append(escJira(nvl(result.summary, "\u2014"))).append("|\n");
        sb.append("|*Confidence*|").append(escJira(nvl(result.confidence, "\u2014"))).append("|\n");
        sb.append("|*Estimated Leak Size (MB)*|")
                .append(result.estimatedLeakSizeMb != null ? result.estimatedLeakSizeMb : "\u2014")
                .append("|\n");
        sb.append("|*Heap Dump*|{{").append(escJira(nvl(result.heapDumpPath, "\u2014"))).append("}}|\n");
        sb.append("|*Analyzed At*|").append(escJira(nvl(result.analyzedAt, "\u2014"))).append("|\n");
        sb.append("\n");

        // Root cause
        if (result.rootCause != null) {
            AnalysisResult.RootCause rc = result.rootCause;
            sb.append("h2. Root Cause\n\n");
            sb.append("||Field||Value||\n");
            sb.append("|*Description*|").append(escJira(nvl(rc.description, "\u2014"))).append("|\n");
            sb.append("|*Responsible Class*|{{").append(escJira(nvl(rc.responsibleClass, "\u2014"))).append("}}|\n");
            sb.append("|*Responsible Method*|{{").append(escJira(nvl(rc.responsibleMethod, "\u2014"))).append("}}|\n");
            sb.append("|*Leak Pattern*|{{").append(escJira(nvl(rc.leakPatternType, "\u2014"))).append("}}|\n");
            sb.append("\n");

            if (rc.detailedExplanation != null && !rc.detailedExplanation.isBlank()) {
                sb.append("h3. Detailed Explanation\n\n");
                sb.append(rc.detailedExplanation.trim()).append("\n\n");
            }

            if (rc.codeSearchKeywords != null && !rc.codeSearchKeywords.isEmpty()) {
                sb.append("h3. Code Search Keywords\n\n");
                for (String kw : rc.codeSearchKeywords) {
                    sb.append("* {{").append(escJira(kw)).append("}}\n");
                }
                sb.append("\n");
            }
        }

        // Remediation steps
        if (result.remediation != null && !result.remediation.isEmpty()) {
            sb.append("h2. Remediation Steps\n\n");
            int i = 1;
            for (String step : result.remediation) {
                sb.append(i++).append(". ").append(escJira(step)).append("\n");
            }
            sb.append("\n");
        }

        // Top retained objects table
        if (result.topRetainedObjects != null && !result.topRetainedObjects.isEmpty()) {
            sb.append("h2. Top Retained Objects\n\n");
            sb.append("||Class||Retained Bytes||% of Heap||Suspect||\n");
            for (AnalysisResult.RetainedObject obj : result.topRetainedObjects) {
                String suspect = Boolean.TRUE.equals(obj.isSuspect) ? "(!) Yes" : "No";
                sb.append("|{{").append(escJira(nvl(obj.className, "?")))
                        .append("}}|")
                        .append(obj.retainedHeapBytes != null ? obj.retainedHeapBytes : "\u2014")
                        .append("|")
                        .append(obj.retainedHeapPct != null
                                ? String.format("%.2f%%", obj.retainedHeapPct) : "\u2014")
                        .append("|").append(suspect).append("|\n");
            }
            sb.append("\n");
        }

        // Footer
        sb.append("----\n");
        sb.append("_This story was auto-generated from heap dump analysis by_ {{HeapDumpWatcher}} _/_ {{AnalyzerPipeline}}. ");
        sb.append("Heap dump: {{").append(escJira(nvl(result.heapDumpPath, "N/A"))).append("}}. ");
        sb.append("Analyzed at: ").append(escJira(nvl(result.analyzedAt, "N/A"))).append("._");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    //  Utility helpers
    // -------------------------------------------------------------------------

    private static String resolveMcpServerUrl() {
        String url = System.getenv("JIRA_MCP_SERVER_URL");
        if (url == null || url.isBlank()) {
            LOGGER.info("JIRA_MCP_SERVER_URL not set; defaulting to {}", DEFAULT_MCP_SERVER_URL);
            return DEFAULT_MCP_SERVER_URL;
        }
        return url;
    }

    private static String resolveProjectKey() {
        String key = System.getenv("JIRA_PROJECT_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "No Jira project key found. Set the JIRA_PROJECT_KEY environment variable "
                            + "or pass the project key to the constructor.");
        }
        return key;
    }

    /** Null-safe value-or-default. */
    private static String nvl(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Escapes characters that have special meaning in Jira wiki markup inside
     * table cells and inline text (pipe, curly braces, square brackets).
     *
     * <p>Backslash is escaped <em>first</em> intentionally: this ensures that
     * the {@code \} characters already present in the input are doubled before
     * we add new {@code \} escape prefixes for other special characters, thereby
     * preventing double-escaping of the newly-inserted prefixes.
     */
    private static String escJira(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("[", "\\[");
    }

    // -------------------------------------------------------------------------
    //  Result type
    // -------------------------------------------------------------------------

    /**
     * Represents a successfully created Jira issue.
     *
     * @param issueKey  the Jira issue key (e.g. {@code "HEAPFIX-123"})
     * @param issueId   the internal Jira issue ID
     * @param selfUrl   the Jira REST API URL for the issue
     * @param browseUrl the human-readable Jira browse URL
     */
    public record CreatedJiraIssue(String issueKey, String issueId,
                                    String selfUrl, String browseUrl) {}

    // -------------------------------------------------------------------------
    //  Exception type
    // -------------------------------------------------------------------------

    /**
     * Thrown when the MCP server call to create a Jira issue fails.
     */
    public static class JiraIssueException extends RuntimeException {
        public JiraIssueException(String message) {
            super(message);
        }
        public JiraIssueException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

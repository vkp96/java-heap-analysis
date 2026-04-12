package org.test.jira;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone creator that turns an {@link AnalysisResult} into a Jira Story via
 * an MCP-enabled Jira server over HTTP.
 *
 * <p>The exact MCP tool name and argument schema can vary by server, so this
 * implementation uses a best-effort strategy:
 * <ol>
 *   <li>tries to list available MCP tools using {@code tools/list}</li>
 *   <li>selects the most Jira/story/issue-creation-looking tool name</li>
 *   <li>invokes it using {@code tools/call}</li>
 *   <li>sends multiple common field aliases for title/description/acceptance criteria</li>
 * </ol>
 *
 * <p>Runtime configuration can be supplied with CLI flags or environment variables:
 * <ul>
 *   <li>{@code HEAPFIXER_JIRA_MCP_URL}</li>
 *   <li>{@code HEAPFIXER_JIRA_MCP_TOKEN}</li>
 *   <li>{@code HEAPFIXER_JIRA_MCP_AUTH_HEADER} (defaults to {@code Authorization})</li>
 *   <li>{@code HEAPFIXER_JIRA_MCP_TOOL_NAME}</li>
 *   <li>{@code HEAPFIXER_JIRA_PROJECT_KEY}</li>
 * </ul>
 */
public class JiraIssueCreator {

    private static final Logger LOG = LoggerFactory.getLogger(JiraIssueCreator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]+-\\d+\\b");
    private static final List<String> DEFAULT_TOOL_CANDIDATES = List.of(
            "jira_create_issue",
            "create_jira_issue",
            "jira.createIssue",
            "jira.create_issue",
            "create_issue",
            "createIssue"
    );

    private final HttpClient httpClient;
    private final URI endpointUri;
    private final String token;
    private final String authHeaderName;
    private final String toolNameOverride;
    private final String projectKey;

    public JiraIssueCreator(String endpointUrl) {
        this(
                endpointUrl,
                blankToNull(System.getenv("HEAPFIXER_JIRA_MCP_TOKEN")),
                firstNonBlank(System.getenv("HEAPFIXER_JIRA_MCP_AUTH_HEADER"), "Authorization"),
                blankToNull(System.getenv("HEAPFIXER_JIRA_MCP_TOOL_NAME")),
                blankToNull(System.getenv("HEAPFIXER_JIRA_PROJECT_KEY"))
        );
    }

    public JiraIssueCreator(String endpointUrl,
                            String token,
                            String authHeaderName,
                            String toolNameOverride,
                            String projectKey) {
        String normalizedEndpoint = requireNonBlank(endpointUrl, "endpointUrl");
        this.endpointUri = URI.create(normalizedEndpoint);
        this.token = blankToNull(token);
        this.authHeaderName = firstNonBlank(authHeaderName, "Authorization");
        this.toolNameOverride = blankToNull(toolNameOverride);
        this.projectKey = blankToNull(projectKey);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Creates a Jira story from the supplied heap analysis result.
     */
    public JiraIssueCreationResult createFromAnalysis(AnalysisResult analysisResult) throws Exception {
        return createIssue(buildDraft(analysisResult));
    }

    /**
     * Creates a Jira story from a normalized draft payload.
     */
    public JiraIssueCreationResult createIssue(JiraStoryDraft draft) throws Exception {
        Objects.requireNonNull(draft, "draft must not be null");

        String toolName = resolveToolName();
        ObjectNode requestPayload = buildToolCallRequest(toolName, draft);
        String requestBody = MAPPER.writeValueAsString(requestPayload);

        LOG.info("Creating Jira story via MCP endpoint {} using tool {}", endpointUri, toolName);
        HttpResponse<String> response = sendJson(requestBody);
        JsonNode parsedResponse = parseResponseBody(response.body());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Jira MCP endpoint returned HTTP " + response.statusCode()
                    + " while creating issue. Body: " + response.body());
        }
        if (parsedResponse.has("error")) {
            throw new IllegalStateException("Jira MCP endpoint returned an error: " + parsedResponse.get("error"));
        }

        JiraIssueCreationResult result = new JiraIssueCreationResult();
        result.created = true;
        result.dryRun = false;
        result.endpointUrl = endpointUri.toString();
        result.toolName = toolName;
        result.title = draft.title;
        result.description = draft.description;
        result.acceptanceCriteria = draft.acceptanceCriteria;
        result.issueKey = extractIssueKey(parsedResponse);
        result.issueId = extractDeepText(parsedResponse, "issueId", "issue_id", "id");
        result.issueUrl = firstNonBlank(
                extractDeepText(parsedResponse, "issueUrl", "issue_url", "browseUrl", "browse_url", "html_url", "url", "self"),
                extractPotentialUrl(parsedResponse)
        );
        result.requestPayload = requestPayload;
        result.responsePayload = parsedResponse;
        result.rawResponseBody = response.body();

        if (result.issueKey == null) {
            result.notes.add("Created response did not contain a clearly identifiable Jira issue key.");
        }
        if (result.issueUrl == null) {
            result.notes.add("Created response did not contain a clearly identifiable Jira issue URL.");
        }
        return result;
    }

    /**
     * Builds the Jira content from the heap analysis result.
     */
    public static JiraStoryDraft buildDraft(AnalysisResult result) throws Exception {
        Objects.requireNonNull(result, "result must not be null");

        JiraStoryDraft draft = new JiraStoryDraft();
        draft.title = buildTitle(result);
        draft.description = buildDescription(result);
        draft.acceptanceCriteria = buildAcceptanceCriteria(result);
        return draft;
    }

    private String resolveToolName() {
        if (toolNameOverride != null) {
            return toolNameOverride;
        }

        try {
            List<String> toolNames = listTools();
            if (!toolNames.isEmpty()) {
                String selected = toolNames.stream()
                        .max(Comparator.comparingInt(JiraIssueCreator::scoreToolName))
                        .orElse(null);
                if (scoreToolName(selected) > 0) {
                    LOG.info("Selected Jira MCP tool '{}' from discovered tools: {}", selected, toolNames);
                    return selected;
                }
                LOG.warn("Discovered MCP tools did not include an obvious Jira issue-creation tool: {}", toolNames);
            }
        } catch (Exception e) {
            LOG.warn("Could not list MCP tools from {}: {}. Falling back to default tool names.", endpointUri, e.getMessage());
        }

        return DEFAULT_TOOL_CANDIDATES.get(0);
    }

    private List<String> listTools() throws Exception {
        ObjectNode request = jsonRpcRequest("tools/list", MAPPER.createObjectNode());
        HttpResponse<String> response = sendJson(MAPPER.writeValueAsString(request));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("tools/list returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode parsedResponse = parseResponseBody(response.body());
        JsonNode toolsNode = parsedResponse.path("result").path("tools");
        if (!toolsNode.isArray()) {
            toolsNode = parsedResponse.path("tools");
        }
        if (!toolsNode.isArray()) {
            return List.of();
        }

        Set<String> toolNames = new LinkedHashSet<>();
        for (JsonNode toolNode : toolsNode) {
            String toolName = blankToNull(toolNode.path("name").asText(null));
            if (toolName != null) {
                toolNames.add(toolName);
            }
        }
        return new ArrayList<>(toolNames);
    }

    private HttpResponse<String> sendJson(String requestBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(endpointUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        if (token != null) {
            builder.header(authHeaderName, formatAuthHeaderValue(authHeaderName, token));
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private ObjectNode buildToolCallRequest(String toolName, JiraStoryDraft draft) {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("title", draft.title);
        arguments.put("summary", draft.title);
        arguments.put("description", draft.description);
        arguments.put("acceptanceCriteria", draft.acceptanceCriteria);
        arguments.put("acceptance_criteria", draft.acceptanceCriteria);
        arguments.put("issueType", "Story");
        arguments.put("issue_type", "Story");
        arguments.put("storyType", "Story");

        if (projectKey != null) {
            arguments.put("projectKey", projectKey);
            arguments.put("project_key", projectKey);
        }

        ObjectNode issueNode = arguments.putObject("issue");
        issueNode.put("title", draft.title);
        issueNode.put("summary", draft.title);
        issueNode.put("description", draft.description);
        issueNode.put("acceptanceCriteria", draft.acceptanceCriteria);
        issueNode.put("acceptance_criteria", draft.acceptanceCriteria);
        issueNode.put("issueType", "Story");
        if (projectKey != null) {
            issueNode.put("projectKey", projectKey);
        }

        ObjectNode fieldsNode = arguments.putObject("fields");
        fieldsNode.put("title", draft.title);
        fieldsNode.put("summary", draft.title);
        fieldsNode.put("description", draft.description);
        fieldsNode.put("acceptanceCriteria", draft.acceptanceCriteria);
        fieldsNode.put("acceptance_criteria", draft.acceptanceCriteria);
        fieldsNode.put("issueType", "Story");
        fieldsNode.put("issuetype", "Story");
        if (projectKey != null) {
            fieldsNode.put("projectKey", projectKey);
            fieldsNode.put("project", projectKey);
        }

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        return jsonRpcRequest("tools/call", params);
    }

    private static ObjectNode jsonRpcRequest(String method, JsonNode params) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", UUID.randomUUID().toString());
        request.put("method", method);
        request.set("params", params);
        return request;
    }

    private static JsonNode parseResponseBody(String responseBody) throws IOException {
        String jsonPayload = extractJsonPayload(responseBody);
        if (jsonPayload != null) {
            return MAPPER.readTree(jsonPayload);
        }

        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("raw_body", responseBody == null ? "" : responseBody);
        return wrapper;
    }

    private static String extractJsonPayload(String body) {
        if (body == null) {
            return null;
        }

        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (looksLikeJson(trimmed)) {
            return trimmed;
        }

        String[] lines = body.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String candidate = line.substring("data:".length()).trim();
            if (candidate.isEmpty() || "[DONE]".equals(candidate)) {
                continue;
            }
            if (looksLikeJson(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean looksLikeJson(String value) {
        return value.startsWith("{") || value.startsWith("[");
    }

    private static int scoreToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Integer.MIN_VALUE;
        }

        String normalized = toolName.toLowerCase(Locale.ROOT);
        int score = 0;
        if (normalized.contains("jira")) {
            score += 6;
        }
        if (normalized.contains("issue")) {
            score += 5;
        }
        if (normalized.contains("story")) {
            score += 4;
        }
        if (normalized.contains("ticket")) {
            score += 3;
        }
        if (normalized.contains("create")) {
            score += 5;
        }
        if (normalized.contains("new")) {
            score += 1;
        }
        if (normalized.contains("list")) {
            score -= 6;
        }
        if (normalized.contains("get")) {
            score -= 3;
        }
        if (normalized.contains("search")) {
            score -= 3;
        }
        return score;
    }

    private static String buildTitle(AnalysisResult result) {
        String classAndMethod = extractResponsibleLocation(result);
        String leakPattern = result.rootCause != null
                ? blankToNull(result.rootCause.leakPatternType)
                : null;
        String estimatedLeak = formatLeakSize(result.estimatedLeakSizeMb);

        StringBuilder title = new StringBuilder("Fix ");
        if (leakPattern != null) {
            title.append(leakPattern.replace('_', ' ').toLowerCase(Locale.ROOT)).append(" memory issue");
        } else {
            title.append("heap retention issue");
        }

        if (classAndMethod != null) {
            title.append(" in ").append(classAndMethod);
        }
        if (estimatedLeak != null) {
            title.append(" (").append(estimatedLeak).append(" retained)");
        }

        String normalized = title.toString().replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
    }

    private static String buildDescription(AnalysisResult result) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Heap Leak Analysis Story\n");
        sb.append("========================\n\n");

        sb.append("Executive Summary\n");
        sb.append("-----------------\n");
        appendKeyValueLine(sb, "Summary", result.summary);
        appendKeyValueLine(sb, "Confidence", result.confidence);
        appendKeyValueLine(sb, "Estimated Leak Size (MB)", result.estimatedLeakSizeMb != null ? String.format(Locale.ROOT, "%.2f", result.estimatedLeakSizeMb) : null);
        appendKeyValueLine(sb, "Heap Dump Path", result.heapDumpPath);
        appendKeyValueLine(sb, "Analyzed At", result.analyzedAt);
        sb.append('\n');

        sb.append("Root Cause\n");
        sb.append("----------\n");
        if (result.rootCause == null) {
            sb.append("- No root cause details were present in the AnalysisResult.\n\n");
        } else {
            appendKeyValueLine(sb, "Description", result.rootCause.description);
            appendKeyValueLine(sb, "Responsible Class", result.rootCause.responsibleClass);
            appendKeyValueLine(sb, "Responsible Method", result.rootCause.responsibleMethod);
            appendKeyValueLine(sb, "Leak Pattern Type", result.rootCause.leakPatternType);
            appendKeyValueLine(sb, "Detailed Explanation", result.rootCause.detailedExplanation);
            appendListSection(sb, "Code Search Keywords", result.rootCause.codeSearchKeywords);
        }

        appendListSection(sb, "Recommended Remediation", result.remediation);

        sb.append("Top Retained Objects\n");
        sb.append("--------------------\n");
        if (result.topRetainedObjects == null || result.topRetainedObjects.isEmpty()) {
            sb.append("- No retained object details were present.\n\n");
        } else {
            int index = 1;
            for (AnalysisResult.RetainedObject object : result.topRetainedObjects) {
                sb.append(index++).append(") ")
                        .append(firstNonBlank(object.className, "UnknownClass"))
                        .append('\n');
                appendIndentedKeyValue(sb, "Instance Count", object.instanceCount != null ? String.valueOf(object.instanceCount) : null);
                appendIndentedKeyValue(sb, "Retained Heap Bytes", object.retainedHeapBytes != null ? String.valueOf(object.retainedHeapBytes) : null);
                appendIndentedKeyValue(sb, "Retained Heap Percent", object.retainedHeapPct != null ? String.format(Locale.ROOT, "%.2f%%", object.retainedHeapPct) : null);
                appendIndentedKeyValue(sb, "Leak Suspect", object.isSuspect != null ? object.isSuspect.toString() : null);
                appendIndentedKeyValue(sb, "Agent Note", object.agentNote);
                sb.append('\n');
            }
        }

        sb.append("GC Root Chains\n");
        sb.append("--------------\n");
        if (result.gcRootChains == null || result.gcRootChains.isEmpty()) {
            sb.append("- No GC root chains were present.\n\n");
        } else {
            int chainIndex = 1;
            for (AnalysisResult.GcRootChain chain : result.gcRootChains) {
                sb.append(chainIndex++).append(") ")
                        .append(firstNonBlank(chain.chainLabel, "Unnamed chain"))
                        .append('\n');
                appendIndentedKeyValue(sb, "Root Type", chain.rootType);
                appendIndentedKeyValue(sb, "Root Object", chain.rootObject);
                appendIndentedKeyValue(sb, "Suspect Object", chain.suspectObject);
                appendIndentedKeyValue(sb, "Retained Heap Bytes", chain.retainedHeapBytes != null ? String.valueOf(chain.retainedHeapBytes) : null);
                if (chain.referencePath != null && !chain.referencePath.isEmpty()) {
                    sb.append("  Reference Path:\n");
                    int stepIndex = 1;
                    for (AnalysisResult.ReferenceStep step : chain.referencePath) {
                        sb.append("    ")
                                .append(stepIndex++)
                                .append(". ")
                                .append(firstNonBlank(step.from, "?"))
                                .append(" --")
                                .append(firstNonBlank(step.viaField, "reference"))
                                .append("--> ")
                                .append(firstNonBlank(step.to, "?"))
                                .append('\n');
                    }
                }
                sb.append('\n');
            }
        }

        sb.append("Dominant Allocator Stacks\n");
        sb.append("-------------------------\n");
        if (result.dominantAllocatorStacks == null || result.dominantAllocatorStacks.isEmpty()) {
            sb.append("- No allocator stack details were present.\n\n");
        } else {
            int stackIndex = 1;
            for (AnalysisResult.AllocatorStack stack : result.dominantAllocatorStacks) {
                sb.append(stackIndex++).append(") ")
                        .append(firstNonBlank(stack.allocatorMethod, "Unknown allocator"))
                        .append('\n');
                appendIndentedKeyValue(sb, "Object Count", stack.objectCount != null ? String.valueOf(stack.objectCount) : null);
                appendIndentedKeyValue(sb, "Retained Heap Bytes", stack.retainedHeapBytes != null ? String.valueOf(stack.retainedHeapBytes) : null);
                appendIndentedKeyValue(sb, "Leak Pattern", stack.leakPattern);
                if (stack.stackFrames != null && !stack.stackFrames.isEmpty()) {
                    sb.append("  Stack Frames:\n");
                    for (String frame : stack.stackFrames) {
                        sb.append("    - ").append(frame).append('\n');
                    }
                }
                sb.append('\n');
            }
        }

        sb.append("Raw Analysis Result JSON\n");
        sb.append("------------------------\n");
        sb.append(result.toJson()).append('\n');
        return sb.toString();
    }

    private static String buildAcceptanceCriteria(AnalysisResult result) {
        List<String> criteria = new ArrayList<>();
        String location = extractResponsibleLocation(result);
        String leakPattern = result.rootCause != null ? blankToNull(result.rootCause.leakPatternType) : null;
        String rootCauseDescription = result.rootCause != null ? blankToNull(result.rootCause.description) : null;
        String primarySuspect = extractPrimarySuspect(result);
        String retainedSize = formatLeakSize(result.estimatedLeakSizeMb);

        if (location != null) {
            criteria.add("The code path in " + location + " is updated so it no longer retains memory beyond the intended lifetime.");
        } else if (rootCauseDescription != null) {
            criteria.add("The root cause described by the heap analysis is addressed in the implementation.");
        } else {
            criteria.add("The heap retention issue identified in the analysis is addressed in the implementation.");
        }

        StringBuilder memoryCriterion = new StringBuilder("Validation shows the previously retained object graph");
        if (primarySuspect != null) {
            memoryCriterion.append(" involving ").append(primarySuspect);
        }
        if (retainedSize != null) {
            memoryCriterion.append(" (~").append(retainedSize).append(")");
        }
        memoryCriterion.append(" is no longer kept alive by the identified GC root chain under the relevant scenario.");
        criteria.add(memoryCriterion.toString());

        if (leakPattern != null) {
            criteria.add("The fix demonstrates that the `" + leakPattern + "` leak pattern is either eliminated or explicitly bounded so memory growth remains controlled.");
        }

        if (result.remediation != null && !result.remediation.isEmpty()) {
            for (String remediation : result.remediation) {
                String normalized = blankToNull(remediation);
                if (normalized != null) {
                    criteria.add("Implementation aligns with this remediation recommendation: " + normalized);
                }
            }
        }

        criteria.add("Verification evidence is captured (for example a rerun, heap comparison, or regression check) showing the issue is resolved and does not regress.");
        return toBulletList(deduplicate(criteria));
    }

    private static void appendKeyValueLine(StringBuilder sb, String key, String value) {
        sb.append("- ").append(key).append(": ").append(firstNonBlank(value, "—")).append('\n');
    }

    private static void appendIndentedKeyValue(StringBuilder sb, String key, String value) {
        sb.append("  ");
        sb.append("- ").append(key).append(": ").append(firstNonBlank(value, "—")).append('\n');
    }

    private static void appendListSection(StringBuilder sb, String title, List<String> items) {
        sb.append(title).append('\n');
        sb.append("-".repeat(title.length()));
        sb.append('\n');
        if (items == null || items.isEmpty()) {
            sb.append("- None provided.\n\n");
            return;
        }
        int index = 1;
        for (String item : items) {
            sb.append(index++).append(") ").append(firstNonBlank(item, "—")).append('\n');
        }
        sb.append('\n');
    }

    private static String extractResponsibleLocation(AnalysisResult result) {
        if (result == null || result.rootCause == null) {
            return null;
        }

        String responsibleClass = blankToNull(result.rootCause.responsibleClass);
        String responsibleMethod = blankToNull(result.rootCause.responsibleMethod);
        if (responsibleClass == null && responsibleMethod == null) {
            return null;
        }
        if (responsibleClass == null) {
            return responsibleMethod;
        }
        if (responsibleMethod == null) {
            return responsibleClass;
        }
        return responsibleClass + "." + responsibleMethod;
    }

    private static String extractPrimarySuspect(AnalysisResult result) {
        if (result == null) {
            return null;
        }
        if (result.gcRootChains != null && !result.gcRootChains.isEmpty()) {
            String suspect = blankToNull(result.gcRootChains.get(0).suspectObject);
            if (suspect != null) {
                return suspect;
            }
        }
        if (result.topRetainedObjects != null && !result.topRetainedObjects.isEmpty()) {
            return blankToNull(result.topRetainedObjects.get(0).className);
        }
        return null;
    }

    private static String formatLeakSize(Double estimatedLeakSizeMb) {
        if (estimatedLeakSizeMb == null) {
            return null;
        }
        return String.format(Locale.ROOT, "%.2f MB", estimatedLeakSizeMb);
    }

    private static String toBulletList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            sb.append("- ").append(item.strip()).append('\n');
        }
        return sb.toString().trim();
    }

    private static List<String> deduplicate(List<String> items) {
        Set<String> normalized = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String item : items) {
            String value = blankToNull(item);
            if (value != null && normalized.add(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static String extractIssueKey(JsonNode node) {
        String direct = extractDeepText(node, "issueKey", "issue_key", "key");
        String matchedDirect = extractIssueKeyFromText(direct);
        if (matchedDirect != null) {
            return matchedDirect;
        }

        Deque<JsonNode> queue = new ArrayDeque<>();
        Set<JsonNode> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(node);
        while (!queue.isEmpty()) {
            JsonNode current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.isValueNode()) {
                String text = blankToNull(current.asText(null));
                String issueKey = extractIssueKeyFromText(text);
                if (issueKey != null) {
                    return issueKey;
                }
                continue;
            }
            if (current.isObject()) {
                Iterator<JsonNode> values = current.elements();
                while (values.hasNext()) {
                    queue.addLast(values.next());
                }
            } else if (current.isArray()) {
                for (JsonNode child : current) {
                    queue.addLast(child);
                }
            }
        }
        return null;
    }

    private static String extractIssueKeyFromText(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = ISSUE_KEY_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private static String extractPotentialUrl(JsonNode node) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        Set<JsonNode> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(node);
        while (!queue.isEmpty()) {
            JsonNode current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.isValueNode()) {
                String text = blankToNull(current.asText(null));
                if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                    return text;
                }
                continue;
            }
            if (current.isObject()) {
                Iterator<JsonNode> values = current.elements();
                while (values.hasNext()) {
                    queue.addLast(values.next());
                }
            } else if (current.isArray()) {
                for (JsonNode child : current) {
                    queue.addLast(child);
                }
            }
        }
        return null;
    }

    private static String extractDeepText(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null || fieldNames.length == 0) {
            return null;
        }

        Set<String> targets = new HashSet<>();
        for (String fieldName : fieldNames) {
            if (fieldName != null) {
                targets.add(fieldName.toLowerCase(Locale.ROOT));
            }
        }

        Deque<JsonNode> queue = new ArrayDeque<>();
        Set<JsonNode> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(root);
        while (!queue.isEmpty()) {
            JsonNode current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.isObject()) {
                Iterator<String> fieldNamesIterator = current.fieldNames();
                while (fieldNamesIterator.hasNext()) {
                    String name = fieldNamesIterator.next();
                    JsonNode child = current.get(name);
                    if (targets.contains(name.toLowerCase(Locale.ROOT)) && child != null && child.isValueNode()) {
                        String value = blankToNull(child.asText(null));
                        if (value != null) {
                            return value;
                        }
                    }
                    if (child != null) {
                        queue.addLast(child);
                    }
                }
            } else if (current.isArray()) {
                for (JsonNode child : current) {
                    queue.addLast(child);
                }
            }
        }
        return null;
    }

    private static String formatAuthHeaderValue(String authHeaderName, String token) {
        if (token == null) {
            return null;
        }
        if ("authorization".equalsIgnoreCase(authHeaderName)
                && !token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return "Bearer " + token;
        }
        return token;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireNonBlank(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String readLiteralOrFile(String value) throws IOException {
        if (value == null) {
            return null;
        }
        try {
            Path maybePath = Path.of(value);
            if (Files.isRegularFile(maybePath)) {
                return Files.readString(maybePath, StandardCharsets.UTF_8).strip();
            }
        } catch (InvalidPathException ignored) {
            // treat as literal
        }
        return value;
    }

    private static void writeOutput(Path outputFile, JiraIssueCreationResult result) throws Exception {
        Files.createDirectories(outputFile.toAbsolutePath().normalize().getParent());
        Files.writeString(outputFile, result.toJson(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            AnalysisResult analysisResult = AnalysisResult.fromJson(
                    Files.readString(options.analysisResultFile, StandardCharsets.UTF_8)
            );
            JiraStoryDraft draft = buildDraft(analysisResult);

            JiraIssueCreationResult creationResult;
            if (options.dryRun) {
                creationResult = JiraIssueCreationResult.forDryRun(draft);
                creationResult.endpointUrl = options.endpointUrl;
                creationResult.toolName = options.toolName;
                creationResult.notes.add("Dry run enabled. No HTTP request was sent to the Jira MCP endpoint.");
                LOG.info("Dry run only. Generated Jira story title: {}", draft.title);
            } else {
                JiraIssueCreator creator = new JiraIssueCreator(
                        options.endpointUrl,
                        options.token,
                        options.authHeaderName,
                        options.toolName,
                        options.projectKey
                );
                creationResult = creator.createIssue(draft);
                LOG.info("Jira story creation request completed. issueKey={}, issueUrl={}", creationResult.issueKey, creationResult.issueUrl);
            }

            writeOutput(options.outputFile, creationResult);
            LOG.info("Jira issue creation result written to {}", options.outputFile);
        } catch (Exception e) {
            LOG.error("JiraIssueCreator execution failed.", e);
            System.exit(1);
        }
    }

    public static final class CliOptions {
        public Path analysisResultFile;
        public String endpointUrl;
        public Path outputFile;
        public String token;
        public String authHeaderName;
        public String toolName;
        public String projectKey;
        public boolean dryRun;

        static CliOptions parse(String[] args) throws Exception {
            if (args == null || args.length == 0) {
                throw new IllegalArgumentException(usage());
            }

            CliOptions options = new CliOptions();
            options.analysisResultFile = Path.of(args[0]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(options.analysisResultFile)) {
                throw new IllegalArgumentException("AnalysisResult JSON file does not exist: " + options.analysisResultFile);
            }

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--endpoint" -> options.endpointUrl = requireOptionValue(args, ++i, arg);
                    case "--output" -> options.outputFile = Path.of(requireOptionValue(args, ++i, arg)).toAbsolutePath().normalize();
                    case "--token" -> options.token = readLiteralOrFile(requireOptionValue(args, ++i, arg));
                    case "--auth-header" -> options.authHeaderName = requireOptionValue(args, ++i, arg);
                    case "--tool" -> options.toolName = requireOptionValue(args, ++i, arg);
                    case "--project-key" -> options.projectKey = requireOptionValue(args, ++i, arg);
                    case "--dry-run" -> options.dryRun = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg + System.lineSeparator() + usage());
                }
            }

            options.endpointUrl = firstNonBlank(options.endpointUrl, System.getenv("HEAPFIXER_JIRA_MCP_URL"));
            options.token = firstNonBlank(options.token, System.getenv("HEAPFIXER_JIRA_MCP_TOKEN"));
            options.authHeaderName = firstNonBlank(options.authHeaderName, System.getenv("HEAPFIXER_JIRA_MCP_AUTH_HEADER"), "Authorization");
            options.toolName = firstNonBlank(options.toolName, System.getenv("HEAPFIXER_JIRA_MCP_TOOL_NAME"));
            options.projectKey = firstNonBlank(options.projectKey, System.getenv("HEAPFIXER_JIRA_PROJECT_KEY"));
            options.outputFile = options.outputFile != null
                    ? options.outputFile
                    : options.analysisResultFile.getParent().resolve("jira_issue_creation_result.json").toAbsolutePath().normalize();

            if (!options.dryRun && blankToNull(options.endpointUrl) == null) {
                throw new IllegalArgumentException("A Jira MCP endpoint URL is required. Supply --endpoint or HEAPFIXER_JIRA_MCP_URL.\n" + usage());
            }
            return options;
        }

        private static String requireOptionValue(String[] args, int index, String optionName) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + optionName + System.lineSeparator() + usage());
            }
            return args[index];
        }

        private static String usage() {
            return "Usage: JiraIssueCreator <analysis-result-json-file> [--endpoint <mcp-url>] [--output <result-json-file>] "
                    + "[--token <token-or-token-file>] [--auth-header <header-name>] [--tool <tool-name>] "
                    + "[--project-key <jira-project-key>] [--dry-run]";
        }
    }

    public static class JiraStoryDraft {
        @JsonProperty("title")
        public String title;

        @JsonProperty("description")
        public String description;

        @JsonProperty("acceptance_criteria")
        public String acceptanceCriteria;
    }

    public static class JiraIssueCreationResult {
        @JsonProperty("generated_at")
        public String generatedAt = Instant.now().toString();

        @JsonProperty("dry_run")
        public boolean dryRun;

        @JsonProperty("created")
        public boolean created;

        @JsonProperty("endpoint_url")
        public String endpointUrl;

        @JsonProperty("tool_name")
        public String toolName;

        @JsonProperty("issue_key")
        public String issueKey;

        @JsonProperty("issue_id")
        public String issueId;

        @JsonProperty("issue_url")
        public String issueUrl;

        @JsonProperty("title")
        public String title;

        @JsonProperty("description")
        public String description;

        @JsonProperty("acceptance_criteria")
        public String acceptanceCriteria;

        @JsonProperty("request_payload")
        public JsonNode requestPayload;

        @JsonProperty("response_payload")
        public JsonNode responsePayload;

        @JsonProperty("raw_response_body")
        public String rawResponseBody;

        @JsonProperty("notes")
        public List<String> notes = new ArrayList<>();

        public static JiraIssueCreationResult forDryRun(JiraStoryDraft draft) {
            JiraIssueCreationResult result = new JiraIssueCreationResult();
            result.dryRun = true;
            result.created = false;
            result.title = draft.title;
            result.description = draft.description;
            result.acceptanceCriteria = draft.acceptanceCriteria;
            return result;
        }

        public String toJson() throws Exception {
            return MAPPER.writeValueAsString(this);
        }
    }
}


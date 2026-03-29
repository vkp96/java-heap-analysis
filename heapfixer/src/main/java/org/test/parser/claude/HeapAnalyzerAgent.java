package org.test.parser.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.test.AnalysisResult;
import org.test.MatReportExtractor;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * HeapAnalyzerAgent
 *
 * Sends MAT report content to Claude via the Anthropic Messages API and
 * returns a structured {@link AnalysisResult}.
 *
 * The agent uses a two-turn conversation:
 *   Turn 1 – Structured extraction  : top retained objects + GC root chains
 *   Turn 2 – Root cause synthesis   : allocator stacks + root cause + remediation
 *
 * This split keeps each prompt focused and within a comfortable context window.
 *
 * Usage:
 *   HeapAnalyzerAgent agent = new HeapAnalyzerAgent(apiKey);
 *   AnalysisResult result   = agent.analyze(matReport, 10);   // top 10 objects
 */
public class HeapAnalyzerAgent {

    private static final Logger LOG = Logger.getLogger(HeapAnalyzerAgent.class.getName());

    private static final String ANTHROPIC_API_URL =
        "https://api.anthropic.com/v1/messages";
    private static final String MODEL             = "claude-sonnet-4-20250514";
    private static final int    MAX_TOKENS        = 4096;
    /** Characters to trim from the MAT report context to stay well within tokens. */
    private static final int    MAX_CONTEXT_CHARS = 32_000;

    private final String        apiKey;
    private final HttpClient    http;
    private final ObjectMapper  mapper;

    public HeapAnalyzerAgent(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "ANTHROPIC_API_KEY must not be null");
        this.http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.mapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Run a full two-phase analysis of a MAT report.
     *
     * @param report   Extracted MAT report (from {@link MatReportExtractor})
     * @param topN     How many top retained-object types to surface (e.g. 10)
     * @return         Fully populated {@link AnalysisResult}
     */
    public AnalysisResult analyze(MatReportExtractor.MatReport report, int topN)
            throws Exception {

        String context = report.toPromptContext();
        if (context.length() > MAX_CONTEXT_CHARS) {
            LOG.warning("MAT report context trimmed from " + context.length()
                + " to " + MAX_CONTEXT_CHARS + " chars.");
            context = context.substring(0, MAX_CONTEXT_CHARS)
                + "\n\n[REPORT TRUNCATED – remaining content omitted to fit context window]";
        }

        LOG.info("Phase 1 – Extracting retained objects and GC root chains…");
        String phase1Json = runPhase1(context, topN);

        LOG.info("Phase 2 – Synthesising allocator stacks, root cause, remediation…");
        String phase2Json = runPhase2(context, phase1Json);

        LOG.info("Merging phases into final AnalysisResult…");
        return mergePhases(phase1Json, phase2Json, report.heapDumpPath);
    }

    // -------------------------------------------------------------------------
    //  Phase 1 – Retained objects + GC roots
    // -------------------------------------------------------------------------

    private String runPhase1(String matContext, int topN) throws Exception {
        String systemPrompt = """
            You are a Java memory profiling expert analysing output from Eclipse Memory Analyser Tool (MAT).
            Your job is to extract precise, structured information from MAT report text.
            Always respond ONLY with valid JSON. No markdown fences, no prose, no explanation outside the JSON.
            """;

        String userPrompt = String.format("""
            Below is the text content of an Eclipse MAT leak suspects report for a Java heap dump.

            %s

            Extract the following and return ONLY a JSON object with this exact schema:

            {
              "top_retained_objects": [
                {
                  "class_name": "<fully qualified class name>",
                  "instance_count": <number>,
                  "retained_heap_bytes": <number>,
                  "retained_heap_pct": <number 0-100>,
                  "is_suspect": <true|false>,
                  "agent_note": "<one line: why this class stands out>"
                }
              ],
              "gc_root_chains": [
                {
                  "chain_label": "<short label, e.g. Thread → ArrayList → MyObject>",
                  "root_type": "<Thread|ClassLoader|JNI|Static|Unknown>",
                  "root_object": "<description of the GC root>",
                  "reference_path": [
                    { "from": "<object>", "via_field": "<field or collection>", "to": "<object>" }
                  ],
                  "suspect_object": "<the final leaked object>",
                  "retained_heap_bytes": <number or null if unknown>
                }
              ],
              "estimated_leak_size_mb": <number or null>
            }

            Rules:
            - Include the top %d retained object types ordered by retained_heap_bytes descending.
            - If the MAT report lists exact byte values, use them. If only MB/GB are given, convert to bytes.
            - If an exact number is unavailable, use null rather than guessing.
            - Do not include objects with retained_heap_pct < 0.5 unless they are flagged as suspects.
            - For gc_root_chains, include every suspect chain MAT explicitly identifies. Cap at 5 chains.
            - Output ONLY the JSON object. No other text.
            """, matContext, topN);

        return callClaude(systemPrompt, userPrompt);
    }

    // -------------------------------------------------------------------------
    //  Phase 2 – Allocator stacks + Root cause + Remediation
    // -------------------------------------------------------------------------

    private String runPhase2(String matContext, String phase1Json) throws Exception {
        String systemPrompt = """
            You are a Java memory profiling expert.
            You have already extracted raw data from a MAT report (provided as phase1_data).
            Now synthesise that data into a root cause analysis with actionable remediation.
            Always respond ONLY with valid JSON. No markdown fences, no prose outside the JSON.
            """;

        String userPrompt = String.format("""
            MAT REPORT TEXT:
            %s

            PHASE 1 EXTRACTION (retained objects + GC chains):
            %s

            Using both inputs above, return ONLY a JSON object with this schema:

            {
              "dominant_allocator_stacks": [
                {
                  "allocator_method": "<ClassName.methodName>",
                  "object_count": <number or null>,
                  "retained_heap_bytes": <number or null>,
                  "stack_frames": [
                    "<frame 1 – most specific first, e.g. com.example.Foo.bar(Foo.java:42)>",
                    "<frame 2>",
                    "<frame 3>"
                  ],
                  "leak_pattern": "<short description: e.g. Objects added to cache but never evicted>"
                }
              ],
              "root_cause": {
                "description": "<one sentence root cause>",
                "responsible_class": "<fully qualified class name>",
                "responsible_method": "<method name or null>",
                "leak_pattern_type": "<one of: UNBOUNDED_CACHE | LISTENER_NOT_REMOVED | STATIC_COLLECTION | THREAD_LOCAL_NOT_CLEARED | CLASSLOADER_LEAK | CONNECTION_NOT_CLOSED | LARGE_OBJECT_GRAPH | OTHER>",
                "detailed_explanation": "<2–4 sentences explaining the mechanism of the leak>",
                "code_search_keywords": ["<keyword1>", "<keyword2>", "<keyword3>"]
              },
              "remediation": [
                "<Step 1 – specific and actionable>",
                "<Step 2>",
                "<Step 3>"
              ],
              "confidence": "<LOW|MEDIUM|HIGH>",
              "summary": "<2–3 sentence plain-English summary a developer can read at a glance>"
            }

            Rules:
            - dominant_allocator_stacks: extract stack traces from MAT output where available.
              If MAT did not produce stack traces, infer the most likely allocator based on the
              suspect class name and leak pattern, and set object_count/retained_heap_bytes to null.
            - root_cause.responsible_class must be the most specific application class identifiable
              (prefer com.yourapp.* over java.util.* unless the leak is genuinely in a JDK class).
            - remediation steps must be concrete – e.g. "Switch from HashMap to WeakHashMap in
              com.example.SessionStore.sessions field" not "fix the memory leak".
            - confidence: HIGH if MAT explicitly named a suspect with a stack trace, MEDIUM if the
              suspect is clear but stack is absent, LOW if the report is ambiguous.
            - Output ONLY the JSON object. No other text.
            """, matContext, phase1Json);

        return callClaude(systemPrompt, userPrompt);
    }

    // -------------------------------------------------------------------------
    //  Merge both phases into AnalysisResult
    // -------------------------------------------------------------------------

    private AnalysisResult mergePhases(String phase1Json, String phase2Json,
                                       String heapDumpPath) throws Exception {
        JsonNode p1 = parseJson(phase1Json);
        JsonNode p2 = parseJson(phase2Json);

        // Merge into a single node so we can deserialize cleanly
        ObjectNode merged = mapper.createObjectNode();
        merged.put("heap_dump_path", heapDumpPath);
        merged.put("analyzed_at",   Instant.now().toString());

        copyField(merged, p1, "top_retained_objects");
        copyField(merged, p1, "gc_root_chains");
        copyField(merged, p1, "estimated_leak_size_mb");
        copyField(merged, p2, "dominant_allocator_stacks");
        copyField(merged, p2, "root_cause");
        copyField(merged, p2, "remediation");
        copyField(merged, p2, "confidence");
        copyField(merged, p2, "summary");

        return mapper.treeToValue(merged, AnalysisResult.class);
    }

    // -------------------------------------------------------------------------
    //  HTTP + JSON helpers
    // -------------------------------------------------------------------------

    /** Calls the Anthropic Messages API synchronously and returns the text content. */
    private String callClaude(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model",      MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system",     systemPrompt);

        ArrayNode messages = body.putArray("messages");
        ObjectNode msg     = messages.addObject();
        msg.put("role", "user");
        msg.putArray("content").addObject()
            .put("type", "text")
            .put("text", userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ANTHROPIC_API_URL))
            .header("Content-Type",      "application/json")
            .header("x-api-key",         apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API error " + response.statusCode()
                + ": " + response.body());
        }

        JsonNode responseNode = mapper.readTree(response.body());
        // Response: { "content": [ { "type": "text", "text": "..." } ] }
        JsonNode content = responseNode.path("content");
        if (content.isEmpty()) {
            throw new RuntimeException("Empty response from Claude: " + response.body());
        }
        return content.get(0).path("text").asText();
    }

    /**
     * Parses JSON from Claude's response, stripping any accidental markdown
     * fences in case the model ignores the "no fences" instruction.
     */
    private JsonNode parseJson(String raw) throws Exception {
        String cleaned = raw.strip();
        // Strip ```json ... ``` fences defensively
        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                .replaceFirst("^```[a-zA-Z]*\\s*", "")
                .replaceFirst("```\\s*$",          "");
        }
        try {
            return mapper.readTree(cleaned);
        } catch (Exception e) {
            // Try to extract the first { ... } block if there's surrounding prose
            int start = cleaned.indexOf('{');
            int end   = cleaned.lastIndexOf('}');
            if (start != -1 && end > start) {
                return mapper.readTree(cleaned.substring(start, end + 1));
            }
            throw new RuntimeException("Could not parse JSON from Claude response: " + raw, e);
        }
    }

    private void copyField(ObjectNode target, JsonNode source, String field) {
        if (source.has(field)) target.set(field, source.get(field));
    }
}

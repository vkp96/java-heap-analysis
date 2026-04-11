package org.test.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;
import org.test.MatReportExtractor;
import org.test.parser.copilot.CopilotResponseParser;
import org.test.parser.copilot.LeakSuspectPromptCreator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Strategy that sends the heap-analysis prompt to the
 * <a href="https://ai.google.dev/api">Google Gemini Generative Language API</a>
 * and parses the JSON response into an {@link AnalysisResult}.
 * <p>
 * Environment:
 * <ul>
 *   <li>{@code GEMINI_API_KEY}  – required</li>
 *   <li>{@code GEMINI_MODEL}    – optional, defaults to {@code gemini-2.0-flash}</li>
 * </ul>
 */
public class GeminiAnalysisStrategy implements HeapAnalysisStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiAnalysisStrategy.class);

    private static final String API_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";
    private static final int    MAX_TOKENS    = 4096;

    private final String       apiKey;
    private final String       model;
    private final HttpClient   http;
    private final ObjectMapper mapper;

    public GeminiAnalysisStrategy(String apiKey) {
        this(apiKey, envOrDefault("GEMINI_MODEL", DEFAULT_MODEL));
    }

    public GeminiAnalysisStrategy(String apiKey, String model) {
        this.apiKey = Objects.requireNonNull(apiKey, "GEMINI_API_KEY must not be null");
        this.model  = model;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public AnalysisResult analyze(MatReportExtractor.MatReport report, int topN) throws Exception {
        LOG.info("[Gemini] Building prompt (topN={})", topN);
        String prompt = new LeakSuspectPromptCreator()
                .withTopN(topN)
                .buildFromReport(report);
        LOG.info("[Gemini] Prompt length: {} chars", prompt.length());

        LOG.info("[Gemini] Calling Gemini API model={}", model);
        String rawResponse = callGemini(prompt);
        LOG.info("[Gemini] Response length: {} chars", rawResponse.length());

        LOG.info("[Gemini] Parsing response into AnalysisResult");
        AnalysisResult result = CopilotResponseParser.parse(rawResponse);
        if (result.heapDumpPath == null || result.heapDumpPath.isBlank()) {
            result.heapDumpPath = report.heapDumpPath;
        }
        LOG.info("[Gemini] Analysis complete. Confidence={}", result.confidence);
        return result;
    }

    @Override
    public String strategyName() {
        return "Gemini (" + model + ")";
    }

    // ── HTTP call ────────────────────────────────────────────────────────────

    private String callGemini(String userPrompt) throws Exception {
        /*
         * Gemini request body:
         * {
         *   "systemInstruction": { "parts": [{ "text": "..." }] },
         *   "contents": [{ "role": "user", "parts": [{ "text": "..." }] }],
         *   "generationConfig": { "maxOutputTokens": 4096, "temperature": 0.2 }
         * }
         */
        ObjectNode body = mapper.createObjectNode();

        // System instruction
        ObjectNode sysInstr = body.putObject("systemInstruction");
        ArrayNode sysParts = sysInstr.putArray("parts");
        sysParts.addObject().put("text",
                "You are a senior Java performance engineer specialising in memory leak analysis. " +
                "Respond ONLY with a single valid JSON object. No markdown fences, no prose outside the JSON.");

        // User content
        ArrayNode contents = body.putArray("contents");
        ObjectNode userContent = contents.addObject();
        userContent.put("role", "user");
        ArrayNode userParts = userContent.putArray("parts");
        userParts.addObject().put("text", userPrompt);

        // Generation config
        ObjectNode genConfig = body.putObject("generationConfig");
        genConfig.put("maxOutputTokens", MAX_TOKENS);
        genConfig.put("temperature", 0.2);

        String url = String.format(API_URL_TEMPLATE, model, apiKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini API error " + response.statusCode()
                    + ": " + response.body());
        }

        // Response: { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
        var root = mapper.readTree(response.body());
        var candidates = root.path("candidates");
        if (candidates.isEmpty()) {
            throw new RuntimeException("Empty candidates from Gemini: " + response.body());
        }
        return candidates.get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String envOrDefault(String var, String def) {
        String v = System.getenv(var);
        return (v != null && !v.isBlank()) ? v : def;
    }
}


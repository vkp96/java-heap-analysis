package org.test.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.test.AnalysisResult;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CopilotResponseParser
 *
 * Parses the JSON text that Copilot Chat returns (after you paste the prompt
 * from {@link LeakSuspectPromptCreator}) into a fully typed {@link AnalysisResult}.
 *
 * Handles the two common Copilot output formats:
 *   1. Raw JSON  — model followed instructions perfectly
 *   2. Fenced    — model wrapped JSON in ```json ... ``` despite being told not to
 *
 * Also runs a lightweight validation pass to log any missing required fields
 * before handing the result to downstream pipeline stages.
 *
 * Usage:
 * <pre>
 *   // Paste the JSON Copilot returned (String, file, or clipboard):
 *   AnalysisResult result = CopilotResponseParser.parse(copilotOutput);
 *
 *   // Or read it from a file you saved the Copilot response into:
 *   AnalysisResult result = CopilotResponseParser.parseFile(Path.of("copilot_response.json"));
 *
 *   // Then feed into the pipeline:
 *   new ReportWriterAgent(apiKey).writeReport(result, outputDir);
 * </pre>
 */
public class CopilotResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Public entry points ───────────────────────────────────────────────────

    /**
     * Parse a JSON string returned by Copilot into an {@link AnalysisResult}.
     *
     * @param copilotOutput  Raw text pasted from Copilot Chat.
     * @return               Populated AnalysisResult.
     * @throws ParseException if no valid JSON object can be extracted.
     */
    public static AnalysisResult parse(String copilotOutput) throws ParseException {
        String cleaned = clean(copilotOutput);
        try {
            AnalysisResult result = MAPPER.readValue(cleaned, AnalysisResult.class);
            validate(result);
            return result;
        } catch (Exception e) {
            throw new ParseException(
                "Failed to parse Copilot JSON response.\n" +
                "Tip: ensure Copilot returned only the JSON object (no extra text).\n" +
                "Extracted text attempted:\n" + truncate(cleaned, 500) + "\n\nCause: " + e.getMessage(), e);
        }
    }

    /**
     * Read a file containing the Copilot JSON response and parse it.
     */
    public static AnalysisResult parseFile(Path jsonFile) throws Exception {
        return parse(Files.readString(jsonFile));
    }

    /**
     * Read the JSON response from the system clipboard and parse it.
     * Useful when you copy the Copilot response directly.
     */
    public static AnalysisResult parseFromClipboard() throws Exception {
        java.awt.datatransfer.Clipboard cb =
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        String text = (String) cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        System.out.println("[CopilotResponseParser] Read " + text.length()
            + " chars from clipboard.");
        return parse(text);
    }

    // ── Cleaning — extract JSON from fenced or prose-wrapped output ───────────

    /**
     * Strip markdown fences and any surrounding prose, leaving only the JSON object.
     */
    static String clean(String raw) {
        if (raw == null || raw.isBlank())
            throw new IllegalArgumentException("Copilot response is empty.");

        String text = raw.strip();

        // Remove ```json ... ``` or ``` ... ``` fences
        if (text.startsWith("```")) {
            text = text
                .replaceFirst("(?s)^```[a-zA-Z]*\\s*", "")   // opening fence
                .replaceFirst("(?s)\\s*```\\s*$", "");        // closing fence
            text = text.strip();
        }

        // If there's still surrounding prose, extract the first { ... } block
        if (!text.startsWith("{")) {
            int start = text.indexOf('{');
            int end   = findMatchingBrace(text, start);
            if (start == -1 || end == -1) {
                throw new IllegalArgumentException(
                    "Could not find a JSON object in the Copilot response.\n" +
                    "First 300 chars: " + truncate(text, 300));
            }
            text = text.substring(start, end + 1);
        }

        return text;
    }

    /**
     * Find the closing brace that matches the opening brace at {@code openPos}.
     * Respects nested objects and strings.
     */
    private static int findMatchingBrace(String text, int openPos) {
        if (openPos < 0) return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ── Validation — log warnings for missing or suspicious fields ─────────────

    private static void validate(AnalysisResult r) {
        List<String> warnings = new ArrayList<>();

        if (isBlank(r.summary))
            warnings.add("'summary' is empty");
        if (r.topRetainedObjects == null || r.topRetainedObjects.isEmpty())
            warnings.add("'top_retained_objects' is empty — Copilot may not have found histogram data");
        if (r.gcRootChains == null || r.gcRootChains.isEmpty())
            warnings.add("'gc_root_chains' is empty — no GC root chains identified");
        if (r.rootCause == null)
            warnings.add("'root_cause' is null — root cause section missing");
        else {
            if (isBlank(r.rootCause.responsibleClass))
                warnings.add("'root_cause.responsible_class' is empty");
            if (isBlank(r.rootCause.leakPatternType))
                warnings.add("'root_cause.leak_pattern_type' is empty");
        }
        if (r.remediation == null || r.remediation.isEmpty())
            warnings.add("'remediation' is empty — no fix steps provided");
        if (isBlank(r.confidence))
            warnings.add("'confidence' is empty");

        if (!warnings.isEmpty()) {
            System.out.println("[CopilotResponseParser] Validation warnings:");
            warnings.forEach(w -> System.out.println("  ⚠ " + w));
            System.out.println("  These fields will be null/empty in the AnalysisResult.\n" +
                "  If critical data is missing, re-run the prompt with more report content.");
        } else {
            System.out.println("[CopilotResponseParser] Validation passed — all key fields present.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ── Exception type ────────────────────────────────────────────────────────

    public static class ParseException extends RuntimeException {
        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

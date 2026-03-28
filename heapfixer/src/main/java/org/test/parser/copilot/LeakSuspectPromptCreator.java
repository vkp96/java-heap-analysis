package org.test.parser.copilot;

import org.test.AnalysisResult;
import org.test.parser.claude.MatReportExtractor;

import java.nio.file.*;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LeakSuspectPromptCreator
 *<p></p>
 * Reads Eclipse MAT report files from disk and assembles a single,
 * self-contained prompt string you can paste directly into GitHub Copilot
 * Chat (or any other LLM interface).
 *<p></p>
 * Copilot will return a JSON object you can parse back with
 * {@link CopilotResponseParser} into an {@link AnalysisResult}.
 *<p></p>
 * Typical workflow:
 * <pre>
 *   // 1. After MAT headless finishes:
 *   String prompt = new LeakSuspectPromptCreator()
 *       .withReportsDir(Path.of("/var/dumps/app-service-1_reports"))
 *       .withHeapDumpPath("/var/dumps/app-service-1.hprof")
 *       .withTopN(10)
 *       .build();
 *
 *   // 2. Print / write to file / copy to clipboard:
 *   // e.g. write to a file or copy to clipboard
 *
 *   // 3. Paste into Copilot Chat, copy the JSON response.
 *
 *   // 4. Parse back to AnalysisResult:
 *   AnalysisResult result = CopilotResponseParser.parse(copilotJsonResponse);
 *
 *   // 5. Feed into the pipeline:
 *   new ReportWriterAgent(apiKey).writeReport(result, outputDir);
 * </pre>
 */
public class LeakSuspectPromptCreator {

    private static final Logger log = LoggerFactory.getLogger(LeakSuspectPromptCreator.class);

    // ── Config ────────────────────────────────────────────────────────────────
    private Path   reportsDir;
    private String heapDumpPath = "unknown.hprof";
    private int    topN         = 10;

    // ── Builder-style setters ─────────────────────────────────────────────────

    public LeakSuspectPromptCreator withReportsDir(Path reportsDir) {
        this.reportsDir = reportsDir;
        return this;
    }

    public LeakSuspectPromptCreator withHeapDumpPath(String heapDumpPath) {
        this.heapDumpPath = heapDumpPath;
        return this;
    }

    public LeakSuspectPromptCreator withTopN(int topN) {
        this.topN = topN;
        return this;
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Build and return the complete prompt string.
     * Reads all available MAT report files; sections that are missing are
     * omitted gracefully rather than failing.
     */
    public String build() throws Exception {
        if (reportsDir == null)
            throw new IllegalStateException("reportsDir must be set before calling build()");

        // Normalize paths in case caller provided relative paths
        reportsDir = reportsDir.toAbsolutePath().normalize();
        if (heapDumpPath != null && !heapDumpPath.isBlank()) {
            heapDumpPath = Paths.get(heapDumpPath).toAbsolutePath().normalize().toString();
        }

        log.info("[LeakSuspectPromptCreator] build() will use reportsDir={} and heapDumpPath={}", reportsDir, heapDumpPath);

        MatReportExtractor extractor = new MatReportExtractor();
        MatReportExtractor.MatReport report = extractor.extract(reportsDir, heapDumpPath);

        return assemblePrompt(report);
    }

    /**
     * Build the prompt and write it to a file so you can open it easily.
     * Returns the path it was written to.
     */
    public Path buildToFile(Path outputFile) throws Exception {
        String prompt = build();
        Files.writeString(outputFile, prompt);
        return outputFile;
    }

    /**
     * Build the prompt and copy it to the system clipboard (requires
     * a desktop environment; works on Mac, Windows, most Linux desktops).
     */
    public String buildToClipboard() throws Exception {
        String prompt = build();
        java.awt.Toolkit.getDefaultToolkit()
            .getSystemClipboard()
            .setContents(new java.awt.datatransfer.StringSelection(prompt), null);
        log.info("[LeakSuspectPromptCreator] Prompt copied to clipboard ({} chars). Paste into Copilot Chat.", prompt.length());
        return prompt;
    }

    // ── Prompt assembly ───────────────────────────────────────────────────────

    private String assemblePrompt(MatReportExtractor.MatReport report) {

        return header() +
                reportContext(report) +
                instructions(topN) +
                jsonSchema(topN) +
                footer();
    }

    // ── Section builders ──────────────────────────────────────────────────────

    private String header() {
        return """
            ╔══════════════════════════════════════════════════════════════════╗
            ║          JAVA HEAP DUMP ANALYSIS — COPILOT PROMPT               ║
            ║  Generated: %s
            ║  Paste this entire message into Copilot Chat and send it.       ║
            ╚══════════════════════════════════════════════════════════════════╝

            You are a senior Java performance engineer specialising in memory leak analysis.
            Analyse the Eclipse MAT (Memory Analyser Tool) report data below and return
            ONLY a single, valid JSON object. No prose, no markdown fences, no explanation
            outside the JSON.

            """.formatted(Instant.now().toString());
    }

    private String reportContext(MatReportExtractor.MatReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════ MAT REPORT DATA ═══════════════════════\n\n");

        if (report.rawSystemOverviewText != null && !report.rawSystemOverviewText.isBlank()) {
            sb.append("--- SYSTEM OVERVIEW ---\n")
              .append(trimToChars(report.rawSystemOverviewText, 6_000))
              .append("\n\n");
        }

        if (report.rawLeakSuspectsText != null && !report.rawLeakSuspectsText.isBlank()) {
            sb.append("--- LEAK SUSPECTS REPORT ---\n")
              .append(trimToChars(report.rawLeakSuspectsText, 12_000))
              .append("\n\n");
        }

        if (!report.suspectBlocks.isEmpty()) {
            sb.append("--- INDIVIDUAL SUSPECT BLOCKS ---\n");
            for (int i = 0; i < report.suspectBlocks.size(); i++) {
                sb.append("• Suspect ").append(i + 1).append(":\n")
                  .append(trimToChars(report.suspectBlocks.get(i), 3_000))
                  .append("\n\n");
            }
        }

        if (report.dominatorTreeText != null && !report.dominatorTreeText.isBlank()) {
            sb.append("--- DOMINATOR TREE (top entries) ---\n")
              .append(trimToChars(report.dominatorTreeText, 5_000))
              .append("\n\n");
        }

        sb.append("═════════════════════════════════════════════════════════════\n\n");
        return sb.toString();
    }

    private String instructions(int topN) {
        return """
            ═══════════════════════ INSTRUCTIONS ════════════════════════════

            Using the MAT report data above, extract and reason over the following:

            1. TOP RETAINED OBJECTS
               - Identify the top %d object types by retained heap size.
               - For each: fully-qualified class name, instance count, retained bytes,
                 retained heap as a percentage of total heap, and whether MAT flagged it
                 as a leak suspect.

            2. GC ROOT CHAINS
               - For every leak suspect MAT identifies, trace the reference chain
                 from its GC root down to the suspected leak object.
               - Identify root type (Thread, ClassLoader, JNI, Static, etc.).

            3. DOMINANT ALLOCATOR STACKS
               - Where are the leaking objects being allocated?
               - If stack frames are present in the report, use them.
               - If absent, infer the most likely allocator from the class name and leak pattern.

            4. ROOT CAUSE
               - Identify the single most responsible class and method.
               - Classify the leak pattern as one of:
                 UNBOUNDED_CACHE | LISTENER_NOT_REMOVED | STATIC_COLLECTION |
                 THREAD_LOCAL_NOT_CLEARED | CLASSLOADER_LEAK |
                 CONNECTION_NOT_CLOSED | LARGE_OBJECT_GRAPH | OTHER
               - Provide a 2–4 sentence technical explanation.
               - List 3–5 code-search keywords useful for finding the leak in source.

            5. REMEDIATION
               - List 3–5 concrete, actionable steps.
               - Each step must name the specific class/method to change and what to do.

            6. CONFIDENCE
               - Rate as HIGH / MEDIUM / LOW.
               - HIGH = MAT explicitly named a suspect with stack trace.
               - MEDIUM = suspect is clear but stack is absent.
               - LOW = report is ambiguous or incomplete.

            Rules:
            - Use exact byte values from the report. If only MB/GB are given, convert to bytes.
            - Use null for any field where data is unavailable rather than guessing.
            - Do not include classes with retained_heap_pct < 0.5 unless they are suspects.
            - Output ONLY the JSON object. Nothing else.

            """.formatted(topN);
    }

    private String jsonSchema(int topN) {
        return """
            ═══════════════════════ REQUIRED JSON SCHEMA ════════════════════

            Return exactly this structure (populate all fields from the report data above):

            {
              "heap_dump_path": "<string — path to .hprof file or null>",
              "analyzed_at": "<ISO-8601 timestamp of now>",
              "summary": "<2–3 sentence plain-English summary for a developer>",
              "estimated_leak_size_mb": <number or null>,
              "confidence": "<HIGH|MEDIUM|LOW>",

              "top_retained_objects": [
                /* Include top %d ordered by retained_heap_bytes descending */
                {
                  "class_name": "<fully.qualified.ClassName>",
                  "instance_count": <number or null>,
                  "retained_heap_bytes": <number or null>,
                  "retained_heap_pct": <0.0–100.0 or null>,
                  "is_suspect": <true|false>,
                  "agent_note": "<one-line explanation of why this class stands out>"
                }
              ],

              "gc_root_chains": [
                /* One entry per suspect chain MAT identifies, max 5 */
                {
                  "chain_label": "<short label e.g. Thread → HashMap → MyObject>",
                  "root_type": "<Thread|ClassLoader|JNI|Static|Unknown>",
                  "root_object": "<description of the GC root object>",
                  "reference_path": [
                    {
                      "from": "<source object description>",
                      "via_field": "<field name or collection type>",
                      "to": "<destination object description>"
                    }
                  ],
                  "suspect_object": "<final leaked object at end of chain>",
                  "retained_heap_bytes": <number or null>
                }
              ],

              "dominant_allocator_stacks": [
                {
                  "allocator_method": "<ClassName.methodName>",
                  "object_count": <number or null>,
                  "retained_heap_bytes": <number or null>,
                  "stack_frames": [
                    "<most specific frame first, e.g. com.example.Foo.bar(Foo.java:42)>",
                    "<frame 2>",
                    "<frame 3>"
                  ],
                  "leak_pattern": "<short description of why this site leaks>"
                }
              ],

              "root_cause": {
                "description": "<one-sentence root cause>",
                "responsible_class": "<fully.qualified.ClassName>",
                "responsible_method": "<methodName or null>",
                "leak_pattern_type": "<UNBOUNDED_CACHE|LISTENER_NOT_REMOVED|STATIC_COLLECTION|THREAD_LOCAL_NOT_CLEARED|CLASSLOADER_LEAK|CONNECTION_NOT_CLOSED|LARGE_OBJECT_GRAPH|OTHER>",
                "detailed_explanation": "<2–4 sentences explaining the leak mechanism>",
                "code_search_keywords": ["<keyword1>", "<keyword2>", "<keyword3>"]
              },

              "remediation": [
                "<Step 1 — concrete and specific, names the class/method to change>",
                "<Step 2>",
                "<Step 3>"
              ]
            }

            """.formatted(topN);
    }

    private String footer() {
        return """
            ══════════════════════════════════════════════════════════════════
            Now output ONLY the JSON object above, populated from the MAT
            report data. No other text before or after the JSON.
            ══════════════════════════════════════════════════════════════════
            """;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String trimToChars(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n[... truncated for context window ...]";
    }

    /**
     * Command-line entry point.
     * Usage: java org.test.parser.copilot.LeakSuspectPromptCreator <reportsDir> <heapDumpPath> [topN]
     * - reportsDir   : directory containing MAT reports (will be normalized to absolute path)
     * - heapDumpPath  : path to the .hprof that was analyzed (kept as-given in the prompt)
     * - topN         : optional integer, number of top retained objects to include (default 10)
     */
    public static void main(String[] args) {
        try {
            if (args.length < 2 || args.length > 3) {
                log.error("Usage: java org.test.parser.copilot.LeakSuspectPromptCreator <reportsDir> <heapDumpPath> [topN]");
                System.exit(1);
            }

            Path reportsDir = Paths.get(args[0]).toAbsolutePath().normalize();
            Path heapDumpPathP = Paths.get(args[1]).toAbsolutePath().normalize();
            String heapDumpPath = heapDumpPathP.toString();
            int topN = 10;
            if (args.length == 3) {
                try {
                    topN = Integer.parseInt(args[2]);
                } catch (NumberFormatException nfe) {
                    log.error("Invalid topN value: {}. Must be an integer.", args[2]);
                    System.exit(2);
                }
            }

            log.info("[LeakSuspectPromptCreator] reportsDir={}", reportsDir);
            log.info("[LeakSuspectPromptCreator] heapDumpPath={}", heapDumpPath);
            log.info("[LeakSuspectPromptCreator] topN={}", topN);

            LeakSuspectPromptCreator creator = new LeakSuspectPromptCreator()
                .withReportsDir(reportsDir)
                .withHeapDumpPath(heapDumpPath)
                .withTopN(topN);

            // Log again right before extraction to ensure absolute paths are recorded
            log.info("[LeakSuspectPromptCreator] About to build prompt using reportsDir={} and heapDumpPath={}", reportsDir, heapDumpPath);

            String prompt = creator.build();

            // Write prompt to a timestamped file in the reports directory for convenience
            String ts = Instant.now().toString().replace(':', '-');
            Path outFile = reportsDir.resolve("leak_suspect_prompt_" + ts + ".txt");
            try {
                Files.createDirectories(reportsDir);
                Files.writeString(outFile, prompt);
                log.info("[LeakSuspectPromptCreator] Prompt written to {}", outFile);
            } catch (Exception e) {
                log.error("[LeakSuspectPromptCreator] Failed to write prompt to file: {}", e.getMessage(), e);
            }

            // Log the prompt (note: not written to stdout)
            log.info(prompt);

        } catch (Exception e) {
            log.error("[LeakSuspectPromptCreator] ERROR building prompt", e);
            System.exit(10);
        }
    }
}

package org.test.claudeAPI;

import org.test.AnalysisResult;

import java.nio.file.*;
import java.util.logging.*;

/**
 * AnalyzerPipeline
 * Ties the watcher service → MAT report extractor → Claude agent → JSON output.
 * Call this from your existing watcher service once MAT has finished:
 *   AnalyzerPipeline pipeline = new AnalyzerPipeline(System.getenv("ANTHROPIC_API_KEY"));
 *   AnalysisResult   result   = pipeline.runAnalysis(heapDumpPath, matReportsDir);
 *   System.out.println(result.toJson());
 * Or run standalone:
 *   java AnalyzerPipeline /path/to/dump.hprof /path/to/mat/reports
 */
public class AnalyzerPipeline {

    private static final Logger LOG = Logger.getLogger(AnalyzerPipeline.class.getName());

    /** How many top retained-object types to surface in the output. */
    private static final int TOP_N = 10;

    private final HeapAnalyzerAgent  agent;
    private final MatReportExtractor extractor;

    public AnalyzerPipeline(String anthropicApiKey) {
        this.agent     = new HeapAnalyzerAgent(anthropicApiKey);
        this.extractor = new MatReportExtractor();
    }

    // -------------------------------------------------------------------------
    //  Primary entry point
    // -------------------------------------------------------------------------

    /**
     * Run the full analysis pipeline.
     *
     * @param heapDumpPath  Path to the original .hprof file (used for provenance).
     * @param matReportsDir Directory where MAT wrote its report output.
     * @return              Structured {@link AnalysisResult}.
     */
    public AnalysisResult runAnalysis(String heapDumpPath, Path matReportsDir)
            throws Exception {

        LOG.info("=== Heap Dump Analysis Pipeline Starting ===");
        LOG.info("Heap dump : " + heapDumpPath);
        LOG.info("MAT dir   : " + matReportsDir);

        if (!Files.isDirectory(matReportsDir)) {
            throw new IllegalArgumentException(
                "MAT reports directory does not exist: " + matReportsDir);
        }

        LOG.info("Step 1/3 – Extracting MAT report content…");
        MatReportExtractor.MatReport report =
            extractor.extract(matReportsDir, heapDumpPath);

        LOG.info("  Suspect blocks found : " + report.suspectBlocks.size());
        LOG.info("  Context size (chars) : " + report.toPromptContext().length());

        LOG.info("Step 2/3 – Running AI analysis (two-phase)…");
        AnalysisResult result = agent.analyze(report, TOP_N);

        LOG.info("Step 3/3 – Analysis complete.");
        LOG.info("  Confidence           : " + result.confidence);
        LOG.info("  Root cause type      : " +
            (result.rootCause != null ? result.rootCause.leakPatternType : "unknown"));
        LOG.info("  Top retained objects : " +
            (result.topRetainedObjects != null ? result.topRetainedObjects.size() : 0));

        return result;
    }

    /**
     * Convenience overload that also writes the JSON result to a file.
     *
     * @param outputJsonPath  File to write the JSON result to.
     */
    public AnalysisResult runAnalysisAndSave(String heapDumpPath,
                                             Path   matReportsDir,
                                             Path   outputJsonPath) throws Exception {
        AnalysisResult result = runAnalysis(heapDumpPath, matReportsDir);
        String json = result.toJson();
        Files.writeString(outputJsonPath, json);
        LOG.info("Analysis written to: " + outputJsonPath.toAbsolutePath());
        return result;
    }

    // -------------------------------------------------------------------------
    //  Standalone CLI entry point
    // -------------------------------------------------------------------------

    /**
     * Run from the command line:
     *   java -cp <classpath> com.heapanalyzer.AnalyzerPipeline \
     *        /path/to/dump.hprof \
     *        /path/to/mat-reports \
     *        [optional: /path/to/output.json]
     * ANTHROPIC_API_KEY must be set as an environment variable.
     */
    public static void main(String[] args) throws Exception {
        configureLogging();

        if (args.length < 2) {
            System.err.println(
                "Usage: AnalyzerPipeline <heap-dump.hprof> <mat-reports-dir> [output.json]");
            System.exit(1);
        }

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: ANTHROPIC_API_KEY environment variable is not set.");
            System.exit(1);
        }

        String  heapDumpPath  = args[0];
        Path    matReportsDir = Path.of(args[1]);
        Path    outputPath    = args.length > 2
            ? Path.of(args[2])
            : matReportsDir.resolve("analysis_result.json");

        AnalyzerPipeline pipeline = new AnalyzerPipeline(apiKey);
        AnalysisResult   result   = pipeline.runAnalysisAndSave(
            heapDumpPath, matReportsDir, outputPath);

        // Print summary to stdout regardless of output file
        System.out.println("\n========================================");
        System.out.println(" ANALYSIS SUMMARY");
        System.out.println("========================================");
        System.out.println(result.summary);
        if (result.rootCause != null) {
            System.out.println("\nRoot cause  : " + result.rootCause.description);
            System.out.println("Leak type   : " + result.rootCause.leakPatternType);
            System.out.println("Class       : " + result.rootCause.responsibleClass);
        }
        System.out.println("Confidence  : " + result.confidence);
        System.out.println("\nFull JSON   : " + outputPath.toAbsolutePath());
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter() {
            @Override public String format(LogRecord r) {
                return String.format("[%s] %s%n",
                    r.getLevel(), r.getMessage());
            }
        });
        root.addHandler(handler);
    }
}

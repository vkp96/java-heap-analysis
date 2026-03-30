package org.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.strategy.AnalysisStrategyFactory;
import org.test.strategy.AnalysisStrategyType;
import org.test.strategy.ClaudeAnalysisStrategy;
import org.test.strategy.HeapAnalysisStrategy;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AnalyzerPipeline
 * Ties MAT report extraction to a pluggable {@link HeapAnalysisStrategy} and
 * writes the final {@link AnalysisResult} JSON output.
 * Call this from your watcher service once MAT has finished:
 *   AnalyzerPipeline pipeline = new AnalyzerPipeline(strategy);
 *   AnalysisResult   result   = pipeline.runAnalysis(heapDumpPath, matReportsDir);
 *   // persist or forward result
 * Or run standalone:
 *   java AnalyzerPipeline /path/to/dump.hprof /path/to/mat/reports [output.json] [strategy]
 */
public class AnalyzerPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyzerPipeline.class);

    /** How many top retained-object types to surface in the output. */
    private static final int DEFAULT_TOP_N = 10;

    private final HeapAnalysisStrategy strategy;
    private final MatReportExtractor extractor;
    private final int topN;

    public AnalyzerPipeline(HeapAnalysisStrategy strategy) {
        this(strategy, DEFAULT_TOP_N);
    }

    public AnalyzerPipeline(HeapAnalysisStrategy strategy, int topN) {
        this.strategy  = strategy;
        this.extractor = new MatReportExtractor();
        this.topN      = topN > 0 ? topN : DEFAULT_TOP_N;
    }

    /**
     * Backward-compatible constructor for legacy Claude-only callers.
     */
    public AnalyzerPipeline(String anthropicApiKey) {
        this(new ClaudeAnalysisStrategy(anthropicApiKey));
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

        Path normalizedReportsDir = resolveMatReportsDir(heapDumpPath, matReportsDir);

        LOG.info("=== Heap Dump Analysis Pipeline Starting ===");
        LOG.info("Heap dump      : {}", heapDumpPath);
        LOG.info("MAT dir        : {}", normalizedReportsDir);
        LOG.info("Strategy       : {}", strategy.strategyName());
        LOG.info("Top N objects  : {}", topN);

        if (!Files.isDirectory(normalizedReportsDir)) {
            throw new IllegalArgumentException(
                "MAT reports directory does not exist: " + normalizedReportsDir);
        }

        LOG.info("Step 1/3 – Extracting MAT report content…");
        MatReportExtractor.MatReport report =
            extractor.extract(normalizedReportsDir, heapDumpPath);

        LOG.info("  Suspect blocks found : {}", report.suspectBlocks.size());
        LOG.info("  Context size (chars) : {}", report.toPromptContext().length());

        LOG.info("Step 2/3 – Running analysis strategy '{}'…", strategy.strategyName());
        AnalysisResult result = strategy.analyze(report, topN);

        LOG.info("Step 3/3 – Analysis complete.");
        LOG.info("  Confidence           : {}", result.confidence);
        LOG.info("  Root cause type      : {}",
            result.rootCause != null ? result.rootCause.leakPatternType : "unknown");
        LOG.info("  Top retained objects : {}",
            result.topRetainedObjects != null ? result.topRetainedObjects.size() : 0);

        return result;
    }

    private Path resolveMatReportsDir(String heapDumpPath, Path matReportsDir) {
        Path normalizedReportsDir = matReportsDir.toAbsolutePath().normalize();

        if (heapDumpPath == null || heapDumpPath.isBlank()) {
            return normalizedReportsDir;
        }

        Path expectedReportsDir = MATRunner.resolveReportDirectory(Path.of(heapDumpPath), normalizedReportsDir);
        if (!normalizedReportsDir.equals(expectedReportsDir) && Files.isDirectory(expectedReportsDir)) {
            LOG.info("MAT reports root {} resolved to heap-specific reports directory {}", normalizedReportsDir, expectedReportsDir);
            return expectedReportsDir;
        }

        return normalizedReportsDir;
    }

    /**
     * Convenience overload that also writes the JSON result to a file.
     *
     * @param outputJsonPath  File to write the JSON result to.
     */
    public AnalysisResult runAnalysisAndSave(String heapDumpPath,
                                             Path   matReportsDir,
                                             Path   outputJsonPath) throws Exception {
        Path normalizedOutput = outputJsonPath.toAbsolutePath().normalize();
        if (normalizedOutput.getParent() != null) {
            Files.createDirectories(normalizedOutput.getParent());
        }
        AnalysisResult result = runAnalysis(heapDumpPath, matReportsDir);
        String json = result.toJson();
        Files.writeString(normalizedOutput, json);
        LOG.info("Analysis written to: {}", normalizedOutput);
        return result;
    }

    // -------------------------------------------------------------------------
    //  Standalone CLI entry point
    // -------------------------------------------------------------------------

    /**
     * Run from the command line:
     *   java -cp <classpath> org.test.AnalyzerPipeline \
     *        /path/to/dump.hprof \
     *        /path/to/mat-reports \
     *        [optional: /path/to/output.json] \
     *        [optional: strategy]
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            LOG.error("Usage: AnalyzerPipeline <heap-dump.hprof> <mat-reports-dir> [output.json] [strategy]");
            System.exit(1);
        }

        String heapDumpPath = args[0];
        Path matReportsDir = Path.of(args[1]).toAbsolutePath().normalize();
        Path outputPath = args.length > 2
            ? Path.of(args[2]).toAbsolutePath().normalize()
            : matReportsDir.resolve("analysis_result.json");
        AnalysisStrategyType strategyType = args.length > 3
            ? AnalysisStrategyType.fromString(args[3])
            : AnalysisStrategyFactory.resolveType();

        LOG.info("Standalone AnalyzerPipeline starting with strategy={}", strategyType);
        HeapAnalysisStrategy strategy = AnalysisStrategyFactory.create(strategyType, matReportsDir);
        AnalyzerPipeline pipeline = new AnalyzerPipeline(strategy);
        AnalysisResult result = pipeline.runAnalysisAndSave(heapDumpPath, matReportsDir, outputPath);

        LOG.info("Summary: {}", result.summary);
        if (result.rootCause != null) {
            LOG.info("Root cause  : {}", result.rootCause.description);
            LOG.info("Leak type   : {}", result.rootCause.leakPatternType);
            LOG.info("Class       : {}", result.rootCause.responsibleClass);
        }
        LOG.info("Confidence  : {}", result.confidence);
        LOG.info("Full JSON   : {}", outputPath);
    }
}

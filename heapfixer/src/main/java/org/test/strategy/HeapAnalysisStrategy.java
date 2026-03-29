package org.test.strategy;

import org.test.AnalysisResult;
import org.test.MatReportExtractor;

/**
 * Pluggable strategy interface for heap dump analysis.
 * <p>
 * Each implementation wraps a different AI backend (Claude, OpenAI, Gemini,
 * file-based Copilot prompt, …) but they all accept the same MAT report input
 * and produce an {@link AnalysisResult}.
 * <p>
 * Register new implementations in {@link AnalysisStrategyType} and
 * {@link AnalysisStrategyFactory} so they can be selected via configuration.
 */
public interface HeapAnalysisStrategy {

    /**
     * Analyse the given MAT report and return a structured result.
     *
     * @param report extracted MAT report (system overview, leak suspects, dominator tree, etc.)
     * @param topN   number of top retained-object types to include in the result
     * @return fully populated {@link AnalysisResult}
     * @throws Exception on API errors, timeouts, parse failures, etc.
     */
    AnalysisResult analyze(MatReportExtractor.MatReport report, int topN) throws Exception;

    /**
     * Human-readable name of this strategy, used in log messages.
     */
    String strategyName();
}


package org.test.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;
import org.test.parser.claude.HeapAnalyzerAgent;
import org.test.MatReportExtractor;

/**
 * Strategy that delegates to the existing {@link HeapAnalyzerAgent} which
 * uses the Anthropic Claude Messages API (two-phase extraction + synthesis).
 */
public class ClaudeAnalysisStrategy implements HeapAnalysisStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeAnalysisStrategy.class);

    private final HeapAnalyzerAgent agent;

    public ClaudeAnalysisStrategy(String anthropicApiKey) {
        this.agent = new HeapAnalyzerAgent(anthropicApiKey);
    }

    @Override
    public AnalysisResult analyze(MatReportExtractor.MatReport report, int topN) throws Exception {
        LOG.info("[Claude] Starting two-phase analysis (topN={})", topN);
        AnalysisResult result = agent.analyze(report, topN);
        LOG.info("[Claude] Analysis complete. Confidence={}", result.confidence);
        return result;
    }

    @Override
    public String strategyName() {
        return "Claude (Anthropic API)";
    }
}


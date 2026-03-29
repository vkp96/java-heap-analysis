package org.test.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Creates a {@link HeapAnalysisStrategy} instance based on a
 * {@link AnalysisStrategyType} and the current environment / configuration.
 * <p>
 * API keys are read from environment variables:
 * <ul>
 *   <li>{@code ANTHROPIC_API_KEY} – for {@link AnalysisStrategyType#CLAUDE}</li>
 *   <li>{@code OPENAI_API_KEY}    – for {@link AnalysisStrategyType#OPENAI}</li>
 *   <li>{@code GEMINI_API_KEY}    – for {@link AnalysisStrategyType#GEMINI}</li>
 * </ul>
 * The strategy type itself is typically supplied via the
 * {@code ANALYSIS_STRATEGY} environment variable or a CLI argument.
 */
public final class AnalysisStrategyFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisStrategyFactory.class);

    private AnalysisStrategyFactory() { /* utility class */ }

    /**
     * Resolve the strategy type from the {@code ANALYSIS_STRATEGY} environment
     * variable, falling back to {@link AnalysisStrategyType#CLAUDE} if unset.
     */
    public static AnalysisStrategyType resolveType() {
        String env = System.getenv("ANALYSIS_STRATEGY");
        if (env == null || env.isBlank()) {
            LOG.info("ANALYSIS_STRATEGY not set; defaulting to CLAUDE");
            return AnalysisStrategyType.CLAUDE;
        }
        AnalysisStrategyType type = AnalysisStrategyType.fromString(env);
        LOG.info("Resolved analysis strategy from environment: {}", type);
        return type;
    }

    /**
     * Create a strategy instance.
     *
     * @param type             the strategy type to create
     * @param promptWorkDir    working directory for file-based strategies
     *                         (e.g. {@code COPILOT_PROMPT}); may be {@code null}
     *                         for API-based strategies
     * @return a ready-to-use {@link HeapAnalysisStrategy}
     */
    public static HeapAnalysisStrategy create(AnalysisStrategyType type, Path promptWorkDir) {
        return switch (type) {
            case CLAUDE -> {
                String key = requireEnv("ANTHROPIC_API_KEY", type);
                LOG.info("Creating ClaudeAnalysisStrategy");
                yield new ClaudeAnalysisStrategy(key);
            }
            case OPENAI -> {
                String key = requireEnv("OPENAI_API_KEY", type);
                LOG.info("Creating OpenAiAnalysisStrategy");
                yield new OpenAiAnalysisStrategy(key);
            }
            case GEMINI -> {
                String key = requireEnv("GEMINI_API_KEY", type);
                LOG.info("Creating GeminiAnalysisStrategy");
                yield new GeminiAnalysisStrategy(key);
            }
            case COPILOT_PROMPT -> {
                Path workDir = promptWorkDir != null
                        ? promptWorkDir
                        : Path.of(System.getProperty("java.io.tmpdir")).resolve("heapfixer-copilot");
                long timeoutMinutes = longEnvOrDefault("COPILOT_PROMPT_TIMEOUT_MINUTES", 30);
                LOG.info("Creating CopilotPromptStrategy (workDir={}, timeout={}m)", workDir, timeoutMinutes);
                yield new CopilotPromptStrategy(workDir, timeoutMinutes);
            }
        };
    }

    /**
     * Convenience overload that resolves the strategy type from the environment.
     */
    public static HeapAnalysisStrategy create(Path promptWorkDir) {
        return create(resolveType(), promptWorkDir);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String requireEnv(String varName, AnalysisStrategyType type) {
        String val = System.getenv(varName);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException(
                    varName + " environment variable is required for strategy " + type);
        }
        return val;
    }

    private static long longEnvOrDefault(String varName, long defaultValue) {
        String val = System.getenv(varName);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Long.parseLong(val.strip());
        } catch (NumberFormatException e) {
            LOG.warn("Invalid numeric value for {}: '{}'; using default {}", varName, val, defaultValue);
            return defaultValue;
        }
    }
}


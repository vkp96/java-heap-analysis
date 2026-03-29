package org.test.strategy;

import java.util.Locale;

/**
 * Enumerates the supported heap-analysis strategies.
 * <p>
 * Pass the name (case-insensitive) via the {@code ANALYSIS_STRATEGY} environment
 * variable or the CLI {@code --strategy} flag to select a backend at runtime.
 */
public enum AnalysisStrategyType {

    /** Anthropic Claude API (two-phase extraction + synthesis). */
    CLAUDE,

    /** OpenAI Chat Completions API */
    OPENAI,

    /** Google Gemini Generative Language API */
    GEMINI,

    /**
     * File-based Copilot prompt flow.
     * <p>
     * Generates a prompt file that a human (or external tool) pastes into
     * Copilot Chat; the strategy then polls for a response file and parses it.
     */
    COPILOT_PROMPT;

    /**
     * Parse a strategy name from a string, case-insensitively.
     * Accepts both enum names ({@code CLAUDE}) and kebab/snake variants
     * ({@code copilot-prompt}, {@code copilot_prompt}).
     *
     * @throws IllegalArgumentException if the value does not match any known strategy
     */
    public static AnalysisStrategyType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Strategy name must not be null or blank");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown analysis strategy: '" + value + "'. " +
                    "Supported values: CLAUDE, OPENAI, GEMINI, COPILOT_PROMPT");
        }
    }
}


package org.test.remediation;

import java.util.Locale;

/**
 * Enumerates supported patch-generation providers.
 * <p>
 * Only {@link #LOCAL_PLAN} is implemented in this increment. Other values are
 * reserved so future AI-backed patch providers can be added without changing
 * the workflow contract.
 */
public enum PatchProviderType {

    /** Deterministic local patch generation using existing planning artifacts. */
    LOCAL_PLAN,

    /** Reserved for a future GitHub Copilot-backed patch backend. */
    COPILOT,

    /** Reserved for future OpenAI-backed patch generation. */
    OPENAI,

    /** Reserved for future Claude-backed patch generation. */
    CLAUDE,

    /** Reserved for future Gemini-backed patch generation. */
    GEMINI;

    /**
     * Parses a provider value case-insensitively and supports kebab/snake case.
     *
     * @param value provider string from configuration
     * @return parsed provider type
     */
    public static PatchProviderType fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL_PLAN;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return valueOf(normalized);
    }
}


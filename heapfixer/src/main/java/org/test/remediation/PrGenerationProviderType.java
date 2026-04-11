package org.test.remediation;

import java.util.Locale;

/**
 * Enumerates supported PR-generation providers.
 */
public enum PrGenerationProviderType {

    /** Deterministic local backend that generates provider-neutral PR artifacts only. */
    LOCAL_ARTIFACT,

    /** Reserved for future Copilot-backed PR generation. */
    COPILOT,

    /** Reserved for future OpenAI-backed PR generation. */
    OPENAI,

    /** Reserved for future Claude-backed PR generation. */
    CLAUDE,

    /** Reserved for future Gemini-backed PR generation. */
    GEMINI;

    /**
     * Parses a configured provider value case-insensitively and supports kebab/snake case.
     *
     * @param value provider string from configuration
     * @return parsed provider type
     */
    public static PrGenerationProviderType fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL_ARTIFACT;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return valueOf(normalized);
    }
}


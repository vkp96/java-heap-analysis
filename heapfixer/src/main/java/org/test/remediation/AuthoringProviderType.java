package org.test.remediation;

import java.util.Locale;

/**
 * Enumerates supported AI authoring backends for PR authoring artifacts.
 * <p>
 * Only {@link #LOCAL_PLAN} and {@link #COPILOT} are implemented in this
 * increment. Other values are reserved so future API-backed implementations can
 * be added without reshaping the configuration model.
 */
public enum AuthoringProviderType {

    /** Deterministic local result derived from the existing change plan. */
    LOCAL_PLAN,

    /** GitHub Copilot-backed authoring backend. */
    COPILOT,

    /** Reserved for future OpenAI-backed authoring. */
    OPENAI,

    /** Reserved for future Claude-backed authoring. */
    CLAUDE,

    /** Reserved for future Gemini-backed authoring. */
    GEMINI;

    /**
     * Parses a provider value case-insensitively and supports kebab/snake case.
     *
     * @param value provider string from configuration
     * @return parsed provider type
     */
    public static AuthoringProviderType fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL_PLAN;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return valueOf(normalized);
    }
}


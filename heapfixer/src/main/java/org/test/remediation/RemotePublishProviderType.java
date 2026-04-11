package org.test.remediation;

import java.util.Locale;

/**
 * Enumerates supported remote publish providers.
 */
public enum RemotePublishProviderType {

    /** Deterministic local backend used for tests and artifact-only dry runs. */
    LOCAL_RECORD_ONLY,

    /** GitHub-backed remote publishing that pushes a branch and creates a draft PR. */
    GITHUB;

    /**
     * Parses a configured provider value case-insensitively and supports kebab/snake case.
     *
     * @param value provider string from configuration
     * @return parsed provider type
     */
    public static RemotePublishProviderType fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL_RECORD_ONLY;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return valueOf(normalized);
    }
}


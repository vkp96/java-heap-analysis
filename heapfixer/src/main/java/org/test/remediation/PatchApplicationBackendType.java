package org.test.remediation;

import java.util.Locale;

/**
 * Enumerates supported local patch-application backends.
 */
public enum PatchApplicationBackendType {

    /** Applies structured patch files to a local Git working tree on a new branch. */
    LOCAL_GIT;

    /**
     * Parses a configured backend value case-insensitively and supports kebab/snake case.
     *
     * @param value backend string from configuration
     * @return parsed backend type
     */
    public static PatchApplicationBackendType fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL_GIT;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        return valueOf(normalized);
    }
}


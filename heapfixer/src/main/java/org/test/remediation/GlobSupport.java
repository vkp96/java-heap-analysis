package org.test.remediation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility methods for matching relative repository paths against a small glob
 * subset used by the remediation workflow.
 * <p>
 * Supported wildcard behavior is intentionally minimal and deterministic:
 * {@code *}, {@code **}, and {@code ?} are translated into regular expressions.
 */
final class GlobSupport {

    /**
     * Utility class; not meant to be instantiated.
     */
    private GlobSupport() {
    }

    /**
     * Returns whether the given relative path matches at least one non-blank glob.
     *
     * @param relativePath repository-relative path to test
     * @param globs        candidate glob expressions
     * @return {@code true} if any glob matches the normalized path, otherwise {@code false}
     */
    static boolean matchesAny(String relativePath, List<String> globs) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        if (globs == null || globs.isEmpty()) {
            return false;
        }
        String normalizedPath = normalizePath(relativePath);
        for (String glob : globs) {
            if (glob != null && !glob.isBlank() && matches(normalizedPath, glob)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests a single relative path against a single glob expression.
     *
     * @param relativePath repository-relative path to test
     * @param glob         glob expression supported by this helper
     * @return {@code true} if the path matches the glob, otherwise {@code false}
     */
    static boolean matches(String relativePath, String glob) {
        String regex = toRegex(glob);
        return Pattern.compile(regex).matcher(normalizePath(relativePath)).matches();
    }

    /**
     * Converts Windows path separators to forward slashes so matching remains
     * platform-independent.
     *
     * @param path path string to normalize
     * @return normalized path using forward slashes
     */
    static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    /**
     * Converts the supported glob syntax into a regular expression.
     *
     * @param glob glob expression to convert
     * @return equivalent regular-expression pattern text
     */
    private static String toRegex(String glob) {
        String normalized = normalizePath(glob);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '*') {
                boolean doubleStar = i + 1 < normalized.length() && normalized.charAt(i + 1) == '*';
                if (doubleStar) {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (ch == '?') {
                regex.append("[^/]");
            } else if (".()[]{}+$^|".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        regex.append('$');
        return regex.toString();
    }
}


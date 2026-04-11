package org.test.remediation;

/**
 * Full output from a provider-backed PR authoring execution.
 *
 * @param promptText prompt sent to the backend, if applicable
 * @param rawResponse raw backend response, if applicable
 * @param result normalized provider-neutral result
 */
public record PrAuthorExecution(String promptText, String rawResponse, PrAuthorResult result) {
}


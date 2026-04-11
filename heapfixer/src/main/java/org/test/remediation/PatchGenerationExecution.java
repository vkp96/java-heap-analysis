package org.test.remediation;

/**
 * Full output from a patch generation backend execution.
 *
 * @param promptText optional prompt sent to an AI backend
 * @param rawResponse optional raw response received from an AI backend
 * @param diffPreview optional human-readable unified-diff preview
 * @param result normalized structured patch generation result
 */
public record PatchGenerationExecution(String promptText,
                                       String rawResponse,
                                       String diffPreview,
                                       PatchGenerationResult result) {
}


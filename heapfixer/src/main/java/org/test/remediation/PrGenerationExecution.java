package org.test.remediation;

/**
 * Full output from a PR-generation backend execution.
 *
 * @param previewMarkdown rendered markdown preview for review
 * @param result normalized PR-generation result
 */
public record PrGenerationExecution(String previewMarkdown,
                                    PrGenerationResult result) {
}

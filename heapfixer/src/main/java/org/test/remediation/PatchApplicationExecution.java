package org.test.remediation;

/**
 * Full output from a patch-application backend execution.
 *
 * @param finalDiff repository diff captured after applying structured edits
 * @param validationOutput combined validation-command output when commands were run
 * @param result normalized patch-application result
 */
public record PatchApplicationExecution(String finalDiff,
                                        String validationOutput,
                                        PatchApplicationResult result) {
}


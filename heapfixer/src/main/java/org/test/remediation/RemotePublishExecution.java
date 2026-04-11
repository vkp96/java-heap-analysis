package org.test.remediation;

/**
 * Full output from a remote publish backend execution.
 *
 * @param pushOutput git push command output, if available
 * @param rawResponse raw provider API response, if available
 * @param result normalized remote publish result
 */
public record RemotePublishExecution(String pushOutput,
                                     String rawResponse,
                                     RemotePublishResult result) {
}


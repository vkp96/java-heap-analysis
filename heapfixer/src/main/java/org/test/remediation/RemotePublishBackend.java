package org.test.remediation;

/**
 * Pushes an applied remediation branch to a remote and creates a draft PR.
 */
public interface RemotePublishBackend {

    /**
     * @return backend/provider name for logging and artifacts
     */
    String backendName();

    /**
     * Executes the remote publish phase.
     *
     * @param request remote publish request with final title/body and branch metadata
     * @param config remote publish configuration
     * @return execution output including push logs, API response, and normalized result
     * @throws Exception if the publish phase encounters a fatal error
     */
    RemotePublishExecution execute(RemotePublishRequest request,
                                   RemediationWorkflowConfig.RemotePublishConfig config) throws Exception;
}


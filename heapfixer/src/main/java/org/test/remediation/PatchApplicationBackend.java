package org.test.remediation;

/**
 * Applies structured patch results to a local repository.
 */
public interface PatchApplicationBackend {

    /**
     * @return provider/backend name for logging and artifacts
     */
    String backendName();

    /**
     * Executes the patch-application phase.
     *
     * @param request machine-oriented patch-application request
     * @param config local patch-application configuration
     * @return execution details including diff, validation output, and normalized result
     * @throws Exception if a fatal git or file-system error occurs
     */
    PatchApplicationExecution execute(PatchApplicationRequest request,
                                      RemediationWorkflowConfig.PatchApplicationConfig config) throws Exception;
}


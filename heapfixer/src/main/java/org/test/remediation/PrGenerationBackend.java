package org.test.remediation;

/**
 * Generates provider-neutral pull request artifacts from an applied remediation branch.
 */
public interface PrGenerationBackend {

    /**
     * @return backend/provider name for logging and artifacts
     */
    String backendName();

    /**
     * Executes the PR-generation phase.
     *
     * @param request request containing applied branch metadata and final diff
     * @param config PR-generation configuration
     * @return execution output including preview content and normalized result
     * @throws Exception if artifact generation fails
     */
    PrGenerationExecution execute(PrGenerationRequest request,
                                  RemediationWorkflowConfig.PrGenerationConfig config) throws Exception;
}

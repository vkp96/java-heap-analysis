package org.test.remediation;

/**
 * Strategy interface for provider-backed patch generation.
 */
public interface PatchGenerationBackend {

    /**
     * Returns the human-readable backend name for logging and artifacts.
     *
     * @return backend name
     */
    String backendName();

    /**
     * Executes patch generation for the supplied request.
     *
     * @param request patch-generation request containing file-level authoring output
     * @param config patch-generation configuration controlling backend behavior
     * @return execution output including optional prompt/response and normalized result
     * @throws Exception if backend execution fails
     */
    PatchGenerationExecution execute(PatchGenerationRequest request,
                                     RemediationWorkflowConfig.PatchGenerationConfig config) throws Exception;
}


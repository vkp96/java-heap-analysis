package org.test.remediation;

/**
 * Strategy interface for provider-backed PR authoring.
 * <p>
 * Implementations can be deterministic/local or backed by external AI APIs,
 * but they must all return the same normalized {@link PrAuthorExecution}
 * contract.
 */
public interface PrAuthorBackend {

    /**
     * Returns the human-readable backend name for logging and artifacts.
     *
     * @return backend name
     */
    String backendName();

    /**
     * Executes PR authoring for the supplied machine-oriented request and change
     * plan.
     *
     * @param request request payload created from the deterministic planning stage
     * @param changePlan deterministic change plan generated for the candidate files
     * @param config authoring configuration controlling backend behavior
     * @return execution output including optional prompt/response text and a normalized result
     * @throws Exception if backend execution fails
     */
    PrAuthorExecution execute(PrAuthorRequest request,
                              PrChangePlan changePlan,
                              RemediationWorkflowConfig.AuthoringConfig config) throws Exception;
}


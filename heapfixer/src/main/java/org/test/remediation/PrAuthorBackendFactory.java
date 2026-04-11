package org.test.remediation;

/**
 * Factory for selecting the configured PR authoring backend.
 */
public final class PrAuthorBackendFactory {

    /**
     * Utility class; not meant to be instantiated.
     */
    private PrAuthorBackendFactory() {
    }

    /**
     * Creates the configured PR authoring backend.
     *
     * @param config authoring configuration containing provider selection
     * @return backend implementation for the configured provider
     */
    public static PrAuthorBackend create(RemediationWorkflowConfig.AuthoringConfig config) {
        AuthoringProviderType provider = AuthoringProviderType.fromString(config.provider);
        return switch (provider) {
            case LOCAL_PLAN -> new LocalPlanPrAuthorBackend();
            case COPILOT -> new CopilotPrAuthorBackend(config);
            case OPENAI, CLAUDE, GEMINI -> throw new IllegalStateException(
                    "Authoring provider " + provider + " is not implemented yet.");
        };
    }
}


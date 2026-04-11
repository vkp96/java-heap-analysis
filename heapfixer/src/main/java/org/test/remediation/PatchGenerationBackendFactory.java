package org.test.remediation;

/**
 * Factory for selecting the configured patch generation backend.
 */
public final class PatchGenerationBackendFactory {

    /**
     * Utility class; not meant to be instantiated.
     */
    private PatchGenerationBackendFactory() {
    }

    /**
     * Creates the configured patch generation backend.
     *
     * @param config patch-generation configuration containing provider selection
     * @return backend implementation for the configured provider
     */
    public static PatchGenerationBackend create(RemediationWorkflowConfig.PatchGenerationConfig config) {
        PatchProviderType provider = PatchProviderType.fromString(config.provider);
        return switch (provider) {
            case LOCAL_PLAN -> new LocalPlanPatchBackend();
            case COPILOT -> new CopilotPatchBackend(config);
            case OPENAI, CLAUDE, GEMINI -> throw new IllegalStateException(
                    "Patch generation provider " + provider + " is not implemented yet.");
        };
    }
}


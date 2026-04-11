package org.test.remediation;

/**
 * Factory for selecting the configured PR-generation backend.
 */
public final class PrGenerationBackendFactory {

    private PrGenerationBackendFactory() {
    }

    public static PrGenerationBackend create(RemediationWorkflowConfig.PrGenerationConfig config) {
        PrGenerationProviderType provider = PrGenerationProviderType.fromString(config.provider);
        return switch (provider) {
            case LOCAL_ARTIFACT -> new LocalArtifactPrGenerationBackend();
            case COPILOT, OPENAI, CLAUDE, GEMINI -> throw new IllegalStateException(
                    "PR generation provider " + provider + " is not implemented yet.");
        };
    }
}

package org.test.remediation;

/**
 * Factory for selecting the configured patch-application backend.
 */
public final class PatchApplicationBackendFactory {

    private PatchApplicationBackendFactory() {
    }

    /**
     * Creates the configured patch-application backend.
     *
     * @param config patch-application configuration containing provider selection
     * @return backend implementation for the configured provider
     */
    public static PatchApplicationBackend create(RemediationWorkflowConfig.PatchApplicationConfig config) {
        PatchApplicationBackendType provider = PatchApplicationBackendType.fromString(config.provider);
        return switch (provider) {
            case LOCAL_GIT -> new LocalGitPatchApplicationBackend();
        };
    }
}


package org.test.remediation;

/**
 * Factory for selecting the configured remote publish backend.
 */
public final class RemotePublishBackendFactory {

    private RemotePublishBackendFactory() {
    }

    /**
     * Creates the configured remote publish backend.
     *
     * @param config remote publish configuration containing provider selection
     * @return backend implementation for the configured provider
     */
    public static RemotePublishBackend create(RemediationWorkflowConfig.RemotePublishConfig config) {
        RemotePublishProviderType provider = RemotePublishProviderType.fromString(config.provider);
        return switch (provider) {
            case LOCAL_RECORD_ONLY -> new LocalRecordOnlyRemotePublishBackend();
            case GITHUB -> new GitHubRemotePublishBackend(config);
        };
    }
}


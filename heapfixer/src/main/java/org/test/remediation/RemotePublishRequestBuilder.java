package org.test.remediation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Builds remote-publish requests from generated PR artifacts and the applied branch state.
 */
public class RemotePublishRequestBuilder {

    /**
     * Builds a remote-publish request.
     *
     * @param repoRoot repository root containing the committed remediation branch
     * @param prGenerationResult generated PR title/body metadata
     * @param patchApplicationResult patch application result containing the branch and commit metadata
     * @param config remote publish configuration
     * @return machine-oriented request for the configured remote publish provider
     */
    public RemotePublishRequest build(Path repoRoot,
                                      PrGenerationResult prGenerationResult,
                                      PatchApplicationResult patchApplicationResult,
                                      RemediationWorkflowConfig.RemotePublishConfig config) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(prGenerationResult, "prGenerationResult must not be null");
        Objects.requireNonNull(patchApplicationResult, "patchApplicationResult must not be null");
        Objects.requireNonNull(config, "config must not be null");

        RemotePublishRequest request = new RemotePublishRequest();
        request.provider = config.provider;
        request.repoRoot = repoRoot.toAbsolutePath().normalize().toString();
        request.remoteName = config.remoteName;
        request.owner = config.githubOwner;
        request.repo = config.githubRepo;
        request.apiBaseUrl = config.githubApiBaseUrl;
        request.baseBranch = prGenerationResult.baseBranch;
        request.headBranch = prGenerationResult.headBranch;
        request.commitSha = patchApplicationResult.commitSha;
        request.title = prGenerationResult.title;
        request.body = prGenerationResult.body;
        request.draft = prGenerationResult.draft;
        return request;
    }
}


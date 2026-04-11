package org.test.remediation;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Builds patch-application requests from generated structured patch artifacts.
 */
public class PatchApplicationRequestBuilder {

    /**
     * Builds a patch-application request.
     *
     * @param repoRoot repository root where the patch will be applied
     * @param patchRequest patch-generation request produced earlier in the workflow
     * @param patchResult normalized structured patch result
     * @param config patch-application configuration
     * @return machine-oriented patch-application request
     */
    public PatchApplicationRequest build(Path repoRoot,
                                         PatchGenerationRequest patchRequest,
                                         PatchGenerationResult patchResult,
                                         RemediationWorkflowConfig.PatchApplicationConfig config) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(patchRequest, "patchRequest must not be null");
        Objects.requireNonNull(patchResult, "patchResult must not be null");
        Objects.requireNonNull(config, "config must not be null");

        PatchApplicationRequest request = new PatchApplicationRequest();
        request.provider = config.provider;
        request.patchProvider = patchResult.provider;
        request.repoRoot = repoRoot.toAbsolutePath().normalize().toString();
        request.branchName = patchRequest.branchName;
        request.prTitle = patchRequest.prTitle;
        request.summary = patchResult.summary != null && !patchResult.summary.isBlank()
                ? patchResult.summary
                : patchRequest.summary;
        request.autoCommit = config.autoCommit;
        request.commitMessage = buildCommitMessage(patchRequest, patchResult, config);
        request.gitUserName = config.gitUserName;
        request.gitUserEmail = config.gitUserEmail;
        request.structuredPatchFiles.addAll(patchResult.structuredPatchFiles);
        request.notes.addAll(patchResult.notes);
        request.validationCommands.addAll(config.validationCommands);

        Set<String> suggestedValidationSteps = new LinkedHashSet<>();
        for (PatchGenerationRequest.PatchTargetFile file : patchRequest.files) {
            suggestedValidationSteps.addAll(file.suggestedTests);
        }
        request.suggestedValidationSteps.addAll(suggestedValidationSteps);
        return request;
    }

    private String buildCommitMessage(PatchGenerationRequest patchRequest,
                                      PatchGenerationResult patchResult,
                                      RemediationWorkflowConfig.PatchApplicationConfig config) {
        String baseMessage = patchRequest.prTitle != null && !patchRequest.prTitle.isBlank()
                ? patchRequest.prTitle.strip()
                : (patchResult.summary != null && !patchResult.summary.isBlank()
                ? patchResult.summary.strip()
                : "Apply remediation patch");
        if (config.commitMessagePrefix == null || config.commitMessagePrefix.isBlank()) {
            return baseMessage;
        }
        return config.commitMessagePrefix.strip() + " " + baseMessage;
    }
}


package org.test.remediation;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Builds PR-generation requests from the applied branch state.
 */
public class PrGenerationRequestBuilder {

    public PrGenerationRequest build(Path repoRoot,
                                     PrDraft draft,
                                     PrAuthorResult authorResult,
                                     PatchApplicationExecution patchApplicationExecution,
                                     RemediationWorkflowConfig.PrGenerationConfig config) {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(authorResult, "authorResult must not be null");
        Objects.requireNonNull(patchApplicationExecution, "patchApplicationExecution must not be null");
        Objects.requireNonNull(config, "config must not be null");

        PatchApplicationResult patchResult = patchApplicationExecution.result();
        PrGenerationRequest request = new PrGenerationRequest();
        request.provider = config.provider;
        request.repoRoot = repoRoot.toAbsolutePath().normalize().toString();
        request.baseBranch = patchResult.baseBranch;
        request.headBranch = patchResult.branchName;
        request.prTitle = draft.title;
        request.prBody = draft.body;
        request.summary = patchResult.summary != null && !patchResult.summary.isBlank()
                ? patchResult.summary
                : authorResult.implementationSummary;
        request.draft = config.draft;
        request.commitSha = patchResult.commitSha;
        request.commitMessage = patchResult.commitMessage;
        request.finalDiff = patchApplicationExecution.finalDiff();
        request.riskNotes.addAll(authorResult.riskNotes);
        request.notes.addAll(patchResult.warnings);
        request.notes.addAll(patchResult.errors);

        for (String command : patchResult.validationCommands) {
            request.validationSummary.add("Command: " + command);
        }
        for (int exitCode : patchResult.validationExitCodes) {
            request.validationSummary.add("Exit code: " + exitCode);
        }

        Set<String> changedFiles = new LinkedHashSet<>();
        for (PatchApplicationResult.AppliedFileResult appliedFile : patchResult.appliedFiles) {
            if (appliedFile.path != null && !appliedFile.path.isBlank()) {
                changedFiles.add(appliedFile.path);
            }
        }
        request.changedFiles.addAll(changedFiles);
        return request;
    }
}

package org.test.remediation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds patch-generation requests from authoring results and targeted
 * retrieval context.
 */
public class PatchGenerationRequestBuilder {

    /**
     * Creates a patch-generation request from the normalized authoring result,
     * deterministic change plan, and targeted retrieval output.
     *
     * @param changePlan deterministic change plan from the authoring preparation phase
     * @param authorResult normalized provider-neutral authoring result
     * @param context targeted repository context gathered earlier in the workflow
     * @param backendName backend name to record in the request
     * @param config patch-generation configuration
     * @return patch-generation request for the selected files
     */
    public PatchGenerationRequest build(PrChangePlan changePlan,
                                        PrAuthorResult authorResult,
                                        RetrievedContext context,
                                        String backendName,
                                        RemediationWorkflowConfig.PatchGenerationConfig config) {
        Objects.requireNonNull(changePlan, "changePlan must not be null");
        Objects.requireNonNull(authorResult, "authorResult must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(backendName, "backendName must not be null");
        Objects.requireNonNull(config, "config must not be null");

        PatchGenerationRequest request = new PatchGenerationRequest();
        request.provider = backendName;
        request.branchName = changePlan.branchName;
        request.prTitle = changePlan.prTitle;
        request.summary = authorResult.implementationSummary;

        Map<String, RetrievedContext.RetrievedFile> contextByPath = new LinkedHashMap<>();
        for (RetrievedContext.RetrievedFile file : context.files) {
            contextByPath.put(file.path, file);
        }

        int fileLimit = Math.min(config.maxFiles, authorResult.proposedFileChanges.size());
        for (int i = 0; i < fileLimit; i++) {
            PrAuthorResult.ProposedFileChange proposed = authorResult.proposedFileChanges.get(i);
            PatchGenerationRequest.PatchTargetFile targetFile = new PatchGenerationRequest.PatchTargetFile();
            targetFile.path = proposed.path;
            targetFile.changeType = proposed.changeType;
            targetFile.intent = proposed.intent;
            targetFile.justification = proposed.justification;
            targetFile.evidence.addAll(proposed.evidence);
            targetFile.suggestedTests.addAll(proposed.suggestedTests);

            RetrievedContext.RetrievedFile retrievedFile = contextByPath.get(proposed.path);
            if (retrievedFile != null) {
                int snippetLimit = Math.min(config.maxHunksPerFile, retrievedFile.snippets.size());
                for (int h = 0; h < snippetLimit; h++) {
                    RetrievedContext.CodeSnippet snippet = retrievedFile.snippets.get(h);
                    PatchGenerationRequest.SnippetContext snippetContext = new PatchGenerationRequest.SnippetContext();
                    snippetContext.startLine = snippet.startLine;
                    snippetContext.endLine = snippet.endLine;
                    snippetContext.content = snippet.content;
                    snippetContext.matchedTerms.addAll(snippet.matchedTerms);
                    targetFile.snippetContexts.add(snippetContext);
                }
            }
            request.files.add(targetFile);
        }

        return request;
    }
}


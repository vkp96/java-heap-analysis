package org.test.remediation;

import org.test.AnalysisResult;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic local authoring layer that converts remediation workflow
 * outputs into machine-consumable authoring artifacts.
 * <p>
 * This first increment does not create code changes or GitHub PRs. It produces
 * structured input that a later PR-authoring integration can consume.
 */
public class PrAuthorAgent {

    /**
     * Builds authoring artifacts from the analysis result, targeted retrieval
     * context, draft PR metadata, and policy decision.
     *
     * @param result structured heap analysis result
     * @param context targeted retrieval output
     * @param draft draft PR metadata and markdown body
     * @param decision policy decision for the current workflow run
     * @param config remediation workflow configuration
     * @return machine-oriented authoring artifacts
     */
    public PrAuthorArtifacts createArtifacts(AnalysisResult result,
                                             RetrievedContext context,
                                             PrDraft draft,
                                             PrPolicyDecision decision,
                                             RemediationWorkflowConfig config) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(config, "config must not be null");

        PrAuthorRequest request = new PrAuthorRequest();
        request.branchName = draft.branchName;
        request.prTitle = draft.title;
        request.prBody = draft.body;
        request.summary = result.summary;
        request.confidence = result.confidence;
        request.rootCauseDescription = result.rootCause != null ? result.rootCause.description : null;
        request.responsibleClass = result.rootCause != null ? result.rootCause.responsibleClass : null;
        request.responsibleMethod = result.rootCause != null ? result.rootCause.responsibleMethod : null;
        if (result.remediation != null) {
            request.remediationSteps.addAll(result.remediation);
        }

        int maxSnippets = config.authoring.maxSnippetsPerFile;
        for (RetrievedContext.RetrievedFile file : context.files) {
            PrAuthorRequest.AuthorFileContext fileContext = new PrAuthorRequest.AuthorFileContext();
            fileContext.path = file.path;
            fileContext.score = file.score;
            fileContext.matchedTerms.addAll(file.matchedTerms);
            int limit = Math.min(maxSnippets, file.snippets.size());
            for (int i = 0; i < limit; i++) {
                RetrievedContext.CodeSnippet snippet = file.snippets.get(i);
                PrAuthorRequest.SnippetReference reference = new PrAuthorRequest.SnippetReference();
                reference.startLine = snippet.startLine;
                reference.endLine = snippet.endLine;
                reference.matchedTerms.addAll(snippet.matchedTerms);
                reference.content = snippet.content;
                fileContext.snippets.add(reference);
            }
            request.candidateFiles.add(fileContext);
        }

        PrChangePlan changePlan = new PrChangePlan();
        changePlan.branchName = draft.branchName;
        changePlan.prTitle = draft.title;
        changePlan.authoringNotes.add("Only modify files covered by targeted retrieval and policy-allowed globs.");
        changePlan.authoringNotes.add("Use the remediation steps as primary intent and the snippets as evidence anchors.");
        changePlan.authoringNotes.add("Do not proceed to GitHub PR creation unless a human approves the generated fix.");
        if (!decision.warnings.isEmpty()) {
            changePlan.authoringNotes.addAll(decision.warnings);
        }

        for (PrAuthorRequest.AuthorFileContext fileContext : request.candidateFiles) {
            PlannedFileChange plannedFileChange = new PlannedFileChange();
            plannedFileChange.path = fileContext.path;
            plannedFileChange.changeType = "UPDATE";
            plannedFileChange.reason = buildReason(result, fileContext.path, fileContext.matchedTerms);
            plannedFileChange.matchedTerms.addAll(fileContext.matchedTerms);
            plannedFileChange.suggestedRemediationSteps.addAll(limitList(request.remediationSteps, config.authoring.maxRemediationStepsPerFile));
            for (PrAuthorRequest.SnippetReference snippet : fileContext.snippets) {
                plannedFileChange.evidence.add("lines " + snippet.startLine + "-" + snippet.endLine + ": "
                        + summarizeSnippet(snippet.content));
            }
            changePlan.plannedFileChanges.add(plannedFileChange);
        }

        return new PrAuthorArtifacts(request, changePlan);
    }

    /**
     * Builds a human-readable reason describing why the file is part of the
     * candidate change set.
     *
     * @param result structured heap analysis result
     * @param path candidate repository file
     * @param matchedTerms evidence terms that matched the file
     * @return reason string for a planned file change
     */
    private String buildReason(AnalysisResult result, String path, List<String> matchedTerms) {
        String rootCause = result.rootCause != null && result.rootCause.description != null
                ? result.rootCause.description.strip()
                : "Heap analysis identified this area as relevant to the suspected OOM cause.";
        return "File '" + path + "' matched targeted evidence terms " + matchedTerms
                + " and is a likely location for addressing the root cause: " + rootCause;
    }

    /**
     * Limits a list to at most the requested number of items.
     *
     * @param values source list
     * @param max maximum number of items to keep
     * @return bounded copy of the source list
     */
    private List<String> limitList(List<String> values, int max) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.subList(0, Math.min(Math.max(0, max), values.size()));
    }

    /**
     * Produces a compact single-line summary for a multi-line snippet.
     *
     * @param content snippet content
     * @return compact snippet summary
     */
    private String summarizeSnippet(String content) {
        if (content == null || content.isBlank()) {
            return "[no snippet content]";
        }
        String normalized = content.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 177) + "...";
    }
}


package org.test.remediation;

import org.test.AnalysisResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates whether a generated PR draft satisfies the configured remediation
 * policy gates.
 */
public class PrPolicyChecker {

    /**
     * Applies the configured policy checks to the analysis result, targeted
     * retrieval context, and generated draft payload.
     *
     * @param result structured heap analysis result
     * @param context targeted repository context gathered for the issue
     * @param draft generated local PR draft
     * @param config workflow configuration containing policy rules
     * @return policy decision summarizing whether the draft is allowed
     */
    public PrPolicyDecision evaluate(AnalysisResult result,
                                     RetrievedContext context,
                                     PrDraft draft,
                                     RemediationWorkflowConfig config) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(config, "config must not be null");

        RemediationWorkflowConfig.PrPolicyConfig policy = config.prPolicy;
        PrPolicyDecision decision = new PrPolicyDecision();

        if (confidenceRank(result.confidence) < confidenceRank(policy.minimumConfidence)) {
            decision.failures.add("Analysis confidence '" + result.confidence + "' is below required minimum '"
                    + policy.minimumConfidence + "'.");
        }

        if (policy.requireRootCause && result.rootCause == null) {
            decision.failures.add("Root cause is required by policy before PR creation can proceed.");
        }

        if (policy.requireResponsibleClassOrKeywords) {
            boolean hasResponsibleClass = result.rootCause != null
                    && result.rootCause.responsibleClass != null
                    && !result.rootCause.responsibleClass.isBlank();
            boolean hasKeywords = result.rootCause != null
                    && result.rootCause.codeSearchKeywords != null
                    && !result.rootCause.codeSearchKeywords.isEmpty();
            if (!hasResponsibleClass && !hasKeywords) {
                decision.failures.add("Policy requires either responsible_class or code_search_keywords in AnalysisResult.");
            }
        }

        int remediationSteps = result.remediation != null ? result.remediation.size() : 0;
        if (remediationSteps < policy.minimumRemediationSteps) {
            decision.failures.add("Policy requires at least " + policy.minimumRemediationSteps
                    + " remediation step(s), but AnalysisResult contains " + remediationSteps + ".");
        }

        if (context.files.isEmpty()) {
            decision.failures.add("Targeted retrieval returned no candidate files.");
        }

        if (context.files.size() > policy.maxCandidateFiles) {
            decision.failures.add("Targeted retrieval returned " + context.files.size()
                    + " candidate files which exceeds policy limit " + policy.maxCandidateFiles + ".");
        }

        if (context.totalSnippetCount() > policy.maxTotalSnippets) {
            decision.failures.add("Targeted retrieval returned " + context.totalSnippetCount()
                    + " snippets which exceeds policy limit " + policy.maxTotalSnippets + ".");
        }

        if (context.ambiguous) {
            decision.failures.add("Targeted retrieval marked the result as ambiguous; PR creation must stop.");
        }

        if (draft.title == null || draft.title.isBlank()) {
            decision.failures.add("Draft PR title must not be blank.");
        } else if (policy.titlePrefix != null && !policy.titlePrefix.isBlank()
                && !draft.title.startsWith(policy.titlePrefix)) {
            decision.failures.add("Draft PR title must start with required prefix '" + policy.titlePrefix + "'.");
        }

        if (draft.body == null || draft.body.isBlank()) {
            decision.failures.add("Draft PR body must not be blank.");
        } else {
            for (String section : safe(policy.requiredBodySections)) {
                if (section != null && !section.isBlank() && !draft.body.contains(section)) {
                    decision.failures.add("Draft PR body is missing required section '" + section + "'.");
                }
            }
        }

        for (String candidateFile : safe(draft.candidateFiles)) {
            if (!GlobSupport.matchesAny(candidateFile, policy.allowedChangeGlobs)) {
                decision.failures.add("Candidate file '" + candidateFile + "' is outside allowed change globs "
                        + policy.allowedChangeGlobs + ".");
            }
        }

        if (!context.warnings.isEmpty()) {
            decision.warnings.addAll(context.warnings);
        }

        decision.allowed = decision.failures.isEmpty();
        decision.summary = decision.allowed
                ? "PR draft passed targeted-retrieval and creation-policy checks."
                : "PR draft failed one or more targeted-retrieval or creation-policy checks.";
        return decision;
    }

    /**
     * Converts textual confidence values into a comparable numeric ranking.
     *
     * @param confidence confidence string such as {@code HIGH}, {@code MEDIUM}, or {@code LOW}
     * @return numeric rank where larger numbers indicate stronger confidence
     */
    private int confidenceRank(String confidence) {
        if (confidence == null) {
            return 0;
        }
        return switch (confidence.strip().toUpperCase()) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    /**
     * Returns an empty list when the supplied list is {@code null}.
     *
     * @param values possibly null list
     * @param <T> list element type
     * @return original list or an empty list when input is null
     */
    private <T> List<T> safe(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}



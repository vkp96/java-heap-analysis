package org.test.remediation;

import org.test.AnalysisResult;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds a deterministic local PR draft from a structured heap analysis result
 * and a targeted retrieval context.
 */
public class PrDraftComposer {

    private static final DateTimeFormatter BRANCH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Composes the draft PR payload that a future PR-authoring agent can use as
     * its starting point.
     *
     * @param result       structured heap analysis result
     * @param context      targeted retrieval context derived from the repository
     * @param policyConfig PR policy settings used to shape the draft
     * @return draft PR payload containing title, body, branch name, and rationale
     */
    public PrDraft compose(AnalysisResult result,
                           RetrievedContext context,
                           RemediationWorkflowConfig.PrPolicyConfig policyConfig) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(policyConfig, "policyConfig must not be null");

        PrDraft draft = new PrDraft();
        String className = safeResponsibleClass(result);
        String simpleClassName = simpleName(className);
        draft.branchName = buildBranchName(simpleClassName);
        draft.title = buildTitle(policyConfig.titlePrefix, result, simpleClassName);
        draft.body = buildBody(result, context);
        draft.candidateFiles = new ArrayList<>();
        for (RetrievedContext.RetrievedFile file : context.files) {
            draft.candidateFiles.add(file.path);
        }
        draft.rationale = context.rationale;
        return draft;
    }

    /**
     * Builds a deterministic branch name for the draft using the component name
     * and the current UTC timestamp.
     *
     * @param simpleClassName simple component or class name associated with the issue
     * @return sanitized branch name suitable for future PR automation
     */
    private String buildBranchName(String simpleClassName) {
        return "oom-fix/"
                + sanitize(simpleClassName.toLowerCase(Locale.ROOT))
                + "-"
                + BRANCH_TIMESTAMP.format(Instant.now());
    }

    /**
     * Builds the draft PR title from the title prefix and the best available
     * root-cause description.
     *
     * @param prefix          configured title prefix
     * @param result          structured heap analysis result
     * @param simpleClassName simple component or class name associated with the issue
     * @return human-readable PR title
     */
    private String buildTitle(String prefix, AnalysisResult result, String simpleClassName) {
        String normalizedPrefix = prefix == null ? "" : prefix.strip();
        String rootCauseDescription = result.rootCause != null ? nullSafe(result.rootCause.description) : "";
        String titleCore;
        if (!rootCauseDescription.isBlank()) {
            titleCore = trimSentence(rootCauseDescription);
        } else if (result.rootCause != null && result.rootCause.responsibleMethod != null
                && !result.rootCause.responsibleMethod.isBlank()) {
            titleCore = "Mitigate OOM risk in " + simpleClassName + "." + result.rootCause.responsibleMethod + "()";
        } else {
            titleCore = "Mitigate OOM risk in " + simpleClassName;
        }

        return normalizedPrefix.isBlank() ? titleCore : normalizedPrefix + " " + titleCore;
    }

    /**
     * Builds the draft PR body with sections required by the remediation policy.
     *
     * @param result  structured heap analysis result
     * @param context targeted repository context for the suspected fix area
     * @return formatted markdown PR body
     */
    private String buildBody(AnalysisResult result, RetrievedContext context) {
        StringBuilder body = new StringBuilder();
        body.append("## Problem\n")
                .append(nullSafe(result.summary, "OOM analysis summary unavailable."))
                .append("\n\n");

        body.append("## Root Cause\n");
        if (result.rootCause != null) {
            body.append("- Description: ").append(nullSafe(result.rootCause.description, "n/a")).append("\n")
                    .append("- Responsible class: ").append(nullSafe(result.rootCause.responsibleClass, "n/a")).append("\n")
                    .append("- Responsible method: ").append(nullSafe(result.rootCause.responsibleMethod, "n/a")).append("\n")
                    .append("- Leak pattern: ").append(nullSafe(result.rootCause.leakPatternType, "n/a")).append("\n");
        } else {
            body.append("- Root cause not available in AnalysisResult.\n");
        }
        body.append("\n");

        body.append("## Proposed Fix\n");
        List<String> remediation = result.remediation != null ? result.remediation : List.of();
        if (remediation.isEmpty()) {
            body.append("- No remediation steps were present in AnalysisResult.\n");
        } else {
            for (String step : remediation) {
                body.append("- ").append(step).append("\n");
            }
        }
        body.append("\n");

        body.append("## Targeted Retrieval Context\n");
        if (context.files.isEmpty()) {
            body.append("- No targeted files were retrieved.\n");
        } else {
            for (RetrievedContext.RetrievedFile file : context.files) {
                body.append("- ").append(file.path)
                        .append(" (score=").append(file.score).append(", matchedTerms=")
                        .append(file.matchedTerms).append(")\n");
            }
        }
        body.append("\n");

        body.append("## Validation\n")
                .append("- Review the targeted files above before applying any code changes.\n")
                .append("- Run the relevant unit/integration tests for the affected component.\n")
                .append("- Confirm the fix addresses the retained-reference pattern identified in the heap analysis.\n\n");

        body.append("## Human Approval Required\n")
                .append("- This PR draft was generated from heap-analysis output and targeted code retrieval.\n")
                .append("- A human reviewer must approve the actual fix PR before merge.\n");

        return body.toString();
    }

    /**
     * Resolves the responsible class name from the analysis result or returns a
     * safe fallback identifier when unavailable.
     *
     * @param result structured heap analysis result
     * @return fully qualified responsible class name or a fallback token
     */
    private String safeResponsibleClass(AnalysisResult result) {
        if (result.rootCause == null || result.rootCause.responsibleClass == null
                || result.rootCause.responsibleClass.isBlank()) {
            return "unknown-component";
        }
        return result.rootCause.responsibleClass.strip();
    }

    /**
     * Extracts a simple class name from a fully qualified class name.
     *
     * @param className fully qualified or simple class name
     * @return simple class name or a fallback token when unavailable
     */
    private String simpleName(String className) {
        if (className == null || className.isBlank()) {
            return "unknown-component";
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 && idx < className.length() - 1 ? className.substring(idx + 1) : className;
    }

    /**
     * Sanitizes a value so it can be embedded safely in a branch name.
     *
     * @param value raw branch-name fragment
     * @return sanitized branch-name fragment
     */
    private String sanitize(String value) {
        return value.replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /**
     * Trims a descriptive sentence to a practical title length.
     *
     * @param value source sentence
     * @return original or truncated sentence text
     */
    private String trimSentence(String value) {
        String normalized = nullSafe(value);
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    /**
     * Converts {@code null} values to an empty string and trims non-null input.
     *
     * @param value input text
     * @return trimmed text or an empty string
     */
    private String nullSafe(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Returns a fallback value when the supplied text is null or blank.
     *
     * @param value    input text
     * @param fallback fallback to use when the input is blank
     * @return trimmed input text or the fallback string
     */
    private String nullSafe(String value, String fallback) {
        String normalized = nullSafe(value);
        return normalized.isBlank() ? fallback : normalized;
    }
}



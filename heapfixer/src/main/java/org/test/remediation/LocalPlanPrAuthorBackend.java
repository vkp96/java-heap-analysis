package org.test.remediation;

/**
 * Deterministic fallback backend that converts the existing change plan into a
 * normalized {@link PrAuthorResult} without calling any external AI service.
 */
public class LocalPlanPrAuthorBackend implements PrAuthorBackend {

    /**
     * {@inheritDoc}
     */
    @Override
    public String backendName() {
        return AuthoringProviderType.LOCAL_PLAN.name();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PrAuthorExecution execute(PrAuthorRequest request,
                                     PrChangePlan changePlan,
                                     RemediationWorkflowConfig.AuthoringConfig config) {
        PrAuthorResult result = new PrAuthorResult();
        result.provider = backendName();
        result.model = null;
        result.implementationSummary = "Deterministic local authoring result derived from the existing draft and change plan.";
        result.confidence = request.confidence;
        result.validationSteps.addAll(changePlan.authoringNotes);
        result.riskNotes.add("No AI backend was used; this output mirrors the deterministic change plan.");

        for (PlannedFileChange fileChange : changePlan.plannedFileChanges) {
            PrAuthorResult.ProposedFileChange proposed = new PrAuthorResult.ProposedFileChange();
            proposed.path = fileChange.path;
            proposed.changeType = fileChange.changeType;
            proposed.intent = fileChange.suggestedRemediationSteps.isEmpty()
                    ? "Inspect and update this file to address the OOM remediation plan."
                    : String.join(" ", fileChange.suggestedRemediationSteps);
            proposed.justification = fileChange.reason;
            proposed.evidence.addAll(fileChange.evidence);
            proposed.suggestedTests.addAll(changePlan.authoringNotes);
            result.proposedFileChanges.add(proposed);
        }

        return new PrAuthorExecution(null, null, result);
    }
}


package org.test.remediation;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic local backend that generates final pull request artifacts from the applied branch state.
 */
public class LocalArtifactPrGenerationBackend implements PrGenerationBackend {

    @Override
    public String backendName() {
        return PrGenerationProviderType.LOCAL_ARTIFACT.name();
    }

    @Override
    public PrGenerationExecution execute(PrGenerationRequest request,
                                         RemediationWorkflowConfig.PrGenerationConfig config) {
        PrGenerationResult result = new PrGenerationResult();
        result.provider = backendName();
        result.draft = request.draft;
        result.generated = true;
        result.baseBranch = request.baseBranch;
        result.headBranch = request.headBranch;
        result.title = request.prTitle;
        result.body = buildBody(request);
        result.summary = request.summary;
        result.commitSha = request.commitSha;
        result.commitMessage = request.commitMessage;
        result.changedFiles.addAll(request.changedFiles);
        result.notes.addAll(request.notes);
        if (request.commitSha == null || request.commitSha.isBlank()) {
            result.notes.add("No commit SHA was recorded for this branch; review the branch before publishing a PR.");
        }
        String preview = renderPreview(result);
        return new PrGenerationExecution(preview, result);
    }

    private String buildBody(PrGenerationRequest request) {
        StringBuilder body = new StringBuilder();
        if (request.prBody != null && !request.prBody.isBlank()) {
            body.append(request.prBody.strip());
        }
        body.append(System.lineSeparator()).append(System.lineSeparator())
                .append("## Applied Patch").append(System.lineSeparator())
                .append("- Base branch: `").append(nullSafe(request.baseBranch, "unknown")).append("`").append(System.lineSeparator())
                .append("- Head branch: `").append(nullSafe(request.headBranch, "unknown")).append("`").append(System.lineSeparator())
                .append("- Commit: `").append(nullSafe(shortSha(request.commitSha), "not committed")).append("`").append(System.lineSeparator());

        if (!request.changedFiles.isEmpty()) {
            body.append("- Changed files:").append(System.lineSeparator());
            for (String changedFile : request.changedFiles) {
                body.append("  - `").append(changedFile).append("`").append(System.lineSeparator());
            }
        }

        if (!request.riskNotes.isEmpty()) {
            body.append(System.lineSeparator())
                    .append("## Automation Notes").append(System.lineSeparator());
            for (String note : request.riskNotes) {
                body.append("- ").append(note).append(System.lineSeparator());
            }
        }

        return body.toString().strip();
    }

    private String renderPreview(PrGenerationResult result) {
        List<String> lines = new ArrayList<>();
        lines.add("# " + nullSafe(result.title, "Untitled remediation PR"));
        lines.add("");
        lines.add("- Draft: " + result.draft);
        lines.add("- Base branch: `" + nullSafe(result.baseBranch, "unknown") + "`");
        lines.add("- Head branch: `" + nullSafe(result.headBranch, "unknown") + "`");
        lines.add("- Commit: `" + nullSafe(shortSha(result.commitSha), "not committed") + "`");
        lines.add("");
        lines.add(result.body);
        return String.join(System.lineSeparator(), lines);
    }

    private String shortSha(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}

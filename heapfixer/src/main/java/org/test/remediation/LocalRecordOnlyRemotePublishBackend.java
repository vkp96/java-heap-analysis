package org.test.remediation;

/**
 * Deterministic local backend that records the final publish intent without contacting a remote service.
 */
public class LocalRecordOnlyRemotePublishBackend implements RemotePublishBackend {

    @Override
    public String backendName() {
        return RemotePublishProviderType.LOCAL_RECORD_ONLY.name();
    }

    @Override
    public RemotePublishExecution execute(RemotePublishRequest request,
                                          RemediationWorkflowConfig.RemotePublishConfig config) {
        RemotePublishResult result = new RemotePublishResult();
        result.provider = backendName();
        result.remoteName = request.remoteName;
        result.owner = request.owner;
        result.repo = request.repo;
        result.baseBranch = request.baseBranch;
        result.headBranch = request.headBranch;
        result.commitSha = request.commitSha;
        result.draft = request.draft;
        result.branchPushed = true;
        result.prCreated = true;
        result.pullRequestNumber = 1;
        result.pullRequestUrl = "https://example.invalid/" + safe(request.owner, "owner") + "/" + safe(request.repo, "repo") + "/pull/1";
        result.summary = "Recorded remote publish intent for branch '" + safe(request.headBranch, "unknown") + "'.";
        result.warnings.add("LOCAL_RECORD_ONLY backend does not contact a real remote service.");
        return new RemotePublishExecution("[local-record-only] simulated git push", "{\"number\":1}", result);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}


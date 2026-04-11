package org.test.remediation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalArtifactPrGenerationBackendTest {

    @Test
    void createsFinalPrArtifactsFromAppliedBranchMetadata() {
        PrGenerationRequest request = new PrGenerationRequest();
        request.provider = PrGenerationProviderType.LOCAL_ARTIFACT.name();
        request.baseBranch = "main";
        request.headBranch = "oom-fix/heapdumper-test";
        request.prTitle = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.prBody = "## Problem\nLeak\n\n## Validation\nPR build will validate.";
        request.summary = "Apply and commit the HeapDumper retention fix.";
        request.draft = true;
        request.commitSha = "1234567890abcdef";
        request.commitMessage = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.changedFiles.add("src/main/java/org/test/HeapDumper.java");
        request.riskNotes.add("Review the bounded allocation behavior before merge.");

        PrGenerationExecution execution = new LocalArtifactPrGenerationBackend().execute(
                request,
                RemediationWorkflowConfig.PrGenerationConfig.defaults()
        );

        assertNotNull(execution.result());
        assertEquals("LOCAL_ARTIFACT", execution.result().provider);
        assertEquals("main", execution.result().baseBranch);
        assertEquals("oom-fix/heapdumper-test", execution.result().headBranch);
        assertTrue(execution.result().body.contains("## Applied Patch"));
        assertTrue(execution.result().body.contains("src/main/java/org/test/HeapDumper.java"));
        assertNotNull(execution.previewMarkdown());
        assertTrue(execution.previewMarkdown().contains("[OOM Fix] Mitigate OOM risk in HeapDumper"));
        assertTrue(execution.previewMarkdown().contains("1234567890ab"));
    }
}


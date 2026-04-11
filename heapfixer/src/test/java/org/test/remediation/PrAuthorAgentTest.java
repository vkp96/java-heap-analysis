package org.test.remediation;

import org.junit.jupiter.api.Test;
import org.test.AnalysisResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PrAuthorAgentTest {

    @Test
    void createsAuthorArtifactsFromDraftWorkflowInputs() throws Exception {
        AnalysisResult result = AnalysisResult.fromJson(loadResultJson());
        RemediationWorkflowConfig config = RemediationWorkflowConfig.defaults();
        RetrievedContext context = new TargetedRetrievalService().collect(Path.of("").toAbsolutePath().normalize(), result, config);
        PrDraft draft = new PrDraftComposer().compose(result, context, config.prPolicy);
        PrPolicyDecision decision = new PrPolicyChecker().evaluate(result, context, draft, config);

        PrAuthorArtifacts artifacts = new PrAuthorAgent().createArtifacts(result, context, draft, decision, config);

        assertNotNull(artifacts);
        assertNotNull(artifacts.request());
        assertNotNull(artifacts.changePlan());
        assertEquals(draft.branchName, artifacts.request().branchName);
        assertEquals(draft.title, artifacts.changePlan().prTitle);
        assertFalse(artifacts.request().candidateFiles.isEmpty(), "Expected candidate files in author request");
        assertFalse(artifacts.changePlan().plannedFileChanges.isEmpty(), "Expected planned file changes in change plan");
        assertTrue(artifacts.changePlan().plannedFileChanges.get(0).reason.contains("root cause"));
    }

    @Test
    void workflowSkipsAuthorArtifactsWhenPolicyFails() throws Exception {
        AnalysisResult result = AnalysisResult.fromJson(loadResultJson());
        RemediationWorkflowConfig config = RemediationWorkflowConfig.defaults();
        config.prPolicy.minimumRemediationSteps = 99;
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        Path outputDir = Files.createTempDirectory("pr-author-agent-policy-fail");

        PrPolicyDecision decision = new PrAutomationDraftWorkflow(config).run(result, repoRoot, outputDir);

        assertFalse(decision.allowed, "Expected policy to fail with excessive remediation-step requirement");
        assertFalse(Files.exists(outputDir.resolve("pr_author_request.json")));
        assertFalse(Files.exists(outputDir.resolve("pr_change_plan.json")));
    }

    private String loadResultJson() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("result.json")) {
            assertNotNull(stream, "Missing test resource: result.json");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}


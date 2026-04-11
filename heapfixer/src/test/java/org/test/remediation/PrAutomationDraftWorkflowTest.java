package org.test.remediation;

import org.junit.jupiter.api.Test;
import org.test.AnalysisResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrAutomationDraftWorkflowTest {

    @Test
    void generatesTargetedContextDraftAndPolicyArtifacts() throws Exception {
        AnalysisResult result = AnalysisResult.fromJson(loadResultJson());
        RemediationWorkflowConfig config = RemediationWorkflowConfig.defaults();
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        Path outputDir = Files.createTempDirectory("pr-draft-workflow-test");

        PrPolicyDecision decision = new PrAutomationDraftWorkflow(config)
                .run(result, repoRoot, outputDir);

        assertTrue(Files.exists(outputDir.resolve("targeted_retrieval_context.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_draft.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_policy_decision.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_author_request.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_change_plan.json")));
        assertTrue(decision.allowed, "Expected policy to allow the sample HeapDumper remediation draft");

        String contextJson = Files.readString(outputDir.resolve("targeted_retrieval_context.json"));
        String draftJson = Files.readString(outputDir.resolve("pr_draft.json"));
        String authorRequestJson = Files.readString(outputDir.resolve("pr_author_request.json"));
        String changePlanJson = Files.readString(outputDir.resolve("pr_change_plan.json"));
        assertTrue(contextJson.contains("HeapDumper"), "Expected targeted retrieval to include HeapDumper context");
        assertTrue(draftJson.contains("[OOM Fix]"), "Expected PR draft title prefix to be present");
        assertTrue(authorRequestJson.contains("HeapDumper"), "Expected author request to include HeapDumper evidence");
        assertTrue(changePlanJson.contains("planned_file_changes"), "Expected change plan to contain planned file changes");
    }

    private String loadResultJson() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("result.json")) {
            assertNotNull(stream, "Missing test resource: result.json");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}


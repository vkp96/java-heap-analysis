package org.test.remediation;

import org.junit.jupiter.api.Test;
import org.test.AnalysisResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrAutomationDraftWorkflowTest {

    @Test
    void generatesTargetedContextDraftAndPolicyArtifacts() throws Exception {
        AnalysisResult result = AnalysisResult.fromJson(loadResultJson());
        RemediationWorkflowConfig config = RemediationWorkflowConfig.defaults();
        Path repoRoot = createTempGitRepo();
        Path outputDir = Files.createTempDirectory("pr-draft-workflow-test");

        PrPolicyDecision decision = new PrAutomationDraftWorkflow(config)
                .run(result, repoRoot, outputDir);

        assertTrue(Files.exists(outputDir.resolve("targeted_retrieval_context.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_draft.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_policy_decision.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_author_request.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_change_plan.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_author_result.json")));
        assertTrue(Files.exists(outputDir.resolve("patch_generation_request.json")));
        assertTrue(Files.exists(outputDir.resolve("patch_generation_result.json")));
        assertTrue(Files.exists(outputDir.resolve("patch_preview.diff")));
        assertTrue(decision.allowed, "Expected policy to allow the sample HeapDumper remediation draft");

        String contextJson = Files.readString(outputDir.resolve("targeted_retrieval_context.json"));
        String draftJson = Files.readString(outputDir.resolve("pr_draft.json"));
        String authorRequestJson = Files.readString(outputDir.resolve("pr_author_request.json"));
        String changePlanJson = Files.readString(outputDir.resolve("pr_change_plan.json"));
        String authorResultJson = Files.readString(outputDir.resolve("pr_author_result.json"));
        String patchRequestJson = Files.readString(outputDir.resolve("patch_generation_request.json"));
        String patchResultJson = Files.readString(outputDir.resolve("patch_generation_result.json"));
        String patchPreview = Files.readString(outputDir.resolve("patch_preview.diff"));
        assertTrue(contextJson.contains("HeapDumper"), "Expected targeted retrieval to include HeapDumper context");
        assertTrue(draftJson.contains("[OOM Fix]"), "Expected PR draft title prefix to be present");
        assertTrue(authorRequestJson.contains("HeapDumper"), "Expected author request to include HeapDumper evidence");
        assertTrue(changePlanJson.contains("planned_file_changes"), "Expected change plan to contain planned file changes");
        assertTrue(authorResultJson.contains("LOCAL_PLAN"), "Expected normalized author result to record the local backend");
        assertTrue(patchRequestJson.contains("src/main/java/org/test/HeapDumper.java"), "Expected patch request to include HeapDumper.java");
        assertTrue(patchResultJson.contains("structured_patch_files"), "Expected patch result to contain structured patch files");
        assertTrue(patchPreview.contains("--- a/src/main/java/org/test/HeapDumper.java"), "Expected patch preview to include a diff header for HeapDumper.java");
    }

    @Test
    void createsBranchCommitsChangesAndGeneratesPrArtifactsWhenPostApplyAutomationIsEnabled() throws Exception {
        AnalysisResult result = AnalysisResult.fromJson(loadResultJson());
        RemediationWorkflowConfig config = RemediationWorkflowConfig.defaults();
        config.patchApplication.enabled = true;
        config.patchApplication.autoCommit = true;
        config.patchApplication.validationCommands = List.of("git diff --name-only");
        config.prGeneration.enabled = true;

        Path repoRoot = createTempGitRepo();
        Path outputDir = Files.createTempDirectory("pr-draft-workflow-apply-test");

        PrPolicyDecision decision = new PrAutomationDraftWorkflow(config)
                .run(result, repoRoot, outputDir);

        assertTrue(decision.allowed, "Expected policy to allow the sample HeapDumper remediation draft");
        assertTrue(Files.exists(outputDir.resolve("patch_application_request.json")));
        assertTrue(Files.exists(outputDir.resolve("patch_application_result.json")));
        assertTrue(Files.exists(outputDir.resolve("patch_application_final.diff")));
        assertTrue(Files.exists(outputDir.resolve("patch_application_validation.log")));
        assertTrue(Files.exists(outputDir.resolve("pr_generation_request.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_generation_result.json")));
        assertTrue(Files.exists(outputDir.resolve("pr_preview.md")));

        String branchName = git(repoRoot, "rev-parse", "--abbrev-ref", "HEAD").strip();
        assertTrue(branchName.startsWith("oom-fix/heapdumper-"), "Expected workflow to create a new oom-fix branch");
        assertTrue(Files.readString(repoRoot.resolve("src/main/java/org/test/HeapDumper.java")).contains("latestChunk = new byte[ONE_MB];"));
        assertTrue(Files.readString(outputDir.resolve("patch_application_final.diff")).contains("latestChunk = new byte[ONE_MB];"));
        assertEquals("[OOM Fix] Mitigate OOM risk in HeapDumper", git(repoRoot, "log", "-1", "--pretty=%s").strip());

        String patchApplicationResultJson = Files.readString(outputDir.resolve("patch_application_result.json"));
        String prGenerationResultJson = Files.readString(outputDir.resolve("pr_generation_result.json"));
        String prPreviewMarkdown = Files.readString(outputDir.resolve("pr_preview.md"));
        assertTrue(patchApplicationResultJson.contains("\"commit_created\" : true"));
        assertTrue(prGenerationResultJson.contains("LOCAL_ARTIFACT"));
        assertTrue(prPreviewMarkdown.contains("## Applied Patch"));
    }

    private String loadResultJson() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("result.json")) {
            assertNotNull(stream, "Missing test resource: result.json");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path createTempGitRepo() throws Exception {
        Path repoRoot = Files.createTempDirectory("heapfixer-remediation-repo");
        Path sourceDir = repoRoot.resolve("src/main/java/org/test");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("HeapDumper.java"), Files.readString(Path.of("src/main/java/org/test/HeapDumper.java").toAbsolutePath().normalize()));
        Files.writeString(repoRoot.resolve("build.gradle"), Files.readString(Path.of("build.gradle").toAbsolutePath().normalize()));
        Files.writeString(repoRoot.resolve("settings.gradle"), Files.readString(Path.of("settings.gradle").toAbsolutePath().normalize()));

        git(repoRoot, "init");
        git(repoRoot, "config", "user.email", "copilot@example.com");
        git(repoRoot, "config", "user.name", "Copilot Test");
        git(repoRoot, "add", ".");
        git(repoRoot, "commit", "-m", "initial");
        return repoRoot;
    }

    private String git(Path workingDir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream stream = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            output = buffer.toString(StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, () -> "Git command failed: " + String.join(" ", command) + "\n" + output);
        return output;
    }
}


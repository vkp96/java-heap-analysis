package org.test.remediation;

import org.junit.jupiter.api.Test;

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

class LocalGitPatchApplicationBackendTest {

    @Test
    void createsBranchAppliesStructuredPatchCommitsChangesAndCapturesDiff() throws Exception {
        Path repoRoot = Files.createTempDirectory("local-git-patch-application-test");
        Path sourceFile = repoRoot.resolve("src/main/java/org/test/HeapDumper.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package org.test;

                public class HeapDumper {
                    public static void main(String[] args) {
                        // Keep references to the arrays so they are not garbage-collected.
                        java.util.List<byte[]> list = new java.util.ArrayList<>();
                        final int ONE_MB = 1024 * 1024;
                        int allocatedMb = 0;
                        while (allocatedMb < 2) {
                            list.add(new byte[ONE_MB]);
                            allocatedMb++;
                        }
                    }
                }
                """);

        git(repoRoot, "init");
        git(repoRoot, "config", "user.email", "copilot@example.com");
        git(repoRoot, "config", "user.name", "Copilot Test");
        git(repoRoot, "add", ".");
        git(repoRoot, "commit", "-m", "initial");

        StructuredPatchHunk hunk = new StructuredPatchHunk();
        hunk.startLine = 1;
        hunk.endLine = 14;
        hunk.currentText = """
                1 | package org.test;
                2 |
                3 | public class HeapDumper {
                4 |     public static void main(String[] args) {
                5 |         // Keep references to the arrays so they are not garbage-collected.
                6 |         java.util.List<byte[]> list = new java.util.ArrayList<>();
                7 |         final int ONE_MB = 1024 * 1024;
                8 |         int allocatedMb = 0;
                9 |         while (allocatedMb < 2) {
                10 |             list.add(new byte[ONE_MB]);
                11 |             allocatedMb++;
                12 |         }
                13 |     }
                14 | }
                """;
        hunk.replacementText = """
                package org.test;

                public class HeapDumper {
                    public static void main(String[] args) {
                        // Avoid retaining every allocated chunk so earlier allocations can be garbage-collected.
                        byte[] latestChunk = null;
                        final int ONE_MB = 1024 * 1024;
                        int allocatedMb = 0;
                        while (allocatedMb < 2) {
                            latestChunk = new byte[ONE_MB];
                            allocatedMb++;
                        }
                    }
                }
                """;

        StructuredPatchFile patchFile = new StructuredPatchFile();
        patchFile.path = "src/main/java/org/test/HeapDumper.java";
        patchFile.changeType = "UPDATE";
        patchFile.hunks.add(hunk);

        PatchApplicationRequest request = new PatchApplicationRequest();
        request.provider = PatchApplicationBackendType.LOCAL_GIT.name();
        request.patchProvider = PatchProviderType.LOCAL_PLAN.name();
        request.repoRoot = repoRoot.toString();
        request.branchName = "oom-fix/heapdumper-test";
        request.prTitle = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.summary = "Apply a bounded allocation patch.";
        request.autoCommit = true;
        request.commitMessage = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.gitUserName = "Heapfixer Automation";
        request.gitUserEmail = "heapfixer@local";
        request.structuredPatchFiles.add(patchFile);
        request.validationCommands.add("git diff --name-only");

        RemediationWorkflowConfig.PatchApplicationConfig config = RemediationWorkflowConfig.PatchApplicationConfig.defaults();
        config.enabled = true;
        config.autoCommit = true;
        config.validationCommands = List.of("git diff --name-only");

        PatchApplicationExecution execution = new LocalGitPatchApplicationBackend().execute(request, config);

        assertNotNull(execution.result());
        assertTrue(execution.result().successful);
        assertTrue(execution.result().commitCreated);
        assertNotNull(execution.result().commitSha);
        assertEquals("oom-fix/heapdumper-test", git(repoRoot, "rev-parse", "--abbrev-ref", "HEAD").strip());
        assertTrue(Files.readString(sourceFile).contains("latestChunk = new byte[ONE_MB];"));
        assertTrue(execution.finalDiff().contains("latestChunk = new byte[ONE_MB];"));
        assertEquals("[OOM Fix] Mitigate OOM risk in HeapDumper", git(repoRoot, "log", "-1", "--pretty=%s").strip());
        assertNotNull(execution.validationOutput());
        assertTrue(execution.validationOutput().contains("src/main/java/org/test/HeapDumper.java"));
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


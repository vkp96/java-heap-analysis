package org.test.remediation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubRemotePublishBackendTest {

    @Test
    void pushesBranchToRemoteAndCreatesDraftPullRequest() throws Exception {
        Path remoteRepo = Files.createTempDirectory("heapfixer-remote-repo");
        git(remoteRepo, "init", "--bare");

        Path localRepo = Files.createTempDirectory("heapfixer-local-repo");
        Path sourceDir = localRepo.resolve("src/main/java/org/test");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("HeapDumper.java"), "class HeapDumper {}\n");
        git(localRepo, "init", "-b", "main");
        git(localRepo, "config", "user.email", "copilot@example.com");
        git(localRepo, "config", "user.name", "Copilot Test");
        git(localRepo, "add", ".");
        git(localRepo, "commit", "-m", "initial");
        git(localRepo, "remote", "add", "origin", remoteRepo.toUri().toString());
        git(localRepo, "checkout", "-b", "oom-fix/heapdumper-test");
        Files.writeString(sourceDir.resolve("HeapDumper.java"), "class HeapDumper { byte[] latestChunk; }\n");
        git(localRepo, "add", ".");
        git(localRepo, "commit", "-m", "[OOM Fix] Mitigate OOM risk in HeapDumper");
        String headSha = git(localRepo, "rev-parse", "HEAD").strip();

        RemotePublishRequest request = new RemotePublishRequest();
        request.provider = RemotePublishProviderType.GITHUB.name();
        request.repoRoot = localRepo.toString();
        request.remoteName = "origin";
        request.owner = "test-org";
        request.repo = "test-repo";
        request.baseBranch = "main";
        request.headBranch = "oom-fix/heapdumper-test";
        request.commitSha = headSha;
        request.title = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.body = "## Problem\nLeak\n\n## Applied Patch\n- Changed files: `HeapDumper.java`";
        request.draft = true;

        AtomicReference<String> capturedTitle = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedHead = new AtomicReference<>();

        GitHubRemotePublishBackend backend = new GitHubRemotePublishBackend(
                (repoRoot, args) -> runGit(repoRoot, args),
                (owner, repo, title, body, headBranch, baseBranch, draft) -> {
                    capturedTitle.set(title);
                    capturedBody.set(body);
                    capturedHead.set(headBranch);
                    return new GitHubRemotePublishBackend.CreatedPullRequest(42, "https://github.com/test-org/test-repo/pull/42", "{\"number\":42}");
                }
        );

        RemediationWorkflowConfig.RemotePublishConfig config = RemediationWorkflowConfig.RemotePublishConfig.defaults();
        config.enabled = true;
        config.provider = RemotePublishProviderType.GITHUB.name();
        config.remoteName = "origin";

        RemotePublishExecution execution = backend.execute(request, config);

        assertNotNull(execution.result());
        assertTrue(execution.result().branchPushed);
        assertTrue(execution.result().prCreated);
        assertEquals(Integer.valueOf(42), execution.result().pullRequestNumber);
        assertEquals("[OOM Fix] Mitigate OOM risk in HeapDumper", capturedTitle.get());
        assertTrue(capturedBody.get().contains("## Applied Patch"));
        assertEquals("oom-fix/heapdumper-test", capturedHead.get());
        assertEquals(headSha, git(remoteRepo, "rev-parse", "refs/heads/oom-fix/heapdumper-test").strip());
    }

    private static GitHubRemotePublishBackend.CommandResult runGit(Path repoRoot, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        Process process = new ProcessBuilder(command)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream stream = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            output = buffer.toString(StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        return new GitHubRemotePublishBackend.CommandResult(exitCode, output);
    }

    private String git(Path workingDir, String... args) throws Exception {
        GitHubRemotePublishBackend.CommandResult result = runGit(workingDir, List.of(args));
        assertEquals(0, result.exitCode(), () -> "Git command failed: git " + String.join(" ", args) + "\n" + result.output());
        return result.output();
    }
}


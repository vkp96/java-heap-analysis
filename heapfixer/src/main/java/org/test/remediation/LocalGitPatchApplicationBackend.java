package org.test.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Applies structured patch results directly into a local Git working tree.
 */
public class LocalGitPatchApplicationBackend implements PatchApplicationBackend {

    private static final Logger LOG = LoggerFactory.getLogger(LocalGitPatchApplicationBackend.class);

    @Override
    public String backendName() {
        return PatchApplicationBackendType.LOCAL_GIT.name();
    }

    @Override
    public PatchApplicationExecution execute(PatchApplicationRequest request,
                                             RemediationWorkflowConfig.PatchApplicationConfig config) throws Exception {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Path repoRoot = Path.of(request.repoRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoRoot)) {
            throw new IllegalArgumentException("Patch application repo root does not exist: " + repoRoot);
        }

        ensureGitRepository(repoRoot);

        PatchApplicationResult result = new PatchApplicationResult();
        result.provider = backendName();
        result.patchProvider = request.patchProvider;
        result.repoRoot = repoRoot.toString();
        result.branchName = normalizeBranchName(request.branchName);
        result.baseBranch = currentBranch(repoRoot);
        result.baseCommitSha = currentHeadSha(repoRoot);
        result.validationCommands.addAll(request.validationCommands);
        result.workingTreeCleanBeforeApply = workingTreeClean(repoRoot);

        if (config.requireCleanWorktree && !result.workingTreeCleanBeforeApply) {
            throw new IllegalStateException("Patch application requires a clean git working tree: " + repoRoot);
        }
        if (result.branchName == null) {
            throw new IllegalArgumentException("Patch application requires a non-blank branch name.");
        }
        if (branchExists(repoRoot, result.branchName)) {
            if (config.failIfBranchExists) {
                throw new IllegalStateException("Target patch branch already exists: " + result.branchName);
            }
            checkoutBranch(repoRoot, result.branchName);
            result.branchCreated = false;
        } else {
            createBranch(repoRoot, result.branchName);
            result.branchCreated = true;
        }

        List<StructuredPatchFile> patchFiles = request.structuredPatchFiles != null
                ? request.structuredPatchFiles
                : List.of();
        for (StructuredPatchFile patchFile : patchFiles) {
            PatchApplicationResult.AppliedFileResult fileResult = applyFile(repoRoot, patchFile, config);
            result.appliedFiles.add(fileResult);
            if ("FAILED".equals(fileResult.status)) {
                result.errors.add("Failed to apply patch to " + fileResult.path + ": " + String.join("; ", fileResult.notes));
            } else if ("SKIPPED".equals(fileResult.status)) {
                result.warnings.add("Skipped patch file " + fileResult.path + ": " + String.join("; ", fileResult.notes));
            }
        }

        String finalDiff = runGit(repoRoot, List.of("diff", "--no-ext-diff")).output();
        String validationOutput = runValidationCommands(repoRoot, request.validationCommands, result);

        if (request.autoCommit && result.errors.isEmpty()) {
            commitAppliedChanges(repoRoot, request, result);
            if (result.commitCreated) {
                finalDiff = diffBetween(repoRoot, result.baseCommitSha, result.commitSha);
                result.committedDiff = finalDiff;
            }
        }

        result.successful = result.errors.isEmpty();
        result.summary = buildSummary(result);
        return new PatchApplicationExecution(finalDiff, validationOutput, result);
    }

    private void commitAppliedChanges(Path repoRoot,
                                      PatchApplicationRequest request,
                                      PatchApplicationResult result) throws Exception {
        result.commitAttempted = true;
        result.commitMessage = request.commitMessage;

        configureCommitIdentity(repoRoot, request);
        if (runGit(repoRoot, List.of("status", "--porcelain")).output().isBlank()) {
            result.warnings.add("Auto-commit was enabled, but no working tree changes were present after patch application.");
            return;
        }

        List<String> changedFiles = new ArrayList<>();
        for (PatchApplicationResult.AppliedFileResult appliedFile : result.appliedFiles) {
            if (("APPLIED".equals(appliedFile.status) || "PARTIAL".equals(appliedFile.status))
                    && appliedFile.path != null && !appliedFile.path.isBlank()) {
                changedFiles.add(appliedFile.path);
            }
        }
        if (changedFiles.isEmpty()) {
            result.warnings.add("Auto-commit was skipped because no applied files were available to stage.");
            return;
        }

        List<String> addArgs = new ArrayList<>();
        addArgs.add("add");
        addArgs.add("--");
        addArgs.addAll(changedFiles);
        CommandResult addResult = runGit(repoRoot, addArgs);
        if (addResult.exitCode() != 0) {
            result.errors.add("Failed to stage applied files for commit: " + addResult.output());
            return;
        }

        if (runGit(repoRoot, List.of("diff", "--cached", "--name-only")).output().isBlank()) {
            result.warnings.add("Auto-commit was enabled, but no staged changes remained after git add.");
            return;
        }

        String commitMessage = request.commitMessage != null && !request.commitMessage.isBlank()
                ? request.commitMessage.strip()
                : "Apply remediation patch";
        CommandResult commitResult = runGit(repoRoot, List.of("commit", "-m", commitMessage));
        if (commitResult.exitCode() != 0) {
            result.errors.add("Failed to create remediation commit: " + commitResult.output());
            return;
        }

        result.commitCreated = true;
        result.commitSha = currentHeadSha(repoRoot);
        result.commitMessage = commitMessage;
    }

    private void configureCommitIdentity(Path repoRoot, PatchApplicationRequest request) throws Exception {
        if (request.gitUserName != null && !request.gitUserName.isBlank()) {
            runGit(repoRoot, List.of("config", "user.name", request.gitUserName.strip()));
        }
        if (request.gitUserEmail != null && !request.gitUserEmail.isBlank()) {
            runGit(repoRoot, List.of("config", "user.email", request.gitUserEmail.strip()));
        }
    }

    private PatchApplicationResult.AppliedFileResult applyFile(Path repoRoot,
                                                               StructuredPatchFile patchFile,
                                                               RemediationWorkflowConfig.PatchApplicationConfig config) throws Exception {
        PatchApplicationResult.AppliedFileResult result = new PatchApplicationResult.AppliedFileResult();
        result.path = patchFile != null ? patchFile.path : null;
        result.changeType = patchFile != null ? patchFile.changeType : null;

        if (patchFile == null || patchFile.path == null || patchFile.path.isBlank()) {
            result.status = "FAILED";
            result.notes.add("Patch file path is missing.");
            return result;
        }
        String normalizedRelativePath = GlobSupport.normalizePath(patchFile.path);
        if (!GlobSupport.matchesAny(normalizedRelativePath, config.allowedChangeGlobs)) {
            result.status = "FAILED";
            result.notes.add("Target path is outside configured allowed_change_globs.");
            return result;
        }
        if (!"UPDATE".equalsIgnoreCase(patchFile.changeType)) {
            result.status = "SKIPPED";
            result.notes.add("Only UPDATE structured patch files are supported in the local patch application phase.");
            result.skippedHunks = patchFile.hunks != null ? patchFile.hunks.size() : 0;
            return result;
        }

        Path targetFile = repoRoot.resolve(patchFile.path).normalize();
        if (!targetFile.startsWith(repoRoot)) {
            result.status = "FAILED";
            result.notes.add("Target path escapes the repository root.");
            return result;
        }
        if (!Files.exists(targetFile)) {
            result.status = "FAILED";
            result.notes.add("Target file does not exist.");
            return result;
        }

        String originalContent = Files.readString(targetFile);
        String lineSeparator = detectLineSeparator(originalContent);
        boolean hadTrailingNewline = endsWithLineSeparator(originalContent);
        List<String> lines = splitFileLines(originalContent);

        List<StructuredPatchHunk> hunks = patchFile.hunks != null ? new ArrayList<>(patchFile.hunks) : new ArrayList<>();
        hunks.sort(Comparator.comparingInt((StructuredPatchHunk hunk) -> hunk.startLine).reversed());

        for (StructuredPatchHunk hunk : hunks) {
            String error = applyHunk(lines, hunk);
            if (error == null) {
                result.appliedHunks++;
            } else {
                result.skippedHunks++;
                result.notes.add(error);
            }
        }

        if (result.appliedHunks == 0) {
            result.status = result.skippedHunks > 0 ? "FAILED" : "SKIPPED";
            if (result.notes.isEmpty()) {
                result.notes.add("No applicable hunks were available for this file.");
            }
            return result;
        }

        String updatedContent = String.join(lineSeparator, lines);
        if (hadTrailingNewline) {
            updatedContent += lineSeparator;
        }
        Files.writeString(targetFile, updatedContent);
        result.status = result.skippedHunks == 0 ? "APPLIED" : "PARTIAL";
        return result;
    }

    private String applyHunk(List<String> fileLines, StructuredPatchHunk hunk) {
        if (hunk == null) {
            return "Structured patch hunk is missing.";
        }
        if (hunk.startLine <= 0 || hunk.endLine < hunk.startLine) {
            return "Invalid hunk line range: " + hunk.startLine + "-" + hunk.endLine;
        }
        if (hunk.endLine > fileLines.size()) {
            return "Hunk line range extends beyond the target file: " + hunk.startLine + "-" + hunk.endLine;
        }

        List<String> expectedCurrentLines = parseLineNumberedBlock(preferredCurrentText(hunk));
        if (expectedCurrentLines.isEmpty()) {
            return "Hunk does not contain exact current_text/current_snippet content.";
        }
        List<String> replacementLines = splitReplacementText(hunk.replacementText);
        if (replacementLines.isEmpty()) {
            return "Hunk does not contain exact replacement_text content.";
        }

        List<String> actualLines = new ArrayList<>(fileLines.subList(hunk.startLine - 1, hunk.endLine));
        if (!actualLines.equals(expectedCurrentLines)) {
            return "Current file content no longer matches the hunk anchor at lines "
                    + hunk.startLine + "-" + hunk.endLine + ".";
        }

        fileLines.subList(hunk.startLine - 1, hunk.endLine).clear();
        fileLines.addAll(hunk.startLine - 1, replacementLines);
        return null;
    }

    private String preferredCurrentText(StructuredPatchHunk hunk) {
        if (hunk.currentText != null && !hunk.currentText.isBlank()) {
            return hunk.currentText;
        }
        return hunk.currentSnippet;
    }

    private List<String> parseLineNumberedBlock(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return lines;
        }
        for (String line : content.split("\\R")) {
            if ("[truncated]".equals(line.strip())) {
                return List.of();
            }
            lines.add(line.replaceFirst("^\\s*\\d+\\s*\\|\\s?", ""));
        }
        return lines;
    }

    private List<String> splitReplacementText(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(List.of(content.split("\\R", -1)));
    }

    private List<String> splitFileLines(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>(List.of(content.split("\\R", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() && endsWithLineSeparator(content)) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private String detectLineSeparator(String content) {
        if (content != null && content.contains("\r\n")) {
            return "\r\n";
        }
        return System.lineSeparator();
    }

    private boolean endsWithLineSeparator(String content) {
        return content != null && (content.endsWith("\n") || content.endsWith("\r"));
    }

    private void ensureGitRepository(Path repoRoot) throws Exception {
        CommandResult result = runGit(repoRoot, List.of("rev-parse", "--is-inside-work-tree"));
        if (result.exitCode() != 0 || !result.output().strip().equalsIgnoreCase("true")) {
            throw new IllegalStateException("Patch application requires a Git repository: " + repoRoot);
        }
    }

    private boolean workingTreeClean(Path repoRoot) throws Exception {
        return runGit(repoRoot, List.of("status", "--porcelain")).output().isBlank();
    }

    private String currentBranch(Path repoRoot) throws Exception {
        return runGit(repoRoot, List.of("rev-parse", "--abbrev-ref", "HEAD")).output().strip();
    }

    private String currentHeadSha(Path repoRoot) throws Exception {
        return runGit(repoRoot, List.of("rev-parse", "HEAD")).output().strip();
    }

    private String diffBetween(Path repoRoot, String baseCommitSha, String headCommitSha) throws Exception {
        if (baseCommitSha == null || baseCommitSha.isBlank() || headCommitSha == null || headCommitSha.isBlank()) {
            return runGit(repoRoot, List.of("diff", "--no-ext-diff")).output();
        }
        return runGit(repoRoot, List.of("diff", "--no-ext-diff", baseCommitSha + ".." + headCommitSha)).output();
    }

    private boolean branchExists(Path repoRoot, String branchName) throws Exception {
        return !runGit(repoRoot, List.of("branch", "--list", branchName)).output().isBlank();
    }

    private void createBranch(Path repoRoot, String branchName) throws Exception {
        LOG.info("Creating patch-application branch '{}' under {}", branchName, repoRoot);
        CommandResult result = runGit(repoRoot, List.of("checkout", "-b", branchName));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Failed to create patch branch " + branchName + ": " + result.output());
        }
    }

    private void checkoutBranch(Path repoRoot, String branchName) throws Exception {
        CommandResult result = runGit(repoRoot, List.of("checkout", branchName));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Failed to checkout existing patch branch " + branchName + ": " + result.output());
        }
    }

    private String runValidationCommands(Path repoRoot,
                                         List<String> commands,
                                         PatchApplicationResult result) throws Exception {
        if (commands == null || commands.isEmpty()) {
            return null;
        }

        StringBuilder output = new StringBuilder();
        result.validationRan = true;
        result.validationSucceeded = true;
        for (String command : commands) {
            if (command == null || command.isBlank()) {
                continue;
            }
            output.append("$ ").append(command.strip()).append(System.lineSeparator());
            CommandResult commandResult = runShellCommand(repoRoot, command.strip());
            result.validationExitCodes.add(commandResult.exitCode());
            output.append(commandResult.output());
            if (!commandResult.output().endsWith(System.lineSeparator())) {
                output.append(System.lineSeparator());
            }
            if (commandResult.exitCode() != 0) {
                result.validationSucceeded = false;
                result.warnings.add("Validation command failed with exit code " + commandResult.exitCode() + ": " + command.strip());
            }
        }
        return output.toString();
    }

    private String buildSummary(PatchApplicationResult result) {
        long fullyAppliedFiles = result.appliedFiles.stream()
                .filter(file -> "APPLIED".equals(file.status) || "PARTIAL".equals(file.status))
                .count();
        return "Applied structured patches to " + fullyAppliedFiles + " file(s) on branch '" + result.branchName + "'"
                + (result.commitCreated ? " and created commit '" + abbreviateSha(result.commitSha) + "'." : " without creating a commit.")
                + (result.validationRan
                ? (result.validationSucceeded ? " Validation passed." : " Validation failed.")
                : "");
    }

    private String abbreviateSha(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        String normalized = value.strip();
        return normalized.length() <= 12 ? normalized : normalized.substring(0, 12);
    }

    private String normalizeBranchName(String branchName) {
        return branchName == null || branchName.isBlank() ? null : branchName.strip();
    }

    private CommandResult runGit(Path repoRoot, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        return runCommand(command, repoRoot);
    }

    private CommandResult runShellCommand(Path repoRoot, String command) throws Exception {
        List<String> shellCommand = new ArrayList<>();
        if (isWindows()) {
            shellCommand.add("powershell.exe");
            shellCommand.add("-NoProfile");
            shellCommand.add("-Command");
            shellCommand.add(command);
        } else {
            shellCommand.add("sh");
            shellCommand.add("-lc");
            shellCommand.add(command);
        }
        return runCommand(shellCommand, repoRoot);
    }

    private CommandResult runCommand(List<String> command, Path workingDirectory) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();

        String output;
        try (InputStream stream = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            output = buffer.toString(StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        return new CommandResult(exitCode, output);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record CommandResult(int exitCode, String output) {
    }
}


package org.test.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub-backed remote publish backend that pushes the branch and opens a draft pull request.
 */
public class GitHubRemotePublishBackend implements RemotePublishBackend {

    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final Pattern HTTPS_REMOTE = Pattern.compile("https?://[^/]+/([^/]+)/([^/.]+)(?:\\.git)?/?$");
    private static final Pattern SSH_REMOTE = Pattern.compile("(?:ssh://)?git@[^:/]+[:/]([^/]+)/([^/.]+)(?:\\.git)?/?$");

    @FunctionalInterface
    interface GitCommandInvoker {
        CommandResult run(Path repoRoot, List<String> args) throws Exception;
    }

    @FunctionalInterface
    interface PullRequestApiInvoker {
        CreatedPullRequest create(String owner,
                                  String repo,
                                  String title,
                                  String body,
                                  String headBranch,
                                  String baseBranch,
                                  boolean draft) throws Exception;
    }

    private final GitCommandInvoker gitInvoker;
    private final PullRequestApiInvoker apiInvoker;

    public GitHubRemotePublishBackend(RemediationWorkflowConfig.RemotePublishConfig config) {
        this(GitHubRemotePublishBackend::runGitCommand, createApiInvoker(config));
    }

    GitHubRemotePublishBackend(GitCommandInvoker gitInvoker,
                               PullRequestApiInvoker apiInvoker) {
        this.gitInvoker = Objects.requireNonNull(gitInvoker, "gitInvoker must not be null");
        this.apiInvoker = Objects.requireNonNull(apiInvoker, "apiInvoker must not be null");
    }

    @Override
    public String backendName() {
        return RemotePublishProviderType.GITHUB.name();
    }

    @Override
    public RemotePublishExecution execute(RemotePublishRequest request,
                                          RemediationWorkflowConfig.RemotePublishConfig config) throws Exception {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Path repoRoot = Path.of(request.repoRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoRoot)) {
            throw new IllegalArgumentException("Remote publish repo root does not exist: " + repoRoot);
        }
        ensureGitRepository(repoRoot);
        requireNonBlank(request.remoteName, "remoteName");
        requireNonBlank(request.headBranch, "headBranch");
        requireNonBlank(request.baseBranch, "baseBranch");
        requireNonBlank(request.title, "title");
        requireNonBlank(request.body, "body");

        String remoteUrl = runGit(repoRoot, List.of("remote", "get-url", request.remoteName)).output().strip();
        OwnerRepo ownerRepo = resolveOwnerRepo(request, remoteUrl);
        CommandResult pushResult = runGit(repoRoot, List.of("push", "-u", request.remoteName, request.headBranch));
        if (pushResult.exitCode() != 0) {
            throw new IllegalStateException("Failed to push remediation branch to remote: " + pushResult.output());
        }

        CreatedPullRequest createdPullRequest = apiInvoker.create(
                ownerRepo.owner(),
                ownerRepo.repo(),
                request.title,
                request.body,
                request.headBranch,
                request.baseBranch,
                request.draft
        );

        RemotePublishResult result = new RemotePublishResult();
        result.provider = backendName();
        result.remoteName = request.remoteName;
        result.owner = ownerRepo.owner();
        result.repo = ownerRepo.repo();
        result.baseBranch = request.baseBranch;
        result.headBranch = request.headBranch;
        result.commitSha = request.commitSha;
        result.draft = request.draft;
        result.branchPushed = true;
        result.prCreated = true;
        result.pullRequestNumber = createdPullRequest.number();
        result.pullRequestUrl = createdPullRequest.htmlUrl();
        result.summary = "Pushed branch '" + request.headBranch + "' and created draft PR #" + createdPullRequest.number() + ".";
        return new RemotePublishExecution(pushResult.output(), createdPullRequest.rawResponse(), result);
    }

    private void ensureGitRepository(Path repoRoot) throws Exception {
        CommandResult result = runGit(repoRoot, List.of("rev-parse", "--is-inside-work-tree"));
        if (result.exitCode() != 0 || !result.output().strip().equalsIgnoreCase("true")) {
            throw new IllegalStateException("Remote publish requires a Git repository: " + repoRoot);
        }
    }

    private OwnerRepo resolveOwnerRepo(RemotePublishRequest request, String remoteUrl) {
        String owner = request.owner != null && !request.owner.isBlank() ? request.owner.strip() : null;
        String repo = request.repo != null && !request.repo.isBlank() ? request.repo.strip() : null;
        if (owner != null && repo != null) {
            return new OwnerRepo(owner, repo);
        }

        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalStateException("Unable to resolve GitHub owner/repo because remote URL is blank.");
        }

        Matcher httpsMatcher = HTTPS_REMOTE.matcher(remoteUrl.strip());
        if (httpsMatcher.matches()) {
            return new OwnerRepo(httpsMatcher.group(1), httpsMatcher.group(2));
        }

        Matcher sshMatcher = SSH_REMOTE.matcher(remoteUrl.strip());
        if (sshMatcher.matches()) {
            return new OwnerRepo(sshMatcher.group(1), sshMatcher.group(2));
        }

        throw new IllegalStateException("Unable to parse GitHub owner/repo from remote URL: " + remoteUrl);
    }

    private CommandResult runGit(Path repoRoot, List<String> args) throws Exception {
        return gitInvoker.run(repoRoot, args);
    }

    private static PullRequestApiInvoker createApiInvoker(RemediationWorkflowConfig.RemotePublishConfig config) {
        String token = resolveToken(config.githubToken);
        String apiBaseUrl = config.githubApiBaseUrl != null && !config.githubApiBaseUrl.isBlank()
                ? config.githubApiBaseUrl.strip()
                : DEFAULT_API_BASE_URL;
        HttpClient httpClient = HttpClient.newHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();

        return (owner, repo, title, body, headBranch, baseBranch, draft) -> {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("title", title);
            payload.put("body", body);
            payload.put("head", headBranch);
            payload.put("base", baseBranch);
            payload.put("draft", draft);

            String requestBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/repos/" + owner + "/" + repo + "/pulls"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException("GitHub pull request API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            ObjectNode responseJson = (ObjectNode) objectMapper.readTree(response.body());
            return new CreatedPullRequest(
                    responseJson.path("number").asInt(),
                    responseJson.path("html_url").asText(),
                    response.body()
            );
        };
    }

    private static String resolveToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.strip();
        }
        String envToken = firstNonBlank(
                System.getenv("GITHUB_TOKEN"),
                System.getenv("GH_TOKEN")
        );
        if (envToken == null) {
            throw new IllegalStateException("No GitHub token configured for remote publish backend.");
        }
        return envToken;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static CommandResult runGitCommand(Path repoRoot, List<String> args) throws Exception {
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
        return new CommandResult(exitCode, output);
    }

    static record CommandResult(int exitCode, String output) {
    }

    static record CreatedPullRequest(int number, String htmlUrl, String rawResponse) {
    }

    private record OwnerRepo(String owner, String repo) {
    }
}


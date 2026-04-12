package org.test.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;

/**
 * Thin integration service that submits an {@link AnalysisResult} to the
 * GitHub Copilot Coding Agent by creating a well-structured GitHub Issue
 * assigned to {@code copilot}.
 *
 * <p>The Copilot Coding Agent autonomously reads the issue, locates the
 * responsible code, writes a fix, and opens a Pull Request — no local
 * patch generation, policy checks, or git operations are needed in this
 * codebase.
 *
 * <p>Configuration is resolved from environment variables:
 * <ul>
 *   <li>{@code COPILOT_AGENT_ENABLED} — set to {@code true} to enable (default: {@code false})</li>
 *   <li>{@code GITHUB_OWNER} — repository owner (user or organization)</li>
 *   <li>{@code GITHUB_REPO} — repository name</li>
 *   <li>{@code GITHUB_TOKEN} / {@code GH_TOKEN} — API token with {@code repo} scope</li>
 *   <li>{@code GITHUB_API_BASE_URL} — optional, for GitHub Enterprise</li>
 *   <li>{@code COPILOT_AGENT_MIN_CONFIDENCE} — minimum confidence to create an issue
 *       (default: {@code MEDIUM}; accepted values: {@code LOW}, {@code MEDIUM}, {@code HIGH})</li>
 * </ul>
 */
public class CopilotAgentRemediationService {

    private static final Logger LOG = LoggerFactory.getLogger(CopilotAgentRemediationService.class);

    private final boolean enabled;
    private final String owner;
    private final String repo;
    private final String token;
    private final String apiBaseUrl;
    private final String minConfidence;

    // -------------------------------------------------------------------------
    //  Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a service configured entirely from environment variables.
     */
    public CopilotAgentRemediationService() {
        this(
                envBool("COPILOT_AGENT_ENABLED", false),
                envOrNull("GITHUB_OWNER"),
                envOrNull("GITHUB_REPO"),
                resolveGitHubToken(),
                envOrNull("GITHUB_API_BASE_URL"),
                envOrDefault("COPILOT_AGENT_MIN_CONFIDENCE", "MEDIUM")
        );
    }

    /**
     * Creates a service with explicit configuration values.
     *
     * @param enabled       whether the service is active
     * @param owner         GitHub repository owner
     * @param repo          GitHub repository name
     * @param token         GitHub API token (may be {@code null} when disabled)
     * @param apiBaseUrl    optional GitHub API base URL for GHE
     * @param minConfidence minimum analysis confidence required to create an issue
     */
    public CopilotAgentRemediationService(boolean enabled,
                                          String owner,
                                          String repo,
                                          String token,
                                          String apiBaseUrl,
                                          String minConfidence) {
        this.enabled = enabled;
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.apiBaseUrl = apiBaseUrl;
        this.minConfidence = minConfidence != null ? minConfidence.strip().toUpperCase() : "MEDIUM";
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Returns whether this service is enabled and has valid configuration.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Submits the analysis result to the Copilot Coding Agent by creating a
     * GitHub Issue. No-ops gracefully when disabled, misconfigured, or when
     * the analysis confidence is below the configured threshold.
     *
     * @param result the heap analysis result
     */
    public void submit(AnalysisResult result) {
        if (!enabled) {
            LOG.debug("Copilot agent remediation is disabled. Skipping issue creation.");
            return;
        }

        if (result == null) {
            LOG.warn("Cannot submit null AnalysisResult to Copilot agent.");
            return;
        }

        if (!meetsConfidenceThreshold(result)) {
            LOG.info("Analysis confidence '{}' is below minimum '{}'. Skipping Copilot agent issue.",
                    result.confidence, minConfidence);
            return;
        }

        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            LOG.warn("Copilot agent remediation enabled but GITHUB_OWNER or GITHUB_REPO is not set. Skipping.");
            return;
        }

        if (token == null || token.isBlank()) {
            LOG.warn("Copilot agent remediation enabled but no GitHub token is available. Skipping.");
            return;
        }

        try {
            GitHubIssueCreator issueCreator = apiBaseUrl != null && !apiBaseUrl.isBlank()
                    ? new GitHubIssueCreator(owner, repo, token, apiBaseUrl)
                    : new GitHubIssueCreator(owner, repo, token);

            GitHubIssueCreator.CreatedIssue issue = issueCreator.createFromAnalysis(result);
            LOG.info("Copilot agent issue submitted: #{} — {}", issue.issueNumber(), issue.htmlUrl());
        } catch (GitHubIssueCreator.GitHubIssueException e) {
            LOG.error("Failed to create Copilot agent issue: {}", e.getMessage(), e);
        } catch (Exception e) {
            LOG.error("Unexpected error during Copilot agent issue creation: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    //  Confidence check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the result's confidence meets or exceeds the
     * configured minimum.
     */
    private boolean meetsConfidenceThreshold(AnalysisResult result) {
        int resultLevel = confidenceLevel(result.confidence);
        int requiredLevel = confidenceLevel(minConfidence);
        return resultLevel >= requiredLevel;
    }

    /**
     * Maps a confidence string to a numeric level for comparison.
     * HIGH=3, MEDIUM=2, LOW=1, unknown=0.
     */
    private static int confidenceLevel(String confidence) {
        if (confidence == null) return 0;
        return switch (confidence.strip().toUpperCase()) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    // -------------------------------------------------------------------------
    //  Environment helpers
    // -------------------------------------------------------------------------

    private static boolean envBool(String name, boolean defaultValue) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val.strip());
    }

    private static String envOrNull(String name) {
        String val = System.getenv(name);
        return val != null && !val.isBlank() ? val.strip() : null;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String val = System.getenv(name);
        return val != null && !val.isBlank() ? val.strip() : defaultValue;
    }

    private static String resolveGitHubToken() {
        String t = System.getenv("GITHUB_TOKEN");
        if (t != null && !t.isBlank()) return t.strip();
        t = System.getenv("GH_TOKEN");
        if (t != null && !t.isBlank()) return t.strip();
        return null;
    }
}


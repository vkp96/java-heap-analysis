package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Configuration holder for the local remediation draft workflow.
 * <p>
 * The configuration controls whether the workflow is enabled, where the
 * repository root is located, how targeted retrieval behaves, and which policy
 * gates must pass before a PR draft is considered acceptable.
 */
public class RemediationWorkflowConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RemediationWorkflowConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_RESOURCE = "/remediation-workflow-config.json";

    @JsonProperty("enabled")
    public boolean enabled = false;

    @JsonProperty("repo_root")
    public String repoRoot;

    @JsonProperty("retrieval")
    public RetrievalConfig retrieval = RetrievalConfig.defaults();

    @JsonProperty("pr_policy")
    public PrPolicyConfig prPolicy = PrPolicyConfig.defaults();

    @JsonProperty("authoring")
    public AuthoringConfig authoring = AuthoringConfig.defaults();

    @JsonProperty("patch_generation")
    public PatchGenerationConfig patchGeneration = PatchGenerationConfig.defaults();

    @JsonProperty("patch_application")
    public PatchApplicationConfig patchApplication = PatchApplicationConfig.defaults();

    @JsonProperty("pr_generation")
    public PrGenerationConfig prGeneration = PrGenerationConfig.defaults();

    @JsonProperty("remote_publish")
    public RemotePublishConfig remotePublish = RemotePublishConfig.defaults();

    /**
     * Creates a default in-memory configuration object.
     *
     * @return a configuration populated with safe default values
     */
    public static RemediationWorkflowConfig defaults() {
        RemediationWorkflowConfig config = new RemediationWorkflowConfig();
        config.enabled = false;
        config.retrieval = RetrievalConfig.defaults();
        config.prPolicy = PrPolicyConfig.defaults();
        config.authoring = AuthoringConfig.defaults();
        config.patchGeneration = PatchGenerationConfig.defaults();
        config.patchApplication = PatchApplicationConfig.defaults();
        config.prGeneration = PrGenerationConfig.defaults();
        config.remotePublish = RemotePublishConfig.defaults();
        return config;
    }

    /**
     * Loads the workflow configuration from either the configured environment
     * override or the default classpath resource.
     *
     * @return normalized workflow configuration
     */
    public static RemediationWorkflowConfig loadDefault() {
        RemediationWorkflowConfig config = loadConfiguredFileOrResource();
        config.applyEnvironmentOverrides();
        config.normalize();
        return config;
    }

    /**
     * Loads the workflow configuration from the supplied file path.
     *
     * @param configFile explicit configuration file to read
     * @return normalized workflow configuration
     */
    public static RemediationWorkflowConfig load(Path configFile) {
        try {
            Path normalized = configFile.toAbsolutePath().normalize();
            LOG.info("Loading remediation workflow config from explicit file {}", normalized);
            RemediationWorkflowConfig config = MAPPER.readValue(Files.readString(normalized), RemediationWorkflowConfig.class);
            config.applyEnvironmentOverrides();
            config.normalize();
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load remediation workflow config from " + configFile, e);
        }
    }

    /**
     * Indicates whether the remediation draft workflow should run.
     *
     * @return {@code true} when the workflow is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Resolves the configured repository root if present.
     *
     * @return normalized repository root or {@code null} if none is configured
     */
    public Path resolveRepoRoot() {
        if (repoRoot == null || repoRoot.isBlank()) {
            return null;
        }
        return Path.of(repoRoot).toAbsolutePath().normalize();
    }

    /**
     * Resolves the repository root, falling back to the supplied path when no
     * explicit root is configured.
     *
     * @param fallback fallback repository root to use when config is unset
     * @return normalized configured root or the normalized fallback
     */
    public Path resolveRepoRoot(Path fallback) {
        Path configured = resolveRepoRoot();
        if (configured != null) {
            return configured;
        }
        return fallback != null ? fallback.toAbsolutePath().normalize() : null;
    }

    /**
     * Loads configuration from the environment-selected file or the default
     * classpath resource.
     *
     * @return configuration loaded from the highest-priority source
     */
    private static RemediationWorkflowConfig loadConfiguredFileOrResource() {
        String configPath = System.getenv("HEAPFIXER_REMEDIATION_CONFIG");
        if (configPath != null && !configPath.isBlank()) {
            Path path = Path.of(configPath.strip()).toAbsolutePath().normalize();
            try {
                LOG.info("Loading remediation workflow config from {}", path);
                return MAPPER.readValue(Files.readString(path), RemediationWorkflowConfig.class);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read remediation workflow config from " + path, e);
            }
        }

        try (InputStream stream = RemediationWorkflowConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (stream == null) {
                LOG.info("No remediation workflow config resource found at {}; using defaults", DEFAULT_RESOURCE);
                return defaults();
            }
            LOG.info("Loading remediation workflow config from classpath resource {}", DEFAULT_RESOURCE);
            return MAPPER.readValue(stream, RemediationWorkflowConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load remediation workflow config resource " + DEFAULT_RESOURCE, e);
        }
    }

    /**
     * Applies environment-variable overrides to the currently loaded
     * configuration instance.
     */
    private void applyEnvironmentOverrides() {
        String enabledOverride = System.getenv("HEAPFIXER_PR_DRAFT_ENABLED");
        if (enabledOverride != null && !enabledOverride.isBlank()) {
            enabled = Boolean.parseBoolean(enabledOverride.strip());
        }

        String repoRootOverride = System.getenv("HEAPFIXER_REPO_ROOT");
        if (repoRootOverride != null && !repoRootOverride.isBlank()) {
            repoRoot = repoRootOverride.strip();
        }
    }

    /**
     * Normalizes nested configuration structures and canonicalizes path values.
     */
    private void normalize() {
        if (retrieval == null) {
            retrieval = RetrievalConfig.defaults();
        } else {
            retrieval.normalize();
        }

        if (prPolicy == null) {
            prPolicy = PrPolicyConfig.defaults();
        } else {
            prPolicy.normalize();
        }

        if (authoring == null) {
            authoring = AuthoringConfig.defaults();
        } else {
            authoring.normalize();
        }

        if (patchGeneration == null) {
            patchGeneration = PatchGenerationConfig.defaults();
        } else {
            patchGeneration.normalize();
        }

        if (patchApplication == null) {
            patchApplication = PatchApplicationConfig.defaults();
        } else {
            patchApplication.normalize();
        }

        if (prGeneration == null) {
            prGeneration = PrGenerationConfig.defaults();
        } else {
            prGeneration.normalize();
        }

        if (remotePublish == null) {
            remotePublish = RemotePublishConfig.defaults();
        } else {
            remotePublish.normalize();
        }

        if (repoRoot != null && !repoRoot.isBlank()) {
            repoRoot = Path.of(repoRoot).toAbsolutePath().normalize().toString();
        }
    }

    /**
     * Configuration block controlling how targeted source retrieval is bounded
     * and filtered.
     */
    public static class RetrievalConfig {

        @JsonProperty("max_files")
        public int maxFiles = 3;

        @JsonProperty("max_snippets_per_file")
        public int maxSnippetsPerFile = 2;

        @JsonProperty("snippet_context_lines")
        public int snippetContextLines = 12;

        @JsonProperty("allow_repo_wide_fallback")
        public boolean allowRepoWideFallback = false;

        @JsonProperty("include_globs")
        public List<String> includeGlobs = List.of(
                "src/main/java/**/*.java",
                "build.gradle",
                "settings.gradle"
        );

        @JsonProperty("exclude_globs")
        public List<String> excludeGlobs = List.of(
                "build/**",
                ".gradle/**",
                ".idea/**",
                "out/**"
        );

        /**
         * Creates a retrieval configuration using the built-in defaults.
         *
         * @return normalized retrieval configuration
         */
        static RetrievalConfig defaults() {
            RetrievalConfig config = new RetrievalConfig();
            config.normalize();
            return config;
        }

        /**
         * Normalizes numeric limits and ensures list properties are non-null.
         */
        void normalize() {
            maxFiles = Math.max(1, maxFiles);
            maxSnippetsPerFile = Math.max(1, maxSnippetsPerFile);
            snippetContextLines = Math.max(3, snippetContextLines);
            if (includeGlobs == null || includeGlobs.isEmpty()) {
                includeGlobs = defaults().includeGlobs;
            }
            if (excludeGlobs == null) {
                excludeGlobs = List.of();
            }
        }
    }

    /**
     * Configuration block controlling which conditions must hold before a PR
     * draft is allowed to progress to later automation stages.
     */
    public static class PrPolicyConfig {

        @JsonProperty("minimum_confidence")
        public String minimumConfidence = "MEDIUM";

        @JsonProperty("require_root_cause")
        public boolean requireRootCause = true;

        @JsonProperty("require_responsible_class_or_keywords")
        public boolean requireResponsibleClassOrKeywords = true;

        @JsonProperty("minimum_remediation_steps")
        public int minimumRemediationSteps = 1;

        @JsonProperty("max_candidate_files")
        public int maxCandidateFiles = 3;

        @JsonProperty("max_total_snippets")
        public int maxTotalSnippets = 6;

        @JsonProperty("allowed_change_globs")
        public List<String> allowedChangeGlobs = List.of(
                "src/main/java/**/*.java",
                "build.gradle",
                "settings.gradle"
        );

        @JsonProperty("title_prefix")
        public String titlePrefix = "[OOM Fix]";

        @JsonProperty("required_body_sections")
        public List<String> requiredBodySections = List.of(
                "## Problem",
                "## Root Cause",
                "## Proposed Fix",
                "## Validation",
                "## Human Approval Required"
        );

        /**
         * Creates a PR policy configuration using the built-in defaults.
         *
         * @return normalized PR policy configuration
         */
        static PrPolicyConfig defaults() {
            PrPolicyConfig config = new PrPolicyConfig();
            config.normalize();
            return config;
        }

        /**
         * Normalizes numeric limits and ensures required collections and strings
         * have safe default values.
         */
        void normalize() {
            minimumConfidence = minimumConfidence == null || minimumConfidence.isBlank()
                    ? "MEDIUM"
                    : minimumConfidence.strip().toUpperCase();
            minimumRemediationSteps = Math.max(0, minimumRemediationSteps);
            maxCandidateFiles = Math.max(1, maxCandidateFiles);
            maxTotalSnippets = Math.max(1, maxTotalSnippets);
            if (allowedChangeGlobs == null || allowedChangeGlobs.isEmpty()) {
                allowedChangeGlobs = defaults().allowedChangeGlobs;
            }
            if (titlePrefix == null) {
                titlePrefix = "";
            }
            if (requiredBodySections == null) {
                requiredBodySections = List.of();
            }
        }
    }

    /**
     * Configuration block controlling generation of local authoring artifacts
     * for a future PR-authoring agent.
     */
    public static class AuthoringConfig {

        @JsonProperty("enabled")
        public boolean enabled = true;

        @JsonProperty("provider")
        public String provider = AuthoringProviderType.LOCAL_PLAN.name();

        @JsonProperty("request_file_name")
        public String requestFileName = "pr_author_request.json";

        @JsonProperty("change_plan_file_name")
        public String changePlanFileName = "pr_change_plan.json";

        @JsonProperty("result_file_name")
        public String resultFileName = "pr_author_result.json";

        @JsonProperty("prompt_file_name")
        public String promptFileName = "pr_author_prompt.txt";

        @JsonProperty("raw_response_file_name")
        public String rawResponseFileName = "pr_author_response.json";

        @JsonProperty("copilot_auth_token_file")
        public String copilotAuthTokenFile;

        @JsonProperty("copilot_model")
        public String copilotModel;

        @JsonProperty("max_snippets_per_file")
        public int maxSnippetsPerFile = 2;

        @JsonProperty("max_remediation_steps_per_file")
        public int maxRemediationStepsPerFile = 3;

        /**
         * Creates an authoring configuration using the built-in defaults.
         *
         * @return normalized authoring configuration
         */
        static AuthoringConfig defaults() {
            AuthoringConfig config = new AuthoringConfig();
            config.normalize();
            return config;
        }

        /**
         * Normalizes file names and numeric limits used by authoring artifacts.
         */
        void normalize() {
            provider = provider == null || provider.isBlank()
                    ? AuthoringProviderType.LOCAL_PLAN.name()
                    : provider.strip();
            requestFileName = requestFileName == null || requestFileName.isBlank()
                    ? "pr_author_request.json"
                    : requestFileName.strip();
            changePlanFileName = changePlanFileName == null || changePlanFileName.isBlank()
                    ? "pr_change_plan.json"
                    : changePlanFileName.strip();
            resultFileName = resultFileName == null || resultFileName.isBlank()
                    ? "pr_author_result.json"
                    : resultFileName.strip();
            promptFileName = promptFileName == null || promptFileName.isBlank()
                    ? "pr_author_prompt.txt"
                    : promptFileName.strip();
            rawResponseFileName = rawResponseFileName == null || rawResponseFileName.isBlank()
                    ? "pr_author_response.json"
                    : rawResponseFileName.strip();
            copilotAuthTokenFile = copilotAuthTokenFile == null || copilotAuthTokenFile.isBlank()
                    ? null
                    : Path.of(copilotAuthTokenFile.strip()).toAbsolutePath().normalize().toString();
            copilotModel = copilotModel == null || copilotModel.isBlank()
                    ? null
                    : copilotModel.strip();
            maxSnippetsPerFile = Math.max(1, maxSnippetsPerFile);
            maxRemediationStepsPerFile = Math.max(1, maxRemediationStepsPerFile);
        }
    }

    /**
     * Configuration block controlling generation of local structured patch
     * artifacts after the authoring phase completes.
     */
    public static class PatchGenerationConfig {

        @JsonProperty("enabled")
        public boolean enabled = true;

        @JsonProperty("provider")
        public String provider = PatchProviderType.LOCAL_PLAN.name();

        @JsonProperty("request_file_name")
        public String requestFileName = "patch_generation_request.json";

        @JsonProperty("result_file_name")
        public String resultFileName = "patch_generation_result.json";

        @JsonProperty("prompt_file_name")
        public String promptFileName = "patch_generation_prompt.txt";

        @JsonProperty("raw_response_file_name")
        public String rawResponseFileName = "patch_generation_response.json";

        @JsonProperty("diff_preview_file_name")
        public String diffPreviewFileName = "patch_preview.diff";

        @JsonProperty("copilot_auth_token_file")
        public String copilotAuthTokenFile;

        @JsonProperty("copilot_model")
        public String copilotModel;

        @JsonProperty("max_files")
        public int maxFiles = 3;

        @JsonProperty("max_hunks_per_file")
        public int maxHunksPerFile = 2;

        @JsonProperty("max_lines_per_hunk")
        public int maxLinesPerHunk = 12;

        @JsonProperty("emit_unified_diff_preview")
        public boolean emitUnifiedDiffPreview = true;

        /**
         * Creates a patch-generation configuration using the built-in defaults.
         *
         * @return normalized patch-generation configuration
         */
        static PatchGenerationConfig defaults() {
            PatchGenerationConfig config = new PatchGenerationConfig();
            config.normalize();
            return config;
        }

        /**
         * Normalizes artifact names and numeric limits used by the patch phase.
         */
        void normalize() {
            provider = provider == null || provider.isBlank()
                    ? PatchProviderType.LOCAL_PLAN.name()
                    : provider.strip();
            requestFileName = requestFileName == null || requestFileName.isBlank()
                    ? "patch_generation_request.json"
                    : requestFileName.strip();
            resultFileName = resultFileName == null || resultFileName.isBlank()
                    ? "patch_generation_result.json"
                    : resultFileName.strip();
            promptFileName = promptFileName == null || promptFileName.isBlank()
                    ? "patch_generation_prompt.txt"
                    : promptFileName.strip();
            rawResponseFileName = rawResponseFileName == null || rawResponseFileName.isBlank()
                    ? "patch_generation_response.json"
                    : rawResponseFileName.strip();
            diffPreviewFileName = diffPreviewFileName == null || diffPreviewFileName.isBlank()
                    ? "patch_preview.diff"
                    : diffPreviewFileName.strip();
            copilotAuthTokenFile = copilotAuthTokenFile == null || copilotAuthTokenFile.isBlank()
                    ? null
                    : Path.of(copilotAuthTokenFile.strip()).toAbsolutePath().normalize().toString();
            copilotModel = copilotModel == null || copilotModel.isBlank()
                    ? null
                    : copilotModel.strip();
            maxFiles = Math.max(1, maxFiles);
            maxHunksPerFile = Math.max(1, maxHunksPerFile);
            maxLinesPerHunk = Math.max(1, maxLinesPerHunk);
        }
    }

    /**
     * Configuration block controlling local patch application into a Git working
     * tree after structured patch artifacts are generated.
     */
    public static class PatchApplicationConfig {

        @JsonProperty("enabled")
        public boolean enabled = false;

        @JsonProperty("provider")
        public String provider = PatchApplicationBackendType.LOCAL_GIT.name();

        @JsonProperty("request_file_name")
        public String requestFileName = "patch_application_request.json";

        @JsonProperty("result_file_name")
        public String resultFileName = "patch_application_result.json";

        @JsonProperty("final_diff_file_name")
        public String finalDiffFileName = "patch_application_final.diff";

        @JsonProperty("validation_output_file_name")
        public String validationOutputFileName = "patch_application_validation.log";

        @JsonProperty("auto_commit")
        public boolean autoCommit = false;

        @JsonProperty("commit_message_prefix")
        public String commitMessagePrefix;

        @JsonProperty("git_user_name")
        public String gitUserName = "Heapfixer Automation";

        @JsonProperty("git_user_email")
        public String gitUserEmail = "heapfixer@local";

        @JsonProperty("require_clean_worktree")
        public boolean requireCleanWorktree = true;

        @JsonProperty("fail_if_branch_exists")
        public boolean failIfBranchExists = true;

        @JsonProperty("allowed_change_globs")
        public List<String> allowedChangeGlobs = List.of(
                "src/main/java/**/*.java",
                "build.gradle",
                "settings.gradle"
        );

        @JsonProperty("validation_commands")
        public List<String> validationCommands = List.of();

        /**
         * Creates a patch-application configuration using the built-in defaults.
         *
         * @return normalized patch-application configuration
         */
        static PatchApplicationConfig defaults() {
            PatchApplicationConfig config = new PatchApplicationConfig();
            config.normalize();
            return config;
        }

        /**
         * Normalizes artifact names and path filters used by the patch-application phase.
         */
        void normalize() {
            provider = provider == null || provider.isBlank()
                    ? PatchApplicationBackendType.LOCAL_GIT.name()
                    : provider.strip();
            requestFileName = requestFileName == null || requestFileName.isBlank()
                    ? "patch_application_request.json"
                    : requestFileName.strip();
            resultFileName = resultFileName == null || resultFileName.isBlank()
                    ? "patch_application_result.json"
                    : resultFileName.strip();
            finalDiffFileName = finalDiffFileName == null || finalDiffFileName.isBlank()
                    ? "patch_application_final.diff"
                    : finalDiffFileName.strip();
            validationOutputFileName = validationOutputFileName == null || validationOutputFileName.isBlank()
                    ? "patch_application_validation.log"
                    : validationOutputFileName.strip();
            commitMessagePrefix = commitMessagePrefix == null || commitMessagePrefix.isBlank()
                    ? null
                    : commitMessagePrefix.strip();
            gitUserName = gitUserName == null || gitUserName.isBlank()
                    ? "Heapfixer Automation"
                    : gitUserName.strip();
            gitUserEmail = gitUserEmail == null || gitUserEmail.isBlank()
                    ? "heapfixer@local"
                    : gitUserEmail.strip();
            if (allowedChangeGlobs == null || allowedChangeGlobs.isEmpty()) {
                allowedChangeGlobs = defaults().allowedChangeGlobs;
            }
            if (validationCommands == null) {
                validationCommands = List.of();
            }
        }
    }

    /**
     * Configuration block controlling final PR artifact generation from an
     * applied remediation branch.
     */
    public static class PrGenerationConfig {

        @JsonProperty("enabled")
        public boolean enabled = false;

        @JsonProperty("provider")
        public String provider = PrGenerationProviderType.LOCAL_ARTIFACT.name();

        @JsonProperty("request_file_name")
        public String requestFileName = "pr_generation_request.json";

        @JsonProperty("result_file_name")
        public String resultFileName = "pr_generation_result.json";

        @JsonProperty("preview_file_name")
        public String previewFileName = "pr_preview.md";

        @JsonProperty("draft")
        public boolean draft = true;

        static PrGenerationConfig defaults() {
            PrGenerationConfig config = new PrGenerationConfig();
            config.normalize();
            return config;
        }

        void normalize() {
            provider = provider == null || provider.isBlank()
                    ? PrGenerationProviderType.LOCAL_ARTIFACT.name()
                    : provider.strip();
            requestFileName = requestFileName == null || requestFileName.isBlank()
                    ? "pr_generation_request.json"
                    : requestFileName.strip();
            resultFileName = resultFileName == null || resultFileName.isBlank()
                    ? "pr_generation_result.json"
                    : resultFileName.strip();
            previewFileName = previewFileName == null || previewFileName.isBlank()
                    ? "pr_preview.md"
                    : previewFileName.strip();
        }
    }

    /**
     * Configuration block controlling remote branch push and draft PR creation.
     */
    public static class RemotePublishConfig {

        @JsonProperty("enabled")
        public boolean enabled = false;

        @JsonProperty("provider")
        public String provider = RemotePublishProviderType.GITHUB.name();

        @JsonProperty("request_file_name")
        public String requestFileName = "remote_publish_request.json";

        @JsonProperty("result_file_name")
        public String resultFileName = "remote_publish_result.json";

        @JsonProperty("push_output_file_name")
        public String pushOutputFileName = "remote_publish_push.log";

        @JsonProperty("raw_response_file_name")
        public String rawResponseFileName = "remote_publish_response.json";

        @JsonProperty("remote_name")
        public String remoteName = "origin";

        @JsonProperty("github_owner")
        public String githubOwner;

        @JsonProperty("github_repo")
        public String githubRepo;

        @JsonProperty("github_token")
        public String githubToken;

        @JsonProperty("github_api_base_url")
        public String githubApiBaseUrl;

        static RemotePublishConfig defaults() {
            RemotePublishConfig config = new RemotePublishConfig();
            config.normalize();
            return config;
        }

        void normalize() {
            provider = provider == null || provider.isBlank()
                    ? RemotePublishProviderType.GITHUB.name()
                    : provider.strip();
            requestFileName = requestFileName == null || requestFileName.isBlank()
                    ? "remote_publish_request.json"
                    : requestFileName.strip();
            resultFileName = resultFileName == null || resultFileName.isBlank()
                    ? "remote_publish_result.json"
                    : resultFileName.strip();
            pushOutputFileName = pushOutputFileName == null || pushOutputFileName.isBlank()
                    ? "remote_publish_push.log"
                    : pushOutputFileName.strip();
            rawResponseFileName = rawResponseFileName == null || rawResponseFileName.isBlank()
                    ? "remote_publish_response.json"
                    : rawResponseFileName.strip();
            remoteName = remoteName == null || remoteName.isBlank()
                    ? "origin"
                    : remoteName.strip();
            githubOwner = githubOwner == null || githubOwner.isBlank() ? null : githubOwner.strip();
            githubRepo = githubRepo == null || githubRepo.isBlank() ? null : githubRepo.strip();
            githubToken = githubToken == null || githubToken.isBlank() ? null : githubToken.strip();
            githubApiBaseUrl = githubApiBaseUrl == null || githubApiBaseUrl.isBlank()
                    ? null
                    : githubApiBaseUrl.strip();
        }
    }
}



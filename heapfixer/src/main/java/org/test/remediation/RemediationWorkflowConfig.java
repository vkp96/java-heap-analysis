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
                "src/test/java/**/*.java",
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

        @JsonProperty("request_file_name")
        public String requestFileName = "pr_author_request.json";

        @JsonProperty("change_plan_file_name")
        public String changePlanFileName = "pr_change_plan.json";

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
            requestFileName = requestFileName == null || requestFileName.isBlank()
                    ? "pr_author_request.json"
                    : requestFileName.strip();
            changePlanFileName = changePlanFileName == null || changePlanFileName.isBlank()
                    ? "pr_change_plan.json"
                    : changePlanFileName.strip();
            maxSnippetsPerFile = Math.max(1, maxSnippetsPerFile);
            maxRemediationStepsPerFile = Math.max(1, maxRemediationStepsPerFile);
        }
    }
}



package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral result produced by the patch-application phase.
 */
public class PatchApplicationResult {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("applied_at")
    public String appliedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("patch_provider")
    public String patchProvider;

    @JsonProperty("repo_root")
    public String repoRoot;

    @JsonProperty("base_branch")
    public String baseBranch;

    @JsonProperty("branch_name")
    public String branchName;

    @JsonProperty("branch_created")
    public boolean branchCreated;

    @JsonProperty("base_commit_sha")
    public String baseCommitSha;

    @JsonProperty("working_tree_clean_before_apply")
    public boolean workingTreeCleanBeforeApply;

    @JsonProperty("validation_ran")
    public boolean validationRan;

    @JsonProperty("validation_succeeded")
    public boolean validationSucceeded = true;

    @JsonProperty("commit_attempted")
    public boolean commitAttempted;

    @JsonProperty("commit_created")
    public boolean commitCreated;

    @JsonProperty("commit_sha")
    public String commitSha;

    @JsonProperty("commit_message")
    public String commitMessage;

    @JsonProperty("successful")
    public boolean successful;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("applied_files")
    public List<AppliedFileResult> appliedFiles = new ArrayList<>();

    @JsonProperty("validation_commands")
    public List<String> validationCommands = new ArrayList<>();

    @JsonProperty("validation_exit_codes")
    public List<Integer> validationExitCodes = new ArrayList<>();

    @JsonProperty("committed_diff")
    public String committedDiff;

    @JsonProperty("warnings")
    public List<String> warnings = new ArrayList<>();

    @JsonProperty("errors")
    public List<String> errors = new ArrayList<>();

    /**
     * Serializes this result to formatted JSON.
     *
     * @return formatted JSON representation of this result
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * File-level application result.
     */
    public static class AppliedFileResult {
        @JsonProperty("path")
        public String path;

        @JsonProperty("change_type")
        public String changeType;

        @JsonProperty("status")
        public String status;

        @JsonProperty("applied_hunks")
        public int appliedHunks;

        @JsonProperty("skipped_hunks")
        public int skippedHunks;

        @JsonProperty("notes")
        public List<String> notes = new ArrayList<>();
    }
}


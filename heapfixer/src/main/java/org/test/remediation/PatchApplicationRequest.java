package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Machine-oriented request payload for the local patch-application phase.
 */
public class PatchApplicationRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("patch_provider")
    public String patchProvider;

    @JsonProperty("repo_root")
    public String repoRoot;

    @JsonProperty("branch_name")
    public String branchName;

    @JsonProperty("pr_title")
    public String prTitle;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("auto_commit")
    public boolean autoCommit;

    @JsonProperty("commit_message")
    public String commitMessage;

    @JsonProperty("git_user_name")
    public String gitUserName;

    @JsonProperty("git_user_email")
    public String gitUserEmail;

    @JsonProperty("structured_patch_files")
    public List<StructuredPatchFile> structuredPatchFiles = new ArrayList<>();

    @JsonProperty("suggested_validation_steps")
    public List<String> suggestedValidationSteps = new ArrayList<>();

    @JsonProperty("validation_commands")
    public List<String> validationCommands = new ArrayList<>();

    @JsonProperty("notes")
    public List<String> notes = new ArrayList<>();

    /**
     * Serializes this request to formatted JSON.
     *
     * @return formatted JSON representation of this request
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}


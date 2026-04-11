package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Machine-oriented request payload for the PR-generation phase.
 */
public class PrGenerationRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("repo_root")
    public String repoRoot;

    @JsonProperty("base_branch")
    public String baseBranch;

    @JsonProperty("head_branch")
    public String headBranch;

    @JsonProperty("pr_title")
    public String prTitle;

    @JsonProperty("pr_body")
    public String prBody;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("draft")
    public boolean draft = true;

    @JsonProperty("commit_sha")
    public String commitSha;

    @JsonProperty("commit_message")
    public String commitMessage;

    @JsonProperty("final_diff")
    public String finalDiff;

    @JsonProperty("changed_files")
    public List<String> changedFiles = new ArrayList<>();

    @JsonProperty("validation_summary")
    public List<String> validationSummary = new ArrayList<>();

    @JsonProperty("risk_notes")
    public List<String> riskNotes = new ArrayList<>();

    @JsonProperty("notes")
    public List<String> notes = new ArrayList<>();

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}

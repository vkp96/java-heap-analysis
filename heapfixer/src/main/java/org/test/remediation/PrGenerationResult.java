package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral result produced by the PR-generation phase.
 */
public class PrGenerationResult {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("draft")
    public boolean draft = true;

    @JsonProperty("generated")
    public boolean generated;

    @JsonProperty("base_branch")
    public String baseBranch;

    @JsonProperty("head_branch")
    public String headBranch;

    @JsonProperty("title")
    public String title;

    @JsonProperty("body")
    public String body;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("commit_sha")
    public String commitSha;

    @JsonProperty("commit_message")
    public String commitMessage;

    @JsonProperty("changed_files")
    public List<String> changedFiles = new ArrayList<>();

    @JsonProperty("notes")
    public List<String> notes = new ArrayList<>();

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}

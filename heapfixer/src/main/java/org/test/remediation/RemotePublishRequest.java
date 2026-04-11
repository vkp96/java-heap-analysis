package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;

/**
 * Machine-oriented request payload for the remote publish phase.
 */
public class RemotePublishRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("repo_root")
    public String repoRoot;

    @JsonProperty("remote_name")
    public String remoteName;

    @JsonProperty("owner")
    public String owner;

    @JsonProperty("repo")
    public String repo;

    @JsonProperty("api_base_url")
    public String apiBaseUrl;

    @JsonProperty("base_branch")
    public String baseBranch;

    @JsonProperty("head_branch")
    public String headBranch;

    @JsonProperty("commit_sha")
    public String commitSha;

    @JsonProperty("title")
    public String title;

    @JsonProperty("body")
    public String body;

    @JsonProperty("draft")
    public boolean draft = true;

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}


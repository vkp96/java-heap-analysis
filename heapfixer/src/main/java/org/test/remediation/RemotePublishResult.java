package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral result produced by the remote publish phase.
 */
public class RemotePublishResult {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("published_at")
    public String publishedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("remote_name")
    public String remoteName;

    @JsonProperty("owner")
    public String owner;

    @JsonProperty("repo")
    public String repo;

    @JsonProperty("base_branch")
    public String baseBranch;

    @JsonProperty("head_branch")
    public String headBranch;

    @JsonProperty("commit_sha")
    public String commitSha;

    @JsonProperty("draft")
    public boolean draft = true;

    @JsonProperty("branch_pushed")
    public boolean branchPushed;

    @JsonProperty("pr_created")
    public boolean prCreated;

    @JsonProperty("pull_request_number")
    public Integer pullRequestNumber;

    @JsonProperty("pull_request_url")
    public String pullRequestUrl;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("warnings")
    public List<String> warnings = new ArrayList<>();

    @JsonProperty("errors")
    public List<String> errors = new ArrayList<>();

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}


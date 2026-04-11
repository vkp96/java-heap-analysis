package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Machine-oriented request payload prepared for a future PR authoring agent.
 * <p>
 * This artifact captures the structured analysis result, the reviewer-friendly
 * PR draft, and the targeted repository evidence that should be used when
 * proposing concrete code changes.
 */
public class PrAuthorRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("branch_name")
    public String branchName;

    @JsonProperty("pr_title")
    public String prTitle;

    @JsonProperty("pr_body")
    public String prBody;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("confidence")
    public String confidence;

    @JsonProperty("root_cause_description")
    public String rootCauseDescription;

    @JsonProperty("responsible_class")
    public String responsibleClass;

    @JsonProperty("responsible_method")
    public String responsibleMethod;

    @JsonProperty("remediation_steps")
    public List<String> remediationSteps = new ArrayList<>();

    @JsonProperty("candidate_files")
    public List<AuthorFileContext> candidateFiles = new ArrayList<>();

    /**
     * Serializes this author request to formatted JSON.
     *
     * @return formatted JSON representation of this request
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Repository-file context that a future authoring step can use to prepare a
     * targeted fix.
     */
    public static class AuthorFileContext {
        @JsonProperty("path")
        public String path;

        @JsonProperty("score")
        public int score;

        @JsonProperty("matched_terms")
        public List<String> matchedTerms = new ArrayList<>();

        @JsonProperty("snippets")
        public List<SnippetReference> snippets = new ArrayList<>();
    }

    /**
     * Lightweight evidence snippet reference copied from targeted retrieval.
     */
    public static class SnippetReference {
        @JsonProperty("start_line")
        public int startLine;

        @JsonProperty("end_line")
        public int endLine;

        @JsonProperty("matched_terms")
        public List<String> matchedTerms = new ArrayList<>();

        @JsonProperty("content")
        public String content;
    }
}

